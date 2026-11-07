import type { AguiEvent } from "@/service/agui";

export function AguiEventLog({ events }: { events: readonly AguiEvent[] }) {
  return (
    <div className="max-h-56 space-y-1 overflow-y-auto font-mono text-xs">
      {events.length === 0 ? (
        <div className="py-6 text-center text-[var(--muted-foreground)]">暂无事件</div>
      ) : null}
      {events.map((event, index) => (
        <div
          key={`${event.type}-${index}`}
          className="flex gap-2 rounded-[var(--radius)] bg-[var(--accent)] px-2 py-1"
        >
          <span className="shrink-0 font-medium text-[var(--primary)]">{event.type}</span>
          <span className="truncate text-[var(--muted-foreground)]">
            {event.delta ?? event.toolCallName ?? ""}
          </span>
        </div>
      ))}
    </div>
  );
}
