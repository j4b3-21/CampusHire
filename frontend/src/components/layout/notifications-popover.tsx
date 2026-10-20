"use client";

import { Bell, BellOff } from "lucide-react";

import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

/** Placeholder until `GET /api/v1/notifications` ships. */
export function NotificationsPopover() {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon-sm" aria-label="Notifications">
          <Bell />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between border-b border-border px-4 py-2.5">
          <h2 className="text-[13px] font-semibold">Notifications</h2>
        </div>
        <EmptyState
          size="sm"
          icon={BellOff}
          title="You're all caught up"
          description="Drive announcements, status changes and interview schedules will show up here."
        />
      </PopoverContent>
    </Popover>
  );
}
