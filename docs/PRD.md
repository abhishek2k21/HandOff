# HandOff: Product Requirements Document (PRD)

## 1. Document Control

| Field | Value |
|---|---|
| Product name | HandOff |
| Document | prd.md |
| Version | 1.0 |
| Status | Approved for MVP build |
| Date | 2026-10-03 |
| Owner | Abhishek Kumar (solo developer / product owner) |
| Related documents | AGENTS.md (agent working rules), docs/events.md (event schema and WebSocket protocol) |
| Project type | Portfolio-grade full-stack product, solo build |
| Target timeline | 6 weeks, approximately 170 hours |

### 1.1 Revision History

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-10-03 | First complete PRD for the MVP |

---

## 2. Executive Summary

HandOff is a web application where a team supervises an AI agent working on a customer-support ticket in a shared, live session. Every teammate sees the agent's steps as they happen. Authorized teammates can steer, pause, resume, or take over the session. Risky actions, such as issuing a refund above a configured limit, pause the agent until a human with the right role approves or denies them. Every agent action and human intervention is recorded in an append-only audit log, and a metrics page shows how often humans had to step in.

The product targets a gap between "shared AI workspace" tools that focus on collaboration and the governance needs of teams who let agents take consequential actions. The MVP proves four guarantees that can be demonstrated by automated tests: ordered live events, lossless reconnect, one decision per approval, and one controller at a time.

---

## 3. Problem Statement

### 3.1 The Problem

AI agents now run tasks lasting minutes or hours, but people still use them alone. In a support team this causes four concrete problems:

1. **No shared visibility.** Only the person who started the agent can see its progress. Teammates receive, at best, a read-only transcript afterward.
2. **No safe intervention.** If the agent goes off track, there is no controlled way for a teammate to steer, pause, or take over.
3. **No decision control.** Consequential actions (refunds, account changes) can be executed without a clear, recorded human decision.
4. **No accountability.** After the fact, nobody can answer: what did the agent do, why, who intervened, and who approved?

### 3.2 Who Feels It

- Support operators who cannot correct an agent mid-task.
- Team leads who must approve risky actions but lack context and a queue.
- Auditors and admins who cannot reconstruct what happened or measure how much human oversight was needed.

### 3.3 Why Now

YC's Fall 2026 Requests for Startups includes "Multiplayer AI", describing teams that watch, redirect, and hand off long-running agent tasks, with a shared history of what agents did and why, plus metrics on throughput, hand-offs, and interventions. Several products already launched shared AI sessions in 2026, so the opportunity is in governance and provable correctness rather than shared chat alone.

---

## 4. Goals and Non-Goals

### 4.1 Product Goals

| ID | Goal |
|---|---|
| G1 | Let a team watch one agent session live, with every viewer seeing the same ordered history. |
| G2 | Let authorized users steer, pause, resume, hand off, and take control safely. |
| G3 | Require a recorded human decision for risky agent actions. |
| G4 | Produce a complete, queryable audit trail of agent actions and human interventions. |
| G5 | Report oversight metrics: hand-offs, interventions, and approval latency. |

### 4.2 Portfolio Goals

| ID | Goal |
|---|---|
| P1 | Demonstrate concurrency correctness with automated, repeatable tests. |
| P2 | Demonstrate full-stack delivery: auth, REST and WebSocket APIs, state management, persistence, observability, CI, and deployment. |
| P3 | Publish a live demo, a load-test result, and a 60-second demo recording. |

### 4.3 Non-Goals (MVP)

- Multi-agent orchestration (more than one agent per session).
- Billing, payments, or subscription management.
- SSO/SAML and enterprise identity.
- Native mobile apps.
- Email, Slack, or ticketing-system integrations.
- Real customer data. The MVP uses a seeded, fake orders database.
- Autonomous code-writing agents (this space is already served by other products).

---

## 5. Market Context and Competitive Analysis

### 5.1 Landscape (as of October 2026)

| Product | Positioning | Relevance to HandOff |
|---|---|---|
| Clairvoyance (Stardock), Multiplayer AI in v0.85, released 2026-08-25 | Local-first desktop app where several people join a live agent session hosted on one machine | Validates demand for shared sessions; hosted on one person's machine; coverage did not describe participant access granularity or audit records |
| SquidHub | Shared rooms where teammates and AI agents collaborate in real time | Collaboration and shared context focus |
| YappJam | Real-time shared AI sessions for product teams, "multiplayer vibe coding" | Prototype-building focus |
| MeshAgent | Secure rooms where humans and agents share live context, with access controls and traceability | Closest in governance claims; hosted commercial platform |
| Coshell | Shared live sessions for coding agents | Coding-agent focus |
| Relevance AI | Hosted no-code agent teams for business tasks | Agent building rather than live supervision |

### 5.2 Differentiation

1. **Governance first:** roles, approval gates, and an immutable audit trail are core features, not add-ons.
2. **Provable correctness:** published tests demonstrate gapless ordering, lossless reconnect, single approval decisions, and a single controller.
3. **Operations focus:** support and operations tasks (refunds, order disputes) rather than coding.
4. **Open and self-hostable:** a transparent reference implementation that can run with Docker Compose.

### 4.4 Honest Limitations of This Analysis

Demand evidence comes from an accelerator request and product launches, not from paying customers. Some competitors already claim access controls and traceability, so the differentiation rests on correctness, openness, and focus, not on being the only product with governance.

---

## 6. Target Users and Personas

### 6.1 Persona: Operator (Maya, Support Agent)

- **Context:** handles 30 to 50 tickets per day, delegates routine ones to an AI agent.
- **Needs:** see what the agent is doing, correct it quickly, take over when stuck, hand off at shift end.
- **Frustrations:** cannot see agent reasoning; has to re-do work when the agent errs.
- **Success looks like:** intervenes in under 10 seconds and the agent continues correctly.

### 6.2 Persona: Approver (Ravi, Team Lead)

- **Context:** accountable for refunds and exceptions.
- **Needs:** a single queue of pending approvals, with enough context to decide fast.
- **Frustrations:** approval requests arrive in chat with missing context; unclear who decided.
- **Success looks like:** decides in under 30 seconds, with a recorded rationale.

### 6.3 Persona: Admin / Auditor (Priya)

- **Context:** responsible for compliance, access, and performance reviews.
- **Needs:** manage roles; filter the audit trail; measure how often humans intervene.
- **Frustrations:** no reliable history; cannot prove who approved what.
- **Success looks like:** answers "who approved refund X and when?" in under a minute.

### 6.4 Persona: Viewer (Arjun, New Team Member)

- **Context:** learning how the team handles tickets.
- **Needs:** watch live sessions and review past ones without being able to change anything.

---

## 7. User Journeys

### 7.1 Refund Journey (happy path)

1. A ticket arrives; an operator starts a session with the agent.
2. The agent looks up the order and streams its reasoning steps to all viewers.
3. The agent requests a refund above the limit. The session enters AWAITING_APPROVAL.
4. The approver sees the request in the approval queue with the full context.
5. The approver approves. Exactly one decision is recorded.
6. The agent completes the refund and the session ends as COMPLETED.
7. The audit log shows each step, the approver, and timestamps.

### 7.2 Takeover Journey

1. The agent starts proposing an incorrect resolution.
2. The operator pauses the session and takes control. The controller change is logged.
3. The operator sends a steer instruction and resumes. The agent follows the instruction.
4. At end of shift, the operator hands off to a teammate; a summary is generated.

### 7.3 Reconnect Journey

1. A viewer loses network for 20 seconds in the middle of a session.
2. The browser reconnects and requests events after its last known sequence number.
3. The server replays the missed events in order. The timeline shows no gaps and no duplicates.

### 7.4 Denied Approval Journey

1. The approver denies a risky action with a note.
2. The agent is informed, chooses an alternative, and continues. The denial is in the audit log.

---

## 8. Scope

### 8.1 In Scope (MVP)

- Registration, login, JWT authentication, and four roles.
- Create, list, and view sessions.
- Live streaming of agent events to all subscribed viewers.
- Steer, pause, resume, hand off, and take control with a single-controller lease.
- Approval gates for risky tool calls with an approval queue.
- Append-only event log with replay on reconnect.
- Audit log screen with filters.
- Metrics page: hand-offs, interventions, approval latency.
- Scripted (deterministic) agent and one real LLM-backed agent behind the same interface.
- Presence indicators showing who is viewing.
- Docker Compose deployment, CI pipeline, observability dashboards.

### 8.2 Out of Scope (Future Candidates)

- Multiple agents per session and agent-to-agent coordination.
- Ticketing-system and chat integrations.
- SSO, SCIM, and enterprise audit export.
- Hash-chained tamper-evident audit log (stretch goal).
- Session recording and playback with speed control.
- Mobile apps.

---

## 9. Functional Requirements

Priority key: **M** = Must (MVP), **S** = Should, **C** = Could (stretch).

### 9.1 Authentication and Accounts

| ID | Requirement | Priority |
|---|---|---|
| FR-AUTH-1 | Users can register with email and password; passwords are stored hashed. | M |
| FR-AUTH-2 | Users can log in and receive a short-lived access token and a rotating refresh token. | M |
| FR-AUTH-3 | Each user belongs to an organization and has exactly one role in it. | M |
| FR-AUTH-4 | Admins can change a member's role; the change is audited. | M |
| FR-AUTH-5 | The system issues a one-time, short-lived WebSocket ticket to logged-in users. | M |

### 9.2 Sessions

| ID | Requirement | Priority |
|---|---|---|
| FR-SES-1 | Operators and above can create a session for a ticket, choosing the agent type (scripted or LLM). | M |
| FR-SES-2 | All roles can list sessions the organization owns, showing status and controller. | M |
| FR-SES-3 | Session status follows the defined state machine; invalid transitions are rejected. | M |
| FR-SES-4 | Sessions end as COMPLETED or FAILED, after which no further commands are accepted. | M |
| FR-SES-5 | Each session has a configurable step budget and token budget; exceeding either ends the run safely. | M |

### 9.3 Live Event Streaming

| ID | Requirement | Priority |
|---|---|---|
| FR-EVT-1 | Every session maintains an append-only event log with gapless, per-session sequence numbers starting at 1. | M |
| FR-EVT-2 | Subscribers receive new events in sequence order in real time. | M |
| FR-EVT-3 | A subscriber can request all events after a given sequence number; the server replays them, then continues live. | M |
| FR-EVT-4 | Clients ignore duplicates and detect gaps, re-subscribing from the last applied sequence number. | M |
| FR-EVT-5 | Agent text streams in chunks and is rendered progressively. | M |
| FR-EVT-6 | The server sends heartbeats; clients reconnect with exponential backoff. | M |

### 9.4 Control and Intervention

| ID | Requirement | Priority |
|---|---|---|
| FR-CTL-1 | Exactly one user holds control of a session at a time, enforced by a time-limited lease. | M |
| FR-CTL-2 | The controller can send STEER instructions; the agent uses them on its next step. | M |
| FR-CTL-3 | The controller can pause and resume the session. | M |
| FR-CTL-4 | Another operator can take control after an explicit confirmation; the previous controller is notified. | M |
| FR-CTL-5 | The controller can hand off to a named teammate; the system attaches a generated summary. | M |
| FR-CTL-6 | An expired or abandoned lease is released automatically and the event is logged. | M |
| FR-CTL-7 | Command messages carry a client-generated id; repeating an id does not repeat the action. | M |

### 9.5 Approval Gates

| ID | Requirement | Priority |
|---|---|---|
| FR-APR-1 | Tools are flagged risky by configuration (for example, refunds above an amount limit). | M |
| FR-APR-2 | When the agent calls a risky tool, the run pauses and an approval request is created. | M |
| FR-APR-3 | Only Approvers and Admins can approve or deny. | M |
| FR-APR-4 | When two users decide at the same moment, exactly one decision is stored; the other receives ALREADY_DECIDED. | M |
| FR-APR-5 | Approvers see a pending-approvals queue with the request context. | M |
| FR-APR-6 | A denial includes an optional note and is communicated to the agent. | M |
| FR-APR-7 | Unanswered approvals expire after a configurable timeout and are logged as denied by timeout. | S |

### 9.6 Audit Log

| ID | Requirement | Priority |
|---|---|---|
| FR-AUD-1 | Every agent action and human action becomes an event recording who, what, when, and context. | M |
| FR-AUD-2 | Users with at least Viewer role can filter audit data by session, person, event type, and date. | M |
| FR-AUD-3 | Events cannot be edited or deleted through the application. | M |
| FR-AUD-4 | Audit data can be exported as CSV. | C |
| FR-AUD-5 | Events are hash-chained so tampering is detectable. | C |

### 9.7 Metrics

| ID | Requirement | Priority |
|---|---|---|
| FR-MET-1 | The metrics page shows counts of sessions, hand-offs, and human interventions per period. | M |
| FR-MET-2 | The metrics page shows approval latency (median and 95th percentile). | M |
| FR-MET-3 | The metrics page shows the percentage of sessions that needed at least one intervention. | S |
| FR-MET-4 | Operational metrics (active sessions, event lag, connected clients) are exposed to Prometheus. | S |

### 9.8 Presence

| ID | Requirement | Priority |
|---|---|---|
| FR-PRS-1 | Each session shows who is currently viewing, updated within a few seconds of joins and leaves. | S |

### 9.9 Agent Behavior

| ID | Requirement | Priority |
|---|---|---|
| FR-AGT-1 | Agents implement a common interface so the scripted agent and LLM agent are interchangeable. | M |
| FR-AGT-2 | The scripted agent follows deterministic scenarios (including a refund requiring approval) for tests and demos. | M |
| FR-AGT-3 | The LLM agent can only call tools on an allowlist (lookup_order, issue_refund, add_note, close_ticket). | M |
| FR-AGT-4 | The agent loop resumes from the last logged event after a server restart. | S |
| FR-AGT-5 | Ticket text and tool results are treated as untrusted input. | M |

---

## 10. User Stories and Acceptance Criteria

| ID | Story | Acceptance Criteria |
|---|---|---|
| US-1 | As an operator, I see agent steps appear live. | Given a running session, when the agent produces a step, then every viewer sees it in order, with a p99 delivery latency target under 200 ms at 50 viewers. |
| US-2 | As a viewer, I do not lose steps when my connection drops. | Given I reconnect with my last sequence number, then I receive every missed event once, and the timeline shows no gaps or duplicates. |
| US-3 | As an operator, I steer the agent mid-task. | Given I hold control, when I send a steer instruction, then it appears in the log and the agent's next step reflects it. |
| US-4 | As a team, only one person controls at a time. | Given A holds control, when B requests control and confirms, then control moves to B, A is notified, and the change is logged. When A and B request at the same instant, exactly one succeeds. |
| US-5 | As an approver, I decide on risky actions. | Given the agent requests a refund above the limit, then the session pauses and the request appears in the approval queue until decided. |
| US-6 | As an approver, I never double-decide with a colleague. | Given two approvers click at the same moment, then exactly one decision is stored and the other sees an "already decided" message. |
| US-7 | As an admin, I enforce role limits. | Given a Viewer sends a steer or approve command, then the server rejects it with FORBIDDEN and nothing changes. |
| US-8 | As an auditor, I find who did what. | Given I filter by session, person, or type, then I see matching events with actor, time, and payload. |
| US-9 | As an admin, I see oversight metrics. | Given sessions have run, then the metrics page shows hand-offs, interventions, and approval latency for a chosen period. |
| US-10 | As an operator, I hand off a running session. | Given I hand off to a teammate, then control moves to them with a generated summary and the event is logged. |
| US-11 | As an operator, I recover from a stuck agent. | Given the agent exceeds its step or token budget, then it stops safely and the session shows the reason. |
| US-12 | As a team member, I see who else is watching. | Given others join or leave, then the presence list updates within a few seconds. |

---

## 11. Use Cases

### UC-1: Approve a Risky Action

| Item | Detail |
|---|---|
| Actor | Approver |
| Preconditions | Session is AWAITING_APPROVAL; the user has the Approver or Admin role. |
| Main flow | 1. Approver opens the approval queue. 2. Selects the request and reviews context. 3. Clicks Approve. 4. System stores one decision, logs APPROVAL_DECIDED, and resumes the agent. |
| Alternate flow | Another approver decided first: system returns ALREADY_DECIDED and shows the existing decision. |
| Postconditions | Exactly one decision exists; session is RUNNING; the audit log reflects the decision. |

### UC-2: Take Control

| Item | Detail |
|---|---|
| Actor | Operator |
| Preconditions | Session is active; the actor has the Operator role or above. |
| Main flow | 1. Operator selects Take Control. 2. System asks for confirmation. 3. Operator confirms. 4. System moves the lease, logs CONTROL_TAKEN, and notifies the previous controller. |
| Alternate flow | Lease changed during confirmation: system refreshes state and asks again. |
| Postconditions | One controller exists; the event is logged. |

### UC-3: Reconnect and Replay

| Item | Detail |
|---|---|
| Actor | Any subscriber |
| Preconditions | The client holds a last applied sequence number. |
| Main flow | 1. Connection drops. 2. Client reconnects with a new ticket. 3. Client subscribes with fromSeq. 4. Server replays missed events, then streams live. |
| Alternate flow | Client detects a gap: it re-subscribes from its last applied number. |
| Postconditions | The client's event list equals the server's log for the session. |

### UC-4: Review the Audit Trail

| Item | Detail |
|---|---|
| Actor | Admin or Auditor |
| Main flow | Opens the audit page, applies filters, reads results, optionally exports. |
| Postconditions | No data is modified. |

---

## 12. Non-Functional Requirements

All numeric values are targets to be measured and reported, not guarantees.

### 12.1 Correctness

| ID | Requirement |
|---|---|
| NFR-COR-1 | Event sequence numbers are gapless and unique per session under concurrent writes. |
| NFR-COR-2 | After any number of disconnects, a client's applied events equal the server log: zero gaps, zero duplicates. |
| NFR-COR-3 | Under N simultaneous decisions on one approval, exactly one is persisted. |
| NFR-COR-4 | At most one lease holder exists per session at any instant. |

### 12.2 Performance

| ID | Requirement |
|---|---|
| NFR-PERF-1 | p99 event delivery latency under about 200 ms with 50 viewers on one session. |
| NFR-PERF-2 | The system sustains at least 200 concurrent sessions and 2,000 WebSocket connections on one modest VPS (to be measured). |
| NFR-PERF-3 | Replay of 10,000 events completes in under 2 seconds. |

### 12.3 Reliability

| ID | Requirement |
|---|---|
| NFR-REL-1 | A server restart does not lose committed events; clients reconnect automatically. |
| NFR-REL-2 | Redis loss degrades presence and leases but never corrupts the durable event log in PostgreSQL. |
| NFR-REL-3 | LLM errors or timeouts produce an ERROR event and a safe session state, never a hung session. |

### 12.4 Security

| ID | Requirement |
|---|---|
| NFR-SEC-1 | Authorization is checked on every command, not only at connection time. |
| NFR-SEC-2 | WebSocket tickets are single-use and expire in 30 seconds. |
| NFR-SEC-3 | All input is validated server-side; all queries are parameterized. |
| NFR-SEC-4 | LLM output and ticket text are never rendered as raw HTML. |
| NFR-SEC-5 | Secrets are stored in environment variables, never in source control. |

### 12.5 Usability and Accessibility

| ID | Requirement |
|---|---|
| NFR-UX-1 | Approve and Deny are fully operable by keyboard. |
| NFR-UX-2 | Streaming text is announced through an aria-live region without flooding screen readers. |
| NFR-UX-3 | Every screen handles loading, empty, and error states. |
| NFR-UX-4 | The interface is usable at widths from 360 px to desktop. |

### 12.6 Maintainability and Observability

| ID | Requirement |
|---|---|
| NFR-OPS-1 | Structured logs include sessionId and correlationId. |
| NFR-OPS-2 | Prometheus metrics and a Grafana dashboard cover active sessions, event lag, approval latency, and error rate. |
| NFR-OPS-3 | The full stack starts with a single Docker Compose command. |
| NFR-OPS-4 | CI runs build, lint, unit, integration, and end-to-end tests on every push. |

### 12.7 Cost Control

| ID | Requirement |
|---|---|
| NFR-COST-1 | Each session has a token and step budget; a global daily LLM spending cap disables the LLM agent when reached. |
| NFR-COST-2 | Demos and tests default to the scripted agent. |

---

## 13. Roles and Permissions Matrix

| Capability | Viewer | Operator | Approver | Admin |
|---|---|---|---|---|
| View sessions and timeline | Yes | Yes | Yes | Yes |
| View audit log | Yes | Yes | Yes | Yes |
| Create session | No | Yes | Yes | Yes |
| Steer, pause, resume | No | Yes (controller only) | Yes (controller only) | Yes (controller only) |
| Take control, hand off | No | Yes | Yes | Yes |
| Approve or deny risky actions | No | No | Yes | Yes |
| View metrics page | No | No | No | Yes |
| Manage users and roles | No | No | No | Yes |

---

## 14. Data Requirements

### 14.1 Entities

| Entity | Key Fields |
|---|---|
| Organization | id, name, created_at |
| User | id, email, password_hash, display_name, created_at |
| Membership | user_id, organization_id, role (VIEWER, OPERATOR, APPROVER, ADMIN) |
| Session | id, organization_id, ticket_id, agent_type, status, controller_user_id, step_budget, token_budget, created_by, created_at, ended_at |
| Event | session_id, seq, type, actor_kind, actor_id, payload (JSON), created_at; unique (session_id, seq) |
| Approval | id, session_id, tool_call_id, status (PENDING, APPROVED, DENIED), requested_at, decided_by, decided_at, note |
| Fake Order (seed data) | id, customer_name, items, total, status, refundable_amount |
| Refresh Token | id, user_id, token_hash, expires_at, revoked |

### 14.2 Storage Rules

- PostgreSQL is the source of truth for users, sessions, events, and approvals.
- Redis holds ordered live streams, control leases with TTL, and presence; it can be rebuilt from PostgreSQL.
- Event rows are insert-only. Schema changes use versioned migrations.
- Approval decisions use a conditional update so only one decision can succeed.

### 14.3 Retention

- Events and approvals are retained for the life of the demo organization.
- Seed data contains no real personal information.

---

## 15. UX Requirements

### 15.1 Screens

| Screen | Purpose | Key Elements |
|---|---|---|
| Login / Register | Account access | Forms with validation and clear error messages |
| Session List | Find and start sessions | Status badges, controller name, filter, New Session button |
| Live Session | Core experience | Streaming timeline, control bar (Steer, Pause, Resume, Take Control, Hand Off), presence list, status banner, inline approval card |
| Approval Queue | Decide on risky actions | Pending list with context, Approve and Deny with note, decided-by display |
| Audit Log | Review history | Filters (session, person, type, date), results table, optional export |
| Metrics | Oversight view | Hand-offs, interventions, approval latency, intervention rate |
| Admin: Members | Manage roles | Member list, role selector |

### 15.2 Interaction Principles

- The timeline always shows connection state (connected, reconnecting, replaying).
- Controls the user lacks permission for are hidden or disabled with an explanation.
- Steer commands appear optimistically and roll back with a clear message if rejected.
- Destructive or consequential actions (take control, approve, deny) require an explicit click, never a keystroke accident.
- Visual language is calm and dense, built with a component library and Tailwind to avoid custom design overhead.

---

## 16. Security, Privacy, and Compliance

### 16.1 Threats and Mitigations

| Threat | Mitigation |
|---|---|
| Prompt injection through ticket text | Tool allowlist, approval gate on risky tools, step and token budgets, untrusted-input handling |
| Privilege escalation through the socket | Per-command authorization, role checks on the server |
| Token theft from URLs or logs | One-time socket tickets, no tokens in URLs, log redaction |
| Cross-site scripting through agent output | No raw HTML rendering, output sanitization |
| Brute force and abuse | Rate limits on login and command endpoints |
| Runaway LLM spending | Per-session budgets and a global daily cap |
| Replay of a command by network retry | Client-generated command ids with server-side deduplication |

### 16.2 Privacy

- The MVP uses synthetic data only.
- Logs must not include passwords, tokens, or full customer details.

### 16.3 Compliance Note

HandOff is a portfolio project and makes no regulatory compliance claims. The design supports later alignment with audit requirements through immutable events and recorded decisions.

---

## 17. Technical Constraints and Architecture Summary

| Area | Decision |
|---|---|
| Backend | Java 21, Spring Boot 3 (MVC), Spring Security with JWT, Maven |
| Database | PostgreSQL 16 with Flyway migrations |
| Cache and live state | Redis 7 (Streams, leases with TTL, presence) |
| Frontend | React 18, TypeScript (strict), Vite, Zustand, Tailwind |
| Real-time | WebSocket with a replay-from-sequence protocol (see docs/events.md) |
| Architecture | Modular monolith; microservices, Kafka, and Kubernetes are intentionally excluded |
| Testing | JUnit 5, Mockito, Testcontainers, Playwright, k6 |
| Delivery | Docker Compose, GitHub Actions, single VPS with automatic TLS |
| Observability | Prometheus and Grafana, structured logs |

### 17.1 Core Rules (must hold at all times)

- R1: Ordered, gapless, append-only events per session.
- R2: Resume after reconnect without loss or duplicates.
- R3: One decision per approval.
- R4: One controller at a time.
- R5: Authorize every command.
- R6: Risky tools always require approval.
- R7: Everything is audited.

---

## 18. Success Metrics and KPIs

### 18.1 Product Quality Metrics (measured by automated tests)

| Metric | Target |
|---|---|
| Approval race test (2 or more simultaneous decisions) repeated 1,000 times | 1,000 of 1,000 produce exactly one decision |
| Reconnect test with random disconnects across 100 sessions | 0 gaps and 0 duplicates |
| Lease race test | 1 holder in every run |
| Authorization test matrix (role by command) | 100% expected results |
| p99 event delivery latency at 50 viewers | Under about 200 ms (measure and report actual) |

### 18.2 Oversight KPIs (shown in the product)

| KPI | Definition |
|---|---|
| Intervention rate | Sessions with at least one human steer, pause, or takeover divided by total sessions |
| Hand-off count | Number of HANDOFF_SUMMARY events per period |
| Approval latency | Time from APPROVAL_REQUESTED to APPROVAL_DECIDED (median and p95) |
| Denial rate | Denied approvals divided by decided approvals |

### 18.3 Portfolio Outcomes

| Outcome | Target |
|---|---|
| Public live demo | Available at the end of week 6 |
| README with architecture diagram and load-test results | Complete |
| 60-second demo recording | Complete |
| Automated test suite in CI | Passing on the main branch |

---

## 19. Business Model (Not Implemented in MVP)

- **Pricing concept:** per-seat subscription for teams plus usage-based pricing per agent run, with a free tier limited by sessions per month.
- **Buyers:** support and operations leaders who want agent automation with human oversight.
- **Expansion paths:** integrations with ticketing systems, SSO, compliance exports, and multi-agent sessions.
- **Note:** this section records a hypothesis for the portfolio narrative and is not validated by customer research.

---

## 20. Release Plan and Milestones

| Week | Milestone | Deliverables |
|---|---|---|
| 0 | Preparation | This PRD, docs/events.md, AGENTS.md, wireframes, Docker Compose skeleton |
| 1 | Foundation | Project skeleton, CI, authentication, roles, scripted agent producing events |
| 2 | Event core | Append-only event log, WebSocket streaming, sequence guarantees |
| 3 | Replay and UI | Reconnect and replay, React timeline, Zustand event store |
| 4 | Control | Steer, pause, resume, controller lease, take control, hand off |
| 5 | Governance | Approval gates and queue, audit log screen, metrics page, presence |
| 6 | Hardening | Real LLM agent with budgets, load test, observability dashboards, README, deployment, demo recording |

### 20.1 Definition of Done (per slice)

- Code builds and all tests pass.
- Required correctness tests for the touched rules exist and pass.
- No unexplained TODOs; documentation updated.
- A short summary of what changed and how to try it is written.

### 20.2 Launch Plan (Portfolio)

1. Deploy to a single VPS with TLS.
2. Seed the demo organization with accounts for each role.
3. Publish the repository with the README, architecture diagram, and load-test results.
4. Share a demo link and recording in the resume and portfolio site.

---

## 21. Testing and Quality Strategy

| Level | Scope | Tools |
|---|---|---|
| Unit | Services, state machine, Zustand event store | JUnit 5, Mockito, Vitest |
| Integration | Event log, approvals, leases against real PostgreSQL and Redis | Testcontainers |
| API and protocol | REST endpoints, WebSocket subscribe, command, and error flows | JUnit, WebSocket test client |
| End to end | Two browser windows on one session: live updates, takeover, approval | Playwright |
| Performance | Concurrent viewers and sessions; latency percentiles | k6 |
| Security | Role-by-command matrix, ticket reuse, injection attempts with the scripted agent | JUnit, manual checks, dependency scanning |

### 21.1 Required Tests Before MVP Is Complete

1. Two approvers decide at once: exactly one decision persists.
2. Disconnect and reconnect mid-stream: no gaps, no duplicates.
3. Two users take control at once: one lease holder.
4. A Viewer attempts to steer: rejected with FORBIDDEN.
5. A repeated command id does not repeat its effect.
6. An agent exceeding its budget ends safely with a logged reason.
7. A server restart resumes the session from the last logged event.

Tests must be deterministic: no fixed sleeps; use latches, awaits, and the scripted agent.

---

## 22. Dependencies and Assumptions

### 22.1 Dependencies

- An LLM API account and key for the real agent (optional for tests and demos).
- A VPS or free-tier host for deployment and a domain name for TLS.
- Docker on the development machine.

### 22.2 Assumptions

- A single developer works about 25 to 30 hours per week for 6 weeks.
- The developer is strong in Java and Spring and new to React and TypeScript; frontend work is the main learning area.
- Demo traffic is small; a single VPS is sufficient.
- A fake orders database is acceptable for demonstrating realistic tool use.

---

## 23. Risks and Mitigations

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| RK-1 | Frontend learning curve slows delivery | High | Medium | Build the Zustand event store early, keep screens simple, use a component library |
| RK-2 | LLM cost or flakiness breaks demos | Medium | High | Scripted agent by default, budgets, daily cap, recorded fallback demo |
| RK-3 | Ordering and replay bugs under concurrency | Medium | High | Tests first for R1 to R4, Testcontainers, repeated race tests |
| RK-4 | Prompt injection through ticket text | Medium | High | Tool allowlist, approval gates, untrusted-input handling |
| RK-5 | Crowded market reduces perceived uniqueness | High | Medium | Emphasize provable correctness, governance, and open implementation |
| RK-6 | Scope creep (multi-agent, integrations) | High | Medium | Enforce the non-goals list and slice-by-slice delivery |
| RK-7 | WebSocket problems behind proxies or on deployment | Medium | Medium | Configure the reverse proxy for long-lived connections early; test in week 1 |
| RK-8 | Redis state lost on restart | Low | Medium | Treat Redis as rebuildable; PostgreSQL is the source of truth |

---

## 24. Open Questions

| ID | Question | Owner | Needed By |
|---|---|---|---|
| OQ-1 | What refund amount threshold should mark a tool call as risky by default? | Product owner | Week 4 |
| OQ-2 | Should denied approvals end the session or let the agent continue with an alternative? (Current decision: continue.) | Product owner | Week 5 |
| OQ-3 | Which LLM provider and model will back the real agent, and what daily spend cap applies? | Product owner | Week 6 |
| OQ-4 | Is the hash-chained audit log worth including in the MVP? (Currently a stretch goal.) | Product owner | Week 5 |
| OQ-5 | What approval timeout is appropriate for the demo? | Product owner | Week 5 |

---

## 25. Glossary

| Term | Meaning |
|---|---|
| Agent | The AI process that works on a ticket and calls tools |
| Session | One live run of an agent on one ticket |
| Event | A single numbered record of something that happened in a session |
| seq | The event's position in the session's history, starting at 1 with no gaps |
| Replay | Re-sending missed events after a reconnect |
| Lease | A temporary lock stating who currently controls a session; expires automatically |
| Controller | The user who currently holds the lease |
| Steer | A human instruction injected into a running agent |
| Hand-off | Transferring control of a session to a teammate with a summary |
| Approval gate | A pause that waits for an authorized human to approve or deny a risky action |
| Risky tool | A tool configured to require approval (for example, a large refund) |
| Intervention | Any human steer, pause, or takeover on a session |
| Scripted agent | A deterministic fake agent used for tests and demos |
| Presence | The list of people currently viewing a session |

---

## 26. Appendix: Traceability (Rules to Requirements to Tests)

| Core Rule | Requirements | Verifying Test |
|---|---|---|
| R1 Ordered, append-only events | FR-EVT-1, FR-AUD-3, NFR-COR-1 | Concurrent append test checks the unique, gapless sequence |
| R2 Resume without loss | FR-EVT-3, FR-EVT-4, NFR-COR-2 | Random-disconnect replay test |
| R3 One decision per approval | FR-APR-4, NFR-COR-3 | 1,000-run simultaneous approval race test |
| R4 One controller at a time | FR-CTL-1, FR-CTL-4, NFR-COR-4 | Simultaneous take-control test |
| R5 Authorize every command | FR-APR-3, NFR-SEC-1, US-7 | Role-by-command matrix test |
| R6 Risky tools need approval | FR-APR-1, FR-APR-2, FR-AGT-3 | Scripted refund scenario test |
| R7 Everything is audited | FR-AUD-1, FR-AUD-2 | Audit completeness test comparing actions to events |