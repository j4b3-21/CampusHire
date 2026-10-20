// Staff bulk operations: import 1,000 student rows (UPSERT on roll number through the data-exchange pipeline, polled
// to completion), full student exports (XLSX, CSV, one department) and the placement report.
//
//   loadtest/run.sh staff                             ITERATIONS=3 ROWS=1000
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";
import { ORG, accessToken, params, seed, students, summary, tls } from "./lib/common.js";

const ROWS = Number(__ENV.ROWS || 1000);
const importDuration = new Trend("import_job_duration", true);
const importRate = new Trend("import_rows_per_second");
const exportDuration = new Trend("export_duration", true);

export const options = {
  ...tls,
  scenarios: {
    staff: { executor: "per-vu-iterations", vus: 1, iterations: Number(__ENV.ITERATIONS || 3), maxDuration: "30m" },
  },
  thresholds: {
    import_job_duration: ["p(95)<60000"],
    export_duration: ["p(95)<15000"],
    "http_req_failed{api:yes}": ["rate<0.001"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(95)", "max"],
};

function csvFor(iteration) {
  const lines = ["roll_number,skills"];
  for (let i = 0; i < Math.min(ROWS, students.length); i++) {
    const roll = students[i].split("@")[0].toUpperCase();
    lines.push(`${roll},"Java, SQL, k6 iteration ${iteration}"`);
  }
  return lines.join("\n");
}

function token() {
  return accessToken(seed.tpo.email, seed.tpo.password);
}

export default function () {
  const options = { mode: "UPSERT", matchField: "roll_number", mapping: { roll_number: "roll_number", skills: "skills" } };
  const body = {
    file: http.file(csvFor(__ITER), "students-update.csv", "text/csv"),
    options: http.file(JSON.stringify(options), "options.json", "application/json"),
  };
  const started = Date.now();
  const submit = http.post(`${ORG}/modules/student/imports`, body, params(token(), "import_submit", "write"));
  check(submit, { "import accepted": (r) => r.status === 202 });
  if (submit.status === 202) {
    let job = submit.json();
    while (job.status === "QUEUED" || job.status === "RUNNING") {
      sleep(1);
      job = http.get(`${ORG}/modules/student/imports/${job.id}`, params(token(), "import_poll")).json();
    }
    const elapsed = Date.now() - started;
    check(job, { "import completed without row errors": (j) => j.status === "COMPLETED" && j.failedRows === 0 });
    importDuration.add(elapsed);
    importRate.add((job.processedRows || 0) / (elapsed / 1000));
  }

  const exports = [
    ["export_students_xlsx", "/modules/student/export?format=xlsx"],
    ["export_students_csv", "/modules/student/export?format=csv"],
    ["export_department_xlsx", `/modules/student/export?format=xlsx&filter%5Bdepartment%5D=${Object.values(seed.departments)[0]}`],
    ["placement_report_xlsx", "/campushire/reports/placement?format=xlsx"],
  ];
  for (const [name, path] of exports) {
    const response = http.get(`${ORG}${path}`,
      params(token(), name, "export", { responseType: "none", timeout: "120s" }));
    check(response, { [`${name} 200`]: (r) => r.status === 200 });
    exportDuration.add(response.timings.duration, { name });
  }
  sleep(2);
}

export const handleSummary = summary("staff");
