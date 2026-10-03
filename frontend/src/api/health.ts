/**
 * Health API client.
 *
 * All API calls live in src/api/ — components never call fetch() directly.
 * This keeps network logic in one place and makes testing easier.
 */

export interface HealthResponse {
  status: string;
  database: string;
  redis: string;
}

export async function fetchHealth(): Promise<HealthResponse> {
  // The Vite proxy forwards /api/* to http://localhost:8080 (see vite.config.ts).
  // In production, the reverse proxy handles this instead.
  const response = await fetch('/api/health');
  // We read the body even on 503 because the server always returns JSON.
  const data: HealthResponse = await response.json();
  return data;
}
