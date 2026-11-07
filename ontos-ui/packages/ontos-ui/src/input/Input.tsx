import { cva, type VariantProps } from "class-variance-authority";
import type { InputHTMLAttributes } from "react";
import { cn } from "../cn";

export const inputVariants = cva(
  "w-full rounded-[var(--radius)] border border-[var(--input)] bg-[var(--card)] text-[var(--foreground)] outline-none transition-colors placeholder:text-[var(--muted-foreground)] focus-visible:border-[var(--ring)] focus-visible:ring-2 focus-visible:ring-[var(--primary-subtle)] disabled:cursor-not-allowed disabled:opacity-50",
  {
    variants: {
      variant: { filled: "bg-[var(--muted)]", outlined: "bg-[var(--card)]" },
      size: { small: "h-8 px-2.5 text-xs", medium: "h-9 px-3 text-sm", large: "h-10 px-3 text-sm" },
    },
    defaultVariants: { variant: "outlined", size: "medium" },
  },
);

// `size` 是原生 input 的数字属性，与尺寸变体冲突，此处以变体为准
export type InputProps = Omit<InputHTMLAttributes<HTMLInputElement>, "size"> &
  VariantProps<typeof inputVariants>;

export function Input({ className, variant, size, ...props }: InputProps) {
  return <input className={cn(inputVariants({ variant, size }), className)} {...props} />;
}
