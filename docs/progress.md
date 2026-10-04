# HandOff: Project Progress

## 1. Slices Status

| Slice | Name | Status | Description |
|---|---|---|---|
| **0** | **Project Skeleton** | **DONE** (merged) | Docker Compose (PostgreSQL 16, Redis 7), Flyway V1 migration (V1__create_core_tables.sql: sessions, events, approvals, commands with triggers), Spring Boot 3 MVC with JDBC, HealthController, temporary WS echo handler, React 19 + TypeScript frontend with health check UI, CI pipeline. |
| **1** | **Authentication & Roles** | **DONE** | V2 migration (organizations, users, memberships, refresh_tokens), register, login, refresh, logout, roles (VIEWER, OPERATOR, APPROVER, ADMIN), one-time WebSocket ticket (`POST /api/ws-ticket`), dev seed data, login & register UI with Zustand store, memory-only access token, persistent refresh token in localStorage, single-flight in-tab refresh, and multi-tab Web Lock coordination. |
| 2 | Sessions & Scripted Agent | NEXT | Scripted agent scenarios, session creation & lifecycle. |
| 3 | Event Log & Real WebSocket | UPCOMING | Gapless sequence assignment (R1), real WS protocol replacing echo handler. |
| 4 | Replay & Reconnect | UPCOMING | Replay protocol (R2), Zustand event store, React timeline. |
| 5 | Control & Intervention | UPCOMING | Single controller lease with Redis Lua (R4), steer, pause, resume, takeover, hand-off. |
| 6 | Approval Gates | UPCOMING | Risky tool approval flow (R3, R6), approval queue UI, timeout job. |
| 7 | Audit & Metrics | UPCOMING | Audit log viewer (R7), oversight metrics, presence indicators. |
| 8 | Budgets & Recovery | UPCOMING | Token/step budgets, server restart recovery, command idempotency. |
| 9 | LLM Agent | UPCOMING | LLM agent behind common interface, spending caps. |
| 10 | Load Test & Polish | UPCOMING | k6 load testing, Prometheus/Grafana, demo recording. |

---

## 2. Key Architecture & Tech Decisions

- **Backend Framework: Spring Boot 3 with Spring MVC**
  - Uses standard synchronous Spring MVC (Servlet-based) rather than Spring WebFlux / reactive stack. Simpler concurrency model, explicit transactional boundaries.
- **Persistence: Spring JDBC (`JdbcTemplate`) + Flyway**
  - Plain SQL with `JdbcTemplate` instead of JPA / Hibernate. Direct, transparent control over locking queries (`UPDATE ... RETURNING last_seq`) and database transactions.
  - Flyway manages immutable, versioned migrations (`backend/src/main/resources/db/migration`).
- **Language & Runtime: Java 21 LTS**
  - Uses modern Java 21 features (records for DTOs, pattern matching).
- **Frontend: React 19 + TypeScript Strict + Vite**
  - Fast HMR with Vite, strict type checking, Zustand for centralized event store.
- **Styling: Tailwind CSS v4**
  - Uses Vite plugin `@tailwindcss/vite` for streamlined, modern CSS compilation.
- **Linting & Quality: oxlint + Vitest**
  - Rust-based `oxlint` for high-speed static analysis; Vitest with `jsdom` for component unit testing.
- **Port Flexibility: `SERVER_PORT` & `BACKEND_PORT`**
  - Backend reads `SERVER_PORT` (defaults to `8080`).
  - Frontend Vite dev server reads `BACKEND_PORT` (defaults to `8080`) for proxying `/api` and `/ws`.
  - Enables running without collision when port 8080 is used by other system services.

---

## 3. How to Run Everything on Windows PowerShell

### 3.1 Start Backing Services
```powershell
# From the repository root
docker compose up -d
```
*(To completely reset PostgreSQL and Redis volumes: `docker compose down -v`)*

### 3.2 Run Backend (Port 8081)
```powershell
cd backend
$env:SERVER_PORT="8081"
.\mvnw.cmd spring-boot:run
```

### 3.3 Run Frontend (Dev Server with Proxy to Port 8081)
```powershell
cd frontend
$env:BACKEND_PORT="8081"
npm run dev
```
Open **http://localhost:5173** to view the app.

### 3.4 Run Tests & Lints
```powershell
# Backend unit & integration tests (runs Testcontainers for Postgres and Redis)
cd backend
.\mvnw.cmd test

# Frontend lint & tests
cd frontend
npm run lint
npm test
```

---

## 4. Next Slice: Slice 2 (Sessions and Scripted Agent)

- **Sessions Lifecycle:** Session creation, state machine (`CREATED`, `RUNNING`, `PAUSED`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED`), step and token budget tracking.
- **Scripted Agent:** Deterministic agent implementation executing predefined scenarios (`REFUND_APPROVAL`, etc.) without LLM costs for reliable automated testing.
- **Scenarios:** Scenarios defined in `docs/events.md` section 13.4 generating predictable event sequences.

