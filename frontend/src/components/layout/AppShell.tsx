"use client";

import * as React from "react";

import { AppHeader } from "@/components/layout/AppHeader";
import { AppSidebar } from "@/components/layout/AppSidebar";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-[radial-gradient(circle_at_top_left,_rgba(99,102,241,0.18),_transparent_28%),linear-gradient(180deg,_rgba(2,6,23,0.98),_rgba(15,23,42,1))] text-foreground">
      <div className="mx-auto flex min-h-screen max-w-[1800px]">
        <AppSidebar />
        <div className="flex min-h-screen flex-1 flex-col">
          <AppHeader />
          <main className="flex-1 px-4 py-6 md:px-6">{children}</main>
        </div>
      </div>
    </div>
  );
}
