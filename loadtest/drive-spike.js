// ADR-006 design peak: a popular drive opens and 1,500 students become active within minutes. Each VU is one
// student (own Keycloak session) issuing one API call every 3-5 s: open drives, drive detail, apply (idempotent),
// dashboard, unread notification count.
//
//   loadtest/run.sh drive-spike                       VUS=1500 RAMP=3m HOLD=5m RAMP_DOWN=1m
import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";
import { ORG, API, accessToken, between, params, pick, seed, students, summary, tls } from "./lib/common.js";

const VUS = Number(__ENV.VUS || 1500);
const THINK_MIN = Number(__ENV.THINK_MIN || 3);
const THINK_MAX = Number(__ENV.THINK_MAX || 5);

const applied = new Counter("applications_created");
const reapplied = new Counter("applications_existing");
const applyConflicts = new Counter("apply_conflicts");
const loginFailures = new Counter("login_failures");

export const options = {
  ...tls,
  discardResponseBodies: false,
  scenarios: {
    spike: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || "3m", target: VUS },
        { duration: __ENV.HOLD || "5m", target: VUS },
        { duration: __ENV.RAMP_DOWN || "1m", target: 0 },
      ],
      gracefulRampDown: "30s",
    },
  },
  thresholds: {
    "http_req_duration{kind:read}": ["p(95)<300"],
    "http_req_duration{name:apply}": ["p(95)<500"],
    "http_req_failed{api:yes}": ["rate<0.001"],
    checks: ["rate>0.999"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function think() {
  sleep(between(THINK_MIN, THINK_MAX));
}

export default function () {
  const email = students[(__VU - 1) % students.length];
  const token = accessToken(email, seed.password);
  if (!token) {
    loginFailures.add(1);
    sleep(5);
    return;
  }

  const list = http.get(`${ORG}/campushire/student/drives?filter=open&size=20`, params(token, "student_drives"));
  check(list, { "open drives 200": (r) => r.status === 200 });
  think();

  let target = null;
  if (list.status === 200) {
    const items = list.json("items") || [];
    const eligible = items.filter((d) => d.eligibility && d.eligibility.eligible && d.registrationOpen);
    target = eligible.length ? pick(eligible) : items.length ? pick(items) : null;
  }
  const driveId = target ? target.id : pick(seed.openDriveIds);

  const detail = http.get(`${ORG}/modules/drive/records/${driveId}`, params(token, "drive_detail"));
  check(detail, { "drive detail 200": (r) => r.status === 200 });
  think();

  if (target && target.eligibility && target.eligibility.eligible) {
    const apply = http.post(`${ORG}/campushire/drives/${driveId}/apply`, null,
      params(token, "apply", "write", { responseCallback: http.expectedStatuses(200, 201, 409) }));
    check(apply, { "apply 200/201": (r) => r.status === 200 || r.status === 201 || r.status === 409 });
    if (apply.status === 201) applied.add(1);
    else if (apply.status === 200) reapplied.add(1);
    else if (apply.status === 409) applyConflicts.add(1);
    think();
  }

  const dashboard = http.get(`${ORG}/campushire/dashboard`, params(token, "dashboard"));
  check(dashboard, { "dashboard 200": (r) => r.status === 200 });
  think();

  const unread = http.get(`${API}/notifications/unread-count`, params(token, "unread_count"));
  check(unread, { "unread count 200": (r) => r.status === 200 });
  think();
}

export const handleSummary = summary("drive-spike");
