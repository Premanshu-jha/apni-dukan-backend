// node --test support-email-worker/  (Node 20+: fetch, crypto.subtle and Response are built in)
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import worker, { sign, headersOnly, MAX_RAW } from './worker.js';

const env = { HELPDESK_URL: 'https://api.test/api/v1/helpdesk/inbound', GMAIL_COPY: 'copy@gmail.com',
  HELPDESK_SECRET: 'test-helpdesk-secret-0123456789-abcdef' };

function message(raw, size = raw.length) {
  const forwarded = [];
  return {
    forwarded,
    rawSize: size,
    raw: new Response(raw).body,
    headers: new Map([['from', 'a@b.com'], ['subject', 'Hi\r\n there'], ['message-id', '<m@b>']]),
    forward: async (to) => forwarded.push(to),
  };
}

test('signs like the backend does (hex HMAC-SHA256 of "ts." + body)', async () => {
  const body = new TextEncoder().encode('From: a@b.com\r\n\r\nHello');
  const expected = createHmac('sha256', env.HELPDESK_SECRET).update('1700000000.').update(body).digest('hex');
  assert.equal(await sign(env.HELPDESK_SECRET, '1700000000', body), expected);
});

test('forwards a copy to Gmail and posts the raw email signed', async () => {
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return new Response('{}', { status: 200 }); };
  const m = message('From: a@b.com\r\n\r\nHello');
  await worker.email(m, env);
  assert.deepEqual(m.forwarded, ['copy@gmail.com']);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, env.HELPDESK_URL);
  assert.equal(calls[0].init.headers['Content-Type'], 'message/rfc822');
  assert.equal(new TextDecoder().decode(calls[0].init.body), 'From: a@b.com\r\n\r\nHello');
  assert.equal(calls[0].init.headers['X-Helpdesk-Truncated'], undefined);
  const ts = calls[0].init.headers['X-Helpdesk-Timestamp'];
  assert.equal(calls[0].init.headers['X-Helpdesk-Signature'], await sign(env.HELPDESK_SECRET, ts, calls[0].init.body));
});

test('an email over 1 MB sends only its headers, marked truncated', async () => {
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push(init); return new Response('{}', { status: 200 }); };
  await worker.email(message('x', MAX_RAW + 1), env);
  assert.equal(calls[0].headers['X-Helpdesk-Truncated'], '1');
  assert.equal(new TextDecoder().decode(calls[0].body), 'from: a@b.com\r\nsubject: Hi  there\r\nmessage-id: <m@b>\r\n\r\n');
});

test('a backend error is tried once more; a refusal is not', async () => {
  let n = 0;
  globalThis.fetch = async () => { n++; return new Response('', { status: 503 }); };
  await worker.email(message('From: a@b.com\r\n\r\nHi'), env);
  assert.equal(n, 2);
  n = 0;
  globalThis.fetch = async () => { n++; return new Response('', { status: 403 }); };
  await worker.email(message('From: a@b.com\r\n\r\nHi'), env);
  assert.equal(n, 1);
});

test('the Gmail copy failing still posts to the backend', async () => {
  let posted = false;
  globalThis.fetch = async () => { posted = true; return new Response('{}'); };
  const m = message('From: a@b.com\r\n\r\nHi');
  m.forward = async () => { throw new Error('not verified'); };
  await worker.email(m, env);
  assert.ok(posted);
});

test('headersOnly makes a header block', () => {
  assert.equal(new TextDecoder().decode(headersOnly(new Map([['a', 'b']]))), 'a: b\r\n\r\n');
});
