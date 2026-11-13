import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@ontos/ui";

import { useAskWorkbenchStore } from "@/app/ask/stores/ask-workbench-store";

/**
 * 数据溯源抽屉（W4 填充内容）：W1 先落骨架与开合状态。
 * 内容规划：DSL 业务树 + 关系路径（始终可见）；编译 SQL（CodeMirror 6，DEV/角色闸门）。
 * 默认不展示任何物理名——这是默认态，不是降级。
 */
export function TraceDrawer() {
  const drawerOpen = useAskWorkbenchStore((state) => state.drawerOpen);
  const traceRequestId = useAskWorkbenchStore((state) => state.traceRequestId);
  const setDrawerOpen = useAskWorkbenchStore((state) => state.setDrawerOpen);

  return (
    <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
      <SheetContent side="right" className="w-[420px] sm:max-w-[480px]">
        <SheetHeader>
          <SheetTitle>数据溯源</SheetTitle>
          <SheetDescription>
            本轮查询的口径构成与编译路径；内容来自服务端编译产物，只读。
          </SheetDescription>
        </SheetHeader>
        <div className="space-y-3 px-4">
          {traceRequestId ? (
            <p className="font-mono text-[11px] text-muted-foreground">
              requestId {traceRequestId}
            </p>
          ) : (
            <p className="text-[11px] text-muted-foreground">当前没有可溯源的查询。</p>
          )}
          <p className="rounded-lg border border-dashed border-border px-3 py-4 text-[11px] leading-relaxed text-muted-foreground">
            DSL 业务树与关系路径视图将在 W4 接入（数据源：执行计划回放端点）；编译 SQL
            将按角色闸门展示。
          </p>
        </div>
      </SheetContent>
    </Sheet>
  );
}
