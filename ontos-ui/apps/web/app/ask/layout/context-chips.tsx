import { X } from "lucide-react";

import { UiBadge } from "@ontos/ui";

import { useAskWorkbenchStore } from "@/app/ask/stores/ask-workbench-store";

const KIND_LABEL: Record<string, string> = {
  metric: "指标",
  dimension: "维度",
  time: "时间",
  mode: "模式",
  scenario: "场景",
};

/**
 * 上下文锚点栏：展示最近一轮实际生效的口径（服务端 contextSummary 下发，业务名）。
 * 点击 × 只移除展示锚点并聚焦输入框——移除语义 = 「换个口径重问」，由用户重新表述；
 * 直接改写服务端会话状态是被禁止的（上下文由认证系统签发，客户端不得自由修改）。
 */
export function ContextChips() {
  const chips = useAskWorkbenchStore((state) => state.chips);
  const removeChip = useAskWorkbenchStore((state) => state.removeChip);
  if (chips.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-wrap items-center gap-1.5 px-0.5">
      {chips.map((chip) => (
        <UiBadge
          key={`${chip.kind}:${chip.label}`}
          className="gap-1 rounded-full border px-2 py-0.5 text-[11px]"
        >
          <span className="text-muted-foreground">{KIND_LABEL[chip.kind] ?? "口径"}:</span>
          {chip.label}
          <button
            type="button"
            aria-label={`移除${KIND_LABEL[chip.kind] ?? ""}锚点 ${chip.label}`}
            className="ml-0.5 rounded-sm hover:text-destructive"
            onClick={() => removeChip(chip)}
          >
            <X size={11} aria-hidden="true" />
          </button>
        </UiBadge>
      ))}
      <span className="text-[10px] text-muted-foreground">
        移除锚点 = 换个口径重新提问（会话口径由服务端管理）
      </span>
    </div>
  );
}
