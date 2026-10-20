import { cn } from "@/lib/utils";

/** CampusHire mark: an open "C" whose end steps upward - a student moving into a role. */
export function BrandMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn("size-7 shrink-0", className)}>
      <rect width="32" height="32" rx="8" className="fill-primary" />
      <path
        d="M21.5 10.2A8 8 0 1 0 21.5 21.8"
        fill="none"
        stroke="white"
        strokeWidth="2.6"
        strokeLinecap="round"
      />
      <path d="M17.5 16h6.2m0 0-2.4-2.4m2.4 2.4-2.4 2.4" fill="none" stroke="white" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function BrandWordmark({ className }: { className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <BrandMark className="size-6" />
      <span className="text-[15px] font-semibold tracking-[-0.02em]">CampusHire</span>
    </span>
  );
}
