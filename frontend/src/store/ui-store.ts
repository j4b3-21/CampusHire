import { create } from "zustand";
import { persist } from "zustand/middleware";

import type { StreamStatus } from "@/types/notifications";

export type Density = "comfortable" | "compact";

interface UiState {
  sidebarCollapsed: boolean;
  mobileNavOpen: boolean;
  commandOpen: boolean;
  density: Density;
  lastOrgId: string | null;
  /** Hidden table columns per module key. */
  hiddenColumns: Record<string, string[]>;
  setSidebarCollapsed: (collapsed: boolean) => void;
  toggleSidebar: () => void;
  setMobileNavOpen: (open: boolean) => void;
  setCommandOpen: (open: boolean) => void;
  setDensity: (density: Density) => void;
  setLastOrgId: (orgId: string | null) => void;
  setHiddenColumns: (moduleKey: string, columns: string[]) => void;
  /** Live notification stream state (not persisted). */
  notificationStream: StreamStatus;
  setNotificationStream: (status: StreamStatus) => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      mobileNavOpen: false,
      commandOpen: false,
      density: "comfortable",
      lastOrgId: null,
      hiddenColumns: {},
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
      setMobileNavOpen: (mobileNavOpen) => set({ mobileNavOpen }),
      setCommandOpen: (commandOpen) => set({ commandOpen }),
      setDensity: (density) => set({ density }),
      setLastOrgId: (lastOrgId) => set({ lastOrgId }),
      setHiddenColumns: (moduleKey, columns) =>
        set((state) => ({ hiddenColumns: { ...state.hiddenColumns, [moduleKey]: columns } })),
      notificationStream: "idle",
      setNotificationStream: (notificationStream) => set({ notificationStream }),
    }),
    {
      name: "campushire.ui",
      version: 1,
      partialize: (state) => ({
        sidebarCollapsed: state.sidebarCollapsed,
        density: state.density,
        lastOrgId: state.lastOrgId,
        hiddenColumns: state.hiddenColumns,
      }),
    }
  )
);
