import { cva, type VariantProps } from "class-variance-authority";
import type { ButtonHTMLAttributes } from "react";
import { cn } from "../cn";
import { Spinner } from "../spinner/Spinner";

export const buttonVariants = cva(
  "inline-flex cursor-pointer items-center justify-center gap-1 whitespace-nowrap rounded-[var(--radius)] font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[var(--ring)] disabled:cursor-not-allowed disabled:opacity-45",
  {
    variants: {
      variant: {
        primary:
          "border border-[var(--primary)] bg-[var(--primary)] text-[var(--primary-foreground)] hover:bg-[var(--primary-hover)]",
        secondary:
          "border border-[var(--border)] bg-[var(--card)] text-[var(--foreground)] hover:bg-[var(--accent)]",
        ghost:
          "border border-transparent bg-transparent text-[var(--foreground)] hover:bg-[var(--accent)]",
        danger:
          "border border-transparent bg-[var(--destructive)] text-[var(--destructive-foreground)] hover:opacity-90",
      },
      size: {
        small: "h-8 px-3.5 text-xs",
        medium: "h-9 px-4 text-sm",
        large: "h-10 px-4 text-sm",
      },
    },
    defaultVariants: { variant: "secondary", size: "medium" },
  },
);

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> &
  VariantProps<typeof buttonVariants> & { loading?: boolean };

export function Button({
  className,
  variant,
  size,
  loading,
  disabled,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      type="button"
      className={cn(buttonVariants({ variant, size }), className)}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <Spinner size="small" /> : null}
      {children}
    </button>
  );
}
