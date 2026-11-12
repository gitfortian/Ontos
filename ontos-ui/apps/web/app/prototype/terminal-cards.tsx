import { Badge, Button, Spinner, Textarea } from "@ontos/ui";
import {
  ArrowRight,
  BarChart3,
  Check,
  CheckCircle2,
  CircleHelp,
  Compass,
  Database,
  LockKeyhole,
  MessageSquareText,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Table2,
  Users,
  XCircle,
} from "lucide-react";
import { useState } from "react";

import { REGION_RESULTS } from "./data";
import type { ClarificationChoice, MetricName } from "./types";

interface ClarificationCardProps {
  onSelect: (choice: ClarificationChoice) => void;
}

export function ClarificationCard({ onSelect }: ClarificationCardProps) {
  const options: ReadonlyArray<{ label: MetricName; hint: string; recommended?: boolean }> = [
    { label: "成交金额", hint: "已支付订单的含税金额", recommended: true },
    { label: "订单量", hint: "已支付订单的数量" },
    { label: "客户数", hint: "发生支付行为的去重客户数" },
  ];

  return (
    <section
      className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-5 shadow-sm"
      aria-label="需要澄清的问题"
    >
      <div className="flex items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-[var(--primary-subtle)] text-[var(--primary)]">
          <CircleHelp size={18} aria-hidden="true" />
        </span>
        <div>
          <h2 className="text-[15px] font-semibold">你想按哪个口径看销售？</h2>
          <p className="mt-1.5 text-xs leading-5 text-[var(--muted-foreground)]">
            “销售”可以对应不同指标。选择后，我会按同一口径完成本次分析。
          </p>
        </div>
      </div>
      <div className="mt-4 grid grid-cols-3 gap-2.5 max-xl:grid-cols-1">
        {options.map((option) => (
          <button
            key={option.label}
            type="button"
            onClick={() => onSelect(option.label)}
            className="group relative rounded-xl border border-[var(--border)] bg-[var(--card)] p-3.5 text-left outline-none transition-colors hover:border-[var(--primary)] hover:bg-[var(--primary-subtle)] focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              {option.label}
              {option.recommended ? <Badge tone="info">推荐</Badge> : null}
            </span>
            <span className="mt-1.5 block text-[11px] leading-4 text-[var(--muted-foreground)]">
              {option.hint}
            </span>
            <ArrowRight
              size={14}
              className="absolute right-3 top-3.5 text-[var(--primary)] opacity-0 transition-opacity group-hover:opacity-100"
              aria-hidden="true"
            />
          </button>
        ))}
      </div>
      <div className="mt-4 flex items-center justify-between border-t border-[var(--border)] pt-3">
        <span className="text-[11px] text-[var(--muted-foreground)]">
          本次时间范围：2026 年 11 月
        </span>
        <Button variant="ghost" size="small" onClick={() => onSelect("skip")}>
          暂不选择，按推荐口径继续
        </Button>
      </div>
    </section>
  );
}

interface ApprovalCardProps {
  onApprove: () => void;
  onReject: () => void;
}

export function ApprovalCard({ onApprove, onReject }: ApprovalCardProps) {
  const [remark, setRemark] = useState("");

  return (
    <section
      className="overflow-hidden rounded-2xl border border-[var(--warning)] bg-[var(--card)] shadow-sm"
      aria-label="敏感查询审批"
    >
      <div className="flex items-start gap-3 p-5">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-[color-mix(in_srgb,var(--warning)_14%,transparent)] text-[var(--warning)]">
          <LockKeyhole size={19} aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-[15px] font-semibold">这次查询需要你确认</h2>
            <Badge tone="warning">等待审批</Badge>
          </div>
          <p className="mt-2 text-xs leading-5 text-[var(--muted-foreground)]">
            结果包含客户名称和联系电话。请确认本次查询用于已授权的客户经营工作。
          </p>
        </div>
      </div>
      <div className="mx-5 grid grid-cols-3 gap-3 rounded-xl bg-[var(--muted)] p-3.5 text-xs max-xl:grid-cols-1">
        <div>
          <span className="block text-[var(--muted-foreground)]">数据范围</span>
          <span className="mt-1 block font-medium">高价值客户 · 最近订单</span>
        </div>
        <div>
          <span className="block text-[var(--muted-foreground)]">敏感字段</span>
          <span className="mt-1 block font-medium">联系电话（脱敏展示）</span>
        </div>
        <div>
          <span className="block text-[var(--muted-foreground)]">预计结果</span>
          <span className="mt-1 block font-medium">不超过 50 条</span>
        </div>
      </div>
      <div className="px-5 pt-4">
        <label htmlFor="approval-remark" className="text-xs font-medium">
          审批备注 <span className="font-normal text-[var(--muted-foreground)]">（可选）</span>
        </label>
        <Textarea
          id="approval-remark"
          value={remark}
          onValueChange={setRemark}
          rows={2}
          placeholder="补充本次查询用途"
          className="mt-2 resize-none"
        />
      </div>
      <div className="flex items-center justify-between gap-3 p-5">
        <span className="flex items-center gap-1.5 text-[11px] text-[var(--muted-foreground)]">
          <ShieldCheck size={13} aria-hidden="true" />
          操作将留痕，数据权限不变
        </span>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={onReject}>
            拒绝查询
          </Button>
          <Button variant="primary" onClick={onApprove}>
            <Check size={15} aria-hidden="true" />
            批准并查看
          </Button>
        </div>
      </div>
    </section>
  );
}

export function ApprovalRejectedCard() {
  return (
    <section
      className="flex items-start gap-3 rounded-2xl border border-[var(--border)] bg-[var(--card)] p-5"
      aria-label="查询已取消"
    >
      <XCircle
        size={20}
        className="mt-0.5 shrink-0 text-[var(--muted-foreground)]"
        aria-hidden="true"
      />
      <div>
        <h2 className="text-sm font-semibold">已取消本次查询</h2>
        <p className="mt-1.5 text-xs leading-5 text-[var(--muted-foreground)]">
          没有返回客户明细。你可以改写问题，或查询不含敏感字段的汇总数据。
        </p>
      </div>
    </section>
  );
}

export function CompositeResultCard() {
  const customerCounts = [624, 488, 410, 256, 203];
  const maxCount = Math.max(...customerCounts);

  return (
    <section className="space-y-3" aria-label="复合查询结果">
      <div className="flex items-center justify-between px-1">
        <div>
          <h2 className="text-[15px] font-semibold">区域经营对比</h2>
          <p className="mt-1 text-xs text-[var(--muted-foreground)]">
            3 项请求已分别处理，成功结果不受失败项影响
          </p>
        </div>
        <Badge tone="warning">2 项完成 · 1 项未执行</Badge>
      </div>
      <div className="grid grid-cols-2 gap-3 max-xl:grid-cols-1">
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-4 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-2 text-sm font-medium">
              <Users size={16} className="text-[var(--primary)]" aria-hidden="true" />
              各区域客户数
            </span>
            <CheckCircle2 size={15} className="text-[var(--success)]" aria-label="已完成" />
          </div>
          <div className="mt-4 space-y-3">
            {REGION_RESULTS.map((item, index) => (
              <div
                key={item.region}
                className="grid grid-cols-[34px_1fr_34px] items-center gap-2 text-xs"
              >
                <span>{item.region}</span>
                <div className="h-1.5 rounded-full bg-[var(--muted)]">
                  <div
                    className="h-full rounded-full bg-[var(--primary)]"
                    style={{ width: `${(customerCounts[index] / maxCount) * 100}%` }}
                  />
                </div>
                <span className="text-right tabular-nums">{customerCounts[index]}</span>
              </div>
            ))}
          </div>
          <p className="mt-4 border-t border-[var(--border)] pt-3 text-[11px] text-[var(--muted-foreground)]">
            去重客户数 · 2026 年 10 月
          </p>
        </section>
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-4 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-2 text-sm font-medium">
              <BarChart3 size={16} className="text-[var(--primary)]" aria-hidden="true" />
              各区域订单量
            </span>
            <CheckCircle2 size={15} className="text-[var(--success)]" aria-label="已完成" />
          </div>
          <div className="mt-3 divide-y divide-[var(--border)]">
            {REGION_RESULTS.map((item) => (
              <div key={item.region} className="flex items-center justify-between py-[9px] text-xs">
                <span>{item.region}</span>
                <span className="font-medium tabular-nums">
                  {item.orders.toLocaleString("zh-CN")} 单
                </span>
              </div>
            ))}
          </div>
          <p className="mt-2 border-t border-[var(--border)] pt-3 text-[11px] text-[var(--muted-foreground)]">
            已支付订单 · 2026 年 10 月
          </p>
        </section>
      </div>
      <section className="flex items-start gap-3 rounded-2xl border border-[var(--warning)] bg-[color-mix(in_srgb,var(--warning)_7%,var(--card))] p-4">
        <ShieldAlert
          size={19}
          className="mt-0.5 shrink-0 text-[var(--warning)]"
          aria-hidden="true"
        />
        <div className="flex-1">
          <h3 className="text-sm font-semibold">客户联系方式未查询</h3>
          <p className="mt-1 text-xs leading-5 text-[var(--muted-foreground)]">
            当前账号没有明文联系方式权限。上方已完成的汇总结果可正常查看。
          </p>
        </div>
      </section>
    </section>
  );
}

interface DiscoveryCardProps {
  onPrompt: (prompt: string) => void;
}

export function DiscoveryCard({ onPrompt }: DiscoveryCardProps) {
  const groups = [
    { title: "经营指标", icon: BarChart3, items: ["成交金额", "订单量", "客户数", "退款金额"] },
    { title: "分析维度", icon: Table2, items: ["地区", "商品品类", "客户分层", "月份"] },
    { title: "常用分析", icon: Compass, items: ["地区对比", "月度趋势", "同比与环比"] },
  ];

  return (
    <section
      className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-5 shadow-sm"
      aria-label="可查询的数据能力"
    >
      <div className="flex items-center gap-3">
        <span className="flex size-9 items-center justify-center rounded-xl bg-[var(--primary-subtle)] text-[var(--primary)]">
          <Database size={17} aria-hidden="true" />
        </span>
        <div>
          <h2 className="text-[15px] font-semibold">当前可以查这些数据</h2>
          <p className="mt-1 text-xs text-[var(--muted-foreground)]">
            以下内容来自你已获授权的销售数据域
          </p>
        </div>
      </div>
      <div className="mt-5 grid grid-cols-3 gap-4 max-xl:grid-cols-1">
        {groups.map((group) => {
          const Icon = group.icon;
          return (
            <div key={group.title} className="rounded-xl bg-[var(--muted)] p-4">
              <h3 className="flex items-center gap-2 text-xs font-semibold">
                <Icon size={14} className="text-[var(--primary)]" aria-hidden="true" />
                {group.title}
              </h3>
              <div className="mt-3 flex flex-wrap gap-2">
                {group.items.map((item) => (
                  <span
                    key={item}
                    className="rounded-md border border-[var(--border)] bg-[var(--card)] px-2 py-1 text-[11px]"
                  >
                    {item}
                  </span>
                ))}
              </div>
            </div>
          );
        })}
      </div>
      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-[var(--border)] pt-4">
        <span className="mr-1 text-xs text-[var(--muted-foreground)]">试着问</span>
        {["上个月各地区成交金额", "近半年订单量趋势", "各品类的客户数"].map((prompt) => (
          <Button
            key={prompt}
            variant="secondary"
            size="small"
            className="h-8 rounded-full text-xs"
            onClick={() => onPrompt(prompt)}
          >
            {prompt}
            <ArrowRight size={12} aria-hidden="true" />
          </Button>
        ))}
      </div>
    </section>
  );
}

interface OutOfCapabilityCardProps {
  onPrompt: (prompt: string) => void;
}

export function OutOfCapabilityCard({ onPrompt }: OutOfCapabilityCardProps) {
  return (
    <section
      className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-5 shadow-sm"
      aria-label="暂不支持的能力"
    >
      <div className="flex items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-[var(--muted)] text-[var(--muted-foreground)]">
          <MessageSquareText size={17} aria-hidden="true" />
        </span>
        <div>
          <h2 className="text-[15px] font-semibold">暂时无法预测未来销量</h2>
          <p className="mt-1.5 text-xs leading-5 text-[var(--muted-foreground)]">
            当前提供的是已发生业务的查询与分析，不进行预测建模。你仍然可以查看历史趋势作为参考。
          </p>
        </div>
      </div>
      <div className="ml-12 mt-4 flex flex-wrap gap-2">
        <Button variant="secondary" size="small" onClick={() => onPrompt("查看近六个月成交趋势")}>
          查看历史成交趋势
        </Button>
        <Button variant="ghost" size="small" onClick={() => onPrompt("对比各地区历史销量")}>
          对比地区历史销量
        </Button>
      </div>
    </section>
  );
}

export function RunningCard({ following }: { following: boolean }) {
  return (
    <section
      className="rounded-2xl border border-[var(--border)] bg-[var(--card)] p-5 shadow-sm"
      aria-live="polite"
      aria-label="正在生成结果"
    >
      <div className="flex items-center gap-3">
        <span className="flex size-9 items-center justify-center rounded-xl bg-[var(--primary-subtle)] text-[var(--primary)]">
          <Spinner size="small" />
        </span>
        <div>
          <h2 className="text-sm font-semibold">
            {following ? "正在整理你的结果" : "结果仍在生成"}
          </h2>
          <p className="mt-1 text-xs text-[var(--muted-foreground)]">
            {following
              ? "已理解问题，正在匹配指标与分析维度…"
              : "已停止跟随，完成后会自动补全结果。"}
          </p>
        </div>
      </div>
      <div className="ml-12 mt-4 flex items-center gap-5 text-[11px] text-[var(--muted-foreground)]">
        <span className="flex items-center gap-1.5 text-[var(--success)]">
          <CheckCircle2 size={13} aria-hidden="true" />
          理解问题
        </span>
        <span className="flex items-center gap-1.5">
          <Sparkles size={13} aria-hidden="true" />
          匹配数据口径
        </span>
        <span>生成结果</span>
      </div>
    </section>
  );
}
