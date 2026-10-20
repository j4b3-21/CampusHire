"use client";

import * as React from "react";
import { useQuery } from "@tanstack/react-query";

import { buildUrl, request } from "@/lib/api/client";
import { saveBlob } from "@/lib/api/download";
import { ApiError } from "@/lib/api/errors";
import { queryKeys } from "@/lib/api/keys";
import { forceRefresh, getValidToken } from "@/lib/auth/keycloak";
import type { ProblemDetails } from "@/types/api";
import type { UploadedFile } from "@/types/records";

function xhrUpload(url: string, file: File, token: string | null, onProgress: (percent: number) => void) {
  return new Promise<{ status: number; body: string; contentType: string }>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", url);
    xhr.setRequestHeader("Accept", "application/json, application/problem+json");
    if (token) xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () =>
      resolve({ status: xhr.status, body: xhr.responseText, contentType: xhr.getResponseHeader("content-type") ?? "" });
    xhr.onerror = () => reject(ApiError.network());
    const form = new FormData();
    form.append("file", file);
    xhr.send(form);
  });
}

/** `POST /orgs/{orgId}/files` (multipart `file`) with upload progress. */
export async function uploadFile(orgId: string, file: File, onProgress: (percent: number) => void = () => {}) {
  const url = buildUrl(`/orgs/${orgId}/files`);
  let response = await xhrUpload(url, file, await getValidToken(), onProgress);
  if (response.status === 401) {
    const token = await forceRefresh();
    if (token) response = await xhrUpload(url, file, token, onProgress);
  }
  const parsed = (() => {
    try {
      return response.body ? (JSON.parse(response.body) as unknown) : undefined;
    } catch {
      return undefined;
    }
  })();
  if (response.status < 200 || response.status >= 300) {
    const problem = (parsed ?? {}) as Partial<ProblemDetails>;
    throw new ApiError({ ...problem, status: response.status });
  }
  return parsed as UploadedFile;
}

export function useUploadFile(orgId: string) {
  const [progress, setProgress] = React.useState(0);
  const [isUploading, setUploading] = React.useState(false);
  const [error, setError] = React.useState<ApiError | null>(null);

  const upload = React.useCallback(
    async (file: File) => {
      setUploading(true);
      setProgress(0);
      setError(null);
      try {
        return await uploadFile(orgId, file, setProgress);
      } catch (cause) {
        const apiError = cause instanceof ApiError ? cause : ApiError.network(cause);
        setError(apiError);
        throw apiError;
      } finally {
        setUploading(false);
      }
    },
    [orgId]
  );

  const reset = React.useCallback(() => {
    setProgress(0);
    setError(null);
  }, []);

  return { upload, progress, isUploading, error, reset };
}

/**
 * Downloads a stored file with the bearer token and exposes it as an object URL. The URL lives as long as the
 * cache entry, so previews survive remounts.
 */
export function useFileObjectUrl(orgId: string | undefined, fileId: string | undefined) {
  const query = useQuery({
    queryKey: queryKeys.file(orgId ?? "", fileId ?? ""),
    queryFn: async ({ signal }) => {
      const result = await request<Blob>(`/orgs/${orgId}/files/${fileId}`, { responseType: "blob", signal });
      return URL.createObjectURL(result.data);
    },
    enabled: Boolean(orgId && fileId),
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: false,
    meta: { silent: true },
  });

  return { url: query.data ?? null, isLoading: query.isLoading, error: query.error };
}

export async function downloadFile(orgId: string, fileId: string, name = "download") {
  const result = await request<Blob>(`/orgs/${orgId}/files/${fileId}`, { responseType: "blob" });
  saveBlob(result.data, name);
}
