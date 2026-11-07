import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "../cn";

const spinnerVariants = cva("inline-flex shrink-0 items-center justify-center", {
  variants: { size: { small: "size-3", medium: "size-3.5", large: "size-4", xlarge: "size-5" } },
  defaultVariants: { size: "medium" },
});

export type SpinnerProps = VariantProps<typeof spinnerVariants> & {
  className?: string;
  label?: string;
};

export function Spinner({ className, label = "Loading", size }: SpinnerProps) {
  return (
    <span
      role="status"
      aria-label={label}
      className={cn("text-[var(--primary)]", spinnerVariants({ size }), className)}
    >
      <svg aria-hidden="true" viewBox="0 0 16 16" className="size-full animate-spin" fill="none">
        <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="2" opacity="0.25" />
        <path d="M8 2a6 6 0 0 1 6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
    </span>
  );
}
