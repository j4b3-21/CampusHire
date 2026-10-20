# CampusHire developer commands (GNU Make 3.81+, macOS and Linux). Run `make` for the list.
.DEFAULT_GOAL := help
SHELL := /bin/bash

COMPOSE := docker compose -f infrastructure/docker-compose.yml
MVNW := ./mvnw
# Long-running dependencies; one-shot jobs (openfga-migrate, minio-init) would make `up --wait` fail on exit.
INFRA_SERVICES := postgres mailpit keycloak openfga redis minio

.PHONY: help infra-env infra-up infra-down infra-reset infra-logs infra-ps backend frontend up down test token google-sso lint

help: ## Show this help
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

infra-env:
	@[ -f infrastructure/.env ] || { cp infrastructure/.env.example infrastructure/.env; echo "created infrastructure/.env from .env.example"; }

infra-up: infra-env ## Start dependencies (postgres, keycloak, openfga, redis, minio, mailpit) and wait until healthy
	$(COMPOSE) up -d --wait $(INFRA_SERVICES)
	$(COMPOSE) up --no-log-prefix --exit-code-from minio-init minio-init
	@echo "Keycloak http://localhost:8180  Mailpit http://localhost:8025  MinIO console http://localhost:9101"

infra-down: ## Stop the stack (keeps volumes)
	$(COMPOSE) --profile app down

infra-reset: ## Delete the stack AND its volumes (all local data); CONFIRM=yes skips the prompt
	@if [ "$(CONFIRM)" != "yes" ]; then \
	  printf 'This deletes all CampusHire containers and volumes (database, Keycloak, MinIO, Redis). Continue? [y/N] '; \
	  read answer; case "$$answer" in y|Y|yes) ;; *) echo "aborted"; exit 1 ;; esac; \
	fi
	$(COMPOSE) --profile app down -v --remove-orphans
	@echo "Volumes removed. Run 'make infra-up' to recreate (realm and databases are re-initialised)."

infra-logs: ## Follow logs (SERVICE=keycloak for one service)
	$(COMPOSE) --profile app logs -f --tail=200 $(SERVICE)

infra-ps: ## Show container status
	$(COMPOSE) --profile app ps -a

backend: ## Run the API on the host (local profile, backend/.env) against make infra-up
	@[ -f backend/.env ] || { cp backend/.env.example backend/.env; echo "created backend/.env from .env.example"; }
	$(MVNW) -B -q -pl bm4a/platform install -DskipTests
	$(MVNW) -pl backend spring-boot:run -Dspring-boot.run.profiles=local

frontend: ## Run the Next.js dev server on the host
	cd frontend && npm install && npm run dev

up: infra-env ## Build and start the whole stack in Docker (dependencies + backend + frontend)
	$(COMPOSE) --profile app up -d --build --wait

down: ## Stop the whole stack (keeps volumes)
	$(COMPOSE) --profile app down

test: ## Run all backend and platform tests (Testcontainers; needs Docker)
	$(MVNW) -B verify

# USER defaults to the shell login name, so only a value given on the command line is used.
token: ## Print an access token: make token USER=student1@iiitt.ac.in [PASSWORD=...]
	@user="$(if $(filter command line,$(origin USER)),$(USER),tpo@campushire.dev)"; \
	scripts/dev-token.sh "$$user" $(PASSWORD)

google-sso: ## Enable "Continue with Google" on the running Keycloak (GOOGLE_* in infrastructure/.env)
	scripts/configure-google-sso.sh

lint: ## Lint the frontend
	cd frontend && npm run lint

# ---- Production (single host, deploy/; docs/operations/DEPLOYMENT.md) ----
PROD_ENV := deploy/.env
PROD_PROFILES := $(if $(filter 1,$(MONITORING)),--profile monitoring,)
PROD_COMPOSE := docker compose -f deploy/docker-compose.prod.yml --env-file $(PROD_ENV) $(PROD_PROFILES)

.PHONY: prod-env prod-config prod-up prod-down prod-logs prod-ps prod-smoke backup seed seed-load load-spike load-browse load-staff

prod-env:
	@[ -f $(PROD_ENV) ] || { echo "$(PROD_ENV) missing: cp deploy/.env.example $(PROD_ENV), then replace every change-me value"; exit 1; }

prod-config: prod-env ## Validate the production compose file against deploy/.env (MONITORING=1 adds Prometheus/Grafana)
	$(PROD_COMPOSE) config -q && echo "deploy/docker-compose.prod.yml: configuration valid"

prod-up: prod-env ## Build and start the production stack; first run bootstraps OpenFGA into deploy/.env (MONITORING=1)
	@if ! grep -Eq '^OPENFGA_MODEL_ID=.+' $(PROD_ENV); then \
	  $(PROD_COMPOSE) up -d --wait openfga && deploy/scripts/openfga-bootstrap.sh --write-env; \
	fi
	$(PROD_COMPOSE) build
	$(PROD_COMPOSE) up -d --wait --remove-orphans

prod-down: prod-env ## Stop the production stack (keeps volumes)
	docker compose -f deploy/docker-compose.prod.yml --env-file $(PROD_ENV) --profile monitoring down

prod-logs: prod-env ## Follow production logs (SERVICE=backend-1 for one service)
	$(PROD_COMPOSE) logs -f --tail=200 $(SERVICE)

prod-ps: prod-env ## Show production container status
	$(PROD_COMPOSE) ps -a

prod-smoke: prod-env ## Smoke test the production stack through nginx (INSECURE=1 for self-signed certificates)
	deploy/scripts/smoke-test.sh $(if $(filter 1,$(INSECURE)),--insecure,)

backup: prod-env ## Back up databases and files of the production stack (deploy/scripts/backup.sh)
	deploy/scripts/backup.sh

# ---- Demo data and load tests (scripts/seed, loadtest/) ----
SEED := python3 scripts/seed/seed.py

seed: ## Seed the dev stack with the demo institute (2,000 students); needs make infra-up and make backend
	$(SEED) --target dev $(SEED_ARGS)

seed-large: ## Seed the dev stack with 10,000 students and 100 student accounts (explore the app at scale)
	$(SEED) --target dev --students 10000 --student-accounts 100 $(SEED_ARGS)

seed-load: prod-env ## Seed a staging/prod-compose stack for load tests (10,000 students, 1,500 student accounts)
	$(SEED) --target prod --students 10000 --student-accounts 1500 --loadtest-client $(SEED_ARGS)

# The load targets run grafana/k6 in Docker against the production compose stack; see loadtest/README.md.
load-spike: ## k6 drive spike: 1,500 student VUs (VUS, RAMP, HOLD, BASE_URL, K6_NETWORK)
	loadtest/run.sh drive-spike

load-browse: ## k6 mixed staff reads (VUS, DURATION)
	loadtest/run.sh browse

load-staff: ## k6 staff import of 1,000 rows and exports (ITERATIONS, ROWS)
	loadtest/run.sh staff
