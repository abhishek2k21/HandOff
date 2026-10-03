1. What this project is

HandOff is a web app where a team watches an AI agent work on a customer-support ticket live. Teammates can steer, pause, or take over the agent, and risky actions (like refunds) need a human to approve them. Every action is recorded in an audit log.

The developer is learning (strong in Java/Spring, new to React/TypeScript). This is a portfolio project: the developer must understand every file. Explain new concepts in simple words and ask before big decisions.

2. Tech stack
Backend: Java 21, Spring Boot 3 (MVC), Spring Security (JWT), Maven
Database: PostgreSQL 16 (Flyway migrations), Redis 7 (Streams, leases, presence)
Frontend: React 18 + TypeScript (strict), Vite, Zustand, Tailwind
Real-time: WebSocket
Tests: JUnit 5, Mockito, Testcontainers, Playwright, k6
Run locally: Docker Compose

Do not add a new library or framework without listing it and getting approval.

3. Folder layout
handoff/
├── AGENTS.md
├── docs/            PRD.md, events.md (event schema), api.md
├── backend/         Spring Boot app (single modular monolith)
│   └── src/main/java/.../{auth,session,events,agent,approval,audit,metrics}
├── frontend/        React app (src/{api,store,components,pages})
├── docker-compose.yml
└── .agents/         skills and workflows (optional)
4. How to work with me (process, most important)
Plan first. For every task, write a short plan: goal, files to change, tests to add, risks. Then STOP and wait for my approval before writing code.
Small slices. One vertical feature at a time (see section 12). Never build several features in one go.
Tests first for the logic in section 6. Write the failing test, then the code.
Show your work. After each slice, summarize what changed and how to run it.
Ask when unsure. If a requirement is unclear, ask one question at a time.
Never change docs/events.md or the database schema without asking me.
Explain. When using something new (pattern, library, hook), add a 1-2 line explanation in your summary.
5. Safety rules for the agent
Never run destructive commands (rm -rf, dropping databases, force-push) without asking.
Never commit secrets. Keys live in .env (gitignored); keep .env.example updated.
Never install global tools or change system settings.
Do not edit files outside this project folder.
Do not disable tests or security checks to make something pass.
6. Core rules (never break these)

These are the guarantees that make the project valuable. Protect them with tests.

R1. Ordered events. Every session has an append-only list of events. Each event has a seq number that starts at 1 and has no gaps. The pair (session_id, seq) is unique. Events are never edited or deleted.

R2. Resume without loss. A client that reconnects sends the last seq it saw. The server replays everything after it. No missed events, no duplicates applied.

R3. One decision per approval. When two approvers click at once, exactly one decision is saved. Use a conditional update (WHERE status = 'PENDING'). The loser gets a clear "already decided" response.

R4. One controller at a time. Only one user can control a session. Control uses a Redis lease with a TTL. Taking over is explicit and logged.

R5. Authorize every command. Check the user's role on every WebSocket command, not only when connecting.

R6. Risky tools need approval. Tools marked risky (e.g. issue_refund above the configured limit) pause the run until an approver decides. The agent cannot skip this.

R7. Everything is audited. Every human action and every agent tool call becomes an event with who, what, when, and why.

7. Domain model (high level)
User, Organization, Membership (role: VIEWER, OPERATOR, APPROVER, ADMIN)
Session (status: RUNNING, PAUSED, AWAITING_APPROVAL, HANDED_OFF, COMPLETED, FAILED)
Event (session_id, seq, type, actor, payload JSON, created_at)
Approval (session_id, tool_call_id, status: PENDING/APPROVED/DENIED, decided_by, decided_at)
Event types: AGENT_TEXT, TOOL_CALL, TOOL_RESULT, HUMAN_MESSAGE, STEER, PAUSE, RESUME, APPROVAL_REQUESTED, APPROVAL_DECIDED, CONTROL_TAKEN, HANDOFF_SUMMARY, ERROR
8. Roles and permissions
Role	Can do
VIEWER	Watch sessions and the audit log
OPERATOR	Viewer + steer, pause, resume, take control
APPROVER	Operator + approve/deny risky actions
ADMIN	Everything + manage users and roles
9. Real-time protocol (WebSocket)
Auth: client gets a short-lived ticket from REST, then connects. No tokens in URLs.
Client sends: subscribe {sessionId, fromSeq}, command {id, type, payload}.
Server sends: event {seq, ...}, ack {commandId}, error {commandId, code}.
Every command has a client-generated id. Repeating the same id must not repeat the action.
Server sends a heartbeat; clients reconnect with exponential backoff.
The scripted agent and the LLM agent implement the same Agent interface. Always build and test with the ScriptedAgent first (no cost, deterministic).
10. Security rules
Passwords hashed with BCrypt. JWT short-lived, refresh tokens rotated.
Validate all input (Bean Validation on backend, schema checks on frontend).
Only parameterized queries (JPA/JDBC), never string-built SQL.
Treat ticket text and LLM output as untrusted: sanitize before rendering in React (never use dangerouslySetInnerHTML).
Prompt-injection defense: tool allowlist, risky-tool approval gate (R6), and a token/step budget per session so a run cannot loop or overspend.
CORS limited to the known frontend origin. Rate-limit login and command endpoints.
No sensitive data (tokens, full customer details) in logs.
11. Coding standards

Backend (Java)

Layers: controller -> service -> repository. Business rules live in services.
Constructor injection only. Use records/DTOs at API boundaries, not JPA entities.
Keep controller methods thin; delegate business logic to services.
Custom exceptions plus one global exception handler with consistent JSON errors.
Structured logs with a sessionId and correlationId.
Small methods, clear names, comments only where the "why" is not obvious.

Frontend (TypeScript)

strict mode on. No any unless commented why.
Function components and hooks only. Keep components small and single-purpose.
One Zustand store applies events strictly in seq order, ignores duplicates, and detects gaps (then asks the server to replay).
API calls go through src/api, never directly inside components.
Handle loading, empty, and error states for every screen.
Accessibility: keyboard-usable Approve/Deny, aria-live for streaming text, visible focus.
12. Build order (vertical slices)
Project skeleton: Docker Compose, Postgres, Redis, health check, CI.
Auth: register/login, JWT, roles.
Sessions + ScriptedAgent that produces events.
Event log (R1) + WebSocket streaming.
Replay and reconnect (R2) + React timeline.
Steer / pause / resume + controller lease (R4).
Approval gates (R3, R6) + approval queue screen.
Audit log screen and metrics (hand-offs, interventions, approval latency).
Real LLM agent behind the same interface, with budget limits.
Load test, README, demo GIF, deploy.
13. Testing rules
Unit tests for services and the Zustand event store.
Integration tests with Testcontainers (real Postgres and Redis), not mocks, for R1-R4.
Required tests, each must exist before a slice is "done":
Two approvers decide at once -> exactly one decision (R3).
Disconnect mid-stream, reconnect -> no gaps, no duplicates (R2).
Two users take control at once -> one lease holder (R4).
A VIEWER sending a steer command is rejected (R5).
No Thread.sleep in tests; use latches, awaits, or deterministic fakes.
E2E (Playwright) with two browser windows watching the same session.
14. Commands (update if they change)
Start dependencies: docker compose up -d
Backend tests: cd backend && mvn test
Backend run: cd backend && mvn spring-boot:run
Frontend: cd frontend && npm install && npm run dev
Frontend checks: npm run lint && npm run test
E2E: npx playwright test
15. Git rules
Branch per slice: slice/<number>-<name>. Small commits with clear messages (feat:, fix:, test:, docs:).
Do not commit until tests pass. Never commit .env or build output.
16. Definition of done (for each slice)
Code builds and all tests pass.
New logic has tests; rules R1-R7 still hold.
No new warnings, no unused code, no leftover TODOs without a note.
README or docs updated if behavior or commands changed.
You summarized what changed and how I can try it.
17. Out of scope (do not build unless I ask)

Multi-agent orchestration, billing or payments, SSO/SAML, mobile apps, Kubernetes, microservices, Kafka, email/Slack integrations.

18. Glossary (simple words)
Event log: the numbered history of everything that happened in a session.
seq: the event's number in that history.
Lease: a temporary lock that says "this user is in control now" and expires.
Approval gate: a pause that waits for a human to approve a risky action.
Replay: re-sending missed events after a reconnect.
Slice: one small feature built end-to-end (database, backend, screen, tests)