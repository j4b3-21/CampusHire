"use client";

import * as React from "react";
import { toast } from "sonner";
import { ChevronDown, Download, FileSpreadsheet, FileText } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Spinner } from "@/components/ui/spinner";
import { errorMessage, exportRecords } from "@/lib/api";
import type { ExchangeFormat } from "@/types/data-exchange";
import type { RecordQueryParams } from "@/types/records";

/** Excel/CSV export of a module with the current filters, sort and search (authenticated blob download). */
export function ExportMenu({
  orgId,
  moduleKey,
  params,
  hint = "With the current filters and sort",
}: {
  orgId: string;
  moduleKey: string;
  params: Pick<RecordQueryParams, "sort" | "q" | "filters">;
  hint?: string;
}) {
  const [pending, setPending] = React.useState<ExchangeFormat | null>(null);

  const run = async (format: ExchangeFormat) => {
    setPending(format);
    try {
      const name = await exportRecords(orgId, moduleKey, format, params);
      toast.success("Export ready", { description: name });
    } catch (error) {
      toast.error("Couldn't export", { description: errorMessage(error) });
    } finally {
      setPending(null);
    }
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" disabled={pending !== null} aria-label="Export">
          {pending ? <Spinner /> : <Download />}
          <span className="hidden sm:inline">Export</span>
          <ChevronDown className="hidden opacity-60 sm:block" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        <DropdownMenuLabel>{hint}</DropdownMenuLabel>
        <DropdownMenuItem onSelect={() => void run("xlsx")}>
          <FileSpreadsheet /> Excel (.xlsx)
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => void run("csv")}>
          <FileText /> CSV (.csv)
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
