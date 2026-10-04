import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from '../App';
import {
  apiLogin,
  apiRefresh,
  getStoredRefreshToken,
  setStoredRefreshToken,
} from '../api/auth';
import { apiRequest, ApiError, configureAuthClient } from '../api/client';
import { LoginPage } from '../pages/LoginPage';
import { RegisterPage } from '../pages/RegisterPage';
import { useAuthStore } from '../store/auth';

describe('Auth Integration and Unit Tests', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    useAuthStore.setState({
      user: null,
      accessToken: null,
      status: 'anonymous',
    });
  });

  // Test 1: Login shows server error message on 401
  it('1. Login shows the server error message on a 401', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          error: {
            code: 'UNAUTHENTICATED',
            message: 'Invalid email or password',
          },
        }),
        {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );

    render(<LoginPage onNavigateToRegister={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/email address/i), {
      target: { value: 'user@example.com' },
    });
    fireEvent.change(screen.getByLabelText(/^password/i), {
      target: { value: 'WrongPassword123' },
    });
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Invalid email or password');
    });
  });

  // Test 2: Successful login shows the user's name and role
  it("2. Successful login shows the user's name and role", async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/api/health')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({ status: 'UP', database: 'UP', redis: 'UP' }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
          )
        );
      }
      if (url.includes('/api/auth/login')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              accessToken: 'access-jwt-token',
              refreshToken: 'refresh-opaque-token',
              expiresInSeconds: 900,
              user: {
                id: 'u-1',
                name: 'Maya Lin',
                role: 'OPERATOR',
                organizationId: 'org-1',
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
          )
        );
      }
      return Promise.reject(new Error(`Unhandled URL: ${url}`));
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getByLabelText(/email address/i)).toBeInTheDocument();
    });

    fireEvent.change(screen.getByLabelText(/email address/i), {
      target: { value: 'operator@example.com' },
    });
    fireEvent.change(screen.getByLabelText(/^password/i), {
      target: { value: 'Password123!' },
    });
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getAllByText('Maya Lin').length).toBeGreaterThan(0);
      expect(screen.getAllByText('OPERATOR').length).toBeGreaterThan(0);
      expect(screen.getByText(/logged in as/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /log out/i })).toBeInTheDocument();
    });
  });

  // Test 3: Register form blocks submit and shows field errors for invalid input
  it('3. Register form blocks submit and shows field errors for invalid input', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    render(<RegisterPage onNavigateToLogin={vi.fn()} />);

    // Provide invalid values
    fireEvent.change(screen.getByLabelText(/organization name/i), {
      target: { value: '' },
    });
    fireEvent.change(screen.getByLabelText(/your name/i), {
      target: { value: '' },
    });
    fireEvent.change(screen.getByLabelText(/work email/i), {
      target: { value: 'invalid-email' },
    });
    fireEvent.change(screen.getByLabelText(/password/i), {
      target: { value: 'short' },
    });

    fireEvent.click(screen.getByRole('button', { name: /create workspace/i }));

    await waitFor(() => {
      expect(screen.getByText('Organization name is required')).toBeInTheDocument();
      expect(screen.getByText('Your display name is required')).toBeInTheDocument();
      expect(screen.getByText('Please enter a valid email address')).toBeInTheDocument();
      expect(screen.getByText('Password must be at least 8 characters')).toBeInTheDocument();
    });

    // Verify submit was blocked without calling backend
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  // Test 4: Logout clears store and localStorage even if API call fails
  it('4. Logout clears the store and localStorage, even if the API call fails', async () => {
    setStoredRefreshToken('persisted-refresh-token');
    useAuthStore.setState({
      user: {
        id: 'u-1',
        name: 'Maya',
        role: 'ADMIN',
        organizationId: 'org-1',
      },
      accessToken: 'sample-access-token',
      status: 'authenticated',
    });

    // Make the logout endpoint fail with a 500 error
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ error: { code: 'SERVER_ERROR', message: 'Internal error' } }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await useAuthStore.getState().logout();

    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(getStoredRefreshToken()).toBeNull();
  });

  // Test 5: Session restore: with a refresh token in localStorage, app calls refresh once and restores user
  it('5. Session restore: with a refresh token in localStorage, the app calls refresh once and shows the user', async () => {
    setStoredRefreshToken('existing-refresh-token');

    vi.spyOn(globalThis, 'fetch').mockImplementation((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/api/auth/refresh')) {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              accessToken: 'new-access-token',
              refreshToken: 'rotated-refresh-token',
              expiresInSeconds: 900,
              user: {
                id: 'u-2',
                name: 'Restored User',
                role: 'ADMIN',
                organizationId: 'org-2',
              },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
          )
        );
      }
      return Promise.resolve(
        new Response(JSON.stringify({ status: 'UP', database: 'UP', redis: 'UP' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      );
    });

    render(<App />);

    await waitFor(() => {
      expect(screen.getAllByText('Restored User').length).toBeGreaterThan(0);
      expect(screen.getAllByText('ADMIN').length).toBeGreaterThan(0);
    });

    expect(getStoredRefreshToken()).toBe('rotated-refresh-token');
  });

  // Test 6: Single-flight: two simultaneous refresh requests produce exactly one network call
  it('6. Single-flight: two simultaneous refresh requests produce exactly one network call', async () => {
    setStoredRefreshToken('initial-token');

    let fetchCount = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation((input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/api/auth/refresh')) {
        fetchCount++;
        return new Promise((resolve) => {
          setTimeout(() => {
            resolve(
              new Response(
                JSON.stringify({
                  accessToken: 'shared-access-token',
                  refreshToken: 'shared-rotated-token',
                  expiresInSeconds: 900,
                  user: {
                    id: 'u-1',
                    name: 'Single Flight User',
                    role: 'OPERATOR',
                    organizationId: 'org-1',
                  },
                }),
                { status: 200, headers: { 'Content-Type': 'application/json' } }
              )
            );
          }, 30);
        });
      }
      return Promise.reject(new Error('Unknown url'));
    });

    const [res1, res2] = await Promise.all([apiRefresh(), apiRefresh()]);

    expect(fetchCount).toBe(1);
    expect(res1.accessToken).toBe('shared-access-token');
    expect(res2.accessToken).toBe('shared-access-token');
    expect(getStoredRefreshToken()).toBe('shared-rotated-token');
  });

  // Test 7: Rate-limit error displays the retry time
  it('7. Rate-limit error displays the retry time', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          error: {
            code: 'RATE_LIMITED',
            message: 'Too many login attempts',
            details: {
              retryAfterSeconds: 42,
            },
          },
        }),
        {
          status: 429,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );

    render(<LoginPage onNavigateToRegister={vi.fn()} />);

    fireEvent.change(screen.getByLabelText(/email address/i), {
      target: { value: 'rate@example.com' },
    });
    fireEvent.change(screen.getByLabelText(/^password/i), {
      target: { value: 'Password123!' },
    });
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(
        screen.getByText('Too many attempts. Please try again in 42 seconds.')
      ).toBeInTheDocument();
    });
  });

  // Test (a): A 401 on login does not trigger a refresh attempt
  it('(a) A 401 on login does not trigger a refresh attempt', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          error: {
            code: 'UNAUTHENTICATED',
            message: 'Invalid credentials',
          },
        }),
        {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }
      )
    );

    await expect(
      apiLogin({ email: 'test@example.com', password: 'Password123!' })
    ).rejects.toBeInstanceOf(ApiError);

    const refreshCalls = fetchSpy.mock.calls.filter((call) =>
      call[0].toString().includes('/api/auth/refresh')
    );
    expect(refreshCalls.length).toBe(0);
  });

  // Test (b): When stored token changed by the time lock is acquired, use new stored token
  it('(b) When stored token changed by the time the lock is acquired, code uses new stored token and does not send old one', async () => {
    setStoredRefreshToken('token_old');

    let bodySent: string | null = null;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      bodySent = init?.body as string;
      return new Response(
        JSON.stringify({
          accessToken: 'fresh-token',
          refreshToken: 'token_new_rotated',
          expiresInSeconds: 900,
          user: { id: 'u1', name: 'User', role: 'VIEWER', organizationId: 'o1' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      );
    });

    // Mock navigator.locks: before running callback, simulate another tab updating localStorage
    const originalLocks = navigator.locks;
    (navigator as any).locks = {
      request: vi.fn(async (_name: string, callback: () => Promise<any>) => {
        // Another tab changed stored token while waiting for lock!
        setStoredRefreshToken('token_new_from_other_tab');
        return await callback();
      }),
    };

    try {
      await apiRefresh();
      expect(bodySent).not.toBeNull();
      const parsed = JSON.parse(bodySent!);
      expect(parsed.refreshToken).toBe('token_new_from_other_tab');
      expect(parsed.refreshToken).not.toBe('token_old');
    } finally {
      (navigator as any).locks = originalLocks;
    }
  });

  // Test (c): A 401 on POST /api/ws-ticket triggers one refresh, one retry, and logout if retry fails
  it('(c) A 401 on POST /api/ws-ticket triggers one refresh, one retry, and logout if retry fails', async () => {
    let wsTicketCalls = 0;
    let refreshCalls = 0;
    let authFailureCalled = false;

    configureAuthClient({
      getAccessToken: () => 'initial-access-token',
      onAuthFailure: () => {
        authFailureCalled = true;
      },
      refreshHandler: async () => {
        refreshCalls++;
        return 'rotated-access-token';
      },
    });

    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.includes('/api/ws-ticket')) {
        wsTicketCalls++;
        // Both initial call and retry fail with 401
        return new Response(
          JSON.stringify({
            error: {
              code: 'UNAUTHENTICATED',
              message: 'Invalid access token',
            },
          }),
          { status: 401, headers: { 'Content-Type': 'application/json' } }
        );
      }
      return Promise.reject(new Error(`Unexpected url: ${url}`));
    });

    await expect(
      apiRequest<{ ticket: string }>('/api/ws-ticket', {
        method: 'POST',
        isProtected: true,
      })
    ).rejects.toBeInstanceOf(ApiError);

    // Initial call (1) + retry after refresh (1) = 2 calls
    expect(wsTicketCalls).toBe(2);
    expect(refreshCalls).toBe(1);
    expect(authFailureCalled).toBe(true);
  });
});
