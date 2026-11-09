import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  Card,
  CardHeader,
  Field,
  FieldLabel,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Table,
} from "@ontos/ui";

import { listSkills } from "@/service/query";
import { executeSkill } from "@/service/query";
import type { ExecuteVO } from "@/service/query";
import { formatCellValue } from "@/utils/cell-format";
import { failureMessage } from "@/utils/error-message";

const HORIZONTAL_FIELD_CLASS = "grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2";

const splitList = (value: string): string[] =>
  value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);

/** 批量逐行可用性徽标：缺失行与「实体不存在」同形，可用性必须显式呈现。 */
const availabilityTone = (available: boolean) => (available ? "success" : "neutral");

export function SkillPanel() {
  const skillsQuery = useQuery({
    queryKey: ["query-skills"],
    queryFn: ({ signal }) => listSkills(signal),
  });

  const [entityId, setEntityId] = useState("concept.customer");
  const [identityPropertyId, setIdentityPropertyId] = useState("property.customer.customer_id");
  const [identityValue, setIdentityValue] = useState("c-1001");
  const [batchIdentities, setBatchIdentities] = useState("c-1001, c-9999");
  const [attributes, setAttributes] = useState("property.customer.email, property.customer.tier");
  const [mode, setMode] = useState<"lookup" | "lookup_batch">("lookup");

  const skill = useMutation({
    mutationFn: ({ skillId, slots }: { skillId: string; slots: Record<string, unknown> }) =>
      executeSkill(skillId, slots),
  });
  const busy = skill.isPending || skillsQuery.isPending;

  const run = () => {
    const attributeList = splitList(attributes);
    const slots: Record<string, unknown> =
      mode === "lookup"
        ? {
            entityId,
            attributes: attributeList,
            identity: { propertyId: identityPropertyId, value: identityValue.trim() },
          }
        : {
            entityId,
            attributes: attributeList,
            mode: "lookup_batch",
            identityPropertyId,
            identities: splitList(batchIdentities),
          };
    skill.reset();
    skill.mutate({ skillId: "entity_lookup", slots });
  };

  const result: ExecuteVO | undefined = skill.data;
  const rows = result?.rows ?? [];
  const rowColumns = rows.length
    ? Object.keys(rows[0]).map((column) => ({
        key: column,
        title: column,
        render: (_value: unknown, record: Record<string, unknown>) =>
          formatCellValue(record[column]),
      }))
    : [];

  return (
    <Card>
      <CardHeader
        title="能力调用 · entity_lookup"
        description="槽位 → DSL → 正式执行通道；校验、授权、审计与手工 DSL 完全一致"
      />
      <div className="space-y-3 p-4">
        <div className="space-y-2.5">
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="skill-mode">调用模式</FieldLabel>
            <Select
              size="small"
              items={[
                { value: "lookup", label: "单实体（lookup）" },
                { value: "lookup_batch", label: "批量（lookup_batch）" },
              ]}
              value={mode}
              onValueChange={(value) =>
                setMode(value === "lookup_batch" ? "lookup_batch" : "lookup")
              }
            >
              <SelectTrigger variant="outlined" aria-label="调用模式">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="lookup">单实体（lookup）</SelectItem>
                <SelectItem value="lookup_batch">批量（lookup_batch）</SelectItem>
              </SelectContent>
            </Select>
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="skill-entity-id">实体 ID</FieldLabel>
            <Input
              id="skill-entity-id"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={entityId}
              onChange={(event) => setEntityId(event.target.value)}
            />
          </Field>
          <Field className={HORIZONTAL_FIELD_CLASS}>
            <FieldLabel htmlFor="skill-attributes">投影属性</FieldLabel>
            <Input
              id="skill-attributes"
              size="small"
              variant="outlined"
              className="font-mono text-xs"
              value={attributes}
              placeholder="Property ID，逗号分隔"
              onChange={(event) => setAttributes(event.target.value)}
            />
          </Field>
          {mode === "lookup" ? (
            <>
              <Field className={HORIZONTAL_FIELD_CLASS}>
                <FieldLabel htmlFor="skill-identity-prop">标识属性 ID</FieldLabel>
                <Input
                  id="skill-identity-prop"
                  size="small"
                  variant="outlined"
                  className="font-mono text-xs"
                  value={identityPropertyId}
                  onChange={(event) => setIdentityPropertyId(event.target.value)}
                />
              </Field>
              <Field className={HORIZONTAL_FIELD_CLASS}>
                <FieldLabel htmlFor="skill-identity-value">标识值</FieldLabel>
                <Input
                  id="skill-identity-value"
                  size="small"
                  variant="outlined"
                  className="font-mono text-xs"
                  value={identityValue}
                  onChange={(event) => setIdentityValue(event.target.value)}
                />
              </Field>
            </>
          ) : (
            <>
              <Field className={HORIZONTAL_FIELD_CLASS}>
                <FieldLabel htmlFor="skill-batch-prop">标识属性 ID</FieldLabel>
                <Input
                  id="skill-batch-prop"
                  size="small"
                  variant="outlined"
                  className="font-mono text-xs"
                  value={identityPropertyId}
                  onChange={(event) => setIdentityPropertyId(event.target.value)}
                />
              </Field>
              <Field className={HORIZONTAL_FIELD_CLASS}>
                <FieldLabel htmlFor="skill-batch-identities">标识值列表</FieldLabel>
                <Input
                  id="skill-batch-identities"
                  size="small"
                  variant="outlined"
                  className="font-mono text-xs"
                  value={batchIdentities}
                  placeholder="逗号分隔；缺失行按不可用返回"
                  onChange={(event) => setBatchIdentities(event.target.value)}
                />
              </Field>
            </>
          )}
        </div>

        <Button size="small" variant="primary" disabled={busy} onClick={run}>
          调用能力
        </Button>

        {skillsQuery.isError ? (
          <Alert>{failureMessage(skillsQuery.error, "能力列表加载失败")}</Alert>
        ) : null}
        {skill.isError ? <Alert>{failureMessage(skill.error, "能力调用失败")}</Alert> : null}

        {skillsQuery.data?.length ? (
          <details className="text-xs text-[var(--muted-foreground)]">
            <summary className="cursor-pointer">
              槽位契约（{skillsQuery.data.length} 个能力）
            </summary>
            <div className="mt-1 space-y-2">
              {skillsQuery.data.map((descriptor) => (
                <div key={descriptor.skillId}>
                  <div className="font-medium text-[var(--foreground)]">
                    {descriptor.name} <code className="font-mono">{descriptor.skillId}</code>
                  </div>
                  <ul className="ml-4 list-disc">
                    {descriptor.slots.map((slot) => (
                      <li key={slot.name}>
                        <code className="font-mono">{slot.name}</code>
                        {slot.required ? "（必填）" : ""} — {slot.description}
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          </details>
        ) : null}

        {result ? (
          <div className="space-y-3">
            <div className="text-xs text-[var(--muted-foreground)]">
              {result.rowCount} 行 · {result.elapsedMs} ms
              {result.cached ? " · 来自结果缓存" : ""}
            </div>
            {rows.length ? (
              <Table
                columns={rowColumns}
                dataSource={rows}
                size="medium"
                emptyText="无结果"
                pagination={false}
              />
            ) : null}
            {result.lookupRows?.length ? (
              <Table
                columns={[
                  { key: "identity", title: "标识值" },
                  {
                    key: "available",
                    title: "可用性",
                    render: (_value, record) => (
                      <Badge tone={availabilityTone(Boolean(record.available))}>
                        {record.available ? "可查看" : "不可用 / 不存在"}
                      </Badge>
                    ),
                  },
                  {
                    key: "values",
                    title: "属性值",
                    render: (_value, record) => (
                      <code className="font-mono text-[11px]">
                        {formatCellValue(record.values)}
                      </code>
                    ),
                  },
                ]}
                dataSource={result.lookupRows}
                size="medium"
                emptyText="无逐行结果"
                pagination={false}
              />
            ) : null}
          </div>
        ) : null}
      </div>
    </Card>
  );
}
