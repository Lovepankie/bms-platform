# Developer entry points. Backend needs JDK 25 and Maven 3.9; frontend needs Node 20.19 or later;
# both need Docker (the backend integration tests start PostgreSQL 16 with Testcontainers).
.PHONY: dev down seed test test-backend test-frontend lint fmt migrate build openapi clean psql

-include .env
export

COMPOSE := docker compose
SEED_SLUG ?= demo

## Start the local stack (PostgreSQL, migrate, API, web, proxy) and seed the demo tenant.
dev:
	$(COMPOSE) up --build --detach --wait
	$(MAKE) seed
	@echo "PWA: http://localhost:8000   API: http://localhost:8080   tenant: $(SEED_SLUG) (header X-Tenant or http://$(SEED_SLUG).localhost:8000)"

down:
	$(COMPOSE) down

## Fabricated demo tenant with a head office and the lending chart of accounts, an invited tenant
## admin (the link is printed) and a platform operator (the setup token is printed). Idempotent.
seed:
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U bms_owner -d bms \
		-v slug=$(SEED_SLUG) -v name='Demo Lender (fabricated)' -v plan=starter -v currency=UGX \
		-v branch_code=HQ -v branch_name='Head Office' -v lending=true -f - < deploy/sql/create-tenant.sql
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U bms_owner -d bms \
		-v slug=$(SEED_SLUG) -v email=owner@$(SEED_SLUG).example.test -v name='Test Owner (fabricated)' \
		-v origin=http://$(SEED_SLUG).localhost:8000 -f - < deploy/sql/invite-tenant-admin.sql
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U bms_owner -d bms \
		-v email=operator@example.test -v name='Test Operator (fabricated)' -f - < deploy/sql/create-platform-user.sql

## Apply migrations to the local database (the same one-shot command the servers run).
migrate:
	$(COMPOSE) run --rm migrate

## Unit, architecture, integration (Testcontainers) and frontend tests.
test: test-backend test-frontend

test-backend:
	cd backend && mvn -B verify

test-frontend:
	cd frontend && npm ci --no-audit --no-fund && npm test

lint:
	cd backend && mvn -B spotless:check
	cd frontend && npm run lint

fmt:
	cd backend && mvn -B spotless:apply

## Build the three images exactly as CI does.
build:
	docker build --build-arg GIT_SHA=$$(git rev-parse --short HEAD) -t bms-platform-api:local backend
	docker build -t bms-platform-web:local frontend
	docker build -t bms-platform-proxy:local deploy/caddy

## Regenerate the committed API contract and the frontend's typed client from it.
openapi:
	cd backend && UPDATE_OPENAPI_SNAPSHOT=true mvn -B verify -Dit.test=OpenApiSnapshotIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
	cd frontend && npm run gen:api

psql:
	$(COMPOSE) exec postgres psql -U bms_owner -d bms

## Stop the stack and delete its database volume.
clean:
	$(COMPOSE) down --volumes
