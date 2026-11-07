import type { ReactNode } from "react";
import { cn } from "../cn";

export interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  extra?: ReactNode;
  bordered?: boolean;
  className?: string;
}

export function PageHeader({
  title,
  description,
  extra,
  bordered = false,
  className,
}: PageHeaderProps) {
  return (
    <header
      className={cn(
        "flex w-full flex-wrap items-start justify-between gap-x-6 gap-y-3 pb-5 pt-4",
        bordered && "border-b border-[var(--border)]",
        className,
      )}
    >
      <div className="min-w-0 basis-full sm:basis-auto sm:flex-1">
        <h1 className="text-xl font-semibold leading-7 tracking-[-0.01em] text-[var(--foreground)]">
          {title}
        </h1>
        {description ? (
          <div className="mt-1.5 text-[13px] leading-5 text-[var(--muted-foreground)]">
            {description}
          </div>
        ) : null}
      </div>
      {extra ? <div className="flex shrink-0 items-center gap-2 sm:ml-auto">{extra}</div> : null}
    </header>
  );
}
