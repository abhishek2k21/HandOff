import { create } from 'zustand';
import {
  apiLogin,
  apiLogout,
  apiRefresh,
  apiRegister,
  clearStoredRefreshToken,
  getStoredRefreshToken,
} from '../api/auth';
import { configureAuthClient } from '../api/client';
import type { AuthResponse, LoginRequest, RegisterRequest, User } from '../api/types';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface AuthState {
  user: User | null;
  accessToken: string | null;
  status: AuthStatus;

  login: (req: LoginRequest) => Promise<void>;
  register: (req: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  restoreSession: () => Promise<void>;
  setSession: (auth: AuthResponse) => void;
  clearSession: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  accessToken: null,
  status: 'loading',

  setSession: (auth: AuthResponse) => {
    set({
      user: auth.user,
      accessToken: auth.accessToken,
      status: 'authenticated',
    });
  },

  clearSession: () => {
    clearStoredRefreshToken();
    set({
      user: null,
      accessToken: null,
      status: 'anonymous',
    });
  },

  login: async (req: LoginRequest) => {
    const auth = await apiLogin(req);
    get().setSession(auth);
  },

  register: async (req: RegisterRequest) => {
    const auth = await apiRegister(req);
    get().setSession(auth);
  },

  logout: async () => {
    const refreshToken = getStoredRefreshToken() || undefined;
    try {
      await apiLogout(refreshToken);
    } catch {
      // Clear local state even if server logout call fails
    } finally {
      get().clearSession();
    }
  },

  restoreSession: async () => {
    const token = getStoredRefreshToken();
    if (!token) {
      set({ status: 'anonymous', user: null, accessToken: null });
      return;
    }

    try {
      const auth = await apiRefresh(token);
      get().setSession(auth);
    } catch {
      get().clearSession();
    }
  },
}));

// Wire up the API client so protected requests can access tokens and refresh on 401
configureAuthClient({
  getAccessToken: () => useAuthStore.getState().accessToken,
  onAuthFailure: () => useAuthStore.getState().clearSession(),
  refreshHandler: async () => {
    const stored = getStoredRefreshToken();
    if (!stored) return null;
    try {
      const auth = await apiRefresh(stored);
      useAuthStore.getState().setSession(auth);
      return auth.accessToken;
    } catch {
      useAuthStore.getState().clearSession();
      return null;
    }
  },
});
