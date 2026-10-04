import { useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { useAuthStore } from '../store/auth';

interface LoginPageProps {
  onNavigateToRegister: () => void;
}

export function LoginPage({ onNavigateToRegister }: LoginPageProps) {
  const login = useAuthStore((s) => s.login);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const validate = (): boolean => {
    const errors: { email?: string; password?: string } = {};

    if (!email.trim()) {
      errors.email = 'Email is required';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      errors.email = 'Please enter a valid email address';
    }

    if (!password) {
      errors.password = 'Password is required';
    } else if (password.length < 8) {
      errors.password = 'Password must be at least 8 characters';
    } else if (password.length > 128) {
      errors.password = 'Password cannot exceed 128 characters';
    }

    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setServerError(null);

    if (!validate()) {
      return;
    }

    setLoading(true);
    try {
      await login({ email: email.trim(), password });
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        if (err.code === 'RATE_LIMITED') {
          const retrySeconds = err.details?.retryAfterSeconds ?? 60;
          setServerError(`Too many attempts. Please try again in ${retrySeconds} seconds.`);
        } else {
          setServerError(err.message || 'Invalid email or password');
        }
      } else {
        setServerError('An unexpected error occurred. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  const handleDevChipClick = (devEmail: string) => {
    if (import.meta.env.DEV) {
      setEmail(devEmail);
      setPassword('Password123!');
      setFieldErrors({});
      setServerError(null);
    }
  };

  return (
    <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm shadow-xl p-6 sm:p-8">
      <div className="mb-6">
        <h2 className="text-xl font-semibold text-white">Sign In</h2>
        <p className="text-sm text-gray-400 mt-1">
          Access the real-time AI supervision console
        </p>
      </div>

      {serverError && (
        <div
          role="alert"
          className="mb-5 rounded-lg border border-red-800/80 bg-red-950/40 p-3.5 text-sm text-red-300"
        >
          {serverError}
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <div>
          <label
            htmlFor="login-email"
            className="block text-xs font-medium uppercase tracking-wider text-gray-400 mb-1.5"
          >
            Email Address
          </label>
          <input
            id="login-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={loading}
            aria-describedby={fieldErrors.email ? 'login-email-error' : undefined}
            className={`w-full rounded-lg border px-3.5 py-2.5 text-sm bg-gray-950 text-white placeholder-gray-500 focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.email
                ? 'border-red-600 focus:ring-red-500/40'
                : 'border-gray-800 focus:border-indigo-500 focus:ring-indigo-500/30'
            }`}
            placeholder="operator@example.com"
          />
          {fieldErrors.email && (
            <p id="login-email-error" role="alert" className="mt-1 text-xs text-red-400">
              {fieldErrors.email}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor="login-password"
            className="block text-xs font-medium uppercase tracking-wider text-gray-400 mb-1.5"
          >
            Password
          </label>
          <input
            id="login-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={loading}
            aria-describedby={fieldErrors.password ? 'login-password-error' : undefined}
            className={`w-full rounded-lg border px-3.5 py-2.5 text-sm bg-gray-950 text-white placeholder-gray-500 focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.password
                ? 'border-red-600 focus:ring-red-500/40'
                : 'border-gray-800 focus:border-indigo-500 focus:ring-indigo-500/30'
            }`}
            placeholder="••••••••"
          />
          {fieldErrors.password && (
            <p id="login-password-error" role="alert" className="mt-1 text-xs text-red-400">
              {fieldErrors.password}
            </p>
          )}
        </div>

        <button
          type="submit"
          disabled={loading}
          className="w-full mt-2 rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white shadow-md hover:bg-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-500/50 disabled:opacity-50 disabled:cursor-not-allowed transition-all cursor-pointer"
        >
          {loading ? (
            <span className="inline-flex items-center gap-2">
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
              Signing in…
            </span>
          ) : (
            'Sign In'
          )}
        </button>
      </form>

      {/* DEV Demo Accounts Chip Selector */}
      {import.meta.env.DEV && (
        <div className="mt-6 pt-5 border-t border-gray-800">
          <p className="text-xs text-gray-400 mb-2 font-medium">Dev Demo Accounts:</p>
          <div className="flex flex-wrap gap-1.5">
            {(['admin', 'approver', 'operator', 'viewer'] as const).map((role) => (
              <button
                key={role}
                type="button"
                onClick={() => handleDevChipClick(`${role}@example.com`)}
                className="px-2 py-1 rounded bg-gray-800 hover:bg-gray-700 text-xs font-mono text-gray-300 transition-colors cursor-pointer"
              >
                {role}@example.com
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="mt-6 text-center text-xs text-gray-400">
        Don&apos;t have an account?{' '}
        <button
          type="button"
          onClick={onNavigateToRegister}
          className="text-indigo-400 hover:text-indigo-300 font-medium underline underline-offset-2 cursor-pointer"
        >
          Register an organization
        </button>
      </div>
    </div>
  );
}
