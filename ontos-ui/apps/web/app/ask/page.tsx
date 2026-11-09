import { PageHeader } from "@ontos/ui";

import { AguiPanel } from "./agui-panel";
import { DslPanel } from "./dsl-panel";
import { SkillPanel } from "./skill-panel";

export function AskPage() {
  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="智能问数"
        description="自然语言经 AG-UI 事件流进入 Agent；确定性链路只接受类型化 DSL"
      />
      <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-2">
        <div className="space-y-4">
          <AguiPanel />
          <SkillPanel />
        </div>
        <DslPanel />
      </div>
    </div>
  );
}
