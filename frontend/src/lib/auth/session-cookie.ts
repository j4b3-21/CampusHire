import { AUTH_HINT_COOKIE } from "@/lib/auth/config";

export function setAuthHintCookie() {
  if (typeof document === "undefined") return;
  const secure = window.location.protocol === "https:" ? "; secure" : "";
  document.cookie = `${AUTH_HINT_COOKIE}=1; path=/; max-age=43200; samesite=lax${secure}`;
}

export function clearAuthHintCookie() {
  if (typeof document === "undefined") return;
  document.cookie = `${AUTH_HINT_COOKIE}=; path=/; max-age=0; samesite=lax`;
}
