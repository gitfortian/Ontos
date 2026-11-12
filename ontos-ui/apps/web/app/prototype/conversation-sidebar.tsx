import { Badge, Button, cn } from "@ontos/ui";
import { BookOpen, CheckSquare, FileClock, MessageSquareText, Plus, Sparkles } from "lucide-react";
import { NavLink } from "react-router-dom";

import { PROTOTYPE_SESSIONS } from "./data";

interface ConversationSidebarProps {
  activeId: string;
  onNew: () => void;
  onSelect: (id: string) => void;
}

const NAV_ITEMS = [
  { label: "审批中心", path: "/approvals", icon: CheckSquare, count: 3 },
  { label: "数据资产", path: "/ontology/manifests", icon: BookOpen },
  { label: "审计日志", path: "/audit", icon: FileClock },
] as const;

export function ConversationSidebar({ activeId, onNew, onSelect }: ConversationSidebarProps) {
  return (
    <aside className="flex w-[252px] shrink-0 flex-col border-r border-[var(--border)] bg-[var(--card)] max-lg:w-[224px] max-md:hidden">
      <div className="flex h-[68px] items-center gap-3 px-5">
        <div className="flex size-9 items-center justify-center rounded-xl bg-[var(--primary)] text-[var(--primary-foreground)] shadow-sm">
          <Sparkles size={18} aria-hidden="true" />
        </div>
        <div>
          <div className="text-[17px] font-semibold tracking-tight">Ontos</div>
          <div className="text-[11px] text-[var(--muted-foreground)]">智能问数</div>
        </div>
      </div>

      <div className="px-4 pb-4 pt-3">
        <Button variant="primary" className="w-full justify-between px-4" onClick={onNew}>
          <span className="flex items-center gap-2">
            <Plus size={16} aria-hidden="true" />
            新建问数
          </span>
          <span className="text-xs opacity-70">⌘ K</span>
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-3">
        <div className="mb-2 flex items-center justify-between px-2">
          <span className="text-xs font-medium text-[var(--muted-foreground)]">最近会话</span>
          <MessageSquareText
            size={14}
            className="text-[var(--muted-foreground)]"
            aria-hidden="true"
          />
        </div>
        <div className="space-y-1">
          {PROTOTYPE_SESSIONS.map((session) => (
            <button
              key={session.id}
              type="button"
              onClick={() => onSelect(session.id)}
              aria-current={activeId === session.id ? "page" : undefined}
              className={cn(
                "w-full rounded-[var(--radius)] px-3 py-2.5 text-left outline-none transition-colors focus-visible:ring-2 focus-visible:ring-[var(--ring)]",
                activeId === session.id
                  ? "bg-[var(--primary-subtle)] text-[var(--primary)]"
                  : "text-[var(--foreground)] hover:bg-[var(--muted)]",
              )}
            >
              <div className="truncate text-[13px] font-medium">{session.title}</div>
              <div
                className={cn(
                  "mt-1 text-[11px]",
                  activeId === session.id
                    ? "text-[var(--primary)] opacity-75"
                    : "text-[var(--muted-foreground)]",
                )}
              >
                {session.time}
              </div>
            </button>
          ))}
        </div>
      </div>

      <div className="border-t border-[var(--border)] px-3 py-3">
        <div className="mb-1 px-2 text-[11px] font-medium text-[var(--muted-foreground)]">
          治理与运营
        </div>
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.path}
              to={item.path}
              className="flex items-center gap-2.5 rounded-[var(--radius)] px-3 py-2 text-[13px] text-[var(--muted-foreground)] outline-none hover:bg-[var(--muted)] hover:text-[var(--foreground)] focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            >
              <Icon size={15} aria-hidden="true" />
              <span className="flex-1">{item.label}</span>
              {"count" in item ? <Badge tone="neutral">{item.count}</Badge> : null}
            </NavLink>
          );
        })}
      </div>

      <div className="m-3 mt-0 flex items-center gap-3 rounded-[var(--radius)] p-2 text-left">
        <span className="flex size-8 items-center justify-center rounded-full bg-[var(--primary-subtle)] text-xs font-semibold text-[var(--primary)]">
          林
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] font-medium">林晓</span>
          <span className="block truncate text-[11px] text-[var(--muted-foreground)]">
            销售运营 · 演示账号
          </span>
        </span>
      </div>
    </aside>
  );
}
