"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { useAuthStore } from "@/store/auth-store";
import type { Page } from "@/types/api";
import type {
  CreateModuleInput,
  FieldDefinition,
  FieldInput,
  ModuleDefinition,
  ModuleLayout,
  ModuleVersion,
  UpdateModuleInput,
} from "@/types/metadata";

function useAuthed() {
  return useAuthStore((state) => state.status === "authenticated");
}

const modulesPath = (orgId: string) => `/orgs/${orgId}/modules`;

/** Every module including archived ones (admin list). */
export function useAllModules(orgId: string | undefined) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.allModules(orgId ?? ""),
    queryFn: ({ signal }) =>
      api.get<ModuleDefinition[]>(modulesPath(orgId!), { query: { includeArchived: true }, signal }),
    enabled: authed && Boolean(orgId),
    staleTime: 30_000,
  });
}

export function useModuleVersions(orgId: string | undefined, moduleKey: string | undefined, page = 0) {
  const authed = useAuthed();
  return useQuery({
    queryKey: queryKeys.moduleVersions(orgId ?? "", moduleKey ?? "", page),
    queryFn: ({ signal }) =>
      api.get<Page<ModuleVersion>>(`${modulesPath(orgId!)}/${moduleKey}/versions`, { query: { page, size: 20 }, signal }),
    enabled: authed && Boolean(orgId && moduleKey),
    placeholderData: keepPreviousData,
  });
}

/** Keeps every module cache in sync after a definition change. */
function useModuleCache(orgId: string) {
  const queryClient = useQueryClient();
  return (module?: ModuleDefinition) => {
    if (module) queryClient.setQueryData(queryKeys.module(orgId, module.apiKey), module);
    return Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.modules(orgId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.allModules(orgId) }),
      queryClient.invalidateQueries({ queryKey: ["orgs", orgId, "modules"] }),
      queryClient.invalidateQueries({ queryKey: ["orgs", orgId, "module-versions"] }),
    ]);
  };
}

export function useCreateModule(orgId: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: (input: CreateModuleInput) => api.post<ModuleDefinition>(modulesPath(orgId), input),
    meta: { handlesValidation: true, errorTitle: "Couldn't create the module" },
    onSuccess: (module) => sync(module),
  });
}

export function useUpdateModule(orgId: string, moduleKey: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: ({ version, input }: { version: number; input: UpdateModuleInput }) =>
      api.patch<ModuleDefinition>(`${modulesPath(orgId)}/${moduleKey}`, input, { ifMatch: version }),
    meta: { handlesValidation: true, errorTitle: "Couldn't save the module" },
    onSuccess: (module) => sync(module),
  });
}

export function useSetModuleStatus(orgId: string, moduleKey: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: ({ version, archive }: { version: number; archive: boolean }) =>
      archive
        ? api.delete<ModuleDefinition>(`${modulesPath(orgId)}/${moduleKey}`, { ifMatch: version })
        : api.post<ModuleDefinition>(`${modulesPath(orgId)}/${moduleKey}/restore`, undefined, { ifMatch: version }),
    meta: { errorTitle: "Couldn't change the module status" },
    onSuccess: (module) => sync(module),
  });
}

export function useSaveLayout(orgId: string, moduleKey: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: ({ version, layout }: { version: number; layout: ModuleLayout }) =>
      api.put<ModuleDefinition>(`${modulesPath(orgId)}/${moduleKey}/layout`, layout, { ifMatch: version }),
    meta: { handlesValidation: true, errorTitle: "Couldn't save the layout" },
    onSuccess: (module) => sync(module),
  });
}

export function useAddField(orgId: string, moduleKey: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: (input: FieldInput) => api.post<FieldDefinition>(`${modulesPath(orgId)}/${moduleKey}/fields`, input),
    meta: { handlesValidation: true, errorTitle: "Couldn't add the field" },
    onSuccess: () => sync(),
  });
}

export function useUpdateField(orgId: string, moduleKey: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: ({ fieldKey, version, input }: { fieldKey: string; version: number; input: FieldInput }) =>
      api.patch<FieldDefinition>(`${modulesPath(orgId)}/${moduleKey}/fields/${fieldKey}`, input, { ifMatch: version }),
    meta: { handlesValidation: true, errorTitle: "Couldn't save the field" },
    onSuccess: () => sync(),
  });
}

export type FieldStatusAction = "deprecate" | "archive" | "activate";

export function useSetFieldStatus(orgId: string, moduleKey: string) {
  const sync = useModuleCache(orgId);
  return useMutation({
    mutationFn: ({ fieldKey, version, action }: { fieldKey: string; version: number; action: FieldStatusAction }) =>
      api.post<FieldDefinition>(`${modulesPath(orgId)}/${moduleKey}/fields/${fieldKey}/${action}`, undefined, {
        ifMatch: version,
      }),
    meta: { errorTitle: "Couldn't change the field status" },
    onSuccess: () => sync(),
  });
}
