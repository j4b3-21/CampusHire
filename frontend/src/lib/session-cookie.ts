const AUTH_COOKIE = "campushire_auth";

export function setAuthHintCookie(value = "1") {
  if (typeof document === "undefined") return;
  document.cookie = `${AUTH_COOKIE}=${value}; path=/; max-age=28800; samesite=lax`;
}

export function clearAuthHintCookie() {
  if (typeof document === "undefined") return;
  document.cookie = `${AUTH_COOKIE}=; path=/; max-age=0; samesite=lax`;
}

export function hasAuthHintCookie(cookieHeader?: string | null) {
  if (!cookieHeader) return false;
  return cookieHeader.split(";").some((cookie) => cookie.trim().startsWith(`${AUTH_COOKIE}=`));
}
