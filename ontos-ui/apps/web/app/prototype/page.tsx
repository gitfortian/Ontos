import { Badge } from "@ontos/ui";
import {
  ArrowUpRight,
  BarChart3,
  Code2,
  MessageSquareText,
  Sparkles,
  TrendingUp,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { NavLink, useSearchParams } from "react-router-dom";

import { ChatComposer } from "./chat-composer";
import { ConversationSidebar } from "./conversation-sidebar";
import { EXAMPLE_PROMPTS, PROTOTYPE_SESSIONS } from "./data";
import { CustomerResultCard, ResultCard } from "./result-card";
import {
  ApprovalCard,
  ApprovalRejectedCard,
  ClarificationCard,
  CompositeResultCard,
  DiscoveryCard,
  OutOfCapabilityCard,
  RunningCard,
} from "./terminal-cards";
import type { ApprovalDecision, ClarificationChoice, PrototypeScenario, RunPhase } from "./types";

const EXAMPLE_ICONS = [BarChart3, TrendingUp, MessageSquareText] as const;

function AssistantMark() {
  return (
    <span className="flex size-8 shrink-0 items-center justify-center rounded-xl bg-[var(--primary)] text-[var(--primary-foreground)] shadow-sm">
      <Sparkles size={15} aria-hidden="true" />
    </span>
  );
}

export function ProductPrototypePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedSession = searchParams.get("session");
  const activeId =
    PROTOTYPE_SESSIONS.some((item) => item.id === requestedSession) || requestedSession === "new"
      ? requestedSession!
      : "revenue";
  const [draft, setDraft] = useState("");
  const [question, setQuestion] = useState("");
  const [phase, setPhase] = useState<RunPhase>(activeId === "new" ? "idle" : "ready");
  const [runScenario, setRunScenario] = useState<PrototypeScenario>("query");
  const [following, setFollowing] = useState(true);
  const [clarificationChoice, setClarificationChoice] = useState<ClarificationChoice | null>(null);
  const [approvalDecision, setApprovalDecision] = useState<ApprovalDecision>("pending");
  const timerRef = useRef<number | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const session = PROTOTYPE_SESSIONS.find((item) => item.id === activeId);
  const isNew = activeId === "new";
  const scenario = isNew ? runScenario : (session?.scenario ?? "query");
  const displayedQuestion = question || session?.question || "";
  const waitingForClarification = scenario === "clarification" && !clarificationChoice;
  const waitingForApproval = scenario === "approval" && approvalDecision === "pending";
  const composerDisabled = waitingForClarification || waitingForApproval;

  const clearPendingTimer = () => {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    timerRef.current = null;
  };

  useEffect(() => () => clearPendingTimer(), []);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        clearPendingTimer();
        setSearchParams({ session: "new" });
        setQuestion("");
        setDraft("");
        setPhase("idle");
      }
    };
    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, [setSearchParams]);

  useEffect(() => {
    clearPendingTimer();
    setQuestion("");
    setDraft("");
    setPhase(activeId === "new" ? "idle" : "ready");
    setRunScenario("query");
    setFollowing(true);
    setClarificationChoice(null);
    setApprovalDecision("pending");
  }, [activeId]);

  useEffect(() => {
    if (phase === "running" && following) {
      scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
    }
  }, [following, phase]);

  const selectSession = (id: string) => {
    clearPendingTimer();
    setSearchParams({ session: id });
    setQuestion("");
    setDraft("");
    setPhase("ready");
    setFollowing(true);
    setRunScenario("query");
    setClarificationChoice(null);
    setApprovalDecision("pending");
  };

  const startNew = () => {
    clearPendingTimer();
    setSearchParams({ session: "new" });
    setQuestion("");
    setDraft("");
    setPhase("idle");
    setFollowing(true);
    setRunScenario("query");
    setClarificationChoice(null);
    setApprovalDecision("pending");
  };

  const startRun = (nextQuestion: string, nextScenario: PrototypeScenario = scenario) => {
    clearPendingTimer();
    setQuestion(nextQuestion);
    setDraft("");
    setPhase("running");
    setFollowing(true);
    setRunScenario(nextScenario);
    timerRef.current = window.setTimeout(() => {
      setPhase("ready");
      timerRef.current = null;
    }, 900);
  };

  const handleClarification = (choice: ClarificationChoice) => {
    setClarificationChoice(choice);
    startRun(session?.question ?? "这个月销售怎么样？");
  };

  const handleApproval = (decision: Exclude<ApprovalDecision, "pending">) => {
    setApprovalDecision(decision);
    if (decision === "approved") startRun(session?.question ?? "查看高价值客户的最近订单明细");
    else setPhase("ready");
  };

  const renderResult = () => {
    if (phase === "running") return <RunningCard following={following} />;

    switch (scenario) {
      case "clarification":
        if (!clarificationChoice) return <ClarificationCard onSelect={handleClarification} />;
        return (
          <>
            <div className="flex justify-center py-1">
              <span className="rounded-full bg-[var(--muted)] px-3 py-1.5 text-[11px] text-[var(--muted-foreground)]">
                你选择了：{clarificationChoice === "skip" ? "按推荐口径继续" : clarificationChoice}
              </span>
            </div>
            <ResultCard
              currentMonth
              degraded={clarificationChoice === "skip"}
              metric={clarificationChoice === "skip" ? "成交金额" : clarificationChoice}
              onPrompt={startRun}
            />
          </>
        );
      case "approval":
        if (approvalDecision === "pending")
          return (
            <ApprovalCard
              onApprove={() => handleApproval("approved")}
              onReject={() => handleApproval("rejected")}
            />
          );
        return approvalDecision === "approved" ? <CustomerResultCard /> : <ApprovalRejectedCard />;
      case "composite":
        return <CompositeResultCard />;
      case "discovery":
        return <DiscoveryCard onPrompt={startRun} />;
      case "out_of_capability":
        return <OutOfCapabilityCard onPrompt={startRun} />;
      default:
        return <ResultCard onPrompt={startRun} />;
    }
  };

  return (
    <div className="flex h-full min-h-0 bg-[var(--background)] text-[var(--foreground)]">
      <ConversationSidebar activeId={activeId} onNew={startNew} onSelect={selectSession} />

      <main className="flex min-w-0 flex-1 flex-col bg-[var(--muted)]">
        <header className="flex h-[68px] shrink-0 items-center gap-3 border-b border-[var(--border)] bg-[var(--card)] px-6 max-md:px-4">
          <div className="flex size-8 items-center justify-center rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] md:hidden">
            <Sparkles size={15} aria-hidden="true" />
          </div>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h1 className="truncate text-sm font-semibold">
                {isNew ? "新的问数" : session?.title}
              </h1>
              {!isNew && phase === "ready" && !waitingForApproval && !waitingForClarification ? (
                <Badge tone="neutral">已保存</Badge>
              ) : null}
            </div>
            <p className="mt-0.5 text-[11px] text-[var(--muted-foreground)]">
              {isNew ? "从一个业务问题开始" : "销售数据域 · 当前会话"}
            </p>
          </div>
          <div className="ml-auto flex items-center gap-3">
            <span className="hidden items-center gap-1.5 text-[11px] text-[var(--muted-foreground)] lg:flex">
              <span className="size-1.5 rounded-full bg-[var(--success)]" />
              数据更新至 2026-11-10
            </span>
            <Badge tone="info" className="max-sm:hidden">
              产品原型 · 演示数据
            </Badge>
            <NavLink
              to="/ask"
              title="打开现有开发者控制台"
              className="flex size-8 items-center justify-center rounded-[var(--radius)] text-[var(--muted-foreground)] outline-none hover:bg-[var(--muted)] hover:text-[var(--foreground)] focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            >
              <Code2 size={16} aria-hidden="true" />
              <span className="sr-only">开发者视图</span>
            </NavLink>
          </div>
        </header>

        <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto">
          {isNew && phase === "idle" && !question ? (
            <div className="mx-auto flex min-h-full w-full max-w-[900px] flex-col items-center justify-center px-8 py-10 text-center">
              <span className="flex size-12 items-center justify-center rounded-2xl bg-[var(--primary-subtle)] text-[var(--primary)]">
                <Sparkles size={22} aria-hidden="true" />
              </span>
              <h2 className="mt-5 text-[26px] font-semibold tracking-tight">晚上好，林晓</h2>
              <p className="mt-2 text-sm text-[var(--muted-foreground)]">想了解什么业务数据？</p>
              <div className="mt-8 grid w-full grid-cols-3 gap-3 max-lg:grid-cols-1">
                {EXAMPLE_PROMPTS.map((example, index) => {
                  const Icon = EXAMPLE_ICONS[index];
                  return (
                    <button
                      key={example.title}
                      type="button"
                      onClick={() => startRun(example.prompt, example.scenario)}
                      className="group rounded-2xl border border-[var(--border)] bg-[var(--card)] p-4 text-left shadow-sm outline-none transition-colors hover:border-[var(--primary)] focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
                    >
                      <div className="flex items-center justify-between">
                        <Icon size={17} className="text-[var(--primary)]" aria-hidden="true" />
                        <ArrowUpRight
                          size={15}
                          className="text-[var(--muted-foreground)] transition-colors group-hover:text-[var(--primary)]"
                          aria-hidden="true"
                        />
                      </div>
                      <div className="mt-4 text-sm font-medium">{example.title}</div>
                      <p className="mt-1.5 line-clamp-2 min-h-10 text-xs leading-5 text-[var(--muted-foreground)]">
                        {example.prompt}
                      </p>
                      <div className="mt-3 text-[11px] text-[var(--muted-foreground)]">
                        {example.description}
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="mx-auto w-full max-w-[960px] px-6 py-7 max-md:px-4">
              <div className="mb-6 flex justify-end pl-12">
                <div className="max-w-[78%] rounded-2xl rounded-br-md bg-[var(--primary)] px-4 py-3 text-sm leading-6 text-[var(--primary-foreground)] shadow-sm">
                  {displayedQuestion}
                </div>
              </div>
              <div className="flex items-start gap-3">
                <AssistantMark />
                <div className="min-w-0 flex-1 pt-0.5">{renderResult()}</div>
              </div>
            </div>
          )}
        </div>

        <footer className="shrink-0 border-t border-[var(--border)] bg-[var(--card)] px-6 pb-3 pt-3 max-md:px-4">
          <ChatComposer
            draft={draft}
            disabled={composerDisabled}
            running={phase === "running"}
            following={following}
            placeholder={
              waitingForClarification
                ? "请先在上方选择指标口径"
                : waitingForApproval
                  ? "请先完成上方的查询审批"
                  : isNew
                    ? "输入你的第一个数据问题…"
                    : "继续追问，或换一个数据问题…"
            }
            onDraftChange={setDraft}
            onSend={() => startRun(draft.trim())}
            onToggleFollowing={() => setFollowing((value) => !value)}
          />
        </footer>
      </main>
    </div>
  );
}
