import * as RadixToast from "@radix-ui/react-toast";
import { useSyncExternalStore, type ReactNode } from "react";
import { cn } from "../cn";

export type ToastTone = "success" | "error" | "warning" | "info";

export interface ToastOptions {
  id?: string;
  description?: ReactNode;
  meta?: ReactNode;
  timeout?: number;
  action?: { label: ReactNode; onClick: () => void };
}

interface ToastItem {
  id: string;
  title: string;
  tone: ToastTone;
  options: ToastOptions;
}

let items: ToastItem[] = [];
const listeners = new Set<() => void>();

const emit = () => {
  items = [...items];
  for (const listener of listeners) listener();
};

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

const snapshot = () => items;

const push = (tone: ToastTone, title: string, options: ToastOptions = {}): string => {
  const id = options.id ?? globalThis.crypto.randomUUID();
  items = [...items.filter((item) => item.id !== id), { id, title, tone, options }];
  emit();
  return id;
};

const dismiss = (id?: string) => {
  items = id ? items.filter((item) => item.id !== id) : [];
  emit();
};

export const toast = {
  success: (title: string, options?: ToastOptions) => push("success", title, options),
  error: (title: string, options?: ToastOptions) => push("error", title, options),
  warning: (title: string, options?: ToastOptions) => push("warning", title, options),
  info: (title: string, options?: ToastOptions) => push("info", title, options),
  dismiss,
};

const TONE_CLASS: Record<ToastTone, string> = {
  success: "border-l-[var(--success)]",
  error: "border-l-[var(--destructive)]",
  warning: "border-l-[var(--warning)]",
  info: "border-l-[var(--primary)]",
};

export function ToastProvider({ children }: { children: ReactNode }) {
  const current = useSyncExternalStore(subscribe, snapshot, snapshot);

  return (
    <RadixToast.Provider swipeDirection="right" duration={4000}>
      {children}
      {current.map((item) => (
        <RadixToast.Root
          key={item.id}
          open
          duration={item.options.timeout ?? 4000}
          onOpenChange={(open) => {
            if (!open) dismiss(item.id);
          }}
          className={cn(
            "rounded-[var(--radius)] border border-[var(--border)] border-l-4 bg-[var(--card)] px-3 py-2 text-[var(--foreground)] shadow-md",
            TONE_CLASS[item.tone],
          )}
        >
          <RadixToast.Title className="text-sm font-medium">{item.title}</RadixToast.Title>
          {item.options.description ? (
            <RadixToast.Description className="mt-1 text-xs text-[var(--muted-foreground)]">
              {item.options.description}
            </RadixToast.Description>
          ) : null}
          {item.options.meta ? (
            <div className="mt-1 text-xs text-[var(--muted-foreground)]">{item.options.meta}</div>
          ) : null}
          {item.options.action ? (
            <RadixToast.Action
              altText={
                typeof item.options.action.label === "string" ? item.options.action.label : "操作"
              }
              className="mt-2 text-xs font-medium text-[var(--primary)]"
              onClick={item.options.action.onClick}
            >
              {item.options.action.label}
            </RadixToast.Action>
          ) : null}
          <RadixToast.Close
            aria-label="关闭"
            className="absolute right-2 top-2 text-[var(--muted-foreground)]"
          >
            x
          </RadixToast.Close>
        </RadixToast.Root>
      ))}
      <RadixToast.Viewport className="fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2 outline-none" />
    </RadixToast.Provider>
  );
}
