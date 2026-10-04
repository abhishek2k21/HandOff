import { useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { useAuthStore } from '../store/auth';

interface RegisterPageProps {
  onNavigateToLogin: () => void;
}

export function RegisterPage({ onNavigateToLogin }: RegisterPageProps) {
  const register = useAuthStore((s) => s.register);

  const [organizationName, setOrganizationName] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const [fieldErrors, setFieldErrors] = useState<{
    organizationName?: string;
    displayName?: string;
    email?: string;
    password?: string;
  }>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const validate = (): boolean => {
    const errors: {
      organizationName?: string;
      displayName?: string;
      email?: string;
      password?: string;
    } = {};

    if (!organizationName.trim()) {
      errors.organizationName = 'Organization name is required';
    } else if (organizationName.trim().length > 100) {
      errors.organizationName = 'Organization name cannot exceed 100 characters';
    }

    if (!displayName.trim()) {
      errors.displayName = 'Your display name is required';
    } else if (displayName.trim().length > 100) {
      errors.displayName = 'Display name cannot exceed 100 characters';
    }

    if (!email.trim()) {
      errors.email = 'Email address is required';
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
      await register({
        organizationName: organizationName.trim(),
        displayName: displayName.trim(),
        email: email.trim(),
        password,
      });
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        if (err.details?.fields) {
          const serverFields: Record<string, string> = {};
          for (const [k, v] of Object.entries(err.details.fields)) {
            if (typeof v === 'string') {
              serverFields[k] = v;
            }
          }
          setFieldErrors((prev) => ({ ...prev, ...serverFields }));
        }
        setServerError(err.message || 'Registration failed');
      } else {
        setServerError('An unexpected error occurred. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="rounded-2xl border border-gray-800 bg-gray-900/60 backdrop-blur-sm shadow-xl p-6 sm:p-8">
      <div className="mb-6">
        <h2 className="text-xl font-semibold text-white">Register Organization</h2>
        <p className="text-sm text-gray-400 mt-1">
          Create a workspace to monitor and guide AI agents
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
            htmlFor="reg-org"
            className="block text-xs font-medium uppercase tracking-wider text-gray-400 mb-1.5"
          >
            Organization Name
          </label>
          <input
            id="reg-org"
            type="text"
            value={organizationName}
            onChange={(e) => setOrganizationName(e.target.value)}
            disabled={loading}
            aria-describedby={fieldErrors.organizationName ? 'reg-org-error' : undefined}
            className={`w-full rounded-lg border px-3.5 py-2.5 text-sm bg-gray-950 text-white placeholder-gray-500 focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.organizationName
                ? 'border-red-600 focus:ring-red-500/40'
                : 'border-gray-800 focus:border-indigo-500 focus:ring-indigo-500/30'
            }`}
            placeholder="Acme Support Inc"
          />
          {fieldErrors.organizationName && (
            <p id="reg-org-error" role="alert" className="mt-1 text-xs text-red-400">
              {fieldErrors.organizationName}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor="reg-name"
            className="block text-xs font-medium uppercase tracking-wider text-gray-400 mb-1.5"
          >
            Your Name
          </label>
          <input
            id="reg-name"
            type="text"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            disabled={loading}
            aria-describedby={fieldErrors.displayName ? 'reg-name-error' : undefined}
            className={`w-full rounded-lg border px-3.5 py-2.5 text-sm bg-gray-950 text-white placeholder-gray-500 focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.displayName
                ? 'border-red-600 focus:ring-red-500/40'
                : 'border-gray-800 focus:border-indigo-500 focus:ring-indigo-500/30'
            }`}
            placeholder="Maya"
          />
          {fieldErrors.displayName && (
            <p id="reg-name-error" role="alert" className="mt-1 text-xs text-red-400">
              {fieldErrors.displayName}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor="reg-email"
            className="block text-xs font-medium uppercase tracking-wider text-gray-400 mb-1.5"
          >
            Work Email
          </label>
          <input
            id="reg-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={loading}
            aria-describedby={fieldErrors.email ? 'reg-email-error' : undefined}
            className={`w-full rounded-lg border px-3.5 py-2.5 text-sm bg-gray-950 text-white placeholder-gray-500 focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.email
                ? 'border-red-600 focus:ring-red-500/40'
                : 'border-gray-800 focus:border-indigo-500 focus:ring-indigo-500/30'
            }`}
            placeholder="maya@example.com"
          />
          {fieldErrors.email && (
            <p id="reg-email-error" role="alert" className="mt-1 text-xs text-red-400">
              {fieldErrors.email}
            </p>
          )}
        </div>

        <div>
          <label
            htmlFor="reg-password"
            className="block text-xs font-medium uppercase tracking-wider text-gray-400 mb-1.5"
          >
            Password (min. 8 characters)
          </label>
          <input
            id="reg-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={loading}
            aria-describedby={fieldErrors.password ? 'reg-password-error' : undefined}
            className={`w-full rounded-lg border px-3.5 py-2.5 text-sm bg-gray-950 text-white placeholder-gray-500 focus:outline-none focus:ring-2 transition-colors ${
              fieldErrors.password
                ? 'border-red-600 focus:ring-red-500/40'
                : 'border-gray-800 focus:border-indigo-500 focus:ring-indigo-500/30'
            }`}
            placeholder="••••••••"
          />
          {fieldErrors.password && (
            <p id="reg-password-error" role="alert" className="mt-1 text-xs text-red-400">
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
              Creating account…
            </span>
          ) : (
            'Create Workspace'
          )}
        </button>
      </form>

      <div className="mt-6 text-center text-xs text-gray-400">
        Already have an account?{' '}
        <button
          type="button"
          onClick={onNavigateToLogin}
          className="text-indigo-400 hover:text-indigo-300 font-medium underline underline-offset-2 cursor-pointer"
        >
          Sign in
        </button>
      </div>
    </div>
  );
}
