import * as RadixSelect from "@radix-ui/react-select";
import { cva, type VariantProps } from "class-variance-authority";
import {
  createContext,
  forwardRef,
  useContext,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from "react";
import { cn } from "../cn";

type SelectSize = "small" | "medium" | "large";
const SelectSizeContext = createContext<SelectSize>("medium");

export interface SelectProps extends Omit<
  ComponentPropsWithoutRef<typeof RadixSelect.Root>,
  "children"
> {
  items?: ReadonlyArray<{ value: string; label: string }>;
  size?: SelectSize;
  children?: ReactNode;
}

/** Radix Select wrapped with the product's compound-component API. */
export function Select({ size = "medium", items, children, ...rootProps }: SelectProps) {
  void items;
  return (
    <SelectSizeContext.Provider value={size}>
      <RadixSelect.Root {...rootProps}>{children}</RadixSelect.Root>
    </SelectSizeContext.Provider>
  );
}

export const selectTriggerVariants = cva(
  "flex items-center justify-between gap-2 rounded-[var(--radius)] border border-[var(--input)] bg-[var(--card)] text-[var(--foreground)] outline-none transition-colors data-placeholder:text-[var(--muted-foreground)] focus-visible:ring-2 focus-visible:ring-[var(--primary-subtle)]",
  {
    variants: {
      variant: { outlined: "bg-[var(--card)]", filled: "bg-[var(--muted)]" },
      size: { small: "h-8 px-2.5 text-xs", medium: "h-9 px-3 text-sm", large: "h-10 px-3 text-sm" },
    },
    defaultVariants: { variant: "outlined", size: "medium" },
  },
);

export interface SelectTriggerProps extends Omit<
  ComponentPropsWithoutRef<typeof RadixSelect.Trigger>,
  "size"
> {
  variant?: NonNullable<VariantProps<typeof selectTriggerVariants>["variant"]>;
}

export const SelectTrigger = forwardRef<HTMLButtonElement, SelectTriggerProps>(
  function SelectTrigger({ className, variant, children, ...props }, ref) {
    const size = useContext(SelectSizeContext);
    return (
      <RadixSelect.Trigger
        ref={ref}
        className={cn(selectTriggerVariants({ variant, size }), className)}
        {...props}
      >
        {children}
        <RadixSelect.Icon aria-hidden="true" className="shrink-0 opacity-60">
          <svg viewBox="0 0 12 12" className="size-3" fill="none">
            <path
              d="m3 4.5 3 3 3-3"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
            />
          </svg>
        </RadixSelect.Icon>
      </RadixSelect.Trigger>
    );
  },
);

export type SelectValueProps = ComponentPropsWithoutRef<typeof RadixSelect.Value>;

export function SelectValue({ className, ...props }: SelectValueProps) {
  return <RadixSelect.Value className={cn("truncate", className)} {...props} />;
}

export interface SelectContentProps extends ComponentPropsWithoutRef<typeof RadixSelect.Content> {
  children?: ReactNode;
}

export function SelectContent({ className, children, ...props }: SelectContentProps) {
  return (
    <RadixSelect.Portal>
      <RadixSelect.Content
        position="popper"
        sideOffset={4}
        className={cn(
          "z-50 min-w-[8rem] overflow-hidden rounded-[var(--radius)] border border-[var(--border)] bg-[var(--card)] p-1 shadow-md",
          className,
        )}
        {...props}
      >
        <RadixSelect.Viewport>{children}</RadixSelect.Viewport>
      </RadixSelect.Content>
    </RadixSelect.Portal>
  );
}

export interface SelectItemProps extends ComponentPropsWithoutRef<typeof RadixSelect.Item> {
  children?: ReactNode;
}

export function SelectItem({ className, children, ...props }: SelectItemProps) {
  return (
    <RadixSelect.Item
      className={cn(
        "relative flex cursor-pointer select-none items-center rounded-sm py-1.5 pl-2 pr-7 text-sm text-[var(--foreground)] outline-none data-highlighted:bg-[var(--accent)] data-disabled:opacity-50",
        className,
      )}
      {...props}
    >
      {children}
    </RadixSelect.Item>
  );
}

export type SelectItemTextProps = ComponentPropsWithoutRef<typeof RadixSelect.ItemText>;

export function SelectItemText({ className, ...props }: SelectItemTextProps) {
  return <RadixSelect.ItemText className={cn("truncate", className)} {...props} />;
}

export type SelectItemIndicatorProps = ComponentPropsWithoutRef<typeof RadixSelect.ItemIndicator>;

export function SelectItemIndicator({ className, ...props }: SelectItemIndicatorProps) {
  return (
    <RadixSelect.ItemIndicator
      className={cn("absolute right-2 inline-flex items-center text-[var(--primary)]", className)}
      {...props}
    >
      <svg viewBox="0 0 12 12" className="size-3" fill="none" aria-hidden="true">
        <path
          d="m2.5 6 2.5 2.5 4.5-5"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
        />
      </svg>
    </RadixSelect.ItemIndicator>
  );
}
