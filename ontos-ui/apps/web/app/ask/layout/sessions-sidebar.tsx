import { NavLink, useNavigate } from "react-router-dom";

import { Button, Tooltip, TooltipContent, TooltipTrigger } from "@ontos/ui";

import { useSessions } from "@/app/ask/hooks/use-sessions";
import { useAskWorkbenchStore } from "@/app/ask/stores/ask-workbench-store";

/** 左栏：场景切换器 + 历史会话列表。场景清单 P1 接入（当前展示固定入口）。 */
export function SessionsSidebar() {
  const navigate = useNavigate();
  const { data: sessions, isPending } = useSessions();
  const activeSessionId = useAskWorkbenchStore((state) => state.activeSessionId);
  const setActiveSession = useAskWorkbenchStore((state) => state.setActiveSession);

  return (
    <aside className="flex h-full w-56 shrink-0 flex-col gap-2 border-r border-border bg-card px-2 py-3">
      <div className="flex items-center gap-2 px-1.5 pb-1">
        <span className="flex size-5 items-center justify-center rounded-md bg-primary text-[10px] font-medium text-primary-foreground">
          O
        </span>
        <span className="text-xs font-medium">Ontos 问数</span>
      </div>

      <Button
        type="button"
        variant="secondary"
        size="small"
        className="w-full justify-start"
        onClick={() => setActiveSession(null)}
      >
        + 新建对话
      </Button>

      <p className="px-1.5 pt-2 text-[11px] text-muted-foreground">业务场景</p>
      <Tooltip>
        <TooltipTrigger asChild>
          <NavLink
            to="/ask"
            className="rounded-md px-1.5 py-1 text-[11px] text-muted-foreground hover:bg-accent"
          >
            自由问数（全部场景）
          </NavLink>
        </TooltipTrigger>
        <TooltipContent>场景切换器随场景清单端点接入（P1）</TooltipContent>
      </Tooltip>

      <p className="px-1.5 pt-3 text-[11px] text-muted-foreground">历史会话</p>
      <nav className="flex-1 space-y-1 overflow-y-auto">
        {isPending ? (
          <p className="px-1.5 text-[11px] text-muted-foreground">加载中…</p>
        ) : (sessions ?? []).length === 0 ? (
          <p className="px-1.5 text-[11px] text-muted-foreground">还没有历史会话</p>
        ) : (
          (sessions ?? []).map((session) => (
            <button
              key={session.sessionId}
              type="button"
              onClick={() => setActiveSession(session.sessionId)}
              className={`w-full rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-accent ${
                session.sessionId === activeSessionId ? "bg-accent" : ""
              }`}
            >
              <p className="line-clamp-1 text-xs">{session.title}</p>
              <p className="mt-0.5 text-[10px] text-muted-foreground">
                {session.runCount} 轮 · {session.lastActiveAt.slice(5, 16).replace("T", " ")}
              </p>
            </button>
          ))
        )}
      </nav>

      <Button
        type="button"
        variant="ghost"
        size="small"
        className="w-full justify-start"
        onClick={() => navigate("/audit")}
      >
        审计与失败看板
      </Button>
    </aside>
  );
}
