import { useEffect, useState } from 'react';
import { fetchHealth, type HealthResponse } from './api/health';

/**
 * Main App component — shows the backend health status.
 *
 * It handles three states (per AGENTS.md §11 — frontend standards):
 * 1. Loading:  a spinner while we wait for /api/health
 * 2. Success:  green/red indicators for backend, database, and redis
 * 3. Error:    a message if the backend is unreachable
 */
function App() {
  // useState<T | null>(null) means "we don't have the data yet."
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // useEffect runs once when the component first appears on screen.
  // We fetch the health data here.
  useEffect(() => {
    let cancelled = false; // prevents setting state after unmount

    fetchHealth()
      .then((data) => {
        if (!cancelled) {
          setHealth(data);
          setError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          const message = err instanceof Error ? err.message : 'Unknown error';
          setError(message);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => { cancelled = true; };
  }, []);

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        {/* Header */}
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold tracking-tight bg-gradient-to-r from-indigo-400 to-cyan-400 bg-clip-text text-transparent">
            HandOff
          </h1>
          <p className="text-gray-400 mt-1 text-sm">
            AI Agent Supervision Platform
          </p>
        </div>

        {/* Health card */}
        <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm shadow-xl p-6">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-gray-400 mb-4">
            System Health
          </h2>

          {loading && (
            <div className="flex items-center gap-3 text-gray-400">
              {/* Simple CSS spinner */}
              <div className="h-5 w-5 animate-spin rounded-full border-2 border-gray-600 border-t-indigo-400" />
              <span>Checking health…</span>
            </div>
          )}

          {error && (
            <div className="rounded-lg bg-red-950/50 border border-red-800 p-4 text-red-300 text-sm">
              <p className="font-medium">Cannot reach the backend</p>
              <p className="mt-1 text-red-400 text-xs">{error}</p>
              <button
                onClick={() => window.location.reload()}
                className="mt-3 text-xs font-medium text-red-200 underline underline-offset-2 hover:text-white transition-colors cursor-pointer"
              >
                Retry
              </button>
            </div>
          )}

          {health && (
            <div className="space-y-3">
              <StatusRow label="Backend"  value={health.status}   />
              <StatusRow label="Database" value={health.database} />
              <StatusRow label="Redis"    value={health.redis}    />
            </div>
          )}
        </div>

        {/* Version */}
        <p className="text-center text-xs text-gray-600 mt-6">
          Slice 0 · Skeleton
        </p>
      </div>
    </div>
  );
}

/** Small reusable row showing a label and an UP / DOWN badge. */
function StatusRow({ label, value }: { label: string; value: string }) {
  const isUp = value === 'UP';
  return (
    <div className="flex items-center justify-between py-2 px-3 rounded-lg bg-gray-800/50">
      <span className="text-sm text-gray-300">{label}</span>
      <span
        className={`inline-flex items-center gap-1.5 text-xs font-semibold px-2.5 py-0.5 rounded-full ${
          isUp
            ? 'bg-emerald-900/50 text-emerald-300'
            : 'bg-red-900/50 text-red-300'
        }`}
      >
        <span
          className={`h-1.5 w-1.5 rounded-full ${isUp ? 'bg-emerald-400' : 'bg-red-400'}`}
        />
        {value}
      </span>
    </div>
  );
}

export default App;
