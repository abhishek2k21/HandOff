import type { ApiErrorDetails, ApiErrorEnvelope } from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: ApiErrorDetails;
  readonly correlationId?: string;

  constructor(
    status: number,
    code: string,
    message: string,
    details?: ApiErrorDetails,
    correlationId?: string
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
    this.correlationId = correlationId;
  }
}

let getAccessToken: () => string | null = () => null;
let onAuthFailure: () => void = () => {};
let refreshHandler: () => Promise<string | null> = async () => null;

/**
 * Configure auth token access and refresh handling for protected requests.
 * Keeps client.ts decoupled from Zustand store and auth.ts.
 */
export function configureAuthClient(config: {
  getAccessToken: () => string | null;
  onAuthFailure: () => void;
  refreshHandler: () => Promise<string | null>;
}) {
  getAccessToken = config.getAccessToken;
  onAuthFailure = config.onAuthFailure;
  refreshHandler = config.refreshHandler;
}

export interface RequestOptions extends RequestInit {
  isProtected?: boolean;
}

/**
 * Parses a failed HTTP response into a typed ApiError matching docs/events.md section 15.
 */
async function parseApiError(response: Response): Promise<ApiError> {
  try {
    const envelope = (await response.json()) as ApiErrorEnvelope;
    if (envelope?.error?.code && envelope?.error?.message) {
      return new ApiError(
        response.status,
        envelope.error.code,
        envelope.error.message,
        envelope.error.details,
        envelope.error.correlationId
      );
    }
  } catch {
    // Non-JSON or malformed error body
  }

  return new ApiError(
    response.status,
    response.status === 401 ? 'UNAUTHENTICATED' : 'HTTP_ERROR',
    response.statusText || `Request failed with status ${response.status}`
  );
}

/**
 * Fetch wrapper that sends JSON, attaches Bearer token on protected calls,
 * and intercepts 401s on protected requests to refresh and retry once.
 */
export async function apiRequest<T>(
  path: string,
  options: RequestOptions = {}
): Promise<T> {
  const { isProtected = true, headers: customHeaders, ...restOptions } = options;

  const headers = new Headers(customHeaders);
  if (!headers.has('Content-Type') && restOptions.body) {
    headers.set('Content-Type', 'application/json');
  }

  if (isProtected) {
    const token = getAccessToken();
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
  }

  const response = await fetch(path, {
    ...restOptions,
    headers,
  });

  // Handle protected 401: try single-flight refresh once, retry, or logout if it fails
  if (response.status === 401 && isProtected) {
    let newAccessToken: string | null = null;
    try {
      newAccessToken = await refreshHandler();
    } catch {
      onAuthFailure();
      throw await parseApiError(response);
    }

    if (!newAccessToken) {
      onAuthFailure();
      throw await parseApiError(response);
    }

    // Retry once with new access token
    const retryHeaders = new Headers(customHeaders);
    if (!retryHeaders.has('Content-Type') && restOptions.body) {
      retryHeaders.set('Content-Type', 'application/json');
    }
    retryHeaders.set('Authorization', `Bearer ${newAccessToken}`);

    const retryResponse = await fetch(path, {
      ...restOptions,
      headers: retryHeaders,
    });

    if (retryResponse.status === 401) {
      onAuthFailure();
      throw await parseApiError(retryResponse);
    }

    if (!retryResponse.ok) {
      throw await parseApiError(retryResponse);
    }

    if (retryResponse.status === 204) {
      return undefined as T;
    }
    return (await retryResponse.json()) as T;
  }

  if (!response.ok) {
    throw await parseApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
