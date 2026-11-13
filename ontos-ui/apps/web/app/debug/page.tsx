import { PageHeader } from "@ontos/ui";

import { AguiPanel } from "./agui-panel";
import { DslPanel } from "./dsl-panel";
import { SkillPanel } from "./skill-panel";

/**
 * 链路调试台：只放排查用的三块（DSL 编译/执行、能力调用、AG-UI 事件流）。
 * 产品主流程不暴露这些面板，避免把调试界面当成功能界面。
 */
export function DebugPage() {
  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="链路调试"
        description="仅供排查：直接构造 DSL、观察编译与受限执行，检查能力调用与 AG-UI 事件流"
      />
      <div className="mt-4 grid grid-cols-1 items-start gap-4 xl:grid-cols-2">
        <div className="space-y-4">
          <AguiPanel />
          <SkillPanel />
        </div>
        <DslPanel />
      </div>
    </div>
  );
}
