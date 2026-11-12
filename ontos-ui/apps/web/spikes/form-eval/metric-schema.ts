import { z } from "zod";

/**
 * Spike 领域模型：三层化之后的「原子指标」草稿。
 * 物理绑定只存在于 ontology.property，指标只引用 objectId / propertyId。
 */

export const OBJECT_OPTIONS = [
  { value: "concept.order", label: "订单" },
  { value: "concept.customer", label: "客户" },
] as const;

export const PROPERTY_OPTIONS: Record<string, ReadonlyArray<{ value: string; label: string }>> = {
  "concept.order": [
    { value: "property.order.amount", label: "订单金额" },
    { value: "property.order.quantity", label: "订单数量" },
  ],
  "concept.customer": [{ value: "property.customer.tier", label: "客户等级" }],
};

export const DIMENSION_OPTIONS = [
  { value: "dim.region", label: "区域" },
  { value: "dim.channel", label: "渠道" },
  { value: "dim.month", label: "月份" },
] as const;

export const AGGREGATION_OPTIONS = ["SUM", "COUNT", "AVG", "COUNT_DISTINCT"] as const;

export const VARIANT_OPERATORS = ["EQ", "IN"] as const;

export const metricDraftSchema = z
  .object({
    id: z.string().min(1, "指标 ID 必填"),
    name: z.string().min(1, "指标名称必填"),
    objectId: z.string().min(1, "业务对象必选"),
    // 对象级计数（COUNT）允许 propertyId 为空，这是校验器里的受控例外。
    propertyId: z.string(),
    aggregation: z.enum(AGGREGATION_OPTIONS),
    allowedDimensions: z.array(z.string()).min(1, "至少选择一个允许维度"),
    variants: z.array(
      z.object({
        field: z.string().min(1, "变体字段必填"),
        op: z.enum(VARIANT_OPERATORS),
        value: z.string().min(1, "变体值必填"),
      }),
    ),
  })
  .superRefine((value, ctx) => {
    if (value.aggregation !== "COUNT" && !value.propertyId) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["propertyId"],
        message: "非 COUNT 聚合必须选择属性",
      });
    }
    const candidates = PROPERTY_OPTIONS[value.objectId] ?? [];
    if (value.propertyId && !candidates.some((item) => item.value === value.propertyId)) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["propertyId"],
        message: "属性不属于所选业务对象",
      });
    }
  });

export type MetricDraft = z.infer<typeof metricDraftSchema>;

export interface FormHarnessProps {
  initial: MetricDraft;
  onSave: (draft: MetricDraft) => void | Promise<void>;
}

export const EMPTY_DRAFT: MetricDraft = {
  id: "",
  name: "",
  objectId: "concept.order",
  propertyId: "",
  aggregation: "SUM",
  allowedDimensions: [],
  variants: [],
};

export const VALID_DRAFT: MetricDraft = {
  id: "metric.order_amount",
  name: "订单金额",
  objectId: "concept.order",
  propertyId: "property.order.amount",
  aggregation: "SUM",
  allowedDimensions: ["dim.region"],
  variants: [],
};

export function propertiesOf(objectId: string): ReadonlyArray<{ value: string; label: string }> {
  return PROPERTY_OPTIONS[objectId] ?? [];
}
