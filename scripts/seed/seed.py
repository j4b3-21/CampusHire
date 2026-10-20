#!/usr/bin/env python3
"""Seeds a CampusHire installation with a realistic demo institute (Python 3.9+, standard library only).

Creates through the Keycloak admin API and the CampusHire/BM4A APIs: the institute "IIIT Tiruchirappalli (Demo)"
(allowed domain iiitt.ac.in) with setup, departments, batches, N students imported through the data-exchange import
API (CSV, UPSERT on roll number), companies with contacts and follow-ups, jobs, drives in every state with
eligibility rules, applications moved through the pipeline, offers, staff accounts (TPO, coordinators, recruiters
via invitations) and K student accounts that claim their records through onboarding.

Re-running is safe: an existing institute is reused (students are re-imported idempotently with UPSERT, accounts and
claims are ensured, departments/batches/companies/drives are not duplicated). --reset deletes the seeded Keycloak
users and archives the institute (organizations cannot be hard-deleted), so the next run starts from scratch.

Examples:
  scripts/seed/seed.py --target dev                                   # demo data on the dev stack (make seed)
  scripts/seed/seed.py --target prod --students 10000 --student-accounts 1500 --loadtest-client  # make seed-load
  scripts/seed/seed.py --target prod --remove-loadtest-client         # after load testing

Writes loadtest/data/seed.json (git-ignored; contains the account password) for the k6 scenarios.
"""

import argparse
import concurrent.futures as futures
import csv
import datetime as dt
import io
import json
import os
import random
import re
import secrets
import ssl
import string
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import data  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
LOADTEST_CLIENT = "campushire-loadtest"
RECRUITER_COMPANIES = 5
RECRUITER_DOMAIN = "recruiters.example.com"
DEV_REALM = ROOT / "infrastructure" / "keycloak" / "realms" / "campushire-realm.json"
UTC = dt.timezone.utc


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def read_env_file(path):
    values = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key.strip()] = value
    return values


# ----------------------------------------------------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------------------------------------------------

class ApiError(Exception):
    def __init__(self, method, url, status, body):
        self.status = status
        self.body = body
        try:
            self.json = json.loads(body) if body else {}
        except ValueError:
            self.json = {}
        self.code = self.json.get("code") if isinstance(self.json, dict) else None
        super().__init__(f"{method} {url} -> {status} {self.code or ''} {body[:400]}")


class Http:
    def __init__(self, insecure):
        self.context = ssl._create_unverified_context() if insecure else ssl.create_default_context()

    def request(self, method, url, token=None, body=None, headers=None, raw=None, content_type=None, attempts=8):
        headers = dict(headers or {})
        payload = None
        if body is not None:
            payload = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        elif raw is not None:
            payload = raw
            if content_type:
                headers["Content-Type"] = content_type
        if token:
            headers["Authorization"] = f"Bearer {token() if callable(token) else token}"
        headers.setdefault("Accept", "application/json")
        delay = 1.0
        for attempt in range(1, attempts + 1):
            request = urllib.request.Request(url, data=payload, method=method, headers=headers)
            try:
                with urllib.request.urlopen(request, context=self.context, timeout=120) as response:
                    text = response.read().decode() if response.length != 0 else ""
                    parsed = json.loads(text) if text and "json" in (response.headers.get("Content-Type") or "") else text
                    return response.status, response.headers, parsed
            except urllib.error.HTTPError as error:
                text = error.read().decode(errors="replace")
                if error.code in (429, 502, 503, 504) and attempt < attempts:
                    wait = float(error.headers.get("Retry-After") or delay)
                    time.sleep(min(max(wait, 0.5), 30))
                    delay = min(delay * 2, 15)
                    continue
                raise ApiError(method, url, error.code, text) from None
            except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
                if attempt == attempts:
                    raise
                time.sleep(delay)
                delay = min(delay * 2, 15)
        raise RuntimeError("unreachable")


class Keycloak:
    def __init__(self, http, base_url, realm, admin_user, admin_password):
        self.http = http
        self.base = base_url.rstrip("/")
        self.realm = realm
        self.admin_user = admin_user
        self.admin_password = admin_password
        self._admin = None
        self._admin_expiry = 0
        self._lock = threading.Lock()

    def password_grant(self, realm, client_id, username, password):
        form = urllib.parse.urlencode({"grant_type": "password", "client_id": client_id, "username": username,
                                       "password": password, "scope": "openid"}).encode()
        _, _, body = self.http.request("POST", f"{self.base}/realms/{realm}/protocol/openid-connect/token", raw=form,
                                       content_type="application/x-www-form-urlencoded")
        return body

    def admin_token(self):
        with self._lock:
            if not self._admin or time.time() > self._admin_expiry:
                if not self.admin_password:
                    raise SystemExit("Keycloak admin password missing (--admin-password or the env file)")
                body = self.password_grant("master", "admin-cli", self.admin_user, self.admin_password)
                self._admin = body["access_token"]
                self._admin_expiry = time.time() + int(body.get("expires_in", 60)) - 15
            return self._admin

    def admin(self, method, path, body=None, **kwargs):
        url = f"{self.base}/admin/realms/{self.realm}{path}"
        return self.http.request(method, url, token=self.admin_token, body=body, **kwargs)

    def find_user(self, email):
        _, _, users = self.admin("GET", "/users?" + urllib.parse.urlencode({"email": email, "exact": "true"}))
        return users[0] if users else None

    def ensure_user(self, email, first, last, password, client_id):
        user = self.find_user(email)
        if not user:
            self.admin("POST", "/users", {"username": email, "email": email, "firstName": first, "lastName": last,
                                          "enabled": True, "emailVerified": True,
                                          "credentials": [{"type": "password", "value": password,
                                                           "temporary": False}]})
            return True
        try:
            self.password_grant(self.realm, client_id, email, password)
        except ApiError:
            self.admin("PUT", f"/users/{user['id']}/reset-password",
                       {"type": "password", "value": password, "temporary": False})
        return False

    def search_users(self, text):
        found, first = [], 0
        while True:
            _, _, page = self.admin("GET", "/users?" + urllib.parse.urlencode(
                {"search": f"*{text}*", "first": first, "max": 200, "briefRepresentation": "true"}))
            found.extend(page)
            if len(page) < 200:
                return found
            first += 200

    def delete_user(self, user_id):
        self.admin("DELETE", f"/users/{user_id}")

    def import_users(self, users):
        _, _, result = self.admin("POST", "/partialImport", {"ifResourceExists": "SKIP", "users": users})
        return result.get("added", 0), result.get("skipped", 0)

    def client(self, client_id):
        _, _, clients = self.admin("GET", "/clients?" + urllib.parse.urlencode({"clientId": client_id}))
        return clients[0] if clients else None

    def ensure_loadtest_client(self):
        if self.client(LOADTEST_CLIENT):
            return False
        self.admin("POST", "/clients", {
            "clientId": LOADTEST_CLIENT, "name": "CampusHire load test (TEMPORARY)",
            "description": "Password grant for seed/k6 runs. Remove with seed.py --remove-loadtest-client.",
            "enabled": True, "protocol": "openid-connect", "publicClient": True, "standardFlowEnabled": False,
            "implicitFlowEnabled": False, "directAccessGrantsEnabled": True, "serviceAccountsEnabled": False,
            "defaultClientScopes": ["basic", "acr", "profile", "email", "roles"],
            "protocolMappers": [{"name": "campushire-api-audience", "protocol": "openid-connect",
                                 "protocolMapper": "oidc-audience-mapper", "consentRequired": False,
                                 "config": {"included.client.audience": "campushire-api",
                                            "access.token.claim": "true", "id.token.claim": "false",
                                            "introspection.token.claim": "true"}}]})
        return True

    def remove_loadtest_client(self):
        existing = self.client(LOADTEST_CLIENT)
        if existing:
            self.admin("DELETE", f"/clients/{existing['id']}")
        return existing is not None


class TokenSource:
    """Access token of one user; logs in again shortly before expiry (thread-safe)."""

    def __init__(self, keycloak, client_id, username, password):
        self.keycloak = keycloak
        self.client_id = client_id
        self.username = username
        self.password = password
        self._token = None
        self._expiry = 0
        self._lock = threading.Lock()

    def __call__(self):
        with self._lock:
            if not self._token or time.time() > self._expiry:
                body = self.keycloak.password_grant(self.keycloak.realm, self.client_id, self.username, self.password)
                self._token = body["access_token"]
                self._expiry = time.time() + int(body.get("expires_in", 300)) - 30
            return self._token


class Api:
    def __init__(self, http, base_url, token):
        self.http = http
        self.base = base_url.rstrip("/") + "/api/v1"
        self.token = token

    def call(self, method, path, body=None, params=None, headers=None):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        return self.http.request(method, url, token=self.token, body=body, headers=headers)

    def get(self, path, **params):
        return self.call("GET", path, params=params)[2]

    def post(self, path, body=None, headers=None):
        return self.call("POST", path, body=body if body is not None else {}, headers=headers)[2]

    def post_empty(self, path, headers=None):
        return self.http.request("POST", self.base + path, token=self.token, headers=headers)[2]

    def patch(self, path, body, version):
        return self.call("PATCH", path, body=body, headers={"If-Match": f'"{version}"'})[2]

    def upload(self, path, filename, content, options):
        boundary = "----campushire" + uuid.uuid4().hex
        parts = [
            (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
             f"Content-Type: text/csv\r\n\r\n").encode() + content + b"\r\n",
            (f'--{boundary}\r\nContent-Disposition: form-data; name="options"\r\n'
             f"Content-Type: application/json\r\n\r\n{json.dumps(options)}\r\n").encode(),
            f"--{boundary}--\r\n".encode(),
        ]
        return self.http.request("POST", self.base + path, token=self.token, raw=b"".join(parts),
                                 content_type=f"multipart/form-data; boundary={boundary}")[2]

    def records(self, org, module, **params):
        items, page = [], 0
        while True:
            body = self.get(f"/orgs/{org}/modules/{module}/records", page=page, size=100, **params)
            items.extend(body["items"])
            page += 1
            if page >= body["totalPages"]:
                return items

    def create(self, org, module, values):
        return self.post(f"/orgs/{org}/modules/{module}/records", {"data": values})


# ----------------------------------------------------------------------------------------------------------------
# Generated data
# ----------------------------------------------------------------------------------------------------------------

def weighted_counts(total, shares):
    counts = [int(total * share) for share in shares]
    for i in range(total - sum(counts)):
        counts[i % len(counts)] += 1
    return counts


def slug(text):
    return "".join(ch for ch in text.lower() if ch.isalnum())


def generate_students(total, rng):
    students = []
    batch_counts = weighted_counts(total, [b[3] for b in data.BATCHES])
    for (batch_name, program, grad_year, _), batch_total in zip(data.BATCHES, batch_counts):
        start_year = grad_year - (2 if program == "mtech" else 4)
        yy = str(start_year)[2:]
        letter = "M" if program == "mtech" else "B"
        departments = [d for d in data.DEPARTMENTS if program != "mtech" or d[0] in data.MTECH_DEPARTMENTS]
        dept_counts = weighted_counts(batch_total, [d[4] for d in departments])
        for (code, prefix, _, _, _), count in zip(departments, dept_counts):
            for seq in range(1, count + 1):
                female = rng.random() < 0.32
                first = rng.choice(data.FEMALE_FIRST if female else data.MALE_FIRST)
                last = rng.choice(data.SURNAMES)
                roll = f"{prefix}{yy}{letter}{seq:04d}"
                cgpa = round(min(9.8, max(5.5, rng.gauss(7.65, 0.85))), 2)
                active = 0 if rng.random() < 0.86 or cgpa > 8.3 else rng.choice([1, 1, 2, 3])
                total_backlogs = active + (rng.choice([0, 0, 0, 1, 2]) if cgpa < 8.0 else 0)
                status = "unplaced"
                if batch_name == data.FINAL_YEAR_BATCH:
                    roll_dice = rng.random()
                    status = "higher_studies" if roll_dice < 0.03 else "opted_out" if roll_dice < 0.05 else "unplaced"
                birth = dt.date(start_year - (22 if program == "mtech" else 18), 1, 1) + \
                    dt.timedelta(days=rng.randint(0, 364))
                skills = ", ".join(rng.sample(data.SKILLS, rng.randint(3, 6)))
                handle = f"{first}-{last}-{roll[-4:]}".lower().replace("'", "")
                students.append({
                    "roll_number": roll,
                    "full_name": f"{first} {last}",
                    "email": f"{roll.lower()}@{data.EMAIL_DOMAIN}",
                    "phone": f"+91{rng.choice('6789')}{rng.randint(100000000, 999999999)}",
                    "gender": "female" if female else "male",
                    "date_of_birth": birth.isoformat(),
                    "department": code,
                    "batch": batch_name,
                    "program": program,
                    "cgpa": f"{cgpa:.2f}",
                    "active_backlogs": str(active),
                    "total_backlogs": str(total_backlogs),
                    "tenth_percentage": f"{min(99.6, max(62.0, rng.gauss(89, 5.5))):.1f}",
                    "twelfth_percentage": f"{min(98.8, max(60.0, rng.gauss(86, 6.5))):.1f}",
                    "skills": skills,
                    "linkedin_url": f"https://www.linkedin.com/in/{handle}",
                    "github_url": f"https://github.com/{handle}" if rng.random() < 0.6 else "",
                    "placement_status": status,
                    "profile_verified": "yes" if rng.random() < 0.7 else "no",
                })
    return students


def students_csv(students):
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(students[0].keys()), lineterminator="\n")
    writer.writeheader()
    writer.writerows(students)
    return buffer.getvalue().encode()


def money(amount):
    return {"amount": f"{amount:.2f}", "currency": "INR"}


def iso(moment):
    return moment.astimezone(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


# ----------------------------------------------------------------------------------------------------------------
# Seeder
# ----------------------------------------------------------------------------------------------------------------

class Seeder:
    def __init__(self, args):
        self.args = args
        self.rng = random.Random(args.seed)
        self.http = Http(args.insecure)
        self.kc = Keycloak(self.http, args.keycloak_url, args.realm, args.admin_user, args.admin_password)
        self.pool = futures.ThreadPoolExecutor(max_workers=args.workers)
        self.now = dt.datetime.now(UTC)
        self.org = None
        self.departments = {}
        self.batches = {}
        self.student_ids = {}
        self.account_rolls = []
        self.companies = []
        self.drives = []
        self.stats = {}

    # ---- helpers ----

    def parallel(self, fn, items, label):
        done, failures = 0, 0
        results = []
        jobs = [self.pool.submit(fn, item) for item in items]
        for job in futures.as_completed(jobs):
            try:
                results.append(job.result())
            except Exception as error:  # noqa: BLE001 - report and continue
                failures += 1
                if failures <= 5:
                    log(f"  {label} failed: {error}")
            done += 1
            if done % 500 == 0:
                log(f"  {label}: {done}/{len(items)}")
        if failures:
            log(f"  {label}: {failures} of {len(items)} failed")
        return results

    def tpo(self):
        return Api(self.http, self.args.api_url, self.tpo_token)

    def as_user(self, email):
        return Api(self.http, self.args.api_url,
                   TokenSource(self.kc, self.args.client_id, email, self.args.password))

    # ---- steps ----

    def run(self):
        args = self.args
        if args.remove_loadtest_client:
            removed = self.kc.remove_loadtest_client()
            log(f"load-test client {LOADTEST_CLIENT} {'removed' if removed else 'was not present'}")
            return
        if args.loadtest_client and self.kc.ensure_loadtest_client():
            log(f"created temporary Keycloak client {LOADTEST_CLIENT} (password grant)")

        created = self.kc.ensure_user(args.tpo_email, "Priya", "Raman", args.tpo_password, args.client_id)
        log(f"placement officer {args.tpo_email} {'created' if created else 'ready'}")
        self.tpo_token = TokenSource(self.kc, args.client_id, args.tpo_email, args.tpo_password)
        api = self.tpo()
        if args.reset:
            self.reset(api)
            return

        fresh = self.ensure_organization(api)
        self.ensure_structure(api)
        self.import_students(api)
        if fresh or not api.records(self.org, "company"):
            self.create_companies(api)
        else:
            self.load_companies(api)
        self.ensure_staff_accounts(api)
        self.ensure_student_accounts()
        if fresh or not self.load_drives(api):
            self.create_drives(api)
            self.seed_history(api)
            self.seed_open_applications()
        self.write_output()
        log("done: " + ", ".join(f"{k}={v}" for k, v in self.stats.items()))

    def reset(self, api):
        """Deletes the seeded Keycloak accounts and archives the demo institute (organizations are only ever archived:
        their records, history and audit trail stay in the database)."""
        protected = {self.args.tpo_email.lower()}
        if DEV_REALM.exists():
            protected |= {(u.get("email") or u.get("username", "")).lower()
                          for u in json.loads(DEV_REALM.read_text()).get("users", [])}
        student = re.compile(r"^[a-z]{2}\d{2}[bm]\d{4}@" + re.escape(data.EMAIL_DOMAIN) + "$")
        users = {u["id"]: u for u in self.kc.search_users("@" + data.EMAIL_DOMAIN) + self.kc.search_users(
            "@" + RECRUITER_DOMAIN) + self.kc.search_users("coordinator.")}
        doomed = []
        for user in users.values():
            email = (user.get("email") or user.get("username") or "").lower()
            if email in protected:
                continue
            if student.match(email) or email.endswith("@" + RECRUITER_DOMAIN) or \
                    re.match(r"^coordinator\.[a-z]+@campushire\.dev$", email):
                doomed.append(user["id"])
        self.parallel(self.kc.delete_user, doomed, "delete Keycloak users")
        log(f"deleted {len(doomed)} demo Keycloak users (kept {', '.join(sorted(protected))})")
        for org in api.get("/orgs"):
            if org["name"] == data.INSTITUTE_NAME and org["status"] == "ACTIVE" and org["role"] == "OWNER":
                api.call("DELETE", f"/orgs/{org['id']}", headers={"If-Match": f'"{org["version"]}"'})
                log(f"archived demo institute {org['id']} (data stays in the database; it cannot be hard-deleted)")
        out = Path(self.args.out)
        if out.exists():
            out.unlink()
        log("reset complete; run again without --reset to seed a fresh demo institute")

    def ensure_organization(self, api):
        existing = [o for o in api.get("/orgs") if o["name"] == data.INSTITUTE_NAME and o["status"] == "ACTIVE"]
        if existing:
            self.org = existing[0]["id"]
            log(f"reusing institute {data.INSTITUTE_NAME} ({self.org}); --reset starts over")
            fresh = False
        else:
            _, _, org = api.call("POST", "/orgs", body={"name": data.INSTITUTE_NAME})
            self.org = org["id"]
            log(f"created institute {data.INSTITUTE_NAME} ({self.org})")
            fresh = True
        setup = api.post(f"/orgs/{self.org}/campushire/setup", {"allowedEmailDomains": [data.EMAIL_DOMAIN]})
        log(f"app pack {setup['report']['status']} (version {setup['report']['version']})")
        return fresh

    def ensure_structure(self, api):
        existing = {r["data"]["code"]: r["id"] for r in api.records(self.org, "department")}
        for code, _, name, hod, _ in data.DEPARTMENTS:
            if code not in existing:
                existing[code] = api.create(self.org, "department", {
                    "name": name, "code": code, "hod_name": hod,
                    "coordinator_email": f"coordinator.{code.lower()}@campushire.dev"})["id"]
        self.departments = existing
        existing = {r["data"]["name"]: r["id"] for r in api.records(self.org, "batch")}
        for name, program, year, _ in data.BATCHES:
            if name not in existing:
                existing[name] = api.create(self.org, "batch", {"name": name, "program": program,
                                                                "graduation_year": year})["id"]
        self.batches = existing
        log(f"{len(self.departments)} departments, {len(self.batches)} batches")

    def import_students(self, api):
        students = generate_students(self.args.students, self.rng)
        self.students = students
        mapping = {key: key for key in students[0]}
        path = f"/orgs/{self.org}/modules/student/imports"
        log(f"importing {len(students)} students (CSV, UPSERT on roll_number)")
        while True:
            try:
                job = api.upload(path, "students.csv", students_csv(students),
                                 {"mode": "UPSERT", "matchField": "roll_number", "mapping": mapping})
                break
            except ApiError as error:
                if error.code != "import_in_progress":
                    raise
                log("  another student import is running; waiting")
                time.sleep(5)
        started = time.time()
        while job["status"] in ("QUEUED", "RUNNING"):
            time.sleep(2)
            job = api.get(f"{path}/{job['id']}")
            log(f"  import {job['status']}: {job['processedRows']}/{job['totalRows'] or '?'} rows")
        elapsed = time.time() - started
        if job["status"] != "COMPLETED":
            raise SystemExit(f"student import {job['status']}: {job.get('errorMessage')}")
        log(f"  created {job['createdRows']}, updated {job['updatedRows']}, failed {job['failedRows']} "
            f"in {elapsed:.0f}s ({len(students) / max(elapsed, 1):.0f} rows/s)")
        for err in job.get("errors", [])[:5]:
            log(f"  row {err['row']}: {err.get('column')} {err['code']} {err['message']}")
        self.stats["students"] = len(students)
        self.stats["import_rows_per_s"] = round(len(students) / max(elapsed, 1))

    def item_rng(self, *key):
        """Independent generator per item, so parallel workers stay deterministic for a given --seed."""
        return random.Random(":".join(str(part) for part in (self.args.seed, *key)))

    def create_companies(self, api):
        stages = ["lead"] * 4 + ["contacted"] * 3 + ["confirmed"] * 3 + ["visited"] * 5 + ["inactive"]
        chosen = data.spread(data.COMPANIES, self.args.companies)
        log(f"creating {len(chosen)} companies with contacts, follow-ups and jobs")

        def one(company):
            name, industry, tier, hq, ctc, role, job_type = company
            rng = self.item_rng("company", name)
            domain = f"{slug(name)}.example.com"
            follow = (self.now + dt.timedelta(days=rng.randint(-5, 20))).date().isoformat()
            record = api.create(self.org, "company", {
                "name": name, "industry": industry, "tier": tier, "pipeline_stage": rng.choice(stages),
                "website": f"https://www.{domain}", "headquarters": hq, "next_follow_up": follow,
                "notes": f"Visited campus in 2025. Prefers {rng.choice(['CSE/AIDS', 'all circuit branches', 'all branches'])}."})
            contacts = []
            for primary in (True, False):
                first = rng.choice(data.FEMALE_FIRST + data.MALE_FIRST)
                last = rng.choice(data.SURNAMES)
                contacts.append(api.create(self.org, "contact", {
                    "full_name": f"{first} {last}", "company": record["id"],
                    "designation": rng.choice(data.DESIGNATIONS),
                    "email": f"{slug(first)}.{slug(last)}@{domain}",
                    "phone": f"+91{rng.choice('6789')}{rng.randint(100000000, 999999999)}", "is_primary": primary})["id"])
            for _ in range(rng.randint(1, 2)):
                due = (self.now + dt.timedelta(days=rng.randint(-6, 14))).date().isoformat()
                api.create(self.org, "follow_up", {
                    "title": rng.choice(data.FOLLOW_UP_TITLES), "company": record["id"], "contact": contacts[0],
                    "type": rng.choice(["call", "email", "meeting", "visit"]), "due_date": due,
                    "follow_up_status": "done" if rng.random() < 0.35 else "open"})
            jobs = []
            for variant in range(2 if tier in ("super_dream", "dream") else 1):
                internship = variant == 1
                values = {
                    "title": f"{role} Intern" if internship else role, "company": record["id"],
                    "job_type": "internship" if internship else job_type,
                    "ctc": money(min(data.CTC_MAX, max(data.CTC_MIN, ctc * rng.uniform(0.95, 1.08)))),
                    "location": hq, "description": f"{role} at {name}. Training in {hq}.",
                    "skills": ", ".join(rng.sample(data.SKILLS, 4))}
                if internship:
                    values["stipend"] = money(rng.choice([40000, 50000, 60000, 80000, 100000]))
                jobs.append(api.create(self.org, "job", values))
            return {"id": record["id"], "name": name, "tier": tier, "industry": industry,
                    "jobs": [{"id": j["id"], "type": j["data"]["job_type"],
                              "ctc": float(j["data"]["ctc"]["amount"])} for j in jobs]}

        self.companies = self.parallel(one, chosen, "companies")
        self.companies.sort(key=lambda c: [x[0] for x in data.COMPANIES].index(c["name"]))
        self.stats["companies"] = len(self.companies)

    def load_companies(self, api):
        jobs = {}
        for job in api.records(self.org, "job"):
            jobs.setdefault(job["data"]["company"], []).append(
                {"id": job["id"], "type": job["data"].get("job_type"),
                 "ctc": float((job["data"].get("ctc") or {}).get("amount", 0))})
        self.companies = [{"id": c["id"], "name": c["data"]["name"], "tier": c["data"].get("tier"),
                           "jobs": jobs.get(c["id"], [])} for c in api.records(self.org, "company")]
        log(f"reusing {len(self.companies)} companies")

    def accept_invitation(self, email, invite):
        if invite is None:
            return
        token = invite["acceptUrl"].rstrip("/").rsplit("/", 1)[-1]
        try:
            self.as_user(email).post_empty(f"/invitations/{token}/accept")
        except ApiError as error:
            if error.code not in ("already_member", "invitation_used"):
                raise

    def invite(self, api, path, email):
        for _ in range(2):
            try:
                return api.post(path, {"email": email})
            except ApiError as error:
                if error.code == "already_member":
                    return None
                if error.code != "invitation_pending":
                    raise
                pending = api.get(f"/orgs/{self.org}/invitations", status="PENDING", size=100)["items"]
                for invitation in pending:
                    if invitation["email"].lower() == email:
                        api.call("DELETE", f"/orgs/{self.org}/invitations/{invitation['id']}")
        raise RuntimeError(f"could not invite {email}")

    def ensure_staff_accounts(self, api):
        staff = []
        for code, *_ in data.DEPARTMENTS:
            staff.append((f"coordinator.{code.lower()}@campushire.dev", "coordinator", code))
        for company in self.companies[:RECRUITER_COMPANIES]:
            staff.append((f"hr.{slug(company['name'])[:24]}@{RECRUITER_DOMAIN}", "recruiter", company["id"]))
        self.staff = {"tpo": self.args.tpo_email, "coordinators": [], "recruiters": []}

        def one(entry):
            email, role, ref = entry
            names = random.Random(email)
            self.kc.ensure_user(email, names.choice(data.FEMALE_FIRST + data.MALE_FIRST), names.choice(data.SURNAMES),
                                self.args.password, self.args.client_id)
            path = (f"/orgs/{self.org}/campushire/departments/{self.departments[ref]}/coordinators/invite"
                    if role == "coordinator" else f"/orgs/{self.org}/campushire/companies/{ref}/recruiters/invite")
            self.accept_invitation(email, self.invite(api, path, email))
            return email, role

        for email, role in self.parallel(one, staff, "staff accounts"):
            self.staff["coordinators" if role == "coordinator" else "recruiters"].append(email)
        self.staff["coordinators"].sort()
        self.staff["recruiters"].sort()
        log(f"{len(self.staff['coordinators'])} coordinators and {len(self.staff['recruiters'])} recruiters ready")

    def ensure_student_accounts(self):
        wanted = self.args.student_accounts
        if wanted <= 0:
            return
        ordered = [s for s in self.students if s["batch"] == data.FINAL_YEAR_BATCH] + \
                  [s for s in self.students if s["batch"] == data.PRE_FINAL_BATCH] + \
                  [s for s in self.students if s["batch"] not in (data.FINAL_YEAR_BATCH, data.PRE_FINAL_BATCH)]
        # Spread accounts over departments instead of taking the first department's roll numbers.
        ordered.sort(key=lambda s: (s["batch"] != data.FINAL_YEAR_BATCH, s["batch"] != data.PRE_FINAL_BATCH,
                                    int(s["roll_number"][-4:]), s["department"]))
        chosen = ordered[:wanted]
        self.account_rolls = [s["roll_number"] for s in chosen]
        log(f"creating {len(chosen)} student accounts in Keycloak (partial import, existing users kept)")
        added = skipped = 0
        for start in range(0, len(chosen), 250):
            batch = [{"username": s["email"], "email": s["email"], "firstName": s["full_name"].split(" ")[0],
                      "lastName": s["full_name"].split(" ", 1)[1], "enabled": True, "emailVerified": True,
                      "credentials": [{"type": "password", "value": self.args.password, "temporary": False}]}
                     for s in chosen[start:start + 250]]
            a, k = self.kc.import_users(batch)
            added += a
            skipped += k
        log(f"  {added} added, {skipped} already existed; claiming student records through onboarding")

        def claim(student):
            body = self.as_user(student["email"]).post("/campushire/onboarding/claim", {"organizationId": self.org})
            return body.get("alreadyLinked", False)

        results = self.parallel(claim, chosen, "claims")
        log(f"  {len(results)} claimed ({sum(1 for r in results if r)} were already linked)")
        self.stats["student_accounts"] = len(results)

    # ---- drives and pipeline ----

    def eligibility(self, kind, rng):
        final = self.batches[data.FINAL_YEAR_BATCH]
        circuit = [self.departments[c] for c in ("CSE", "ECE", "AIDS", "EEE")]
        if kind == "internship":
            return {"minCgpa": 7.0, "maxActiveBacklogs": 0, "batches": [self.batches[data.PRE_FINAL_BATCH]],
                    "programs": ["btech"]}
        rules = {"minCgpa": rng.choice([6.0, 6.5, 7.0]), "maxActiveBacklogs": rng.choice([0, 1, 1]),
                 "batches": [final, self.batches[data.MTECH_BATCH]], "minTenth": 60, "minTwelfth": 60,
                 "excludePlaced": kind != "dream"}
        if rng.random() < 0.4:
            rules["departments"] = circuit
        return rules

    def create_drives(self, api):
        full_plan = (["completed"] * 10 + ["in_progress"] * 3 + ["registration_closed"] * 3 + ["open"] * 8 +
                     ["upcoming"] * 2 + ["draft"] * 3 + ["cancelled"])
        plan = data.spread(full_plan, self.args.drives) + ["open"] * max(0, self.args.drives - len(full_plan))
        companies = list(self.companies)
        self.rng.shuffle(companies)
        log(f"creating {len(plan)} drives ({', '.join(f'{plan.count(s)} {s}' for s in dict.fromkeys(plan))})")

        def one(index_state):
            index, state = index_state
            rng = self.item_rng("drive", index)
            company = companies[index % len(companies)]
            internship = state == "open" and plan[:index].count("open") % 4 == 3 and len(company["jobs"]) > 1
            job = company["jobs"][1] if internship else company["jobs"][0]
            kind = "internship" if job["type"] == "internship" else "dream" if job["ctc"] >= 1200000 else "regular"
            opens = self.now - dt.timedelta(days=2)
            closes = self.now + dt.timedelta(days=rng.randint(10, 20))
            if state == "upcoming":
                opens = self.now + dt.timedelta(days=rng.randint(2, 6))
                closes = opens + dt.timedelta(days=7)
            drive_day = (closes + dt.timedelta(days=rng.randint(3, 10))).date()
            title = f"{company['name']} {'Summer Internship 2027' if kind == 'internship' else 'Campus Hiring 2027'}"
            rules = self.eligibility(kind, rng)
            record = api.create(self.org, "drive", {
                "title": title, "company": company["id"], "job": job["id"],
                "registration_opens_at": iso(opens), "registration_closes_at": iso(closes),
                "drive_date": drive_day.isoformat(), "mode": rng.choice(["on_campus", "on_campus", "virtual"]),
                "venue": rng.choice(data.VENUES), "eligibility": rules,
                "description": f"{title}: online assessment, two technical rounds and an HR round."})
            drive = {"id": record["id"], "title": title, "state": state, "company": company["id"],
                     "ctc": job["ctc"], "kind": kind, "eligibility": rules, "version": record["version"]}
            base = f"/orgs/{self.org}/campushire/drives/{drive['id']}"
            steps = {"open": ["publish"], "upcoming": ["publish"], "registration_closed": ["publish", "close"],
                     "in_progress": ["publish", "close", "start"], "completed": ["publish", "close", "complete"],
                     "cancelled": ["publish", "cancel"], "draft": []}[state]
            for step in steps:
                drive["version"] = api.post_empty(f"{base}/{step}")["version"]
            if state == "completed":
                past = self.now - dt.timedelta(days=rng.randint(20, 70))
                try:
                    drive["version"] = api.patch(f"/orgs/{self.org}/modules/drive/records/{drive['id']}", {"data": {
                        "registration_opens_at": iso(past - dt.timedelta(days=12)),
                        "registration_closes_at": iso(past - dt.timedelta(days=5)),
                        "drive_date": past.date().isoformat()}}, drive["version"])["version"]
                except ApiError as error:
                    log(f"  could not back-date {title}: {error.code}")
            return drive

        self.drives = self.parallel(one, list(enumerate(plan)), "drives")
        self.stats["drives"] = len(self.drives)

    def load_drives(self, api):
        records = api.records(self.org, "drive")
        status_map = {"published": "open", "registration_closed": "registration_closed", "in_progress": "in_progress",
                      "completed": "completed", "draft": "draft", "cancelled": "cancelled"}
        self.drives = []
        for r in records:
            state = status_map.get(r["data"].get("drive_status"), "draft")
            if state == "open" and r["data"].get("registration_opens_at", "") > iso(self.now):
                state = "upcoming"
            self.drives.append({"id": r["id"], "title": r["data"]["title"], "state": state,
                                "company": r["data"]["company"], "version": r["version"]})
        if self.drives:
            log(f"reusing {len(self.drives)} drives")
        return bool(self.drives)

    def wait_for_applicants(self, api, drive_id, expected, timeout=120):
        """Applicant lists and bulk transitions read projections, which trail record writes by about a second."""
        deadline = time.time() + timeout
        while True:
            total = api.get(f"/orgs/{self.org}/campushire/drives/{drive_id}/applicants", size=1)["totalElements"]
            if total >= expected or time.time() > deadline:
                return total
            time.sleep(1)

    @staticmethod
    def eligible(student, rules):
        return (float(student["cgpa"]) >= rules.get("minCgpa", 0)
                and int(student["active_backlogs"]) <= rules.get("maxActiveBacklogs", 100)
                and (not rules.get("departments") or student["department_id"] in rules["departments"]))

    def seed_history(self, api):
        """Applications for closed, running and completed drives (import), then pipeline transitions and offers."""
        rng = self.rng
        pool = [dict(s, department_id=self.departments[s["department"]]) for s in self.students
                if s["batch"] in (data.FINAL_YEAR_BATCH, data.MTECH_BATCH) and s["placement_status"] == "unplaced"]
        historical = [d for d in self.drives if d["state"] in ("completed", "in_progress", "registration_closed")]
        if not historical or not pool:
            return
        rows, applicants = [], {}
        for drive in historical:
            candidates = [s for s in pool if self.eligible(s, drive["eligibility"])]
            picks = rng.sample(candidates, min(len(candidates), max(20, int(len(candidates) * 0.6))))
            applicants[drive["id"]] = [s["roll_number"] for s in picks]
            rows.extend({"drive": drive["id"], "student": s["roll_number"], "company": drive["company"],
                         "department": s["department"]} for s in picks)
        if not rows:
            return
        log(f"importing {len(rows)} applications for {len(historical)} past and running drives (CSV, CREATE)")
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=["drive", "student", "company", "department"], lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
        path = f"/orgs/{self.org}/modules/application/imports"
        job = api.upload(path, "applications.csv", buffer.getvalue().encode(),
                         {"mode": "CREATE", "mapping": {"drive": "drive", "student": "student", "company": "company",
                                                        "department": "department"}})
        started = time.time()
        while job["status"] in ("QUEUED", "RUNNING"):
            time.sleep(2)
            job = api.get(f"{path}/{job['id']}")
        log(f"  applications import {job['status']}: created {job['createdRows']}, failed {job['failedRows']} "
            f"in {time.time() - started:.0f}s")
        for err in job.get("errors", [])[:5]:
            log(f"  row {err['row']}: {err.get('column')} {err['code']} {err['message']}")
        self.stats["historical_applications"] = job["createdRows"]
        for drive in historical:
            expected = len(applicants[drive["id"]])
            seen = self.wait_for_applicants(api, drive["id"], expected)
            if seen < expected:
                log(f"  {drive['title']}: only {seen}/{expected} applicants projected; transitions may skip some")

        placed = set()
        offers = []
        moves = {"ok": 0, "failed": 0}

        def move(drive, rolls, to):
            moved = {}
            for start in range(0, len(rolls), 2000):
                if not rolls[start:start + 2000]:
                    continue
                body = api.post(f"/orgs/{self.org}/campushire/drives/{drive['id']}/applicants/transition",
                                {"rollNumbers": rolls[start:start + 2000], "to": to, "comment": "Seeded"})
                moves["ok"] += body["succeeded"]
                moves["failed"] += body["failed"] + len(body.get("unknownRollNumbers") or [])
                for result in body["results"]:
                    if result["ok"]:
                        moved[(result.get("rollNumber") or "").upper()] = result["applicationId"]
            return moved

        for drive in historical:
            rolls = applicants[drive["id"]]
            if drive["state"] == "registration_closed":
                continue
            shortlisted = rolls[: int(len(rolls) * 0.4)]
            move(drive, shortlisted, "shortlisted")
            move(drive, rolls[len(shortlisted):], "rejected")
            assessed = shortlisted[: int(len(shortlisted) * 0.8)]
            move(drive, assessed, "assessment")
            interviewed = assessed[: int(len(assessed) * 0.7)]
            interview_ids = move(drive, interviewed, "interview")
            if drive["state"] != "completed":
                continue
            move(drive, [r for r in assessed if r not in interviewed], "rejected")
            offered = [r for r in interviewed if r not in placed][: max(1, int(len(interviewed) * 0.4))]
            for roll in offered:
                placed.add(roll)
                if roll.upper() in interview_ids:
                    offers.append((drive, roll, interview_ids[roll.upper()]))
            move(drive, [r for r in interviewed if r not in offered], "rejected")
        log(f"  pipeline transitions: {moves['ok']} ok, {moves['failed']} failed")
        self.stats["transitions"] = moves["ok"]

        log(f"creating {len(offers)} offers (one per student; placement policy maxOffersPerStudent=1)")
        respond_by = (self.now + dt.timedelta(days=10)).date().isoformat()

        def offer(item):
            drive, roll, application_id = item
            body = api.post(f"/orgs/{self.org}/campushire/offers", {
                "applicationId": application_id, "ctc": round(drive["ctc"]), "offerType": "full_time",
                "joiningDate": "2027-07-01", "respondBy": respond_by})
            return roll, body["id"]

        created = self.parallel(offer, offers, "offers")
        self.stats["offers"] = len(created)
        accounts = set(self.account_rolls)
        decisions = [(roll, oid) for roll, oid in created if roll in accounts]

        def decide(item):
            roll, offer_id = item
            action = "accept" if self.item_rng("offer", roll).random() < 0.75 else "decline"
            self.as_user(f"{roll.lower()}@{data.EMAIL_DOMAIN}").post_empty(
                f"/orgs/{self.org}/campushire/offers/{offer_id}/{action}")
            return action

        decided = self.parallel(decide, decisions, "offer decisions")
        if decided:
            log(f"  students accepted {decided.count('accept')} and declined {decided.count('decline')} offers")

    def seed_open_applications(self):
        """Some students with accounts apply to open drives through the student API (as they would in the app)."""
        open_drives = [d for d in self.drives if d["state"] == "open"]
        if not open_drives or not self.account_rolls:
            return
        appliers = [r for r in self.account_rolls if self.rng.random() < 0.5] or self.account_rolls[:1]

        def apply(roll):
            rng = self.item_rng("apply", roll)
            student = self.as_user(f"{roll.lower()}@{data.EMAIL_DOMAIN}")
            listing = student.get(f"/orgs/{self.org}/campushire/student/drives", filter="open", size=20)
            eligible = [d for d in listing["items"] if d["eligibility"]["eligible"] and d["registrationOpen"]]
            chosen = rng.sample(eligible, min(len(eligible), rng.randint(1, 2)))
            for drive in chosen:
                student.post_empty(f"/orgs/{self.org}/campushire/drives/{drive['id']}/apply")
            return len(chosen)

        results = self.parallel(apply, appliers, "student applications")
        self.stats["open_applications"] = sum(results)
        log(f"  {len(appliers)} students with accounts submitted {sum(results)} applications to open drives")

    def write_output(self):
        out = Path(self.args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        if not self.account_rolls and out.exists():
            previous = json.loads(out.read_text())
            if previous.get("orgId") == self.org:
                self.account_rolls = [e.split("@")[0].upper() for e in previous.get("students", [])]
        payload = {
            "generatedAt": iso(dt.datetime.now(UTC)),
            "orgId": self.org,
            "realm": self.args.realm,
            "clientId": self.args.client_id,
            "password": self.args.password,
            "tpo": {"email": self.args.tpo_email, "password": self.args.tpo_password},
            "staff": getattr(self, "staff", {}),
            "departments": self.departments,
            "batches": self.batches,
            "openDriveIds": [d["id"] for d in self.drives if d["state"] == "open"],
            "driveIds": [d["id"] for d in self.drives if d["state"] != "draft"],
            "historicalDriveIds": [d["id"] for d in self.drives
                                   if d["state"] in ("completed", "in_progress", "registration_closed")],
            "students": [f"{r.lower()}@{data.EMAIL_DOMAIN}" for r in self.account_rolls],
            "studentCount": len(self.students),
        }
        out.write_text(json.dumps(payload, indent=1))
        os.chmod(out, 0o600)
        log(f"wrote {out} ({len(payload['students'])} student accounts, {len(payload['openDriveIds'])} open drives)")


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", choices=["dev", "prod"], default="dev",
                        help="dev: make infra-up stack; prod: deploy/docker-compose.prod.yml (reads deploy/.env)")
    parser.add_argument("--api-url", help="base URL of the API (dev http://localhost:8090, prod CAMPUSHIRE_PUBLIC_URL)")
    parser.add_argument("--keycloak-url", help="Keycloak base URL (dev http://localhost:8180, prod <public>/auth)")
    parser.add_argument("--realm", default="campushire")
    parser.add_argument("--client-id", help="password-grant client (dev campushire-cli, prod campushire-loadtest)")
    parser.add_argument("--admin-user", help="Keycloak master admin (default from the env file)")
    parser.add_argument("--admin-password", help="Keycloak master admin password (default from the env file)")
    parser.add_argument("--tpo-email", default="tpo@campushire.dev")
    parser.add_argument("--tpo-password", help="placement officer password (default: --password)")
    parser.add_argument("--password", help="password of every created account (dev default dev-password-1; prod: "
                                           "SEED_PASSWORD or generated and stored in the output file)")
    parser.add_argument("--students", type=int, default=2000)
    parser.add_argument("--student-accounts", type=int, default=25)
    parser.add_argument("--companies", type=int, default=50)
    parser.add_argument("--drives", type=int, default=30)
    parser.add_argument("--loadtest-client", action="store_true",
                        help=f"create the temporary password-grant client {LOADTEST_CLIENT} (prod realms have none)")
    parser.add_argument("--remove-loadtest-client", action="store_true", help=f"delete {LOADTEST_CLIENT} and exit")
    parser.add_argument("--reset", action="store_true",
                        help="delete the seeded Keycloak users, archive the demo institute and exit")
    parser.add_argument("--insecure", action="store_true", help="accept self-signed TLS (default for localhost)")
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--seed", type=int, default=2027, help="random seed (same seed = same students)")
    parser.add_argument("--out", default=str(ROOT / "loadtest" / "data" / "seed.json"))
    args = parser.parse_args()

    if args.target == "dev":
        env = read_env_file(ROOT / "infrastructure" / ".env")
        args.api_url = args.api_url or "http://localhost:8090"
        args.keycloak_url = args.keycloak_url or env.get("KC_HOSTNAME", "http://localhost:8180")
        args.client_id = args.client_id or "campushire-cli"
        args.password = args.password or os.environ.get("SEED_PASSWORD") or "dev-password-1"
    else:
        env = read_env_file(ROOT / "deploy" / ".env")
        domain = env.get("CAMPUSHIRE_DOMAIN", "")
        public = (env.get("CAMPUSHIRE_PUBLIC_URL") or (f"https://{domain}" if domain else "")).rstrip("/")
        if not public and not args.api_url:
            raise SystemExit("deploy/.env with CAMPUSHIRE_DOMAIN is required for --target prod (or pass --api-url)")
        args.api_url = args.api_url or public
        args.keycloak_url = args.keycloak_url or f"{public}/auth"
        args.client_id = args.client_id or LOADTEST_CLIENT
        previous = Path(args.out)
        stored = json.loads(previous.read_text()).get("password") if previous.exists() else None
        args.password = args.password or os.environ.get("SEED_PASSWORD") or stored or \
            "Ch-" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(16))
    args.admin_user = args.admin_user or env.get("KEYCLOAK_ADMIN_USER", "admin")
    args.admin_password = args.admin_password or env.get("KEYCLOAK_ADMIN_PASSWORD")
    args.tpo_password = args.tpo_password or args.password
    host = urllib.parse.urlparse(args.api_url).hostname
    args.insecure = args.insecure or host in ("localhost", "127.0.0.1")
    return args


def unreachable(args, error):
    hint = ("start the dev stack with `make infra-up` and the API with `make backend`"
            if args.target == "dev" else
            "start the production stack with `make prod-up`, or seed the dev stack with "
            "`make seed` / `make seed-large`")
    return SystemExit(f"seed failed: cannot reach {error}; {hint}")


def preflight(args):
    context = ssl._create_unverified_context() if args.insecure else None
    for url in (args.api_url, args.keycloak_url):
        try:
            urllib.request.urlopen(url, context=context, timeout=5).close()
        except urllib.error.HTTPError:
            pass
        except (urllib.error.URLError, OSError) as error:
            raise unreachable(args, f"{url} ({getattr(error, 'reason', error)})")


def main():
    args = parse_args()
    log(f"seeding {args.api_url} (Keycloak {args.keycloak_url}, client {args.client_id})")
    preflight(args)
    started = time.time()
    try:
        Seeder(args).run()
    except ApiError as error:
        raise SystemExit(f"seed failed: {error}")
    except urllib.error.URLError as error:
        raise unreachable(args, f"{args.api_url} or {args.keycloak_url} ({getattr(error, 'reason', error)})")
    log(f"finished in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
