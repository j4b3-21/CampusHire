# CampusHire developer commands (GNU Make 3.81+, macOS and Linux). Run `make` for the list.
.DEFAULT_GOAL := help
SHELL := /bin/bash

COMPOSE := docker compose -f infrastructure/docker-compose.yml
MVNW := ./mvnw
# Long-running dependencies; one-shot jobs (openfga-migrate, minio-init) would make `up --wait` fail on exit.
INFRA_SERVICES := postgres mailpit keycloak openfga redis minio

.PHONY: help infra-env infra-up infra-down infra-reset infra-logs infra-ps backend frontend up down test token lint

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
