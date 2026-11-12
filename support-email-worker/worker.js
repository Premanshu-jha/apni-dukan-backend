// Cloudflare Email Worker for support@clozest.in (Email Routing → this worker).
//
// For every email:
//   1. a copy is forwarded to the support Gmail (GMAIL_COPY, a verified destination address), so nothing is ever
//      lost, even if the backend is down;
//   2. the raw email is posted to the backend (HELPDESK_URL), which makes it a ticket or adds it to one. Over 1 MB
//      (photos, PDFs) only its headers are posted, marked X-Helpdesk-Truncated: 1: the ticket says to read it in Gmail.
//
// The post is signed: X-Helpdesk-Timestamp (seconds) and X-Helpdesk-Signature = hex HMAC-SHA256 of
// "<timestamp>.<body>" under HELPDESK_SECRET (the backend's HELPDESK_INBOUND_SECRET). The backend refuses anything
// unsigned, wrongly signed or more than 5 minutes old.
//
// Settings (Workers → this worker → Settings → Variables): HELPDESK_URL (plain text), GMAIL_COPY (plain text),
// HELPDESK_SECRET (secret).

export const MAX_RAW = 1024 * 1024;

export default {
  async email(message, env) {
    const truncated = message.rawSize > MAX_RAW;
    const body = truncated ? headersOnly(message.headers) : new Uint8Array(await new Response(message.raw).arrayBuffer());

    try {
      await message.forward(env.GMAIL_COPY);
    } catch (e) {
      console.error('Gmail copy failed', e && e.message);
    }

    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        const res = await post(env, body, truncated);
        if (res.ok) {
          return;
        }
        console.error('Backend answered', res.status);
        if (res.status < 500) {
          return; // our request is wrong (signature, not an email): trying again won't help
        }
      } catch (e) {
        console.error('Backend not reachable', e && e.message);
      }
    }
  },
};

export async function post(env, body, truncated) {
  const timestamp = String(Math.floor(Date.now() / 1000));
  const headers = {
    'Content-Type': 'message/rfc822',
    'X-Helpdesk-Timestamp': timestamp,
    'X-Helpdesk-Signature': await sign(env.HELPDESK_SECRET, timestamp, body),
  };
  if (truncated) {
    headers['X-Helpdesk-Truncated'] = '1';
  }
  return fetch(env.HELPDESK_URL, { method: 'POST', headers, body });
}

/** hex HMAC-SHA256 of "<timestamp>." followed by the body's bytes. */
export async function sign(secret, timestamp, body) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false,
    ['sign']);
  const prefix = enc.encode(timestamp + '.');
  const data = new Uint8Array(prefix.length + body.length);
  data.set(prefix, 0);
  data.set(body, prefix.length);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, data));
  return Array.from(mac, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** The email's headers as a raw header block (for an email too large to send whole). */
export function headersOnly(headers) {
  let out = '';
  for (const [name, value] of headers) {
    out += name + ': ' + String(value).replace(/\r?\n/g, ' ') + '\r\n';
  }
  return new TextEncoder().encode(out + '\r\n');
}
