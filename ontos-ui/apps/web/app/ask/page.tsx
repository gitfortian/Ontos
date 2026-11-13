import { TooltipProvider } from "@ontos/ui";

import { ChatPanel } from "./chat-panel";
import { ContextChips } from "./layout/context-chips";
import { SessionsSidebar } from "./layout/sessions-sidebar";
import { TraceDrawer } from "./layout/trace-drawer";

/**
 * 问数工作台（三栏）：
 * 左 = 会话与场景；中 = 对话流（顶部上下文锚点）；右 = 溯源抽屉（Sheet，按需滑出）。
 * 会话列表等 REST 数据归 TanStack Query，流式运行态与 UI 态归 Zustand（见 store 注释）。
 */
export function AskPage() {
  return (
    <TooltipProvider delayDuration={200}>
      <div className="flex h-full min-h-0">
        <SessionsSidebar />
        <main className="flex min-h-0 min-w-0 flex-1 flex-col gap-3 px-5 py-4">
          <div className="flex items-baseline justify-between gap-3">
            <div>
              <h1 className="text-[15px] font-medium">智能问数</h1>
              <p className="mt-0.5 text-[11px] text-muted-foreground">
                按已发布的语义资产编译查询；口径、执行过程与结果一并返回
              </p>
            </div>
          </div>
          <ContextChips />
          <ChatPanel />
        </main>
        <TraceDrawer />
      </div>
    </TooltipProvider>
  );
}
