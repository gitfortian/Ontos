import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "../cn";

export interface AlertProps extends Omit<HTMLAttributes<HTMLDivElement>, "children"> {
  children: ReactNode;
}

export function Alert({ children, className, ...props }: AlertProps) {
  return (
    <div
      {...props}
      role="alert"
      className={cn(
        "flex min-h-9 w-full items-start gap-2 rounded-[var(--radius)] border border-[var(--warning)] bg-[color-mix(in_srgb,var(--warning)_10%,transparent)] px-3 py-2 text-xs leading-5 text-[var(--foreground)]",
        className,
      )}
    >
      <span aria-hidden="true" className="mt-0.5 shrink-0 text-[var(--warning)]">
        !
      </span>
      <div className="min-w-0 flex-1 [overflow-wrap:anywhere]">{children}</div>
    </div>
  );
}
