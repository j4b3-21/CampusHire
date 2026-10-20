import { create } from "zustand";

import { api } from "@/lib/api";
import { clearAuthHintCookie, setAuthHintCookie } from "@/lib/session-cookie";
import {
  forgotPassword as keycloakForgotPassword,
  initKeycloak,
  isAuthenticated,
  login as keycloakLogin,
  loginWithGoogle as keycloakLoginWithGoogle,
  logout as keycloakLogout,
  register as keycloakRegister,
  onSessionExpired,
} from "@/lib/keycloak";
import type {
  ApiResponse,
  AuthProvider,
  AuthUser,
  ForgotPasswordFormValues,
  LoginFormValues,
  RegisterFormValues,
} from "@/types/auth";

export interface AuthStore {
  user: AuthUser | null;
  roles: string[];
  authenticated: boolean;
  loading: boolean;
  initializeAuth: () => Promise<void>;
  fetchCurrentUser: () => Promise<AuthUser | null>;
  login: (values: LoginFormValues, provider?: AuthProvider) => Promise<void>;
  register: (values: RegisterFormValues) => Promise<void>;
  forgotPassword: (values: ForgotPasswordFormValues) => Promise<void>;
  logout: () => Promise<void>;
  setUser: (user: AuthUser | null) => void;
}

async function loadCurrentUser() {
  const response = await api.get<ApiResponse<AuthUser>>("/auth/me");
  return response.data.data;
}

export const useAuthStore = create<AuthStore>((set) => ({
  user: null,
  roles: [],
  authenticated: false,
  loading: true,

  initializeAuth: async () => {
    set({ loading: true });
    try {
      try {
        await initKeycloak();
      } catch {
        set({ authenticated: false });
      }
      const authed = isAuthenticated();
      set({ authenticated: authed });
      if (authed) {
        setAuthHintCookie();
        onSessionExpired(() => {
          clearAuthHintCookie();
          set({ user: null, roles: [], authenticated: false });
        });
      } else {
        clearAuthHintCookie();
        set({ user: null, roles: [], authenticated: false });
      }
    } finally {
      set({ loading: false });
    }
  },

  fetchCurrentUser: async () => {
    try {
      const user = await loadCurrentUser();
      set({
        user,
        roles: user.roles ?? [],
        authenticated: true,
      });
      setAuthHintCookie();
      return user;
    } catch {
      clearAuthHintCookie();
      set({ user: null, roles: [], authenticated: false });
      return null;
    }
  },

  login: async (values, provider = "password") => {
    if (provider === "google") {
      await keycloakLoginWithGoogle({ email: values.email });
      return;
    }

    await keycloakLogin({ email: values.email });
  },

  register: async (values) => {
    sessionStorage.setItem("campushire.register", JSON.stringify(values));
    await keycloakRegister({ redirectUri: `${window.location.origin}/dashboard` });
  },

  forgotPassword: async (values) => {
    await keycloakForgotPassword({ email: values.email });
  },

  logout: async () => {
    clearAuthHintCookie();
    set({ user: null, roles: [], authenticated: false });
    await keycloakLogout({ redirectUri: `${window.location.origin}/login` });
  },

  setUser: (user) => {
    set({
      user,
      roles: user?.roles ?? [],
      authenticated: Boolean(user),
    });
  },
}));
