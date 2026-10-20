import { buildUrl } from "@/lib/api/client";
import type { StreamStatus } from "@/types/notifications";

export interface SseMessage {
  event: string;
  data: string;
  id: string | null;
}

export interface EventStreamOptions {
  /** API path, e.g. `/notifications/stream`. */
  path: string;
  /** Resolves the bearer token; `force` after a 401. `null` stops the stream (signed out). */
  getToken: (force: boolean) => Promise<string | null>;
  onMessage: (message: SseMessage) => void;
  /** Called every time a connection is established (`reconnect` is false for the first one). */
  onOpen?: (info: { reconnect: boolean }) => void;
  onStatus?: (status: StreamStatus) => void;
  /** First reconnect delay unless the server sends `retry:`. */
  retryMs?: number;
  maxRetryMs?: number;
  /** Reconnect when nothing (not even a keep-alive comment) arrives for this long. */
  idleTimeoutMs?: number;
}

export interface EventStreamHandle {
  close: () => void;
  /** Drops the current connection and reconnects immediately (e.g. when the browser comes back online). */
  reconnectNow: () => void;
}

/**
 * Incremental `text/event-stream` parser (WHATWG HTML, "Server-sent events"): fields `event`, `data`, `id`,
 * `retry`; comment lines start with `:`; a blank line dispatches the event.
 */
export class SseParser {
  private buffer = "";
  private data: string[] = [];
  private event = "";
  private id: string | null = null;

  constructor(
    private readonly onMessage: (message: SseMessage) => void,
    private readonly onRetry: (ms: number) => void,
    private readonly onComment: () => void = () => {}
  ) {}

  push(chunk: string) {
    this.buffer += chunk;
    let index: number;
    while ((index = this.lineEnd()) >= 0) {
      const line = this.buffer.slice(0, index);
      const skip = this.buffer[index] === "\r" && this.buffer[index + 1] === "\n" ? 2 : 1;
      this.buffer = this.buffer.slice(index + skip);
      this.line(line);
    }
  }

  private lineEnd() {
    const lf = this.buffer.indexOf("\n");
    const cr = this.buffer.indexOf("\r");
    if (cr >= 0 && cr === this.buffer.length - 1) return lf >= 0 && lf < cr ? lf : -1;
    if (lf < 0) return cr;
    if (cr < 0) return lf;
    return Math.min(lf, cr);
  }

  private line(line: string) {
    if (line === "") {
      if (this.data.length > 0) {
        this.onMessage({ event: this.event || "message", data: this.data.join("\n"), id: this.id });
      }
      this.data = [];
      this.event = "";
      return;
    }
    if (line.startsWith(":")) {
      this.onComment();
      return;
    }
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    switch (field) {
      case "event":
        this.event = value;
        break;
      case "data":
        this.data.push(value);
        break;
      case "id":
        if (!value.includes("\0")) this.id = value;
        break;
      case "retry":
        if (/^\d+$/.test(value)) this.onRetry(Number(value));
        break;
    }
  }
}

function sleep(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    if (signal.aborted) return resolve();
    const timer = setTimeout(done, ms);
    function done() {
      clearTimeout(timer);
      signal.removeEventListener("abort", done);
      resolve();
    }
    signal.addEventListener("abort", done, { once: true });
  });
}

class RetryableError extends Error {
  constructor(
    message: string,
    readonly authFailure = false
  ) {
    super(message);
  }
}

/**
 * Server-Sent Events over `fetch` so the request can carry `Authorization: Bearer` (EventSource cannot).
 * Reconnects with exponential backoff and jitter after errors, idle timeouts and normal stream ends; refreshes the
 * token before every attempt and forces a refresh after a 401. Stops when `close()` is called or no token is left.
 */
export function openEventStream(options: EventStreamOptions): EventStreamHandle {
  const { path, getToken, onMessage, onOpen, onStatus } = options;
  const maxRetryMs = options.maxRetryMs ?? 60_000;
  const idleTimeoutMs = options.idleTimeoutMs ?? 70_000;
  let retryMs = options.retryMs ?? 2_000;
  const lifetime = new AbortController();
  let attempt: AbortController | null = null;
  let wake: AbortController | null = null;
  let connections = 0;

  const status = (value: StreamStatus) => onStatus?.(value);

  const connectOnce = async (forceToken: boolean) => {
    const token = await getToken(forceToken);
    if (!token) return "signed-out" as const;
    attempt = new AbortController();
    const controller = attempt;
    const abortAttempt = () => controller.abort();
    lifetime.signal.addEventListener("abort", abortAttempt, { once: true });

    let idleTimer: ReturnType<typeof setTimeout> | null = null;
    const touch = () => {
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(() => controller.abort(), idleTimeoutMs);
    };

    try {
      touch();
      const response = await fetch(buildUrl(path), {
        headers: { Accept: "text/event-stream", Authorization: `Bearer ${token}`, "Cache-Control": "no-cache" },
        cache: "no-store",
        signal: controller.signal,
      });
      if (response.status === 401) throw new RetryableError("unauthorized", true);
      if (!response.ok || !response.body) throw new RetryableError(`HTTP ${response.status}`);
      if (!(response.headers.get("content-type") ?? "").includes("text/event-stream")) {
        throw new RetryableError("unexpected content type");
      }

      connections += 1;
      status("open");
      onOpen?.({ reconnect: connections > 1 });

      const parser = new SseParser(
        (message) => {
          touch();
          onMessage(message);
        },
        (ms) => {
          retryMs = ms;
        },
        touch
      );
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        touch();
        parser.push(value);
      }
      return "ended" as const;
    } finally {
      if (idleTimer) clearTimeout(idleTimer);
      lifetime.signal.removeEventListener("abort", abortAttempt);
      attempt = null;
    }
  };

  const run = async () => {
    let failures = 0;
    let forceToken = false;
    status("connecting");
    while (!lifetime.signal.aborted) {
      let healthy = false;
      try {
        const outcome = await connectOnce(forceToken);
        forceToken = false;
        if (outcome === "signed-out") break;
        healthy = true;
      } catch (error) {
        if (lifetime.signal.aborted) break;
        forceToken = error instanceof RetryableError && error.authFailure;
        // An attempt aborted by reconnectNow() or the idle watchdog after a successful open counts as healthy.
        healthy = error instanceof DOMException && error.name === "AbortError" && connections > 0 && failures === 0;
      }
      if (lifetime.signal.aborted) break;
      failures = healthy ? 0 : failures + 1;
      status("reconnecting");
      const base = failures === 0 ? Math.min(retryMs, 3_000) : Math.min(maxRetryMs, retryMs * 2 ** (failures - 1));
      const delay = Math.round(base * (0.8 + Math.random() * 0.4));
      wake = new AbortController();
      await sleep(forceToken ? 0 : delay, AbortSignal.any([lifetime.signal, wake.signal]));
      wake = null;
    }
    status("closed");
  };

  void run();

  return {
    close: () => lifetime.abort(),
    reconnectNow: () => {
      if (wake) wake.abort();
      else attempt?.abort();
    },
  };
}
