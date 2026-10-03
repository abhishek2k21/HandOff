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
mvnw.cmd spring-boot:run      # Windows

# 3. In another terminal — start the frontend
cd frontend
npm install
npm run dev
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

**Slice 0 — Project Skeleton**: Docker Compose, health endpoint, Flyway migration, frontend health dashboard, WebSocket echo, CI.
