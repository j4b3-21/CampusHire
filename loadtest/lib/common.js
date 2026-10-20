// Shared configuration and seed data (loadtest/data/seed.json, written by scripts/seed/seed.py) for the k6 scenarios.
import { SharedArray } from "k6/data";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.1.0/index.js";
import { createAuth } from "./auth.js";

export const BASE_URL = (__ENV.BASE_URL || "https://nginx").replace(/\/$/, "");
export const API = `${BASE_URL}/api/v1`;
export const KEYCLOAK_URL = (__ENV.KEYCLOAK_URL || `${BASE_URL}/auth`).replace(/\/$/, "");

const seedFile = __ENV.SEED_FILE || "/loadtest/data/seed.json";
const seedData = JSON.parse(open(seedFile));
export const seed = {
  orgId: seedData.orgId,
  realm: seedData.realm || "campushire",
  clientId: __ENV.CLIENT_ID || seedData.clientId,
  password: seedData.password,
  tpo: seedData.tpo,
  coordinators: (seedData.staff && seedData.staff.coordinators) || [],
  departments: seedData.departments || {},
  openDriveIds: seedData.openDriveIds || [],
  driveIds: seedData.driveIds || [],
  historicalDriveIds: seedData.historicalDriveIds || [],
};
export const students = new SharedArray("students", () => seedData.students);
export const ORG = `${API}/orgs/${seed.orgId}`;

export function between(min, max) {
  return min + Math.random() * (max - min);
}

export function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

export const accessToken = createAuth({ keycloakUrl: KEYCLOAK_URL, realm: seed.realm, clientId: seed.clientId });

export function params(token, name, kind = "read", extra = {}) {
  return {
    headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
    tags: { name, kind, api: "yes" },
    ...extra,
  };
}

export function summary(scenario) {
  return (data) => {
    const prefix = __ENV.RESULT_PREFIX || `/results/${scenario}`;
    return {
      stdout: textSummary(data, { indent: " ", enableColors: false }),
      [`${prefix}.summary.txt`]: textSummary(data, { indent: " ", enableColors: false }),
      [`${prefix}.json`]: JSON.stringify(data, null, 1),
    };
  };
}

export const tls = { insecureSkipTLSVerify: (__ENV.INSECURE || "true") === "true" };
