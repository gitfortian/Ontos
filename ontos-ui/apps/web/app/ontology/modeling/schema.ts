import { z } from "zod";

/** 本体建模表单契约（B2/P2-B）：三张草稿表单与 definition JSON 的唯一转换点。 */

export const VALUE_TYPES = [
  "string",
  "integer",
  "decimal",
  "boolean",
  "date",
  "timestamp",
] as const;
export type ValueType = (typeof VALUE_TYPES)[number];

export const VALUE_TYPE_LABEL: Record<ValueType, string> = {
  string: "文本",
  integer: "整数",
  decimal: "小数",
  boolean: "布尔",
  date: "日期",
  timestamp: "时间戳",
};

const CONCEPT_ID = /^concept\.[a-z][a-z0-9_]*$/;
const PROPERTY_ID = /^property\.[a-z][a-z0-9_]*$/;
const RELATION_ID = /^ontology\.[A-Za-z][A-Za-z0-9_]*\.[A-Za-z][A-Za-z0-9_]*$/;
/** 物理列二段式「表.列」，与 v22 清单 Property.column / Relation.fromColumn 同形。 */
const PHYSICAL_COLUMN = /^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$/;

export const conceptFormSchema = z.object({
  id: z.string().regex(CONCEPT_ID, "ID 形如 concept.customer"),
  name: z.string().min(1, "业务名必填"),
  domain: z.string().min(1, "领域必填"),
  aliases: z.string().min(1, "至少一个常见叫法"),
  summary: z.string().min(1, "业务说明必填"),
  identityProperty: z.string().regex(PROPERTY_ID, "主标识属性形如 property.x.y"),
});
export type ConceptFormValues = z.infer<typeof conceptFormSchema>;

export const propertyFormSchema = z
  .object({
    id: z.string().regex(PROPERTY_ID, "ID 形如 property.order.amount"),
    name: z.string().min(1, "业务名必填"),
    conceptId: z.string().regex(CONCEPT_ID, "请选择所属业务对象"),
    /** 所属数据视图（B3/T8）：建模经视图资产引用物理列，不再直持表名。 */
    viewId: z.string().min(1, "请选择数据视图"),
    column: z.string().min(1, "物理列必填"),
    valueType: z.enum(VALUE_TYPES),
    aliases: z.string().min(1, "至少一个常见叫法"),
    summary: z.string().min(1, "业务说明必填"),
    filterable: z.boolean(),
  })
  .superRefine((values, ctx) => {
    // 新口径：viewId + 裸列名；带表前缀说明数据混用两种形态，表单侧直接拒绝
    if (values.viewId && values.column.includes(".")) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["column"],
        message: "视图列应为裸列名（不含表前缀）",
      });
    }
  });
export type PropertyFormValues = z.infer<typeof propertyFormSchema>;

export const relationFormSchema = z.object({
  id: z.string().regex(RELATION_ID, "ID 形如 ontology.Customer.placesOrder"),
  name: z.string().min(1, "关系名必填"),
  aliases: z.string().min(1, "至少一个常见叫法"),
  summary: z.string().min(1, "业务说明必填"),
  from: z.string().regex(CONCEPT_ID, "请选择起点对象"),
  to: z.string().regex(CONCEPT_ID, "请选择终点对象"),
  fromColumn: z.string().regex(PHYSICAL_COLUMN, "外键列形如 表名.列名"),
  toColumn: z.string().regex(PHYSICAL_COLUMN, "被引用列形如 表名.列名"),
  reverseLabel: z.string().min(1, "反向说法必填"),
});
export type RelationFormValues = z.infer<typeof relationFormSchema>;

const splitAliases = (text: string): string[] =>
  text
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean);

export function toConceptDefinition(values: ConceptFormValues): Record<string, unknown> {
  return {
    id: values.id,
    name: values.name,
    domain: values.domain,
    aliases: splitAliases(values.aliases),
    summary: values.summary,
    identityProperty: values.identityProperty,
  };
}

export function toPropertyDefinition(values: PropertyFormValues): Record<string, unknown> {
  return {
    id: values.id,
    name: values.name,
    conceptId: values.conceptId,
    viewId: values.viewId,
    column: values.column,
    valueType: values.valueType,
    aliases: splitAliases(values.aliases),
    summary: values.summary,
    filterable: values.filterable,
    // 平台默认值：Phase 1 不开放编辑，敏感等级等走发布侧治理
    sensitivity: "INTERNAL",
    lookupProjectable: true,
    existenceSensitive: false,
  };
}

export function toRelationDefinition(values: RelationFormValues): Record<string, unknown> {
  return {
    id: values.id,
    name: values.name,
    aliases: splitAliases(values.aliases),
    summary: values.summary,
    from: values.from,
    to: values.to,
    fromColumn: values.fromColumn,
    toColumn: values.toColumn,
    cardinality: "N:1",
    queryable: true,
    reverseLabel: values.reverseLabel,
  };
}

const str = (value: unknown, fallback = ""): string =>
  typeof value === "string" ? value : fallback;

const bool = (value: unknown): boolean => value === true;

const aliasesText = (value: unknown): string =>
  Array.isArray(value) ? value.map((item) => str(item)).join(", ") : str(value);

export function conceptFormOf(definition: Record<string, unknown>): ConceptFormValues {
  return {
    id: str(definition.id),
    name: str(definition.name),
    domain: str(definition.domain),
    aliases: aliasesText(definition.aliases),
    summary: str(definition.summary),
    identityProperty: str(definition.identityProperty),
  };
}

export function propertyFormOf(definition: Record<string, unknown>): PropertyFormValues {
  return {
    id: str(definition.id),
    name: str(definition.name),
    conceptId: str(definition.conceptId),
    viewId: str(definition.viewId),
    column: str(definition.column),
    valueType: (VALUE_TYPES as readonly string[]).includes(str(definition.valueType))
      ? (str(definition.valueType) as ValueType)
      : "string",
    aliases: aliasesText(definition.aliases),
    summary: str(definition.summary),
    filterable: bool(definition.filterable),
  };
}

export function relationFormOf(definition: Record<string, unknown>): RelationFormValues {
  return {
    id: str(definition.id),
    name: str(definition.name),
    aliases: aliasesText(definition.aliases),
    summary: str(definition.summary),
    from: str(definition.from),
    to: str(definition.to),
    fromColumn: str(definition.fromColumn),
    toColumn: str(definition.toColumn),
    reverseLabel: str(definition.reverseLabel),
  };
}

/** 把真实目录列类型映射为清单 valueType；映射不了返回 null，由用户手选。 */
export function valueTypeOfColumn(dataType: string): ValueType | null {
  const type = dataType.toLowerCase();
  if (type.includes("timestamp") || type === "timestamptz") return "timestamp";
  if (type === "date") return "date";
  if (type === "boolean" || type === "bool") return "boolean";
  if (type.includes("int")) return "integer";
  if (
    type.includes("numeric") ||
    type.includes("decimal") ||
    type.includes("double") ||
    type.includes("real") ||
    type.includes("money")
  ) {
    return "decimal";
  }
  if (type.includes("char") || type.includes("text") || type.includes("uuid")) return "string";
  return null;
}
