# HandOff

AI Agent Supervision Platform — a team watches an AI agent work on a customer-support ticket live, with steering, approval gates, and an audit trail.

## Prerequisites

- **Docker Desktop** — for PostgreSQL and Redis
- **Java 21** — for the backend (Maven wrapper included, no Maven install needed)
- **Node.js 22+** — for the frontend

## Quick Start

```bash
# 1. Start PostgreSQL 16 and Redis 7
docker compose up -d

# 2. Start the backend (runs Flyway migrations on boot)
cd backend
./mvnw spring-boot:run        # Linux / macOS
.\mvnw.cmd spring-boot:run     # Windows PowerShell

# If port 8080 is in use, override with SERVER_PORT:
# $env:SERVER_PORT="8081"; .\mvnw.cmd spring-boot:run

# 3. In another terminal — start the frontend
cd frontend
npm install
npm run dev

# If backend is on a non-default port, pass BACKEND_PORT:
# $env:BACKEND_PORT="8081"; npm run dev
```

Open **http://localhost:5173** to see the health dashboard.

## Running Tests

```bash
# Backend (needs Docker running for Testcontainers)
cd backend
./mvnw test                   # Linux / macOS
mvnw.cmd test                 # Windows

# Frontend
cd frontend
npm run lint
npm run test
```

## Project Structure

```
handoff/
├── AGENTS.md                 Agent working rules
├── docs/                     PRD, event schema, API spec
├── backend/                  Spring Boot 3 (MVC), Java 21
│   └── src/main/resources/db/migration/   Flyway SQL
├── frontend/                 React 19 + TypeScript, Vite, Tailwind v4
├── docker-compose.yml        PostgreSQL 16 + Redis 7
└── .github/workflows/ci.yml  CI pipeline
```

## Current Slice

**Slice 1 — Authentication & Roles**:
- Backend: Flyway V2 migration, JWT authentication, BCrypt, role-based access, token rotation & reuse detection, Redis-backed rate limiting, and single-use WebSocket tickets.
- Frontend: Zustand auth store with memory-only access token, persistent refresh token in `localStorage`, single-flight refresh in-tab, Web Locks API coordination across browser tabs, and accessible login/register screens.

### Auth Token Architecture & Multi-Tab Behavior
- **Access Token:** Stored in memory only (inside the Zustand auth store). Never persisted to `localStorage` or `sessionStorage` to mitigate token exfiltration via XSS.
- **Refresh Token:** Stored in `localStorage` (`handoff_refresh_token`) to allow session restoration across browser reloads.
- **In-Tab Single-Flight:** A module-level in-flight promise ensures that multiple concurrent 401s or effect re-renders in the same tab share a single `/api/auth/refresh` network call.
- **Multi-Tab Web Lock Coordination:** Because refresh tokens rotate on every use and token reuse revokes the entire user token family, concurrent refreshes across multiple tabs in the same browser are serialized using the Web Locks API (`navigator.locks.request("handoff-refresh", ...)`). Inside the lock, the tab re-checks `localStorage` and uses the newly rotated token if another tab already completed a refresh, preventing stale token reuse and accidental logouts.

