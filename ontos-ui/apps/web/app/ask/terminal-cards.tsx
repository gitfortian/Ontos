import type { ReactNode } from "react";

import { Button } from "@ontos/ui";

import type { AskCandidate, AskTerminalVO } from "@/service/ask";

/**
 * 五态卡片：全部按真实载荷渲染。
 *
 * <p>候选只显示业务叫法（matchedTerm），资产 ID 仅作为回传值留在事件/payload 里，
 * 不上屏（附录 A.4「ID 与文案分离」）。
 */

function CardShell({
  tone,
  label,
  children,
}: {
  tone: "warning" | "muted" | "danger" | "primary";
  label: string;
  children: ReactNode;
}) {
  const dot =
    tone === "warning"
      ? "bg-warning"
      : tone === "danger"
        ? "bg-destructive"
        : tone === "primary"
          ? "bg-primary"
          : "bg-muted-foreground";
  return (
    <section className="rounded-xl border border-border bg-card p-4">
      <div className="flex items-center gap-1.5">
        <span className={`size-1.5 rounded-full ${dot}`} />
        <span className="text-[11px] text-muted-foreground">{label}</span>
      </div>
      <div className="mt-2">{children}</div>
    </section>
  );
}

function CandidateList({
  candidates,
  onChoose,
  disabled,
}: {
  candidates: AskCandidate[];
  onChoose?: (candidateId: string, matchedTerm: string) => void;
  disabled?: boolean;
}) {
  return (
    <div className="mt-2 flex flex-col gap-1.5">
      {candidates.map((candidate) => {
        const name = candidate.label ?? candidate.matchedTerm;
        const extraTerms = (candidate.aliases ?? []).filter(
          (term) => term !== name && term !== candidate.matchedTerm,
        );
        return (
          <button
            key={candidate.assetId}
            type="button"
            disabled={disabled || !onChoose}
            onClick={() => onChoose?.(candidate.assetId, name)}
            className="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-left text-xs transition-colors hover:border-primary hover:bg-accent disabled:cursor-default disabled:hover:border-border disabled:hover:bg-transparent"
          >
            <span className="min-w-0">
              <span className="font-medium">{name}</span>
              {extraTerms.length > 0 ? (
                <span className="ml-1.5 text-[11px] text-muted-foreground">
                  也叫「{extraTerms.join("、")}」
                </span>
              ) : null}
            </span>
            <span className="ml-auto shrink-0 text-[11px] text-muted-foreground">
              {candidate.assetType === "metric"
                ? "指标"
                : candidate.assetType === "dimension"
                  ? "分析维度"
                  : candidate.assetType === "entity" || candidate.assetType === "concept"
                    ? "业务对象"
                    : candidate.assetType === "property"
                      ? "信息项"
                      : candidate.assetType === "scenario"
                        ? "业务场景"
                        : candidate.assetType === "approximation"
                          ? "近似能力"
                          : "资产"}
            </span>
          </button>
        );
      })}
    </div>
  );
}

export function ClarificationCard({
  terminal,
  onChoose,
  disabled,
  stuck,
}: {
  terminal: AskTerminalVO;
  onChoose: (candidateId: string, matchedTerm: string) => void;
  disabled: boolean;
  stuck?: boolean;
}) {
  const body = terminal.body ?? {};
  const candidates = body.candidates ?? [];
  return (
    <CardShell tone="warning" label="需要确认">
      <p className="text-[13px] leading-relaxed">
        {body.ambiguity ?? "这句话对应多种口径，选一个继续："}
      </p>
      {stuck ? (
        <p className="mt-2 rounded-md border border-warning/40 bg-warning/5 px-2 py-1.5 text-[11px] leading-relaxed">
          刚才的选择没能缩小歧义范围。可以直接说明指标再问，例如「按渠道看成交金额」。
        </p>
      ) : null}
      {candidates.length > 0 ? (
        <CandidateList candidates={candidates} onChoose={onChoose} disabled={disabled} />
      ) : (
        <p className="mt-2 text-[11px] text-muted-foreground">
          没有可用候选，请换一个更具体的说法，例如点名某个指标。
        </p>
      )}
      {body.defaultSuggestion &&
      !/^[a-z][a-z0-9_]*(\.[a-z0-9_]+)+$/.test(body.defaultSuggestion) ? (
        <p className="mt-2 text-[11px] text-muted-foreground">默认建议：{body.defaultSuggestion}</p>
      ) : null}
      {body.skippable ? (
        <p className="mt-1 text-[11px] text-muted-foreground">
          也可以不选，直接问「能查什么」看当前范围。
        </p>
      ) : null}
    </CardShell>
  );
}

export function DiscoveryCard({ terminal }: { terminal: AskTerminalVO }) {
  const body = terminal.body ?? {};
  const metrics = body.metrics ?? [];
  const dimensions = body.dimensions ?? [];
  return (
    <CardShell tone="primary" label="当前可查询的范围">
      {metrics.length === 0 && dimensions.length === 0 ? (
        <p className="text-[13px] text-muted-foreground">暂无命中的资产，请换个说法。</p>
      ) : (
        <div className="space-y-2">
          {metrics.length > 0 ? (
            <div>
              <p className="text-[11px] text-muted-foreground">指标</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                {metrics.map((metric) => (
                  <span key={metric.assetId} className="rounded-md bg-accent px-2 py-1 text-[11px]">
                    {metric.matchedTerm}
                  </span>
                ))}
              </div>
            </div>
          ) : null}
          {dimensions.length > 0 ? (
            <div>
              <p className="text-[11px] text-muted-foreground">分析维度</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                {dimensions.map((dimension) => (
                  <span
                    key={dimension.assetId}
                    className="rounded-md bg-accent px-2 py-1 text-[11px]"
                  >
                    {dimension.matchedTerm}
                  </span>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      )}
    </CardShell>
  );
}

export function OutOfCapabilityCard({ terminal }: { terminal: AskTerminalVO }) {
  return (
    <CardShell tone="danger" label="这次没能回答">
      <p className="text-[13px] leading-relaxed">
        {terminal.copy?.text ?? "这个问题超出了当前已发布的范围。"}
      </p>
      <p className="mt-2 text-[11px] text-muted-foreground">
        可以换成已发布的指标或维度再问一次；原因明细已记录在审计里。
      </p>
    </CardShell>
  );
}

export function WaitingCard({
  onOpenApprovals,
}: {
  terminal?: AskTerminalVO;
  onOpenApprovals?: () => void;
}) {
  return (
    <CardShell tone="warning" label="等待审批">
      <p className="text-[13px] leading-relaxed">
        这次查询触及敏感范围，需要审批后才会执行；决策完成后结果会出现在这条对话里。
      </p>
      <div className="mt-2 flex items-center gap-3">
        <Button type="button" variant="secondary" size="small" onClick={onOpenApprovals}>
          去审批
        </Button>
        <span className="text-[11px] text-muted-foreground">等待期间可以继续问其它问题</span>
      </div>
    </CardShell>
  );
}

export function CompositeCard({ terminal }: { terminal: AskTerminalVO }) {
  const subResults = terminal.body?.subResults ?? [];
  return (
    <CardShell tone="primary" label="复合查询">
      <p className="text-[13px]">
        本次问题被拆成 {subResults.length} 个子查询，结果按子问题分别返回。
      </p>
      <div className="mt-2 space-y-1">
        {subResults.map((result, index) => (
          <div
            key={index}
            className="flex items-center gap-2 rounded-md bg-muted px-2.5 py-1.5 text-[11px]"
          >
            <span className="font-mono text-muted-foreground">子查询 {index + 1}</span>
            <span className="ml-auto text-muted-foreground">
              {typeof result.rowCount === "number" ? `${result.rowCount} 行` : "已返回"}
            </span>
          </div>
        ))}
      </div>
    </CardShell>
  );
}
