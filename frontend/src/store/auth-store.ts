import { create } from "zustand";

import {
  getProfile,
  initAuth,
  isAuthenticated,
  login as keycloakLogin,
  logout as keycloakLogout,
  onSessionChange,
  register as keycloakRegister,
  type AuthProfile,
  type LoginOptions,
} from "@/lib/auth/keycloak";
import { clearAuthHintCookie, setAuthHintCookie } from "@/lib/auth/session-cookie";

export type AuthStatus = "loading" | "authenticated" | "unauthenticated";

export interface AuthState {
  status: AuthStatus;
  profile: AuthProfile | null;
  /** Set when Keycloak could not be reached during initialization. */
  error: string | null;
  initialize: () => Promise<void>;
  login: (options?: LoginOptions) => Promise<void>;
  register: (options?: { next?: string | null }) => Promise<void>;
  logout: (options?: { next?: string | null }) => Promise<void>;
}

let unsubscribe: (() => void) | null = null;

export const useAuthStore = create<AuthState>((set, get) => ({
  status: "loading",
  profile: null,
  error: null,

  initialize: async () => {
    if (!unsubscribe) {
      unsubscribe = onSessionChange((authenticated) => {
        if (authenticated) {
          setAuthHintCookie();
          set({ status: "authenticated", profile: getProfile() });
        } else {
          clearAuthHintCookie();
          set({ status: "unauthenticated", profile: null });
        }
      });
    }
    if (get().status !== "loading") return;
    try {
      const authenticated = await initAuth();
      if (authenticated && isAuthenticated()) {
        setAuthHintCookie();
        set({ status: "authenticated", profile: getProfile(), error: null });
      } else {
        clearAuthHintCookie();
        set({ status: "unauthenticated", profile: null, error: null });
      }
    } catch {
      clearAuthHintCookie();
      set({
        status: "unauthenticated",
        profile: null,
        error: "The sign-in service is not reachable right now.",
      });
    }
  },

  login: async (options) => {
    try {
      await keycloakLogin(options);
    } catch {
      set({ error: "The sign-in service is not reachable right now." });
      throw new Error("login_unavailable");
    }
  },

  register: async (options) => {
    try {
      await keycloakRegister(options);
    } catch {
      set({ error: "The sign-in service is not reachable right now." });
      throw new Error("register_unavailable");
    }
  },

  logout: async (options) => {
    clearAuthHintCookie();
    await keycloakLogout(options);
  },
}));
