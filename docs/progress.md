# HandOff: Project Progress

## 1. Slices Status

| Slice | Name | Status | Description |
|---|---|---|---|
| **0** | **Project Skeleton** | **DONE** (merged) | Docker Compose (PostgreSQL 16, Redis 7), Flyway V1 migration (V1__create_core_tables.sql: sessions, events, approvals, commands with triggers), Spring Boot 3 MVC with JDBC, HealthController, temporary WS echo handler, React 19 + TypeScript frontend with health check UI, CI pipeline. |
| **1** | **Authentication & Roles** | **DONE** | V2 migration (organizations, users, memberships, refresh_tokens), register, login, refresh, logout, roles (VIEWER, OPERATOR, APPROVER, ADMIN), one-time WebSocket ticket (`POST /api/ws-ticket`), dev seed data, login & register UI with Zustand store, memory-only access token, persistent refresh token in localStorage, single-flight in-tab refresh, and multi-tab Web Lock coordination. |
| **2** | **Sessions & Scripted Agent** | **DONE** | V3 migration (tickets, orders), EventStore.append with gapless seq (T1, T2), session REST API, ToolRuntime allowlist, SIMPLE_LOOKUP & LONG_STREAM scenarios, SessionsPage with ticket selector & status badges, SessionDetailPage with polling event log (1s) and compact row rendering. |
| **3** | **Event Log & Real WebSocket** | **DONE** | Stage A: WebSocket protocol core (R1, ticket auth, replay batches, live Redis stream). Stage B: Backend hardening (reconciliation job, slow-consumer 4420, connection/session limits, heartbeat 4409, resilience). |
| 4 | Replay & Reconnect | UPCOMING | Frontend WebSocket client, Zustand event store, React timeline (R2). |
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

# Frontend lint, build & tests
cd frontend
npm run lint
npm test
npm run build
```

---

## 4. Slice 2: Sessions and Scripted Agent (Completed)

- **Sessions Lifecycle:** Session creation, state machine (`RUNNING`, `PAUSED`, `AWAITING_APPROVAL`, `HANDED_OFF`, `COMPLETED`, `FAILED`), org tenant scoping.
- **Event Persistence:** EventStore.append with atomic `UPDATE sessions SET last_seq = last_seq + 1 ... RETURNING last_seq` (T1, T2) and SessionStatusReducer.
- **Scripted Agent:** Deterministic execution for `SIMPLE_LOOKUP` and `LONG_STREAM` scenarios with zero test delay.
- **Tool Runtime:** Allowlist and argument validation for synthetic tools (`lookup_order`, `add_note`, `close_ticket`, `issue_refund`). Atomic tool DB effects and `TOOL_RESULT` commit.
- **Frontend Sessions UI:**
  - `SessionsPage`: session cards (newest first) with accessible status badges, ticket details, empty state, and new session creation with role-based restriction (hidden for VIEWER, active for OPERATOR/APPROVER/ADMIN).
  - `SessionDetailPage`: ticket header, live event polling every 1s (`fromSeq = lastSeenSeq`), event deduplication, display of latest 200 events, auto-stop on terminal state or hidden tab, and compact readable rows with safe text rendering.

### Known Limitations
- Server restart recovery is planned for Slice 8. In Slice 2, if the backend server restarts while a session is running, the session row remains in `RUNNING` status without an active background runner until restart recovery and `AGENT_RECOVERED` are implemented in Slice 8.
- Commands over the socket arrive in slice 5 (a "command" op currently returns `VALIDATION_FAILED`), and role or membership changes do not affect open sockets yet.

---

## 5. Slice 3: Event Log & Real WebSocket (Completed)

- **Stage A:** WebSocket protocol core (96 tests at the time).
- **B1:** reconciliation job.
- **B2:** limits, heartbeat (close code 4409), origin checks.
- **B3:** hardening tests; the redis reader test uses a test hook that makes the reader throw once and never touches the shared Redis container.
- **Totals:** 112 backend tests, 24 frontend tests.
- **Live browser check passed:** joined a running LONG_STREAM, received seq 1 to 2003 with 0 duplicates, 0 out of order, 0 missing.
- **Not done yet:** push, pull request, CI result, slice 4 (frontend WebSocket client).

---

## 6. Deployment TODO

Behind a reverse proxy, every client looks like the proxy's address. In slice 10, configure Tomcat remote IP handling (`server.forward-headers-strategy=native` with `server.tomcat.remoteip.internal-proxies` limited to the proxy's address) and make the proxy overwrite `X-Forwarded-For`. Verify against the Spring Boot documentation first. Add a test for it.


