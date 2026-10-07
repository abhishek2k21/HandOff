import { useEffect, useRef, useState } from 'react';
import { getSession, getSessionEvents } from '../api/sessions';
import type { Session, SessionEvent } from '../api/types';
import { StatusBadge } from './SessionsPage';

// TEMPORARY: Slice 2 polling. Replaced by WebSocket streaming in Slice 3 and Zustand event log replay in Slice 4.

interface SessionDetailPageProps {
  sessionId: string;
  onBack: () => void;
}

export function SessionDetailPage({ sessionId, onBack }: SessionDetailPageProps) {
  const [session, setSession] = useState<Session | null>(null);
  const [events, setEvents] = useState<SessionEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Keep tracks of last seen seq to poll only for seq > lastSeenSeq
  const lastSeenSeqRef = useRef<number>(0);
  const isTerminalRef = useRef<boolean>(false);
  const intervalIdRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Initial load and polling lifecycle
  useEffect(() => {
    let cancelled = false;

    // Helper to stop polling
    const stopPolling = () => {
      if (intervalIdRef.current !== null) {
        clearInterval(intervalIdRef.current);
        intervalIdRef.current = null;
      }
    };

    // Helper to start polling interval
    const startPolling = () => {
      if (intervalIdRef.current !== null || isTerminalRef.current) return;

      intervalIdRef.current = setInterval(async () => {
        if (isTerminalRef.current) {
          stopPolling();
          return;
        }

        try {
          const res = await getSessionEvents(sessionId, lastSeenSeqRef.current, 200);
          if (res.events && res.events.length > 0) {
            // Deduplicate: apply only events with seq > lastSeenSeq
            const newEvents = res.events.filter((e) => e.seq > lastSeenSeqRef.current);
            if (newEvents.length > 0) {
              lastSeenSeqRef.current = newEvents[newEvents.length - 1].seq;
              setEvents((prev) => {
                // Combine and ensure strictly unique seqs
                const existingSeqs = new Set(prev.map((e) => e.seq));
                const deduplicated = newEvents.filter((e) => !existingSeqs.has(e.seq));
                return [...prev, ...deduplicated];
              });

              // Check if any event marks session completion or failure
              for (const ev of newEvents) {
                if (ev.type === 'SESSION_COMPLETED') {
                  isTerminalRef.current = true;
                  setSession((curr) => (curr ? { ...curr, status: 'COMPLETED' } : curr));
                  stopPolling();
                  break;
                } else if (ev.type === 'SESSION_FAILED') {
                  isTerminalRef.current = true;
                  setSession((curr) => (curr ? { ...curr, status: 'FAILED' } : curr));
                  stopPolling();
                  break;
                }
              }
            }
          }
        } catch (err: unknown) {
          // Do not break the UI on transient polling errors
          console.warn('Polling error:', err);
        }
      }, 1000);
    };

    async function initialize() {
      setLoading(true);
      setError(null);
      try {
        const [loadedSession, initialEvents] = await Promise.all([
          getSession(sessionId),
          getSessionEvents(sessionId, 0, 200),
        ]);

        if (cancelled) return;

        setSession(loadedSession);
        const evList = initialEvents.events || [];
        setEvents(evList);

        if (evList.length > 0) {
          lastSeenSeqRef.current = evList[evList.length - 1].seq;
        }

        const isTerminal =
          loadedSession.status === 'COMPLETED' ||
          loadedSession.status === 'FAILED' ||
          evList.some((e) => e.type === 'SESSION_COMPLETED' || e.type === 'SESSION_FAILED');

        if (isTerminal) {
          isTerminalRef.current = true;
          stopPolling();
        } else {
          isTerminalRef.current = false;
          startPolling();
        }
      } catch (err: unknown) {
        if (!cancelled) {
          const message = err instanceof Error ? err.message : 'Failed to load session details';
          setError(message);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    initialize();

    // Handle tab visibility: pause polling when hidden, resume when visible
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'hidden') {
        stopPolling();
      } else if (document.visibilityState === 'visible' && !isTerminalRef.current) {
        startPolling();
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      cancelled = true;
      stopPolling();
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [sessionId]);

  // Cap visible events to latest 200
  const totalCount = events.length;
  const currentSeq = events.length > 0 ? events[events.length - 1].seq : (session?.lastSeq ?? 0);
  const visibleEvents = totalCount > 200 ? events.slice(-200) : events;

  return (
    <div className="space-y-6">
      {/* Navigation and Actions */}
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={onBack}
          className="inline-flex items-center gap-1.5 rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-xs font-medium text-gray-300 hover:bg-gray-700 hover:text-white focus:outline-none focus:ring-2 focus:ring-indigo-500 cursor-pointer transition-colors"
        >
          ← Back to sessions
        </button>

        {session && (
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-400">Status:</span>
            <StatusBadge status={session.status} />
          </div>
        )}
      </div>

      {/* Error Alert */}
      {error && (
        <div
          role="alert"
          className="rounded-xl border border-red-800 bg-red-950/60 p-4 text-sm text-red-300"
        >
          <p className="font-semibold">Error loading session</p>
          <p className="text-xs text-red-400 mt-1">{error}</p>
        </div>
      )}

      {/* Session Header Card */}
      {session && (
        <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm p-6 shadow-xl space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-4 border-b border-gray-800">
            <div>
              <span className="text-xs font-semibold uppercase tracking-wider text-indigo-400">
                Ticket Session
              </span>
              <h2 className="text-2xl font-bold font-mono text-white mt-0.5">
                {session.ticketId}
              </h2>
            </div>
            <div>
              <StatusBadge status={session.status} />
            </div>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
            <div>
              <span className="text-gray-500 block uppercase font-medium">Scenario</span>
              <span className="text-gray-200 font-medium mt-0.5 block">{session.scenario}</span>
            </div>
            <div>
              <span className="text-gray-500 block uppercase font-medium">Agent Type</span>
              <span className="font-mono text-gray-200 mt-0.5 block">{session.agentType}</span>
            </div>
            <div>
              <span className="text-gray-500 block uppercase font-medium">Current Seq</span>
              <span className="font-mono text-gray-200 mt-0.5 block">{currentSeq}</span>
            </div>
            <div>
              <span className="text-gray-500 block uppercase font-medium">Created</span>
              <span className="text-gray-200 mt-0.5 block">
                {new Date(session.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Events Log Header */}
      <div className="flex items-center justify-between pt-2">
        <div>
          <h3 className="text-base font-semibold text-white">Event Log</h3>
          <p className="text-xs text-gray-400 mt-0.5">
            {totalCount > 200 ? `Showing latest 200 of ${totalCount}` : `Showing ${totalCount} events`}
          </p>
        </div>

        {session && (session.status === 'RUNNING' || session.status === 'PAUSED') && (
          <span className="inline-flex items-center gap-1.5 text-xs text-indigo-400">
            <span className="h-2 w-2 rounded-full bg-indigo-500 animate-pulse" />
            Polling live events (1s)
          </span>
        )}
      </div>

      {/* Events Stream / Log View */}
      {loading ? (
        <div className="rounded-2xl border border-gray-800 bg-gray-900/60 p-8 text-center">
          <div className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-indigo-400 border-t-transparent" />
          <p className="mt-3 text-sm text-gray-400">Loading events…</p>
        </div>
      ) : visibleEvents.length === 0 ? (
        <div className="rounded-2xl border border-gray-800 bg-gray-900/60 p-12 text-center">
          <p className="text-sm font-medium text-gray-400">No events in this session yet.</p>
        </div>
      ) : (
        <div className="space-y-2 font-sans" data-testid="events-container">
          {visibleEvents.map((event) => (
            <EventRow key={`${event.sessionId}-${event.seq}`} event={event} />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Compact readable row component for each event type.
 * Ensures plain text rendering without dangerouslySetInnerHTML.
 */
function EventRow({ event }: { event: SessionEvent }) {
  const { seq, type, actor, payload, createdAt } = event;
  const time = new Date(createdAt).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });

  switch (type) {
    case 'AGENT_TEXT': {
      const text = typeof payload.text === 'string' ? payload.text : JSON.stringify(payload.text);
      const isFinal = Boolean(payload.final);
      return (
        <div className="rounded-xl border border-indigo-900/40 bg-gray-900/70 p-3.5 space-y-1.5 shadow-sm">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-indigo-950 px-2 py-0.5 font-semibold text-indigo-300 border border-indigo-800/80">
                AGENT_TEXT
              </span>
              {isFinal && (
                <span className="rounded bg-gray-800 px-1.5 py-0.5 text-[10px] font-mono text-gray-400">
                  final
                </span>
              )}
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          <p className="text-sm text-gray-100 font-mono whitespace-pre-wrap leading-relaxed pl-1">
            {text}
          </p>
        </div>
      );
    }

    case 'TOOL_CALL': {
      const toolName = typeof payload.tool === 'string' ? payload.tool : 'unknown';
      const args = payload.args ? JSON.stringify(payload.args, null, 2) : '{}';
      return (
        <div className="rounded-xl border border-cyan-900/40 bg-gray-900/70 p-3.5 space-y-1.5 shadow-sm">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-cyan-950 px-2 py-0.5 font-semibold text-cyan-300 border border-cyan-800/80">
                TOOL_CALL
              </span>
              <span className="font-mono font-medium text-cyan-200">{toolName}</span>
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          <pre className="text-xs text-gray-300 font-mono bg-gray-950/70 p-2.5 rounded-lg overflow-x-auto border border-gray-800">
            {args}
          </pre>
        </div>
      );
    }

    case 'TOOL_RESULT': {
      const ok = Boolean(payload.ok);
      const resultData = payload.result !== undefined ? payload.result : payload.error;
      const formatted = typeof resultData === 'object' ? JSON.stringify(resultData, null, 2) : String(resultData ?? '');
      return (
        <div className="rounded-xl border border-teal-900/40 bg-gray-900/70 p-3.5 space-y-1.5 shadow-sm">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-teal-950 px-2 py-0.5 font-semibold text-teal-300 border border-teal-800/80">
                TOOL_RESULT
              </span>
              <span className={`text-[11px] font-semibold ${ok ? 'text-emerald-400' : 'text-red-400'}`}>
                {ok ? 'SUCCESS' : 'FAILED'}
              </span>
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          <pre className="text-xs text-gray-300 font-mono bg-gray-950/70 p-2.5 rounded-lg overflow-x-auto border border-gray-800">
            {formatted}
          </pre>
        </div>
      );
    }

    case 'SESSION_STARTED': {
      return (
        <div className="rounded-xl border border-gray-800 bg-gray-900/50 p-3 flex items-center justify-between text-xs">
          <div className="flex items-center gap-2">
            <span className="font-mono text-gray-500">#{seq}</span>
            <span className="rounded bg-gray-800 px-2 py-0.5 font-semibold text-gray-300">
              SESSION_STARTED
            </span>
            <span className="text-gray-400">
              Ticket: <strong className="text-gray-200">{String(payload.ticketId ?? '')}</strong> ({String(payload.scenario ?? '')})
            </span>
          </div>
          <span className="text-gray-500 font-mono text-[11px]">{time}</span>
        </div>
      );
    }

    case 'CONTROL_TAKEN': {
      return (
        <div className="rounded-xl border border-gray-800 bg-gray-900/50 p-3 flex items-center justify-between text-xs">
          <div className="flex items-center gap-2">
            <span className="font-mono text-gray-500">#{seq}</span>
            <span className="rounded bg-purple-950 px-2 py-0.5 font-semibold text-purple-300 border border-purple-800">
              CONTROL_TAKEN
            </span>
            <span className="text-gray-300">
              Assigned to {actor.name || actor.id}
            </span>
          </div>
          <span className="text-gray-500 font-mono text-[11px]">{time}</span>
        </div>
      );
    }

    case 'SESSION_COMPLETED': {
      const summary = typeof payload.summary === 'string' ? payload.summary : '';
      return (
        <div className="rounded-xl border border-emerald-900/50 bg-emerald-950/20 p-3.5 space-y-1">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-emerald-950 px-2 py-0.5 font-semibold text-emerald-300 border border-emerald-800">
                SESSION_COMPLETED
              </span>
              <span className="text-emerald-400 font-medium">Outcome: {String(payload.outcome ?? 'RESOLVED')}</span>
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          {summary && <p className="text-xs text-gray-300 mt-1">{summary}</p>}
        </div>
      );
    }

    case 'SESSION_FAILED': {
      const message = typeof payload.message === 'string' ? payload.message : '';
      return (
        <div className="rounded-xl border border-red-900/50 bg-red-950/20 p-3.5 space-y-1">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-red-950 px-2 py-0.5 font-semibold text-red-300 border border-red-800">
                SESSION_FAILED
              </span>
              <span className="text-red-400 font-medium">Reason: {String(payload.reason ?? 'ERROR')}</span>
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          {message && <p className="text-xs text-red-300 mt-1">{message}</p>}
        </div>
      );
    }

    case 'ERROR': {
      const message = typeof payload.message === 'string' ? payload.message : '';
      return (
        <div className="rounded-xl border border-red-900/50 bg-red-950/20 p-3.5 space-y-1">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-red-950 px-2 py-0.5 font-semibold text-red-300 border border-red-800">
                ERROR
              </span>
              <span className="text-red-400 font-mono">Code: {String(payload.code ?? '')}</span>
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          {message && <p className="text-xs text-red-300 mt-1">{message}</p>}
        </div>
      );
    }

    default: {
      // Fallback: render unknown types gracefully in a generic row
      return (
        <div
          data-testid="generic-event-row"
          className="rounded-xl border border-gray-800 bg-gray-900/60 p-3.5 space-y-1"
        >
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-mono text-gray-500">#{seq}</span>
              <span className="rounded bg-gray-800 px-2 py-0.5 font-semibold text-gray-300">
                {type}
              </span>
              <span className="text-gray-400">Actor: {actor.kind} ({actor.name || actor.id})</span>
            </div>
            <span className="text-gray-500 font-mono text-[11px]">{time}</span>
          </div>
          <pre className="text-xs text-gray-400 font-mono bg-gray-950 p-2 rounded overflow-x-auto border border-gray-800/80">
            {JSON.stringify(payload, null, 2)}
          </pre>
        </div>
      );
    }
  }
}
