"use client";

import * as React from "react";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { toast } from "sonner";

import { ThemeProvider } from "@/providers/theme-provider";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Toaster } from "@/components/ui/sonner";
import { ApiError, errorMessage, isUnexpectedError } from "@/lib/api/errors";
import { useAuthStore } from "@/store/auth-store";

function toastError(error: unknown, title?: string) {
  const correlationId = error instanceof ApiError ? error.correlationId : undefined;
  const message = errorMessage(error);
  toast.error(title ?? message, {
    id: correlationId ?? `${title ?? ""}${message}`,
    description:
      [title ? message : null, correlationId ? `Reference ${correlationId}.` : null].filter(Boolean).join(" ") ||
      undefined,
  });
}

function makeQueryClient() {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (query.meta?.silent) return;
        if (isUnexpectedError(error)) toastError(error);
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        const meta = mutation.meta;
        if (meta?.silent) return;
        if (meta?.handlesValidation && error instanceof ApiError && error.isValidation) return;
        toastError(error, meta?.errorTitle);
      },
    }),
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        staleTime: 30_000,
        retry: (failureCount, error) => {
          if (error instanceof ApiError && !error.isNetwork && error.status < 500) return false;
          return failureCount < 2;
        },
      },
      mutations: { retry: false },
    },
  });
}

function AuthBootstrap({ children }: { children: React.ReactNode }) {
  const initialize = useAuthStore((state) => state.initialize);
  React.useEffect(() => {
    void initialize();
  }, [initialize]);
  return <>{children}</>;
}

export function AppProviders({ children }: { children: React.ReactNode }) {
  const [queryClient] = React.useState(makeQueryClient);
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <TooltipProvider delayDuration={300} skipDelayDuration={150}>
          <AuthBootstrap>{children}</AuthBootstrap>
          <Toaster />
        </TooltipProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
