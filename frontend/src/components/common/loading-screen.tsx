import { BrandMark } from "@/components/common/brand";
import { Spinner } from "@/components/ui/spinner";

export function LoadingScreen({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-background" aria-busy="true">
      <BrandMark className="size-9" />
      <div className="flex items-center gap-2 text-[13px] text-muted-foreground">
        <Spinner className="size-3.5" />
        {label}
      </div>
    </div>
  );
}
