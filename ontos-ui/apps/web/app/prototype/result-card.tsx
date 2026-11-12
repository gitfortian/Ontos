import { Badge, Button, cn } from "@ontos/ui";
import {
  ArrowDownRight,
  ArrowUpRight,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Info,
  ShieldCheck,
} from "lucide-react";
import { useState } from "react";

import { REGION_RESULTS } from "./data";
import type { MetricName } from "./types";

const numberFormatter = new Intl.NumberFormat("zh-CN");
const CUSTOMER_COUNTS = [624, 488, 410, 256, 203] as const;
const METRIC_CONFIG = {
  成交金额: { total: "¥1,284.62", unit: "万", growth: "8.4%", share: "36.8%", chartUnit: "万元" },
  订单量: { total: "11,014", unit: "单", growth: "6.9%", share: "34.7%", chartUnit: "单" },
  客户数: { total: "1,981", unit: "家", growth: "5.2%", share: "31.5%", chartUnit: "家" },
} as const;

interface ResultCardProps {
  currentMonth?: boolean;
  degraded?: boolean;
  metric?: MetricName;
  onPrompt: (prompt: string) => void;
}

export function ResultCard({
  currentMonth = false,
  degraded = false,
  metric = "成交金额",
  onPrompt,
}: ResultCardProps) {
  const [showTable, setShowTable] = useState(true);
  const config = METRIC_CONFIG[metric];
  const values = REGION_RESULTS.map((item, index) =>
    metric === "成交金额"
      ? item.amount
      : metric === "订单量"
        ? item.orders
        : CUSTOMER_COUNTS[index],
  );
  const maxValue = Math.max(...values);
  const formatChartValue = (value: number) =>
    metric === "成交金额" ? (value / 10000).toFixed(1) : numberFormatter.format(value);

  return (
    <section
      className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--card)] shadow-sm"
      aria-label="查询结果"
    >
      <div className="flex items-start justify-between gap-4 px-5 pb-4 pt-5">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-[15px] font-semibold">
              2026 年 {currentMonth ? "11" : "10"} 月各地区{metric}
              {currentMonth ? "（截至 11 月 10 日）" : ""}
            </h2>
            <Badge tone={degraded ? "warning" : "success"}>
              {degraded ? (
                "默认口径"
              ) : (
                <>
                  <CheckCircle2 size={11} className="mr-1" aria-hidden="true" />
                  已完成
                </>
              )}
            </Badge>
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[var(--muted-foreground)]">
            <span>{currentMonth ? "2026-11-01 至 2026-11-10" : "2026-10-01 至 2026-10-31"}</span>
            <span aria-hidden="true">·</span>
            <span>按地区汇总</span>
            <span aria-hidden="true">·</span>
            <span>金额单位：人民币</span>
          </div>
        </div>
        <span className="shrink-0 text-[11px] text-[var(--muted-foreground)]">5 个地区</span>
      </div>

      {degraded ? (
        <div className="mx-5 mb-4 flex items-start gap-2 rounded-[var(--radius)] bg-[var(--muted)] px-3 py-2.5 text-xs text-[var(--muted-foreground)]">
          <Info size={14} className="mt-0.5 shrink-0" aria-hidden="true" />
          结果来自确定性检索（未走大模型），本次按“{metric}”口径展示。
        </div>
      ) : null}

      <div className="mx-5 grid grid-cols-[minmax(150px,0.8fr)_minmax(300px,1.6fr)] gap-7 rounded-xl bg-[var(--muted)] px-5 py-4 max-xl:grid-cols-1 max-xl:gap-5">
        <div className="flex flex-col justify-center border-r border-[var(--border)] max-xl:border-b max-xl:border-r-0 max-xl:pb-4">
          <span className="text-xs text-[var(--muted-foreground)]">全国{metric}</span>
          <div className="mt-1.5 text-[28px] font-semibold tracking-tight">
            {config.total}
            <span className="ml-1 text-sm font-normal">{config.unit}</span>
          </div>
          <div className="mt-2 flex items-center gap-1.5 text-xs">
            <span className="flex items-center gap-0.5 font-medium text-[var(--success)]">
              <ArrowUpRight size={14} aria-hidden="true" />
              {config.growth}
            </span>
            <span className="text-[var(--muted-foreground)]">
              {currentMonth ? "较 10 月同期" : "较 9 月"}
            </span>
          </div>
          <p className="mt-3 text-xs leading-5 text-[var(--muted-foreground)]">
            华东贡献最高，占全国{metric}的 {config.share}。
          </p>
        </div>

        <figure className="min-w-0" aria-label="各地区成交金额横向柱状图">
          <figcaption className="mb-3 flex items-center justify-between text-[11px] text-[var(--muted-foreground)]">
            <span>地区{metric}</span>
            <span>单位：{config.chartUnit}</span>
          </figcaption>
          <div className="space-y-2.5">
            {REGION_RESULTS.map((item, index) => (
              <div
                key={item.region}
                className="grid grid-cols-[32px_minmax(80px,1fr)_62px] items-center gap-2.5 text-xs"
              >
                <span>{item.region}</span>
                <div className="h-2 overflow-hidden rounded-full bg-[var(--border)]">
                  <div
                    className="h-full rounded-full bg-[var(--primary)]"
                    style={{ width: `${(values[index] / maxValue) * 100}%` }}
                  />
                </div>
                <span className="text-right font-medium tabular-nums">
                  {formatChartValue(values[index])}
                </span>
              </div>
            ))}
          </div>
        </figure>
      </div>

      <div className="px-5 pb-2 pt-4">
        <button
          type="button"
          onClick={() => setShowTable((visible) => !visible)}
          aria-expanded={showTable}
          className="flex w-full items-center justify-between rounded-[var(--radius)] py-1 text-left text-xs font-medium outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
        >
          <span>地区明细</span>
          <span className="flex items-center gap-1 text-[var(--muted-foreground)]">
            {showTable ? "收起" : "展开"}
            {showTable ? (
              <ChevronUp size={14} aria-hidden="true" />
            ) : (
              <ChevronDown size={14} aria-hidden="true" />
            )}
          </span>
        </button>
        {showTable ? (
          <div className="mt-2 overflow-x-auto">
            <table className="w-full min-w-[640px] border-collapse text-xs">
              <thead>
                <tr className="border-b border-[var(--border)] text-left text-[var(--muted-foreground)]">
                  <th className="px-2 py-2.5 font-medium">地区</th>
                  <th className="px-2 py-2.5 text-right font-medium">客户数</th>
                  <th className="px-2 py-2.5 text-right font-medium">订单量</th>
                  <th className="px-2 py-2.5 text-right font-medium">成交金额（元）</th>
                  <th className="px-2 py-2.5 text-right font-medium">环比</th>
                </tr>
              </thead>
              <tbody>
                {REGION_RESULTS.map((item, index) => (
                  <tr key={item.region} className="border-b border-[var(--border)] last:border-0">
                    <td className="px-2 py-3 font-medium">{item.region}</td>
                    <td className="px-2 py-3 text-right tabular-nums">
                      {numberFormatter.format(CUSTOMER_COUNTS[index])}
                    </td>
                    <td className="px-2 py-3 text-right tabular-nums">
                      {numberFormatter.format(item.orders)}
                    </td>
                    <td className="px-2 py-3 text-right tabular-nums">
                      {numberFormatter.format(item.amount)}
                    </td>
                    <td
                      className={cn(
                        "px-2 py-3 text-right font-medium tabular-nums",
                        item.mom >= 0 ? "text-[var(--success)]" : "text-[var(--destructive)]",
                      )}
                    >
                      <span className="inline-flex items-center justify-end gap-0.5">
                        {item.mom >= 0 ? (
                          <ArrowUpRight size={12} aria-hidden="true" />
                        ) : (
                          <ArrowDownRight size={12} aria-hidden="true" />
                        )}
                        {Math.abs(item.mom).toFixed(1)}%
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-[var(--border)] px-5 py-3">
        <span className="mr-1 text-[11px] text-[var(--muted-foreground)]">继续追问</span>
        {["那华南呢？", "按周看趋势", "对比去年同期"].map((prompt) => (
          <Button
            key={prompt}
            variant="ghost"
            size="small"
            className="h-7 rounded-full bg-[var(--muted)] px-2.5 text-[11px]"
            onClick={() => onPrompt(prompt)}
          >
            {prompt}
          </Button>
        ))}
      </div>
      <div className="flex items-center gap-1.5 border-t border-[var(--border)] bg-[var(--muted)] px-5 py-2.5 text-[11px] text-[var(--muted-foreground)]">
        <Info size={12} aria-hidden="true" />
        口径：已支付订单，剔除退款；金额含税
        <span className="ml-auto">数据来源：销售数据域</span>
      </div>
    </section>
  );
}

const CUSTOMER_ROWS = [
  ["云杉科技", "138****2061", "SO-202611-0842", "¥86,400", "2026-11-10"],
  ["星河贸易", "186****7315", "SO-202611-0819", "¥62,800", "2026-11-09"],
  ["远望实业", "135****4928", "SO-202611-0763", "¥48,600", "2026-11-08"],
  ["启辰零售", "159****3056", "SO-202611-0697", "¥35,200", "2026-11-06"],
] as const;

export function CustomerResultCard() {
  return (
    <section
      className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--card)] shadow-sm"
      aria-label="审批后的客户订单结果"
    >
      <div className="flex items-center justify-between px-5 py-4">
        <div>
          <h2 className="text-[15px] font-semibold">高价值客户最近订单</h2>
          <p className="mt-1 text-xs text-[var(--muted-foreground)]">
            仅展示当前账号获授权的数据范围
          </p>
        </div>
        <Badge tone="success">
          <ShieldCheck size={11} className="mr-1" aria-hidden="true" />
          审批已通过
        </Badge>
      </div>
      <div className="overflow-x-auto px-5 pb-4">
        <table className="w-full min-w-[650px] border-collapse text-xs">
          <thead>
            <tr className="border-y border-[var(--border)] bg-[var(--muted)] text-left text-[var(--muted-foreground)]">
              {["客户名称", "联系电话", "最近订单", "订单金额", "下单日期"].map((label) => (
                <th key={label} className="px-3 py-2.5 font-medium">
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {CUSTOMER_ROWS.map((row) => (
              <tr key={row[2]} className="border-b border-[var(--border)] last:border-0">
                {row.map((cell) => (
                  <td key={cell} className="px-3 py-3 tabular-nums">
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="border-t border-[var(--border)] bg-[var(--muted)] px-5 py-2.5 text-[11px] text-[var(--muted-foreground)]">
        共 4 条演示记录 · 联系电话已脱敏
      </div>
    </section>
  );
}
