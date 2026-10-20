"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";

import { fetchCurrentUser } from "@/lib/api";
import { useAuthStore } from "@/store/auth-store";
import type { ApiResponse, AuthUser } from "@/types/auth";

export function useCurrentUserQuery(enabled: boolean) {
  const setUser = useAuthStore((state) => state.setUser);

  const query = useQuery({
    queryKey: ["auth", "me"],
    enabled,
    queryFn: async () => {
      const response = await fetchCurrentUser<ApiResponse<AuthUser>>();
      return response.data;
    },
  });

  React.useEffect(() => {
    if (query.data) {
      setUser(query.data);
    }
  }, [query.data, setUser]);

  return query;
}
