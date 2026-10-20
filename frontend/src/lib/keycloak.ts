import Keycloak, { type KeycloakInstance } from "keycloak-js";

const keycloakUrl = process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8765";
const keycloakRealm = process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "campushire";
const keycloakClientId = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "campushire-web";

type LoginOptions = {
  email?: string;
  redirectUri?: string;
};

type RedirectOptions = {
  redirectUri?: string;
};

type SocialLoginOptions = {
  email?: string;
  redirectUri?: string;
  idpHint?: string;
};

let keycloakInstance: KeycloakInstance | null = null;
let initPromise: Promise<boolean> | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | null = null;

function assertConfig() {
  if (!keycloakUrl || !keycloakRealm || !keycloakClientId) {
    throw new Error("Missing Keycloak environment variables.");
  }
}

function buildKeycloak() {
  assertConfig();
  return new Keycloak({
    url: keycloakUrl!,
    realm: keycloakRealm!,
    clientId: keycloakClientId!,
  });
}

export function getKeycloak() {
  if (!keycloakInstance) {
    keycloakInstance = buildKeycloak();
  }
  return keycloakInstance;
}

export function getDefaultRedirectUri() {
  if (typeof window === "undefined") {
    return "/";
  }
  return `${window.location.origin}/dashboard`;
}

function scheduleRefresh() {
  if (typeof window === "undefined") return;
  if (refreshTimer) clearTimeout(refreshTimer);
  refreshTimer = setTimeout(async () => {
    try {
      await refreshToken();
      scheduleRefresh();
    } catch {
      refreshTimer = null;
    }
  }, 60_000);
}

export async function initKeycloak() {
  if (typeof window === "undefined") {
    return false;
  }

  if (!keycloakUrl || !keycloakRealm || !keycloakClientId) {
    return false;
  }

  const keycloak = getKeycloak();

  if (!initPromise) {
    initPromise = keycloak.init({
      onLoad: "check-sso",
      pkceMethod: "S256",
      flow: "standard",
      checkLoginIframe: false,
      enableLogging: false,
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    }).catch(() => {
      initPromise = null;
      return false;
    });
  }

  const authenticated = await initPromise;

  if (authenticated) {
    scheduleRefresh();
  }

  return authenticated;
}

export async function refreshToken(minValidity = 30) {
  const keycloak = getKeycloak();
  const refreshed = await keycloak.updateToken(minValidity);
  if (refreshed) {
    scheduleRefresh();
  }
  return refreshed;
}

export async function login(options: LoginOptions = {}) {
  const keycloak = getKeycloak();
  await keycloak.login({
    loginHint: options.email,
    redirectUri: options.redirectUri ?? getDefaultRedirectUri(),
    scope: "openid profile email",
  });
}

export async function loginWithGoogle(options: SocialLoginOptions = {}) {
  const keycloak = getKeycloak();
  await keycloak.login({
    idpHint: options.idpHint ?? "google",
    loginHint: options.email,
    redirectUri: options.redirectUri ?? getDefaultRedirectUri(),
    scope: "openid profile email",
  });
}

export async function register(options: RedirectOptions = {}) {
  const keycloak = getKeycloak();
  await keycloak.register({
    redirectUri: options.redirectUri ?? getDefaultRedirectUri(),
  });
}

export async function forgotPassword(options: SocialLoginOptions = {}) {
  if (typeof window === "undefined") return;
  const baseUrl = `${keycloakUrl}/realms/${keycloakRealm}/login-actions/reset-credentials`;
  const url = new URL(baseUrl);
  url.searchParams.set("client_id", keycloakClientId ?? "");
  url.searchParams.set("redirect_uri", options.redirectUri ?? `${window.location.origin}/login`);
  if (options.email) {
    url.searchParams.set("login_hint", options.email);
  }

  window.location.assign(url.toString());
}

export async function logout(options: RedirectOptions = {}) {
  const keycloak = getKeycloak();
  if (refreshTimer) {
    clearTimeout(refreshTimer);
    refreshTimer = null;
  }
  initPromise = null;
  await keycloak.logout({
    redirectUri: options.redirectUri ?? `${window.location.origin}/login`,
  });
}

export function isAuthenticated() {
  return Boolean(getKeycloak().authenticated);
}

export function getAccessToken() {
  return getKeycloak().token ?? null;
}

export function onSessionExpired(handler: () => void) {
  getKeycloak().onTokenExpired = handler;
}
