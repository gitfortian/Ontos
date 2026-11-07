import { cva, type VariantProps } from "class-variance-authority";
import type { TextareaHTMLAttributes } from "react";
import { cn } from "../cn";

export const textareaVariants = cva(
  "w-full rounded-[var(--radius)] border border-[var(--input)] bg-[var(--card)] px-3 py-2 text-sm text-[var(--foreground)] outline-none transition-colors placeholder:text-[var(--muted-foreground)] focus-visible:border-[var(--ring)] focus-visible:ring-2 focus-visible:ring-[var(--primary-subtle)]",
  {
    variants: { variant: { filled: "bg-[var(--muted)]", outlined: "bg-[var(--card)]" } },
    defaultVariants: { variant: "outlined" },
  },
);

export type TextareaProps = Omit<
  TextareaHTMLAttributes<HTMLTextAreaElement>,
  "value" | "onChange"
> &
  VariantProps<typeof textareaVariants> & {
    value?: string | number;
    onValueChange?: (value: string) => void;
  };

export function Textarea({ className, variant, value, onValueChange, ...props }: TextareaProps) {
  return (
    <textarea
      className={cn(textareaVariants({ variant }), className)}
      value={value}
      onChange={(event) => onValueChange?.(event.target.value)}
      {...props}
    />
  );
}
