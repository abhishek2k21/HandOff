import { useEffect, useState } from 'react';
import { fetchHealth, type HealthResponse } from './api/health';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { SessionDetailPage } from './pages/SessionDetailPage';
import { SessionsPage } from './pages/SessionsPage';
import { useAuthStore } from './store/auth';

function App() {
  const user = useAuthStore((s) => s.user);
  const authStatus = useAuthStore((s) => s.status);
  const logout = useAuthStore((s) => s.logout);
  const restoreSession = useAuthStore((s) => s.restoreSession);

  const [authView, setAuthView] = useState<'login' | 'register'>('login');
  const [currentView, setCurrentView] = useState<'sessions' | 'session-detail'>('sessions');
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);

  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [healthError, setHealthError] = useState<string | null>(null);
  const [healthLoading, setHealthLoading] = useState(true);

  // Restore session from localStorage on initial app load
  useEffect(() => {
    restoreSession();
  }, [restoreSession]);

  // Fetch health status
  useEffect(() => {
    let cancelled = false;

    fetchHealth()
      .then((data) => {
        if (!cancelled) {
          setHealth(data);
          setHealthError(null);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          const message = err instanceof Error ? err.message : 'Unknown error';
          setHealthError(message);
        }
      })
      .finally(() => {
        if (!cancelled) setHealthLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 flex flex-col items-center justify-center p-4 selection:bg-indigo-500 selection:text-white">
      <div className={`w-full ${authStatus === 'authenticated' ? 'max-w-4xl' : 'max-w-md'} space-y-6 transition-all`}>
        {/* Brand Header */}
        <header className="text-center">
          <h1 className="text-3xl font-bold tracking-tight bg-gradient-to-r from-indigo-400 to-cyan-400 bg-clip-text text-transparent">
            HandOff
          </h1>
          <p className="text-gray-400 mt-1 text-sm">
            AI Agent Supervision Platform
          </p>
        </header>

        {/* Auth Loading State */}
        {authStatus === 'loading' && (
          <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm p-8 text-center">
            <div className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-indigo-400 border-t-transparent" />
            <p className="mt-3 text-sm text-gray-400">Loading session…</p>
          </div>
        )}

        {/* Anonymous: Show Login or Register */}
        {authStatus === 'anonymous' && (
          <>
            {authView === 'login' ? (
              <LoginPage onNavigateToRegister={() => setAuthView('register')} />
            ) : (
              <RegisterPage onNavigateToLogin={() => setAuthView('login')} />
            )}
          </>
        )}

        {/* Authenticated User Dashboard */}
        {authStatus === 'authenticated' && user && (
          <div className="space-y-6">
            <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm shadow-xl p-6">
              <div className="flex items-center justify-between pb-4 border-b border-gray-800">
                <div>
                  <p className="text-xs uppercase tracking-wider font-semibold text-gray-400">
                    Signed In
                  </p>
                  <div className="mt-1 flex items-center gap-2">
                    <span className="text-base font-medium text-white">{user.name}</span>
                    <span className="rounded bg-indigo-950/80 border border-indigo-800 px-2 py-0.5 text-xs font-semibold text-indigo-300">
                      {user.role}
                    </span>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => logout()}
                  className="rounded-lg border border-gray-700 bg-gray-800/80 px-3 py-1.5 text-xs font-medium text-gray-300 hover:bg-gray-700 hover:text-white transition-colors cursor-pointer"
                >
                  Log Out
                </button>
              </div>

              <div className="mt-3 text-xs text-gray-400">
                <p>
                  Logged in as <strong className="text-gray-200">{user.name}</strong> ({user.role})
                </p>
                <p className="mt-0.5 font-mono text-[11px] text-gray-500 truncate">
                  Org ID: {user.organizationId}
                </p>
              </div>
            </div>

            {/* State-based View Switching */}
            {currentView === 'sessions' ? (
              <SessionsPage
                userRole={user.role}
                onSelectSession={(sessionId) => {
                  setSelectedSessionId(sessionId);
                  setCurrentView('session-detail');
                }}
              />
            ) : (
              selectedSessionId && (
                <SessionDetailPage
                  sessionId={selectedSessionId}
                  onBack={() => {
                    setCurrentView('sessions');
                    setSelectedSessionId(null);
                  }}
                />
              )
            )}
          </div>
        )}

        {/* System Health Card (Visible in all states) */}
        <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm shadow-xl p-6">
          <h2 className="text-sm font-semibold uppercase tracking-wider text-gray-400 mb-4">
            System Health
          </h2>

          {healthLoading && (
            <div className="flex items-center gap-3 text-gray-400">
              <div className="h-5 w-5 animate-spin rounded-full border-2 border-gray-600 border-t-indigo-400" />
              <span>Checking health…</span>
            </div>
          )}

          {healthError && (
            <div className="rounded-lg bg-red-950/50 border border-red-800 p-4 text-red-300 text-sm">
              <p className="font-medium">Cannot reach the backend</p>
              <p className="mt-1 text-red-400 text-xs">{healthError}</p>
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
              <StatusRow label="Backend" value={health.status} />
              <StatusRow label="Database" value={health.database} />
              <StatusRow label="Redis" value={health.redis} />
            </div>
          )}
        </div>

        {/* Footer info */}
        <footer className="text-center text-xs text-gray-600">
          Slice 2 · Sessions & Scripted Agent
        </footer>
      </div>
    </div>
  );
}

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
