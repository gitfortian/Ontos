import { z } from "zod";

/**
 * 语义资产定义表单模型（B1/P1）。
 *
 * <p>字段与后端 `SemanticManifest.Metric/Dimension` 对齐：物理绑定只存在于本体属性，
 * 表单只让用户选择 `objectId + propertyId`，不出现表名与列名。
 */

export const AGGREGATIONS = ["SUM", "COUNT", "COUNT_DISTINCT", "AVG", "MIN", "MAX"] as const;

export const ANALYSIS_MODES = [
  "QUERY",
  "LOOKUP",
  "LOOKUP_BATCH",
  "YOY_MOM",
  "FUNNEL",
  "RETENTION",
  "ATTRIBUTION",
] as const;

/** 别名在表单里用逗号分隔，减少一次「数组增删」交互。 */
export function splitAliases(input: string): string[] {
  return input
    .split(/[,，\n]/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

const baseSchema = z.object({
  id: z.string().min(1, "ID 必填"),
  name: z.string().min(1, "名称必填"),
  summary: z.string().min(1, "说明必填"),
  aliases: z.string().min(1, "至少一个别名"),
  objectId: z.string().min(1, "业务对象必选"),
  propertyId: z.string(),
  sensitive: z.boolean(),
});

export const metricFormSchema = baseSchema
  .extend({
    aggregation: z.enum(AGGREGATIONS),
    allowedModes: z.array(z.string()).min(1, "至少选择一个分析模式"),
    allowedDimensions: z.array(z.string()).min(1, "至少选择一个允许维度"),
  })
  .superRefine((value, ctx) => {
    // 对象级计数允许 propertyId 为空，与 ManifestStructureValidator R3 一致
    if (value.aggregation !== "COUNT" && !value.propertyId) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["propertyId"],
        message: "非 COUNT 聚合必须选择属性",
      });
    }
  });

export const dimensionFormSchema = baseSchema.refine((value) => Boolean(value.propertyId), {
  path: ["propertyId"],
  message: "维度必须选择属性",
});

export type MetricFormValues = z.infer<typeof metricFormSchema>;
export type DimensionFormValues = z.infer<typeof dimensionFormSchema>;

export const EMPTY_METRIC: MetricFormValues = {
  id: "",
  name: "",
  summary: "",
  aliases: "",
  objectId: "",
  propertyId: "",
  aggregation: "SUM",
  allowedModes: ["QUERY"],
  allowedDimensions: [],
  sensitive: false,
};

export const EMPTY_DIMENSION: DimensionFormValues = {
  id: "",
  name: "",
  summary: "",
  aliases: "",
  objectId: "",
  propertyId: "",
  sensitive: false,
};

export function toMetricDefinition(values: MetricFormValues): Record<string, unknown> {
  return {
    id: values.id.trim(),
    name: values.name.trim(),
    summary: values.summary.trim(),
    aliases: splitAliases(values.aliases),
    objectId: values.objectId,
    // 对象级计数不带 propertyId（后端按 null 处理），避免存一个空串进去
    ...(values.propertyId ? { propertyId: values.propertyId } : {}),
    aggregation: values.aggregation,
    allowedModes: values.allowedModes,
    allowedDimensions: values.allowedDimensions,
    sensitive: values.sensitive,
  };
}

export function toDimensionDefinition(values: DimensionFormValues): Record<string, unknown> {
  return {
    id: values.id.trim(),
    name: values.name.trim(),
    summary: values.summary.trim(),
    aliases: splitAliases(values.aliases),
    objectId: values.objectId,
    propertyId: values.propertyId,
    sensitive: values.sensitive,
  };
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function textArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string")
    : [];
}

export function metricFormValues(definition: Record<string, unknown>): MetricFormValues {
  const aggregation = text(definition.aggregation);
  return {
    id: text(definition.id),
    name: text(definition.name),
    summary: text(definition.summary),
    aliases: textArray(definition.aliases).join(", "),
    objectId: text(definition.objectId),
    propertyId: text(definition.propertyId),
    aggregation: AGGREGATIONS.includes(aggregation as (typeof AGGREGATIONS)[number])
      ? (aggregation as (typeof AGGREGATIONS)[number])
      : "SUM",
    allowedModes: textArray(definition.allowedModes),
    allowedDimensions: textArray(definition.allowedDimensions),
    sensitive: definition.sensitive === true,
  };
}

export function dimensionFormValues(definition: Record<string, unknown>): DimensionFormValues {
  return {
    id: text(definition.id),
    name: text(definition.name),
    summary: text(definition.summary),
    aliases: textArray(definition.aliases).join(", "),
    objectId: text(definition.objectId),
    propertyId: text(definition.propertyId),
    sensitive: definition.sensitive === true,
  };
}
