import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { createSession, getTickets, listSessions } from '../api/sessions';
import type { Role, Session, SessionStatus, Ticket } from '../api/types';
import { useAuthStore } from '../store/auth';

interface SessionsPageProps {
  userRole: Role;
  onSelectSession: (sessionId: string) => void;
}

export function SessionsPage({ userRole, onSelectSession }: SessionsPageProps) {
  const authStatus = useAuthStore((s) => s.status);
  const accessToken = useAuthStore((s) => s.accessToken);

  const [sessions, setSessions] = useState<Session[]>([]);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // New session form state
  const [showNewForm, setShowNewForm] = useState(false);
  const [selectedTicketId, setSelectedTicketId] = useState<string>('');
  const [selectedScenario, setSelectedScenario] = useState<'SIMPLE_LOOKUP' | 'LONG_STREAM'>('SIMPLE_LOOKUP');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Load initial sessions and tickets only when auth store is authenticated with an access token
  useEffect(() => {
    if (authStatus !== 'authenticated' || !accessToken) {
      return;
    }

    let cancelled = false;

    async function loadData() {
      setLoading(true);
      setError(null);
      try {
        const [loadedSessions, loadedTickets] = await Promise.all([
          listSessions(),
          getTickets(),
        ]);
        if (!cancelled) {
          setSessions(Array.isArray(loadedSessions) ? loadedSessions : []);
          setTickets(Array.isArray(loadedTickets) ? loadedTickets : []);
        }
      } catch (err: unknown) {
        if (!cancelled) {
          setSessions([]);
          setTickets([]);
          const message = err instanceof Error ? err.message : 'Failed to load sessions';
          setError(message);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadData();

    return () => {
      cancelled = true;
    };
  }, [authStatus, accessToken]);

  const safeSessions = Array.isArray(sessions) ? sessions : [];
  const safeTickets = Array.isArray(tickets) ? tickets : [];

  // Filter open tickets that do not have an active session
  const activeTicketIds = new Set(
    safeSessions
      .filter((s) => s.status !== 'COMPLETED' && s.status !== 'FAILED')
      .map((s) => s.ticketId)
  );

  const availableTickets = safeTickets.filter(
    (t) => t.status === 'OPEN' && !activeTicketIds.has(t.id)
  );

  async function handleCreateSession(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);

    const ticketToUse = selectedTicketId || (availableTickets.length > 0 ? availableTickets[0].id : '');
    if (!ticketToUse) {
      setFormError('Please select a ticket.');
      return;
    }

    setSubmitting(true);
    try {
      const newSession = await createSession({
        ticketId: ticketToUse,
        agentType: 'SCRIPTED',
        scenario: selectedScenario,
      });

      // Update local sessions list
      setSessions((prev) => [newSession, ...prev.filter((s) => s.id !== newSession.id)]);
      setShowNewForm(false);
      setSelectedTicketId('');
      onSelectSession(newSession.id);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          setFormError(err.message || 'An active session already exists for this ticket.');
        } else if (err.status === 403) {
          setFormError('You do not have permission to create sessions.');
        } else if (err.status === 400) {
          setFormError(err.message || 'Invalid session request. Please check inputs.');
        } else {
          setFormError(err.message || 'Failed to create session.');
        }
      } else {
        setFormError(err instanceof Error ? err.message : 'Failed to create session.');
      }
    } finally {
      setSubmitting(false);
    }
  }

  const isViewer = userRole === 'VIEWER';
  const sortedSessions = [...safeSessions].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  );

  return (
    <div className="space-y-6">
      {/* Header and Action */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold tracking-tight text-white">Supervision Sessions</h2>
          <p className="text-sm text-gray-400 mt-0.5">
            Monitor and steer AI agents working on customer support tickets
          </p>
        </div>

        {!isViewer && (
          <button
            type="button"
            onClick={() => {
              setShowNewForm((prev) => {
                const next = !prev;
                if (next && availableTickets.length > 0 && !selectedTicketId) {
                  setSelectedTicketId(availableTickets[0].id);
                }
                return next;
              });
              setFormError(null);
            }}
            className="inline-flex items-center justify-center rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-gray-900 transition-colors cursor-pointer"
          >
            {showNewForm ? 'Cancel' : 'New session'}
          </button>
        )}
      </div>

      {/* Server Error Alert */}
      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-800 bg-red-950/60 p-4 text-sm text-red-300"
        >
          <p className="font-semibold">Error loading sessions</p>
          <p className="text-xs text-red-400 mt-1">{error}</p>
        </div>
      )}

      {/* New Session Form */}
      {!isViewer && showNewForm && (
        <div className="rounded-2xl border border-indigo-900/60 bg-gray-900/80 backdrop-blur-sm p-6 shadow-xl">
          <h3 className="text-base font-semibold text-white mb-4">Start New Agent Session</h3>

          {formError && (
            <div
              role="alert"
              className="mb-4 rounded-lg border border-red-800 bg-red-950/70 p-3 text-sm text-red-300"
            >
              {formError}
            </div>
          )}

          <form onSubmit={handleCreateSession} className="space-y-4">
            <div>
              <label
                htmlFor="ticket-select"
                className="block text-xs font-semibold uppercase tracking-wider text-gray-300 mb-1.5"
              >
                Ticket
              </label>
              {availableTickets.length === 0 ? (
                <p className="text-xs text-amber-400 bg-amber-950/40 border border-amber-900 p-2.5 rounded-lg">
                  No open tickets available without an active session.
                </p>
              ) : (
                <select
                  id="ticket-select"
                  value={selectedTicketId || availableTickets[0]?.id || ''}
                  onChange={(e) => setSelectedTicketId(e.target.value)}
                  className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-gray-100 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  {availableTickets.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.id} — {t.subject}
                    </option>
                  ))}
                </select>
              )}
            </div>

            <div>
              <label
                htmlFor="scenario-select"
                className="block text-xs font-semibold uppercase tracking-wider text-gray-300 mb-1.5"
              >
                Scenario
              </label>
              <select
                id="scenario-select"
                value={selectedScenario}
                onChange={(e) => setSelectedScenario(e.target.value as 'SIMPLE_LOOKUP' | 'LONG_STREAM')}
                className="w-full rounded-lg border border-gray-700 bg-gray-800 px-3 py-2 text-sm text-gray-100 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="SIMPLE_LOOKUP">SIMPLE_LOOKUP (Order lookup, note & close)</option>
                <option value="LONG_STREAM">LONG_STREAM (2,000 text chunks stream)</option>
              </select>
            </div>

            <div className="flex items-center justify-end gap-3 pt-2">
              <button
                type="button"
                onClick={() => setShowNewForm(false)}
                className="rounded-lg border border-gray-700 bg-gray-800 px-3.5 py-2 text-xs font-medium text-gray-300 hover:bg-gray-700 hover:text-white focus:outline-none focus:ring-2 focus:ring-gray-500 cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={submitting || availableTickets.length === 0}
                className="rounded-lg bg-indigo-600 px-4 py-2 text-xs font-semibold text-white hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:ring-offset-2 focus:ring-offset-gray-900 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              >
                {submitting ? 'Starting…' : 'Start Session'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Sessions List */}
      {loading ? (
        <div className="rounded-2xl border border-gray-800 bg-gray-900/60 p-8 text-center">
          <div className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-indigo-400 border-t-transparent" />
          <p className="mt-3 text-sm text-gray-400">Loading sessions…</p>
        </div>
      ) : sortedSessions.length === 0 ? (
        <div
          data-testid="empty-sessions"
          className="rounded-2xl border border-gray-800 bg-gray-900/60 p-12 text-center"
        >
          <p className="text-base font-medium text-gray-300">No sessions yet</p>
          <p className="text-sm text-gray-500 mt-1">
            {isViewer
              ? 'There are currently no active or past sessions in this organization.'
              : 'Create a new session above to watch the AI agent process a ticket.'}
          </p>
        </div>
      ) : (
        <div className="grid gap-3">
          {sortedSessions.map((s) => (
            <div
              key={s.id}
              onClick={() => onSelectSession(s.id)}
              className="group flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-4 rounded-xl border border-gray-800 bg-gray-900/60 hover:bg-gray-800/60 hover:border-indigo-800/80 transition-all cursor-pointer shadow-sm"
            >
              <div className="space-y-1">
                <div className="flex items-center gap-2.5">
                  <span className="font-mono text-sm font-semibold text-white group-hover:text-indigo-300 transition-colors">
                    {s.ticketId}
                  </span>
                  <StatusBadge status={s.status} />
                </div>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-400">
                  <span>Scenario: <strong className="text-gray-300">{s.scenario}</strong></span>
                  <span>Agent: <span className="font-mono text-gray-300">{s.agentType}</span></span>
                  <span>Last seq: <span className="font-mono text-gray-300">{s.lastSeq}</span></span>
                </div>
              </div>

              <div className="flex items-center gap-3">
                <span className="text-xs text-gray-500">
                  {new Date(s.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                </span>
                <span className="text-xs font-medium text-indigo-400 group-hover:translate-x-0.5 transition-transform">
                  View →
                </span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function StatusBadge({ status }: { status: SessionStatus }) {
  const styles: Record<SessionStatus, string> = {
    RUNNING: 'bg-indigo-950/80 text-indigo-300 border-indigo-700',
    PAUSED: 'bg-amber-950/80 text-amber-300 border-amber-700',
    AWAITING_APPROVAL: 'bg-orange-950/80 text-orange-300 border-orange-700',
    HANDED_OFF: 'bg-purple-950/80 text-purple-300 border-purple-700',
    COMPLETED: 'bg-emerald-950/80 text-emerald-300 border-emerald-700',
    FAILED: 'bg-red-950/80 text-red-300 border-red-700',
  };

  const currentStyle = styles[status] || 'bg-gray-800 text-gray-300 border-gray-700';

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold border ${currentStyle}`}
      aria-label={`Status: ${status}`}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
      {status}
    </span>
  );
}
