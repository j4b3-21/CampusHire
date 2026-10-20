export const keycloakConfig = {
  url: process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8180",
  realm: process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "campushire",
  clientId: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "campushire-web",
} as const;

/** Non-sensitive cookie that only tells the Next.js proxy "this browser probably has a session". */
export const AUTH_HINT_COOKIE = "campushire_session_hint";

export const DEFAULT_AFTER_LOGIN = "/app";

/** Only allow same-origin, app-relative redirect targets. */
export function safeNextPath(next: string | null | undefined, fallback = DEFAULT_AFTER_LOGIN) {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/login")) return fallback;
  return next;
}
