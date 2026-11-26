// Phone Pilot relay: a tiny Cloudflare Worker + Durable Object that passes already-encrypted messages between
// the controller (phonectl.py) and the phone. It never sees plaintext: payloads are AES-256-GCM blobs made with the
// pairing code, and the room name is a hash of that code, so it holds no secrets and needs no configuration.
//
//   phone:       GET  /poll/<id>   long-poll (about 20 s): 200 {"c":n,"p":"<blob>"} = a command, 204 = nothing yet
//                POST /reply/<id>  body {"c":n,"p":"<blob>"}: the answer to command n
//   controller:  POST /ctl/<id>?wait=90   body "<blob>": waits for the phone's answer and returns its blob
//                503 = the phone has not polled lately (relay mode off or phone offline), 504 = no answer in time
//
// Free-plan notes: the Durable Object is SQLite-backed (required on the free plan) and only holds in-memory queues;
// it is busy only while the phone is polling, so it costs about as much as the time relay mode is switched on.

const ID = /^pp[0-9a-f]{30}$/;
const POLL_MS = 20000;
const PHONE_SEEN_MS = 45000;      // a phone that polled within this long counts as connected
const MAX_BODY = 8 * 1024 * 1024;

export default {
  async fetch(req, env) {
    const [, op, id] = new URL(req.url).pathname.split("/");
    if (!["poll", "reply", "ctl"].includes(op) || !ID.test(id || "")) return text("Phone Pilot relay", 404);
    return env.RELAY.get(env.RELAY.idFromName(id)).fetch(req);
  },
};

export class Relay {
  constructor() {
    this.queue = [];            // commands waiting for the phone: {c, p}
    this.waiter = null;         // the phone's open poll: resolve(Response)
    this.pending = new Map();   // c -> resolve(payload) for controllers waiting for an answer
    this.seq = 0;
    this.phoneSeen = 0;
  }

  async fetch(req) {
    const op = new URL(req.url).pathname.split("/")[1];
    if (op === "poll" && req.method === "GET") return this.poll();
    if (op === "reply" && req.method === "POST") return this.reply(req);
    if (op === "ctl" && req.method === "POST") return this.ctl(req);
    return text("method not allowed", 405);
  }

  async poll() {
    this.phoneSeen = Date.now();
    if (this.waiter) this.waiter(new Response(null, { status: 204 }));    // a newer poll replaces an older one
    if (this.queue.length) return json(this.queue.shift());
    return new Promise((resolve) => {
      const done = (r) => { clearTimeout(t); if (this.waiter === done) this.waiter = null; resolve(r); };
      const t = setTimeout(() => done(new Response(null, { status: 204 })), POLL_MS);
      this.waiter = done;
    }).then((r) => { this.phoneSeen = Date.now(); return r; });
  }

  async reply(req) {
    this.phoneSeen = Date.now();
    let m;
    try { m = JSON.parse(await bounded(req)); } catch { return text("bad body", 400); }
    const resolve = this.pending.get(m.c);
    if (resolve) { this.pending.delete(m.c); resolve(String(m.p)); }
    return text("ok");
  }

  async ctl(req) {
    if (Date.now() - this.phoneSeen > PHONE_SEEN_MS) return text("phone not connected: is relay mode on and the phone online?", 503);
    const payload = await bounded(req);
    if (!payload) return text("empty body", 400);
    const wait = Math.min(Math.max(Number(new URL(req.url).searchParams.get("wait")) || 90, 1), 300) * 1000;
    const c = ++this.seq;
    const answer = new Promise((resolve) => this.pending.set(c, resolve));
    if (this.waiter) { const w = this.waiter; w(json({ c, p: payload })); }
    else this.queue.push({ c, p: payload });
    const timeout = new Promise((resolve) => setTimeout(() => resolve(null), wait));
    const got = await Promise.race([answer, timeout]);
    if (got === null) {
      this.pending.delete(c);
      this.queue = this.queue.filter((x) => x.c !== c);
      return text("no answer from the phone in time", 504);
    }
    return text(got);
  }
}

async function bounded(req) {
  const s = await req.text();
  if (s.length > MAX_BODY) throw new Error("too large");
  return s;
}
function text(s, status = 200) { return new Response(s, { status, headers: { "content-type": "text/plain" } }); }
function json(o) { return new Response(JSON.stringify(o), { headers: { "content-type": "application/json" } }); }
