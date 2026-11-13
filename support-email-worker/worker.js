// Cloudflare Email Worker for support@clozest.in (Email Routing → this worker).
//
// For every email:
//   1. a copy is forwarded to the support Gmail (GMAIL_COPY, a verified destination address), so nothing is ever
//      lost, even if the backend is down;
//   2. the raw email is posted to the backend (HELPDESK_URL), which makes it a ticket or adds it to one, with its
//      photos, PDFs and videos. Over 1 MB the whole email is first parked in R2 (the INBOX bucket binding) as
//      inbound/<uuid>.eml, and only its headers are posted, marked X-Helpdesk-Truncated: 1, with a line
//      X-Clozest-Raw: <that key> among them (so it is signed too): the backend reads the email from there and deletes
//      it. Without the binding, or if parking fails, only the headers go and the ticket says to read it in Gmail.
//
// The post is signed: X-Helpdesk-Timestamp (seconds) and X-Helpdesk-Signature = hex HMAC-SHA256 of
// "<timestamp>.<body>" under HELPDESK_SECRET (the backend's HELPDESK_INBOUND_SECRET). The backend refuses anything
// unsigned, wrongly signed or more than 5 minutes old.
//
// Settings (Workers → this worker → Settings → Variables): HELPDESK_URL (plain text), GMAIL_COPY (plain text),
// HELPDESK_SECRET (secret). Bindings: INBOX, the R2 bucket (the backend's R2_BUCKET).

export const MAX_RAW = 1024 * 1024;
export const PARKED_HEADER = 'X-Clozest-Raw';

export default {
  async email(message, env) {
    const truncated = message.rawSize > MAX_RAW;
    let body;
    if (truncated) {
      const key = await park(message, env);
      body = headersOnly(message.headers, key);
    } else {
      body = new Uint8Array(await new Response(message.raw).arrayBuffer());
    }

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

/** Parks the whole email in R2 for the backend to read; its key, or null (no binding, or it failed). */
export async function park(message, env) {
  if (!env.INBOX) {
    return null;
  }
  try {
    const key = 'inbound/' + crypto.randomUUID() + '.eml';
    await env.INBOX.put(key, new Uint8Array(await new Response(message.raw).arrayBuffer()),
      { httpMetadata: { contentType: 'message/rfc822' } });
    return key;
  } catch (e) {
    console.error('Parking the email in R2 failed', e && e.message);
    return null;
  }
}

/**
 * The email's headers as a raw header block (for an email too large to send whole), with the key it was parked under
 * (any header of that name the sender wrote is left out: only ours counts).
 */
export function headersOnly(headers, parkedKey) {
  let out = '';
  for (const [name, value] of headers) {
    if (name.toLowerCase() === PARKED_HEADER.toLowerCase()) {
      continue;
    }
    out += name + ': ' + String(value).replace(/\r?\n/g, ' ') + '\r\n';
  }
  if (parkedKey) {
    out += PARKED_HEADER + ': ' + parkedKey + '\r\n';
  }
  return new TextEncoder().encode(out + '\r\n');
}
