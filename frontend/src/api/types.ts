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
