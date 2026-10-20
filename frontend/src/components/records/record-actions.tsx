"use client";

import * as React from "react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Spinner } from "@/components/ui/spinner";
import { useDeleteRecord, useRestoreRecord } from "@/lib/api";
import { recordTitle } from "@/lib/metadata/fields";
import type { ModuleDefinition } from "@/types/metadata";
import type { RecordItem } from "@/types/records";

const ifMatchOf = (record: RecordItem & { etag?: string }) => record.etag ?? record.version;

/** Soft delete / restore with an "Undo" toast that restores using the version returned by the delete. */
export function useRecordActions(orgId: string, module: ModuleDefinition) {
  const del = useDeleteRecord(orgId, module.apiKey);
  const restore = useRestoreRecord(orgId, module.apiKey);

  const restoreRecord = React.useCallback(
    async (record: RecordItem & { etag?: string }) => {
      const restored = await restore.mutateAsync({ id: record.id, ifMatch: ifMatchOf(record) });
      toast.success(`${recordTitle(module, restored)} restored`);
      return restored;
    },
    [restore, module]
  );

  const deleteRecords = React.useCallback(
    async (records: (RecordItem & { etag?: string })[]) => {
      const results = await Promise.allSettled(
        records.map((record) => del.mutateAsync({ id: record.id, ifMatch: ifMatchOf(record) }))
      );
      const deleted = results.flatMap((result) => (result.status === "fulfilled" ? [result.value] : []));
      if (deleted.length > 0) {
        const label =
          deleted.length === 1 ? recordTitle(module, deleted[0]) : `${deleted.length} ${module.labelPlural.toLowerCase()}`;
        toast.success(`${label} deleted`, {
          action: {
            label: "Undo",
            onClick: () => {
              void Promise.allSettled(
                deleted.map((record) => restore.mutateAsync({ id: record.id, ifMatch: record.etag }))
              ).then(() => toast.success("Restored"));
            },
          },
        });
      }
      return deleted;
    },
    [del, restore, module]
  );

  return { deleteRecords, restoreRecord, deleting: del.isPending, restoring: restore.isPending };
}

export function ConfirmDeleteDialog({
  open,
  onOpenChange,
  module,
  count,
  title,
  onConfirm,
  pending,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  module: ModuleDefinition;
  count: number;
  title?: string;
  onConfirm: () => void | Promise<void>;
  pending?: boolean;
}) {
  const subject = count === 1 ? (title ?? `this ${module.labelSingular.toLowerCase()}`) : `${count} ${module.labelPlural.toLowerCase()}`;
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete {subject}?</AlertDialogTitle>
          <AlertDialogDescription>
            Deleted records move to the trash and keep their history. You can restore them later.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>Cancel</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={pending}
            onClick={(event) => {
              event.preventDefault();
              void Promise.resolve(onConfirm()).finally(() => onOpenChange(false));
            }}
          >
            {pending ? <Spinner className="text-destructive-foreground" /> : null}
            Delete
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
