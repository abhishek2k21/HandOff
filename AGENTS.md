# AGENTS.md: HandOff

Standing instructions for every AI agent working in this repository.
Keep this file under 12,000 characters. Details live in `docs/prd.md` and `docs/events.md`.

## 1. Project in one paragraph
HandOff is a web app where a team watches an AI agent work on a support ticket live. Teammates can steer, pause, hand off, or take control of the session, and risky actions (for example large refunds) need a human approval. Every action is stored in an append-only audit log. The developer is strong in Java/Spring and new to React/TypeScript. This is a portfolio project: the developer must understand every file. Explain new concepts in simple words.

## 2. Sources of truth (read before coding)
1. `docs/events.md`: event schema, commands, WebSocket protocol, REST contracts, DB schema, tests T1 to T19.
2. `docs/prd.md`: requirements, personas, scope, KPIs, milestones.
3. This file: how to work.

If documents disagree, `docs/events.md` wins for protocol and data, `docs/prd.md` wins for scope. Never edit `docs/` or the database schema without the developer's approval; propose the change and wait.

## 3. Tech stack (do not add libraries without asking)
- Backend: Java 21, Spring Boot 3 with **Spring MVC** (not WebFlux), Spring JDBC (`JdbcTemplate`), Spring Security with JWT, Maven wrapper (`mvnw`), base package `com.handoff`
- Real-time: plain Spring WebSocket with a raw `WebSocketHandler` and the JSON protocol in `docs/events.md` (no STOMP)
- Data: PostgreSQL 16 with Flyway (`backend/src/main/resources/db/migration`), Redis 7 via Spring Data Redis (Lettuce) for stream, lease, presence, tickets
- Frontend: React 19, TypeScript strict, Vite, Zustand, Tailwind v4, Vitest, oxlint
- Tests: JUnit 5, Mockito, Testcontainers, Playwright, k6
- Run: Docker Compose

## 4. Repository layout
```
handoff/
├── AGENTS.md
├── docs/            prd.md, events.md
├── backend/         modular monolith: auth, session, events, agent, approval, control, audit, metrics, ws
├── frontend/        src/{api,ws,store,components,pages}
├── docker-compose.yml
└── .github/workflows/ci.yml
```

## 5. How to work (most important)
1. **One agent, one slice, one branch.** Work on a single slice at a time on branch `slice/<number>-<name>`. Do not start the next slice until the developer says so.
2. **Plan first.** For each task write: goal, files to touch, tests to add, risks. Then STOP and wait for approval.
3. **Tests first** for rules R1 to R7: write the failing test, then the code.
4. **Summarize after each slice:** what changed, how to run it, the list of files changed, what to review.
5. **Match the docs exactly.** If your output differs from `docs/events.md` (tables, fields, names), stop and say so; do not invent your own version.
6. **Ask one question at a time** when a requirement is unclear.
7. **Explain** each new pattern or library in 1 to 2 lines.
8. Keep diffs small. Do not refactor unrelated code.

## 6. Safety rules
- No destructive commands (`rm -rf`, dropping databases, force-push) without asking.
- Never commit secrets. Use `.env` (gitignored) and keep `.env.example` current.
- Do not edit files outside this repository or install global tools.
- Do not disable, skip, or weaken tests or security checks to get a pass.
- Never use real customer data; use seeded fake orders only.

## 7. Core rules (never violate; each needs a test)
- **R1 Ordered events.** Events are append-only. `seq` is per session, starts at 1, gapless, unique. Assign it inside one transaction: `UPDATE sessions SET last_seq = last_seq + 1 ... RETURNING last_seq`, then insert the event. A database trigger blocks UPDATE and DELETE on `events`.
- **R2 Lossless resume.** Delivery is at-least-once, client application is exactly-once. Client applies only `seq == lastSeq + 1`, ignores duplicates, and re-subscribes with `fromSeq = lastSeq` on a gap.
- **R3 One decision per approval.** Use `UPDATE approvals SET ... WHERE id = ? AND status = 'PENDING'`. Zero rows means `ALREADY_DECIDED` and no event. Timeouts use the same update.
- **R4 One controller.** Redis lease with 30 s TTL, changed only through the Lua compare-and-set in `docs/events.md` section 11. Mismatch returns `CONTROL_CHANGED`.
- **R5 Authorize every command** on the server, in the validation order of `docs/events.md` section 8.2. Never rely on the UI hiding buttons.
- **R6 Risky tools need approval.** A risky `TOOL_CALL` creates an approval, pauses the agent, and the tool does not run until `APPROVAL_DECIDED`.
- **R7 Everything is audited.** Every human command and agent tool call produces events written in the same transaction as the command record.
- **Idempotency.** Every command has a client `id`; store it in the `commands` table in the same transaction; a repeat returns the original result with `duplicate: true`.
- **PostgreSQL is the source of truth.** Redis can be lost and rebuilt; it must never hold the only copy of anything.

## 8. Protocol essentials
- Get a one-time ticket with `POST /api/ws-ticket`, open `/ws`, send `{"op":"auth","ticket":...}` as the first message. Never put tokens or tickets in URLs.
- Messages use `op`: client `auth, subscribe, unsubscribe, command, pong`; server `auth_ok, subscribed, events, event, caught_up, ack, error, presence, ping`.
- Replay batches are at most 500 events. Presence is not stored and uses no `seq`.
- Status is derived from events by one reducer; server and client must share the same logic and be property-tested against each other (T19).
- Agents implement one interface (scripted and LLM). Build and test with the **scripted agent first**; the LLM agent comes last.

## 9. Security rules
- BCrypt for passwords; short-lived access tokens (15 min); rotating refresh tokens.
- Validate all input (Bean Validation on the backend, type guards on the frontend). Parameterized queries only.
- Treat ticket text, tool results, and LLM output as untrusted. Never use `dangerouslySetInnerHTML`.
- Prompt-injection defense: tool allowlist, approval gate on risky tools, per-session step and token budgets, daily spending cap.
- Rate limits and size limits come from `docs/events.md` section 16.
- Do not log passwords, tokens, or full customer details. Include `sessionId` and `correlationId` in logs.

## 10. Coding standards
**Backend**
- Layers: controller, service, repository. Business rules live in services.
- Constructor injection; records for DTOs; never expose database rows or entities directly in APIs.
- Keep database transactions short and explicit; use Spring `@Transactional` on service methods.
- One global exception handler returning the error model in `docs/events.md` section 15.
- Small methods, clear names, comments only for the "why".

**Frontend**
- Function components and hooks; small single-purpose components; no `any` without a comment.
- All server access in `src/api` and `src/ws`; components never call `fetch` or the socket directly.
- One Zustand event store implements the apply rules of `docs/events.md` section 9.5.
- Every screen handles loading, empty, and error states.
- Accessibility: Approve and Deny work by keyboard, streaming text uses `aria-live`, visible focus.
- Types come from `docs/events.md` section 18.

## 11. Testing rules
- Unit: services, state reducer, Zustand store. Integration: real PostgreSQL and Redis through Testcontainers (not mocks) for R1 to R4. E2E: Playwright with two browser windows.
- The conformance tests T1 to T19 in `docs/events.md` section 20 are required before the MVP is complete.
- No `Thread.sleep` or fixed waits. Use awaits, latches, and scripted agents with zero delay.
- A slice is not done while any test fails.

## 12. Build order (vertical slices)
0. **DONE:** skeleton: Docker Compose, Postgres, Redis, health endpoint, CI, temporary `/ws` echo handler. V1 migration creates `sessions`, `events`, `approvals`, `commands` exactly as `docs/events.md` section 10.1.
1. Auth: V2 migration (organizations, users, memberships, refresh tokens), register, login, refresh, roles, `/ws-ticket`.
2. Sessions and scripted agent producing events (scenarios in `docs/events.md` section 13.4).
3. Event log with gapless `seq` (T1, T2) and the real WebSocket protocol, replacing the echo handler.
4. Replay and reconnect (T3, T17, T18) and React timeline with the Zustand store.
5. Steer, pause, resume, controller lease, take control, hand off (T6, T7).
6. Approval gates, queue screen, timeout job (T4, T5, T10, T11).
7. Audit screen, metrics page, presence.
8. Budgets, restart recovery (T14, T15), command idempotency (T13).
9. LLM agent behind the same interface, spending cap.
10. Load test with k6, Prometheus and Grafana, README, deployment, demo recording.

## 13. Commands (Windows; use `./mvnw` on Linux or macOS)
- Dependencies: `docker compose up -d` (reset database: `docker compose down -v`)
- Backend: `cd backend`, then `mvnw.cmd test` and `mvnw.cmd spring-boot:run`
- Frontend: `cd frontend`, then `npm install`, `npm run dev`, `npm run lint`, `npm test`, `npm run build`
- E2E: `npx playwright test`

## 14. Git rules
- Remote: origin = https://github.com/abhishek2k21/HandOff. Do not add, remove or
  change remotes.
- Branch per slice: slice/<number>-<name>. Small commits: feat:, fix:, test:,
  docs:, chore:.
- Commit only when tests pass. Never commit .env, build output or node_modules.
- You may push the current slice branch to origin only after I approve, with
  git push -u origin <branch>. Never push to main. Never force-push. Never
  delete remote branches.
- Merge into main only through a GitHub Pull Request that I merge myself, after
  CI passes and CodeRabbit has reviewed it.

## 15. Definition of done (per slice)
- Builds, lints, and all tests pass; the new rules have tests.
- R1 to R7 still hold; no TODO without a note; docs updated if behavior changed.
- Summary written: what changed, the list of changed files, how to try it, open questions.

## 16. Out of scope (do not build unless asked)
Multi-agent sessions, billing, SSO, mobile apps, Kafka, Kubernetes, microservices, reactive stack (WebFlux, R2DBC), email or Slack integrations, real customer data.

## 17. Tools in this project
- Antigravity's agent is the only builder. Roo Code, Get Shit Done, and Ralph loop stay off for now so tools do not edit the same files.
- CodeRabbit is the reviewer: run it on each slice before merging.
- Review the plan before approving code; keep terminal commands on manual approval.
- Use parallel agents only after `docs/events.md` is frozen, and only on separate files.
- For frontend work, read every diff and ask for explanations of unfamiliar patterns.

## 18. Glossary
- **Event log:** the numbered history of a session. **seq:** an event's number in it.
- **Replay:** re-sending missed events after reconnect. **Lease:** a temporary lock showing who controls a session.
- **Approval gate:** a pause waiting for a human decision. **Slice:** one small feature built end to end.