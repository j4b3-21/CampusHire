const API_INTERNAL_URL = (process.env.API_INTERNAL_URL ?? "http://localhost:8090").replace(/\/$/, "");

/**
 * Streams `GET /api/v1/notifications/stream` from the API unbuffered. The generic `/api/*` rewrite would let the
 * Next server gzip `text/event-stream`, which holds events back; `no-transform` keeps it out of compression. In
 * production nginx routes this path straight to the API.
 */
export async function GET(request: Request) {
  let upstream: Response;
  try {
    upstream = await fetch(`${API_INTERNAL_URL}/api/v1/notifications/stream`, {
      headers: {
        Accept: "text/event-stream",
        Authorization: request.headers.get("authorization") ?? "",
      },
      cache: "no-store",
      signal: request.signal,
    });
  } catch {
    return Response.json(
      { status: 502, title: "Bad Gateway", code: "network_error", detail: "The notification stream is unavailable." },
      { status: 502, headers: { "Content-Type": "application/problem+json" } }
    );
  }

  const headers = new Headers({
    "Content-Type": upstream.headers.get("content-type") ?? "text/event-stream",
    "Cache-Control": "no-cache, no-store, no-transform",
    "X-Accel-Buffering": "no",
  });
  const correlationId = upstream.headers.get("x-correlation-id");
  if (correlationId) headers.set("X-Correlation-Id", correlationId);
  return new Response(upstream.body, { status: upstream.status, headers });
}
