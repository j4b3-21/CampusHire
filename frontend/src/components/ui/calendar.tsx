"use client";

import * as React from "react";
import { DayPicker, getDefaultClassNames } from "react-day-picker";
import { ChevronDown, ChevronLeft, ChevronRight } from "lucide-react";

import { cn } from "@/lib/utils";
import { buttonVariants } from "@/components/ui/button";

export type CalendarProps = React.ComponentProps<typeof DayPicker>;

export function Calendar({ className, classNames, showOutsideDays = true, captionLayout = "dropdown", ...props }: CalendarProps) {
  const defaults = getDefaultClassNames();
  return (
    <DayPicker
      showOutsideDays={showOutsideDays}
      captionLayout={captionLayout}
      className={cn("p-1 [--cell-size:2rem]", className)}
      classNames={{
        root: cn("w-fit", defaults.root),
        months: cn("relative flex flex-col gap-4 sm:flex-row", defaults.months),
        month: cn("flex w-full flex-col gap-3", defaults.month),
        nav: cn("absolute inset-x-0 top-0 flex items-center justify-between gap-1", defaults.nav),
        button_previous: cn(buttonVariants({ variant: "ghost", size: "icon-sm" }), "size-(--cell-size)", defaults.button_previous),
        button_next: cn(buttonVariants({ variant: "ghost", size: "icon-sm" }), "size-(--cell-size)", defaults.button_next),
        month_caption: cn("flex h-(--cell-size) items-center justify-center px-(--cell-size)", defaults.month_caption),
        dropdowns: cn("flex items-center justify-center gap-1.5 text-[13px] font-medium", defaults.dropdowns),
        dropdown_root: cn(
          "relative rounded-md border border-input px-2 py-1 shadow-xs has-focus:border-ring has-focus:ring-3 has-focus:ring-ring/20",
          defaults.dropdown_root
        ),
        dropdown: cn("absolute inset-0 cursor-pointer bg-popover opacity-0", defaults.dropdown),
        caption_label: cn(
          "flex select-none items-center gap-1 text-[13px] font-medium [&>svg]:size-3.5 [&>svg]:text-muted-foreground",
          defaults.caption_label
        ),
        month_grid: "w-full border-collapse",
        weekdays: cn("flex", defaults.weekdays),
        weekday: cn("flex-1 select-none text-[11px] font-medium text-muted-foreground", defaults.weekday),
        week: cn("mt-1 flex w-full", defaults.week),
        day: cn(
          "group/day relative aspect-square size-(--cell-size) p-0 text-center text-[13px] select-none",
          defaults.day
        ),
        day_button: cn(
          "focus-ring flex size-(--cell-size) items-center justify-center rounded-md tabular transition-colors hover:bg-accent group-data-[selected]/day:bg-primary group-data-[selected]/day:text-primary-foreground group-data-[selected]/day:hover:bg-primary-hover",
          defaults.day_button
        ),
        today: cn("[&>button]:font-semibold [&>button]:text-primary group-data-[selected]/day:[&>button]:text-primary-foreground", defaults.today),
        outside: cn("text-subtle-foreground", defaults.outside),
        disabled: cn("text-muted-foreground opacity-50", defaults.disabled),
        hidden: cn("invisible", defaults.hidden),
        ...classNames,
      }}
      components={{
        Chevron: ({ orientation, className: chevronClassName, ...rest }) => {
          const Icon = orientation === "left" ? ChevronLeft : orientation === "right" ? ChevronRight : ChevronDown;
          return <Icon className={cn("size-4", chevronClassName)} {...rest} />;
        },
      }}
      {...props}
    />
  );
}
