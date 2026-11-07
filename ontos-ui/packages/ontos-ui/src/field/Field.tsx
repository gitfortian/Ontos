import type { HTMLAttributes, LabelHTMLAttributes, ReactNode } from "react";
import { cn } from "../cn";

export type FieldProps = HTMLAttributes<HTMLDivElement>;

export function Field({ className, ...props }: FieldProps) {
  return <div className={cn("min-w-0", className)} {...props} />;
}

export interface FieldLabelProps extends LabelHTMLAttributes<HTMLLabelElement> {
  required?: boolean;
}

export function FieldLabel({ children, className, required, ...props }: FieldLabelProps) {
  return (
    <label className={cn("text-xs leading-5 text-[var(--foreground)]", className)} {...props}>
      {children}
      {required ? <span className="ml-0.5 text-[var(--destructive)]">*</span> : null}
    </label>
  );
}

export interface FieldErrorProps extends HTMLAttributes<HTMLDivElement> {
  match?: boolean;
}

export function FieldError({ children, className, match = true, ...props }: FieldErrorProps) {
  if (!match) return null;
  return (
    <div className={cn("text-xs text-[var(--destructive)]", className)} {...props}>
      {children}
    </div>
  );
}

export type FieldDescriptionProps = HTMLAttributes<HTMLDivElement> & { children?: ReactNode };

export function FieldDescription({ className, ...props }: FieldDescriptionProps) {
  return <div className={cn("text-xs text-[var(--muted-foreground)]", className)} {...props} />;
}
