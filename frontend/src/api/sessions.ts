import { apiRequest } from './client';
import type {
  CreateSessionRequest,
  EventsResponse,
  Session,
  SessionsResponse,
  Ticket,
  TicketsResponse,
} from './types';

/**
 * Fetch all tickets available in the current user's organization.
 */
export async function getTickets(): Promise<Ticket[]> {
  const response = await apiRequest<TicketsResponse>('/api/tickets');
  return response?.items || [];
}

/**
 * List sessions in the organization, ordered newest first by the backend.
 */
export async function listSessions(limit = 50): Promise<Session[]> {
  const response = await apiRequest<SessionsResponse>(`/api/sessions?limit=${limit}`);
  return response?.items || [];
}

/**
 * Create a new session for a ticket and scenario.
 */
export async function createSession(req: CreateSessionRequest): Promise<Session> {
  return await apiRequest<Session>('/api/sessions', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

/**
 * Fetch a single session snapshot by ID.
 */
export async function getSession(id: string): Promise<Session> {
  return await apiRequest<Session>(`/api/sessions/${id}`);
}

/**
 * Fetch session events with seq > fromSeq up to limit.
 * Used for polling in Slice 2 (replaced by WS and replay in Slices 3-4).
 */
export async function getSessionEvents(
  sessionId: string,
  fromSeq = 0,
  limit = 200
): Promise<EventsResponse> {
  return await apiRequest<EventsResponse>(
    `/api/sessions/${sessionId}/events?fromSeq=${fromSeq}&limit=${limit}`
  );
}
