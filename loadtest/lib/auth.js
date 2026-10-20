// Keycloak sessions for k6 virtual users: one access token per VU through the password grant of a dedicated
// load-test client, refreshed a minute before expiry.
//
// DEV / STAGING ONLY. Production realms have no password-grant client. The seeder creates the temporary public client
// `campushire-loadtest` (scripts/seed/seed.py --loadtest-client) and removes it again with
// --remove-loadtest-client; on the dev stack the realm's `campushire-cli` client is used instead.
import http from "k6/http";

export function createAuth({ keycloakUrl, realm, clientId }) {
  const tokenUrl = `${keycloakUrl}/realms/${realm}/protocol/openid-connect/token`;

  function tokenRequest(form) {
    const response = http.post(tokenUrl, form, { tags: { name: "keycloak_token", kind: "auth" } });
    if (response.status !== 200) {
      return null;
    }
    const body = response.json();
    const now = Date.now() / 1000;
    return {
      access: body.access_token,
      refresh: body.refresh_token,
      expiresAt: now + body.expires_in,
      refreshExpiresAt: now + (body.refresh_expires_in || 0),
    };
  }

  // Module scope is per VU in k6, so this is one session per virtual user.
  let session = null;
  let sessionUser = null;

  return function accessToken(username, password) {
    const now = Date.now() / 1000;
    if (session && sessionUser === username && now < session.expiresAt - 60) {
      return session.access;
    }
    let next = null;
    if (session && sessionUser === username && session.refresh && now < session.refreshExpiresAt - 30) {
      next = tokenRequest({ grant_type: "refresh_token", client_id: clientId, refresh_token: session.refresh });
    }
    if (!next) {
      next = tokenRequest({ grant_type: "password", client_id: clientId, username, password, scope: "openid" });
    }
    session = next;
    sessionUser = next ? username : null;
    return next ? next.access : null;
  };
}
