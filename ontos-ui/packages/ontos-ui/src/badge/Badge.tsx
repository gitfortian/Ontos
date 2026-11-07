import { cva, type VariantProps } from "class-variance-authority";
import type { HTMLAttributes } from "react";
import { cn } from "../cn";

const badgeVariants = cva(
  "inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium",
  {
    variants: {
      tone: {
        neutral: "bg-[var(--muted)] text-[var(--muted-foreground)]",
        success: "bg-[color-mix(in_srgb,var(--success)_12%,transparent)] text-[var(--success)]",
        warning: "bg-[color-mix(in_srgb,var(--warning)_14%,transparent)] text-[var(--warning)]",
        danger:
          "bg-[color-mix(in_srgb,var(--destructive)_12%,transparent)] text-[var(--destructive)]",
        info: "bg-[var(--primary-subtle)] text-[var(--primary)]",
      },
    },
    defaultVariants: { tone: "neutral" },
  },
);

export type BadgeProps = HTMLAttributes<HTMLSpanElement> & VariantProps<typeof badgeVariants>;

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
