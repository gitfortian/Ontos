import { useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";

import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight } from "lucide-react";

import { Button } from "@ontos/ui";

import { useSessions } from "@/app/ask/hooks/use-sessions";
import { useAskWorkbenchStore } from "@/app/ask/stores/ask-workbench-store";
import { listAskScenarios, type AskScenarioVO } from "@/service/ask";
import { fetchSessionRuns, type AskSessionRunVO } from "@/service/sessions";

/** 终态名 → 侧栏摘要文案（与后端六态冻结一致）。 */
const terminalLabel = (run: AskSessionRunVO): string => {
  if (run.status === "FAILED") {
    return "执行失败";
  }
  return (
    {
      query: "查询结果",
      composite: "复合结果",
      clarification: "需要澄清",
      discovery: "能力发现",
      out_of_capability: "超出能力",
      waiting: "等待审批",
    }[run.terminal ?? ""] ?? "已完成"
  );
};

/** 单个会话条目：点击切换会话，chevron 展开只读轮次摘要（终态事件回放）。 */
function SessionItem({
  sessionId,
  title,
  runCount,
  lastActiveAt,
  active,
  onSelect,
}: {
  sessionId: string;
  title: string;
  runCount: number;
  lastActiveAt: string;
  active: boolean;
  onSelect: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const {
    data: runs,
    isPending,
    isError,
  } = useQuery({
    queryKey: ["ask", "session-runs", sessionId],
    queryFn: ({ signal }) => fetchSessionRuns(sessionId, 20, signal),
    enabled: expanded,
    staleTime: 30_000,
  });

  return (
    <div>
      <div
        className={`flex items-center rounded-lg transition-colors hover:bg-accent ${active ? "bg-accent" : ""}`}
      >
        <button type="button" onClick={onSelect} className="min-w-0 flex-1 px-2 py-1.5 text-left">
          <p className="line-clamp-1 text-xs">{title}</p>
          <p className="mt-0.5 text-[10px] text-muted-foreground">
            {runCount} 轮 · {lastActiveAt.slice(5, 16).replace("T", " ")}
          </p>
        </button>
        <button
          type="button"
          aria-label={expanded ? `收起「${title}」的轮次摘要` : `展开「${title}」的轮次摘要`}
          aria-expanded={expanded}
          className="mr-1 shrink-0 rounded-md p-1 text-muted-foreground hover:text-foreground"
          onClick={() => setExpanded((value) => !value)}
        >
          {expanded ? (
            <ChevronDown aria-hidden="true" size={12} />
          ) : (
            <ChevronRight aria-hidden="true" size={12} />
          )}
        </button>
      </div>
      {expanded ? (
        <div className="mb-1 ml-3 space-y-1 border-l border-border pl-2">
          {isPending ? (
            <p className="py-0.5 text-[10px] text-muted-foreground">加载轮次摘要…</p>
          ) : isError ? (
            <p className="py-0.5 text-[10px] text-destructive">摘要加载失败</p>
          ) : (runs ?? []).length === 0 ? (
            <p className="py-0.5 text-[10px] text-muted-foreground">没有轮次记录</p>
          ) : (
            (runs ?? []).map((run) => (
              <div key={run.runId} className="py-0.5">
                <p className="line-clamp-2 text-[11px]">
                  {run.utterance ?? `第 ${run.turnIndex + 1} 轮`}
                </p>
                <p
                  className={`text-[10px] ${
                    run.status === "FAILED" ? "text-destructive" : "text-muted-foreground"
                  }`}
                >
                  {terminalLabel(run)}
                  {run.rowCount != null ? ` · ${run.rowCount} 行` : ""}
                  {run.summaryText ? ` · ${run.summaryText}` : ""}
                </p>
              </div>
            ))
          )}
          <p className="pt-0.5 text-[10px] text-muted-foreground">只读摘要；点会话名继续追问</p>
        </div>
      ) : null}
    </div>
  );
}

/** 场景入口模式 → 展示文案（与 ScenarioEntryMode 冻结枚举一致）。 */
const entryModeLabel = (entryMode: string | null): string =>
  ({
    manual_only: "仅手动进入",
    recommend_or_confirm: "推荐后确认",
    auto_if_confident: "高置信自动",
  })[entryMode ?? ""] ?? "推荐后确认";

/** 场景目录项：只读事实呈现——进入场景仍由路由推荐后确认（M6 语义），目录不提供钉住。 */
function ScenarioItem({ scenario }: { scenario: AskScenarioVO }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div>
      <button
        type="button"
        className="w-full rounded-md px-1.5 py-1 text-left text-[11px] transition-colors hover:bg-accent"
        onClick={() => setExpanded((value) => !value)}
      >
        <span className="flex items-center gap-1">
          {expanded ? (
            <ChevronDown aria-hidden="true" size={11} />
          ) : (
            <ChevronRight aria-hidden="true" size={11} />
          )}
          <span className="min-w-0 truncate">{scenario.name}</span>
        </span>
      </button>
      {expanded ? (
        <div className="mb-1 ml-3 border-l border-border pl-2">
          {scenario.summary ? (
            <p className="py-0.5 text-[10px] leading-relaxed text-muted-foreground">
              {scenario.summary}
            </p>
          ) : null}
          <p className="py-0.5 text-[10px] text-muted-foreground">
            {entryModeLabel(scenario.entryMode)} · 提问命中后按提示确认即可进入
          </p>
        </div>
      ) : null}
    </div>
  );
}

/** 左栏：场景目录 + 历史会话列表（可展开轮次摘要）。 */
export function SessionsSidebar() {
  const navigate = useNavigate();
  const { data: sessions, isPending } = useSessions();
  const { data: scenarios, isPending: scenariosPending } = useQuery({
    queryKey: ["ask", "scenarios"],
    queryFn: ({ signal }) => listAskScenarios(signal),
    staleTime: 5 * 60_000,
  });
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
      <nav className="space-y-0.5 px-0.5">
        {scenariosPending ? (
          <p className="px-1.5 py-0.5 text-[11px] text-muted-foreground">加载场景目录…</p>
        ) : (scenarios ?? []).length === 0 ? (
          <p className="px-1.5 py-0.5 text-[11px] text-muted-foreground">
            当前清单没有已发布场景，自由问数即可
          </p>
        ) : (
          (scenarios ?? []).map((scenario) => (
            <ScenarioItem key={scenario.scenarioId} scenario={scenario} />
          ))
        )}
        <NavLink
          to="/ask"
          className="block rounded-md px-1.5 py-1 text-[11px] text-muted-foreground hover:bg-accent"
        >
          自由问数（全部场景）
        </NavLink>
      </nav>

      <p className="px-1.5 pt-3 text-[11px] text-muted-foreground">历史会话</p>
      <nav className="flex-1 space-y-1 overflow-y-auto">
        {isPending ? (
          <p className="px-1.5 text-[11px] text-muted-foreground">加载中…</p>
        ) : (sessions ?? []).length === 0 ? (
          <p className="px-1.5 text-[11px] text-muted-foreground">还没有历史会话</p>
        ) : (
          (sessions ?? []).map((session) => (
            <SessionItem
              key={session.sessionId}
              sessionId={session.sessionId}
              title={session.title}
              runCount={session.runCount}
              lastActiveAt={session.lastActiveAt}
              active={session.sessionId === activeSessionId}
              onSelect={() => setActiveSession(session.sessionId)}
            />
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
