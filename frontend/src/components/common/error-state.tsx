"use client";

import { CloudOff, Lock, RotateCw, SearchX, ServerCrash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { ApiError, errorMessage } from "@/lib/api/errors";

export function ErrorState({
  error,
  onRetry,
  title,
  description,
  alwaysRetry,
  className,
  size,
  action,
}: {
  error: unknown;
  onRetry?: () => void;
  title?: string;
  /** Replaces the message derived from the error. */
  description?: React.ReactNode;
  /** Offer "Try again" even for 403/404 (e.g. when the whole API is unreachable). */
  alwaysRetry?: boolean;
  className?: string;
  size?: "default" | "sm";
  action?: React.ReactNode;
}) {
  const apiError = error instanceof ApiError ? error : null;
  const icon = apiError?.isNetwork
    ? CloudOff
    : apiError?.isNotFound
      ? SearchX
      : apiError?.isForbidden
        ? Lock
        : ServerCrash;
  const heading =
    title ??
    (apiError?.isNetwork
      ? "Can't reach the server"
      : apiError?.isNotFound
        ? "Not found"
        : apiError?.isForbidden
          ? "No access"
          : "Something went wrong");

  return (
    <EmptyState
      icon={icon}
      tone={apiError?.isNotFound || apiError?.isForbidden ? "default" : "destructive"}
      size={size}
      className={className}
      title={heading}
      description={
        <>
          {description ?? errorMessage(error)}
          {apiError?.correlationId ? (
            <span className="mt-1 block font-mono text-[11px] text-subtle-foreground">
              Reference {apiError.correlationId}
            </span>
          ) : null}
        </>
      }
      action={
        <>
          {onRetry && (alwaysRetry || (!apiError?.isNotFound && !apiError?.isForbidden)) ? (
            <Button variant="outline" size="sm" onClick={onRetry}>
              <RotateCw /> Try again
            </Button>
          ) : null}
          {action}
        </>
      }
    />
  );
}
