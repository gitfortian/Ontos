import { Info } from "lucide-react";

import { Tooltip, TooltipContent, TooltipTrigger } from "@ontos/ui";

/**
 * 摘要区一句话结论：数据全部来自 buildInsights 的确定性计算（rows 实算），
 * Tooltip 明示「非 AI 推测」——这是系统的确定性卖点，不是话术。
 */
export function InsightSummary({ insights }: { insights: string[] }) {
  if (insights.length === 0) {
    return null;
  }
  return (
    <div className="flex items-start gap-1.5 rounded-lg bg-muted/60 px-3 py-2">
      <p className="min-w-0 flex-1 text-xs leading-relaxed">
        {insights.map((line, index) => (
          <span key={line} className={index > 0 ? "ml-2 text-muted-foreground" : ""}>
            {index > 0 ? "· " : ""}
            {line}
          </span>
        ))}
      </p>
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            type="button"
            aria-label="结论来源说明"
            className="mt-0.5 shrink-0 text-muted-foreground hover:text-foreground"
          >
            <Info aria-hidden="true" size={13} />
          </button>
        </TooltipTrigger>
        <TooltipContent side="top" className="max-w-56 text-[11px]">
          该结论由查询结果实时计算得出（合计 / 最高项 / 占比），非 AI 推测。
        </TooltipContent>
      </Tooltip>
    </div>
  );
}
