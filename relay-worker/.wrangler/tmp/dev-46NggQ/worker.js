var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// worker.js
var ID = /^pp[0-9a-f]{30}$/;
var POLL_MS = 2e4;
var PHONE_SEEN_MS = 45e3;
var MAX_BODY = 8 * 1024 * 1024;
var worker_default = {
  async fetch(req, env) {
    const [, op, id] = new URL(req.url).pathname.split("/");
    if (!["poll", "reply", "ctl"].includes(op) || !ID.test(id || "")) return text("Phone Pilot relay", 404);
    return env.RELAY.get(env.RELAY.idFromName(id)).fetch(req);
  }
};
var Relay = class {
  static {
    __name(this, "Relay");
  }
  constructor() {
    this.queue = [];
    this.waiter = null;
    this.pending = /* @__PURE__ */ new Map();
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
    if (this.waiter) this.waiter(new Response(null, { status: 204 }));
    if (this.queue.length) return json(this.queue.shift());
    return new Promise((resolve) => {
      const done = /* @__PURE__ */ __name((r) => {
        clearTimeout(t);
        if (this.waiter === done) this.waiter = null;
        resolve(r);
      }, "done");
      const t = setTimeout(() => done(new Response(null, { status: 204 })), POLL_MS);
      this.waiter = done;
    }).then((r) => {
      this.phoneSeen = Date.now();
      return r;
    });
  }
  async reply(req) {
    this.phoneSeen = Date.now();
    let m;
    try {
      m = JSON.parse(await bounded(req));
    } catch {
      return text("bad body", 400);
    }
    const resolve = this.pending.get(m.c);
    if (resolve) {
      this.pending.delete(m.c);
      resolve(String(m.p));
    }
    return text("ok");
  }
  async ctl(req) {
    if (Date.now() - this.phoneSeen > PHONE_SEEN_MS) return text("phone not connected: is relay mode on and the phone online?", 503);
    const payload = await bounded(req);
    if (!payload) return text("empty body", 400);
    const wait = Math.min(Math.max(Number(new URL(req.url).searchParams.get("wait")) || 90, 1), 300) * 1e3;
    const c = ++this.seq;
    const answer = new Promise((resolve) => this.pending.set(c, resolve));
    if (this.waiter) {
      const w = this.waiter;
      w(json({ c, p: payload }));
    } else this.queue.push({ c, p: payload });
    const timeout = new Promise((resolve) => setTimeout(() => resolve(null), wait));
    const got = await Promise.race([answer, timeout]);
    if (got === null) {
      this.pending.delete(c);
      this.queue = this.queue.filter((x) => x.c !== c);
      return text("no answer from the phone in time", 504);
    }
    return text(got);
  }
};
async function bounded(req) {
  const s = await req.text();
  if (s.length > MAX_BODY) throw new Error("too large");
  return s;
}
__name(bounded, "bounded");
function text(s, status = 200) {
  return new Response(s, { status, headers: { "content-type": "text/plain" } });
}
__name(text, "text");
function json(o) {
  return new Response(JSON.stringify(o), { headers: { "content-type": "application/json" } });
}
__name(json, "json");

// ../../../../tmp/claude-0/wtest/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
}, "drainBody");
var middleware_ensure_req_body_drained_default = drainBody;

// ../../../../tmp/claude-0/wtest/node_modules/wrangler/templates/middleware/middleware-miniflare3-json-error.ts
function reduceError(e) {
  return {
    name: e?.name,
    message: e?.message ?? String(e),
    stack: e?.stack,
    cause: e?.cause === void 0 ? void 0 : reduceError(e.cause)
  };
}
__name(reduceError, "reduceError");
var jsonError = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } catch (e) {
    const error = reduceError(e);
    const body = JSON.stringify(error);
    const headers = {
      "Content-Type": "application/json",
      "MF-Experimental-Error-Stack": "true"
    };
    const encoded = encodeURIComponent(body);
    if (encoded.length <= 8192) {
      headers["MF-Experimental-Error-Stack-Payload"] = encoded;
    }
    return new Response(body, { status: 500, headers });
  }
}, "jsonError");
var middleware_miniflare3_json_error_default = jsonError;

// .wrangler/tmp/bundle-mVkEMz/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default,
  middleware_miniflare3_json_error_default
];
var middleware_insertion_facade_default = worker_default;

// ../../../../tmp/claude-0/wtest/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
__name(__facade_register__, "__facade_register__");
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
__name(__facade_invokeChain__, "__facade_invokeChain__");
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}
__name(__facade_invoke__, "__facade_invoke__");

// .wrangler/tmp/bundle-mVkEMz/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class ___Facade_ScheduledController__ {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  scheduledTime;
  cron;
  static {
    __name(this, "__Facade_ScheduledController__");
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof ___Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = /* @__PURE__ */ __name(function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  }, "fetchDispatcher");
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = /* @__PURE__ */ __name(function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      }, "dispatcher");
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
__name(wrapExportedHandler, "wrapExportedHandler");
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = /* @__PURE__ */ __name((request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    }, "#fetchDispatcher");
    #dispatcher = /* @__PURE__ */ __name((type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    }, "#dispatcher");
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
__name(wrapWorkerEntrypoint, "wrapWorkerEntrypoint");
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  Relay,
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default
};
//# sourceMappingURL=worker.js.map
