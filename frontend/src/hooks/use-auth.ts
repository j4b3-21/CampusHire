import { useShallow } from "zustand/react/shallow";

import { useAuthStore } from "@/store/auth-store";

export function useAuth() {
  return useAuthStore(
    useShallow((state) => ({
      status: state.status,
      profile: state.profile,
      error: state.error,
      authenticated: state.status === "authenticated",
      loading: state.status === "loading",
      login: state.login,
      register: state.register,
      logout: state.logout,
    }))
  );
}
