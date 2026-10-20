import Keycloak from "keycloak-js";

import { keycloakConfig, safeNextPath } from "@/lib/auth/config";

export interface AuthProfile {
  subject: string;
  email: string | null;
  emailVerified: boolean;
  name: string | null;
  username: string | null;
}

export type SessionListener = (authenticated: boolean) => void;

/** Refresh this many seconds before the access token expires. */
const REFRESH_LEAD_SECONDS = 60;

let instance: Keycloak | null = null;
let initPromise: Promise<boolean> | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<SessionListener>();

function keycloak() {
  if (!instance) {
    instance = new Keycloak({ ...keycloakConfig });
    instance.onAuthRefreshSuccess = scheduleRefresh;
    instance.onAuthRefreshError = () => sessionLost();
    instance.onAuthLogout = () => sessionLost();
    instance.onTokenExpired = () => {
      void instance?.updateToken(REFRESH_LEAD_SECONDS).catch(() => sessionLost());
    };
  }
  return instance;
}

function notify(authenticated: boolean) {
  listeners.forEach((listener) => listener(authenticated));
}

function sessionLost() {
  clearRefreshTimer();
  notify(false);
}

function clearRefreshTimer() {
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = null;
}

/** Schedules a silent refresh shortly before the access token expires (token exp + clock skew aware). */
function scheduleRefresh() {
  clearRefreshTimer();
  const kc = instance;
  const exp = kc?.tokenParsed?.exp;
  if (!kc?.authenticated || !exp) return;
  const skewMs = (kc.timeSkew ?? 0) * 1000;
  const expiresInMs = exp * 1000 - Date.now() + skewMs;
  const delay = Math.max(5_000, expiresInMs - REFRESH_LEAD_SECONDS * 1000);
  refreshTimer = setTimeout(() => {
    void kc
      .updateToken(REFRESH_LEAD_SECONDS)
      .then(() => scheduleRefresh())
      .catch(() => sessionLost());
  }, delay);
}

export function onSessionChange(listener: SessionListener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Initializes keycloak-js once per page load (Authorization Code + PKCE S256, `check-sso` through a silent iframe).
 * Resolves `false` when there is no session; rejects when Keycloak cannot be reached.
 */
export function initAuth(): Promise<boolean> {
  if (typeof window === "undefined") return Promise.resolve(false);
  if (!initPromise) {
    initPromise = keycloak()
      .init({
        onLoad: "check-sso",
        pkceMethod: "S256",
        flow: "standard",
        responseMode: "fragment",
        checkLoginIframe: false,
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        messageReceiveTimeout: 10_000,
      })
      .then((authenticated) => {
        if (authenticated) scheduleRefresh();
        return authenticated;
      });
  }
  return initPromise;
}

export function isAuthenticated() {
  return Boolean(instance?.authenticated);
}

export function getProfile(): AuthProfile | null {
  const token = instance?.tokenParsed as
    | (Record<string, unknown> & { sub?: string })
    | undefined;
  if (!instance?.authenticated || !token?.sub) return null;
  const str = (key: string) => (typeof token[key] === "string" ? (token[key] as string) : null);
  const given = [str("given_name"), str("family_name")].filter(Boolean).join(" ");
  return {
    subject: token.sub,
    email: str("email"),
    emailVerified: token.email_verified === true,
    name: str("name") ?? (given || null),
    username: str("preferred_username"),
  };
}

/** Returns an access token valid for at least `minValidity` seconds, refreshing if needed. */
export async function getValidToken(minValidity = 30): Promise<string | null> {
  const kc = instance;
  if (!kc?.authenticated) return null;
  try {
    await kc.updateToken(minValidity);
    return kc.token ?? null;
  } catch {
    sessionLost();
    return null;
  }
}

/** Forces a refresh (used after a 401). */
export async function forceRefresh(): Promise<string | null> {
  const kc = instance;
  if (!kc?.authenticated) return null;
  try {
    await kc.updateToken(-1);
    return kc.token ?? null;
  } catch {
    sessionLost();
    return null;
  }
}

function absolute(path: string) {
  return `${window.location.origin}${safeNextPath(path)}`;
}

export interface LoginOptions {
  next?: string | null;
  idpHint?: string;
  loginHint?: string;
}

export async function login({ next, idpHint, loginHint }: LoginOptions = {}) {
  await keycloak().login({ redirectUri: absolute(next ?? "/app"), idpHint, loginHint, scope: "openid profile email" });
}

export async function register({ next }: { next?: string | null } = {}) {
  await keycloak().register({ redirectUri: absolute(next ?? "/app"), scope: "openid profile email" });
}

/** Keycloak's own "forgot password" page; the app never handles credentials. */
export function resetPasswordUrl() {
  const url = new URL(
    `${keycloakConfig.url}/realms/${encodeURIComponent(keycloakConfig.realm)}/login-actions/reset-credentials`
  );
  url.searchParams.set("client_id", keycloakConfig.clientId);
  return url.toString();
}

export function accountUrl() {
  return `${keycloakConfig.url}/realms/${encodeURIComponent(keycloakConfig.realm)}/account`;
}

/** Ends the Keycloak session; `next` is passed on to the login page (e.g. to come back to an invitation). */
export async function logout({ next }: { next?: string | null } = {}) {
  clearRefreshTimer();
  const target = next ? `/login?next=${encodeURIComponent(safeNextPath(next))}` : "/login";
  const kc = instance;
  if (!kc?.authenticated) {
    window.location.assign(target);
    return;
  }
  await kc.logout({ redirectUri: `${window.location.origin}${target}` });
}
