import { apiRequest, ApiError } from './client';
import type {
  AuthResponse,
  LoginRequest,
  LogoutRequest,
  RegisterRequest,
  WsTicketResponse,
} from './types';

export const REFRESH_TOKEN_STORAGE_KEY = 'handoff_refresh_token';

export function getStoredRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function setStoredRefreshToken(token: string): void {
  try {
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, token);
  } catch {
    // Ignore storage errors in restricted contexts
  }
}

export function clearStoredRefreshToken(): void {
  try {
    localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
  } catch {
    // Ignore storage errors in restricted contexts
  }
}

/**
 * Register a new organization and admin user.
 * Not protected: does not retry on 401.
 */
export async function apiRegister(req: RegisterRequest): Promise<AuthResponse> {
  const data = await apiRequest<AuthResponse>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(req),
    isProtected: false,
  });
  setStoredRefreshToken(data.refreshToken);
  return data;
}

/**
 * Log in with email and password.
 * Not protected: does not retry or refresh on 401.
 */
export async function apiLogin(req: LoginRequest): Promise<AuthResponse> {
  const data = await apiRequest<AuthResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(req),
    isProtected: false,
  });
  setStoredRefreshToken(data.refreshToken);
  return data;
}

/**
 * Log out and revoke refresh token on backend.
 * Clears localStorage immediately.
 */
export async function apiLogout(refreshToken?: string): Promise<void> {
  const token = refreshToken || getStoredRefreshToken();
  try {
    if (token) {
      const payload: LogoutRequest = { refreshToken: token };
      await apiRequest<void>('/api/auth/logout', {
        method: 'POST',
        body: JSON.stringify(payload),
        isProtected: false,
      });
    }
  } finally {
    clearStoredRefreshToken();
  }
}

/**
 * Request a single-use WebSocket ticket.
 * Protected: triggers 401 single-flight refresh and retry.
 */
export async function apiGetWsTicket(): Promise<WsTicketResponse> {
  return await apiRequest<WsTicketResponse>('/api/ws-ticket', {
    method: 'POST',
    isProtected: true,
  });
}

// Module-level in-flight promise for single-flight refresh across calls within the same tab
let inFlightRefreshPromise: Promise<AuthResponse> | null = null;

/**
 * Refreshes session tokens.
 * Single-flight in-tab, Web Lock coordinated across tabs, and immediately persists rotated token.
 */
export function apiRefresh(tokenOverride?: string): Promise<AuthResponse> {
  if (inFlightRefreshPromise) {
    return inFlightRefreshPromise;
  }

  const initialToken = tokenOverride || getStoredRefreshToken();

  inFlightRefreshPromise = (async () => {
    // Multi-tab concurrency protection via Web Locks API
    const runWithLock = async (fn: () => Promise<AuthResponse>): Promise<AuthResponse> => {
      if (
        typeof navigator !== 'undefined' &&
        navigator.locks &&
        typeof navigator.locks.request === 'function'
      ) {
        return await navigator.locks.request('handoff-refresh', fn);
      }
      return await fn();
    };

    return await runWithLock(async () => {
      // Inside lock, re-read token from localStorage.
      // If another tab rotated the token while waiting, use the new stored token.
      const currentStored = getStoredRefreshToken();
      const tokenToSend =
        currentStored && currentStored !== initialToken
          ? currentStored
          : currentStored || initialToken;

      if (!tokenToSend) {
        throw new ApiError(401, 'UNAUTHENTICATED', 'No refresh token available');
      }

      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ refreshToken: tokenToSend }),
      });

      if (!response.ok) {
        if (response.status === 401) {
          clearStoredRefreshToken();
        }
        let errPayload: any = null;
        try {
          errPayload = await response.json();
        } catch {
          // ignore parse error
        }
        throw new ApiError(
          response.status,
          errPayload?.error?.code || 'UNAUTHENTICATED',
          errPayload?.error?.message || 'Failed to refresh token',
          errPayload?.error?.details,
          errPayload?.error?.correlationId
        );
      }

      const data: AuthResponse = await response.json();

      // Persist the rotated refresh token to localStorage immediately,
      // before any caller receives the result.
      setStoredRefreshToken(data.refreshToken);

      return data;
    });
  })().finally(() => {
    inFlightRefreshPromise = null;
  });

  return inFlightRefreshPromise;
}
