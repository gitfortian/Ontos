import { PageHeader } from "@ontos/ui";

import { ChatPanel } from "./chat-panel";

export function AskPage() {
  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="智能问数"
        description="用自然语言问业务数据；系统按已发布的语义资产编译 DSL，并把口径、路径与执行结果一并返回"
      />
      <div className="mt-4 max-w-4xl">
        <ChatPanel />
      </div>
    </div>
  );
}
