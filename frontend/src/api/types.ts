export type Role = 'ADMIN' | 'APPROVER' | 'OPERATOR' | 'VIEWER';

export interface User {
  id: string;
  name: string;
  role: Role;
  organizationId: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: User;
}

export interface WsTicketResponse {
  ticket: string;
  expiresInSeconds: number;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  organizationName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface LogoutRequest {
  refreshToken: string;
}

export interface ApiErrorDetails {
  fields?: Record<string, string>;
  retryAfterSeconds?: number;
  [key: string]: unknown;
}

export interface ApiErrorPayload {
  code: string;
  message: string;
  details?: ApiErrorDetails;
  correlationId?: string;
}

export interface ApiErrorEnvelope {
  error: ApiErrorPayload;
}

export type SessionStatus =
  | 'RUNNING'
  | 'PAUSED'
  | 'AWAITING_APPROVAL'
  | 'HANDED_OFF'
  | 'COMPLETED'
  | 'FAILED';

export interface Session {
  id: string;
  ticketId: string;
  agentType: string;
  scenario: string;
  status: SessionStatus;
  controllerUserId: string | null;
  lastSeq: number;
  stepBudget: number;
  tokenBudget: number;
  createdAt: string;
  endedAt: string | null;
}

export interface Ticket {
  id: string;
  customerName: string;
  customerEmail: string;
  subject: string;
  description: string;
  status: 'OPEN' | 'PENDING' | 'CLOSED';
  orderId?: string | null;
  createdAt: string;
  updatedAt: string;
}

export type ActorKind = 'AGENT' | 'USER' | 'SYSTEM';

export interface EventActor {
  kind: ActorKind;
  id: string;
  name?: string | null;
}

export interface SessionEvent {
  sessionId: string;
  seq: number;
  type: string;
  actor: EventActor;
  commandId?: string | null;
  payload: Record<string, unknown>;
  createdAt: string;
}

export interface EventsResponse {
  sessionId: string;
  events: SessionEvent[];
  lastSeq: number;
  hasMore: boolean;
}

export interface TicketsResponse {
  items: Ticket[];
}

export interface SessionsResponse {
  items: Session[];
}

export interface CreateSessionRequest {
  ticketId: string;
  agentType?: string;
  scenario: string;
  stepBudget?: number;
  tokenBudget?: number;
}
