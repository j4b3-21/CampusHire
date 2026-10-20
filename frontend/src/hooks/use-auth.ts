import { useShallow } from "zustand/react/shallow";

import { useAuthStore } from "@/store/auth-store";
import type { AuthStore } from "@/store/auth-store";

type AuthSlice = Pick<
  AuthStore,
  | "user"
  | "roles"
  | "authenticated"
  | "loading"
  | "initializeAuth"
  | "fetchCurrentUser"
  | "login"
  | "logout"
  | "register"
  | "forgotPassword"
>;

export function useAuth(): AuthSlice {
  const selectAuth = useShallow((state: AuthStore): AuthSlice => ({
    user: state.user,
    roles: state.roles,
    authenticated: state.authenticated,
    loading: state.loading,
    initializeAuth: state.initializeAuth,
    fetchCurrentUser: state.fetchCurrentUser,
    login: state.login,
    logout: state.logout,
    register: state.register,
    forgotPassword: state.forgotPassword,
  }));

  return useAuthStore(selectAuth);
}
