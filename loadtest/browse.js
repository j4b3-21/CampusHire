// Mixed staff reads: placement officer and department coordinators browsing record lists with filters, sorting
// and search, analytics, applicants, eligibility previews and dashboards.
//
//   loadtest/run.sh browse                            VUS=100 DURATION=5m (think time 1-3 s)
import http from "k6/http";
import { check, sleep } from "k6";
import { ORG, accessToken, between, params, pick, seed, summary, tls } from "./lib/common.js";

const VUS = Number(__ENV.VUS || 100);
const staff = [seed.tpo.email, ...seed.coordinators];
const departmentIds = Object.values(seed.departments);
const searches = ["kumar", "priya", "reddy", "CS23", "nair", "iyer"];

export const options = {
  ...tls,
  scenarios: {
    browse: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP || "1m", target: VUS },
        { duration: __ENV.DURATION || "5m", target: VUS },
        { duration: __ENV.RAMP_DOWN || "30s", target: 0 },
      ],
    },
  },
  thresholds: {
    "http_req_duration{kind:read}": ["p(95)<300"],
    "http_req_duration{kind:heavy}": ["p(95)<1500"],
    "http_req_failed{api:yes}": ["rate<0.001"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function passwordFor(email) {
  return email === seed.tpo.email ? seed.tpo.password : seed.password;
}

const requests = [
  // [weight, name, kind, path builder]
  [6, "students_by_department", "read",
    () => `/modules/student/records?filter%5Bdepartment%5D=${pick(departmentIds)}&sort=-cgpa&size=50`],
  [4, "students_cgpa_filter", "read",
    () => `/modules/student/records?filter%5Bcgpa%5D%5Bgte%5D=8.5&filter%5Bactive_backlogs%5D=0&sort=roll_number&size=50`],
  [4, "students_search", "read", () => `/modules/student/records?q=${pick(searches)}&size=20`],
  [3, "companies", "read", () => `/modules/company/records?sort=name&size=50`],
  [3, "drives", "read", () => `/modules/drive/records?sort=-created_at&size=20`],
  [3, "applications", "read", () => `/modules/application/records?filter%5Bstage%5D=shortlisted&size=50`],
  [4, "applicants", "read", () => `/campushire/drives/${pick(seed.historicalDriveIds)}/applicants?sort=-cgpa&size=50`],
  [3, "analytics_overview", "read", () => `/campushire/analytics/overview`],
  [3, "analytics_departments", "read", () => `/campushire/analytics/departments`],
  [2, "analytics_companies", "read", () => `/campushire/analytics/companies?limit=10`],
  [4, "dashboard", "read", () => `/campushire/dashboard`],
  [1, "eligibility_preview", "heavy", () => `/campushire/drives/${pick(seed.openDriveIds)}/eligibility/preview?sample=10`],
];
const weighted = requests.flatMap((entry) => Array(entry[0]).fill(entry));

export default function () {
  const email = staff[(__VU - 1) % staff.length];
  const token = accessToken(email, passwordFor(email));
  if (!token) {
    sleep(5);
    return;
  }
  const [, name, kind, path] = pick(weighted);
  const response = http.get(`${ORG}${path()}`, params(token, name, kind));
  check(response, { [`${name} 200`]: (r) => r.status === 200 });
  sleep(between(1, 3));
}

export const handleSummary = summary("browse");
