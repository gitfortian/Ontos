/**
 * TanStack Form 字段绑定适配（ADR-0024 D3 定档的正式适配层）。
 *
 * 形态来自 2026-11-12 表单库 Spike 的实证（apps/web/spikes/form-eval）：
 * 共享控件（Field / FieldLabel / FieldError / Input / Select）已自带 label、错误与 aria 结构，
 * 因此无需 shadcn 那套 Form context 组件层，只差一层「FieldApi → 控件 props」的映射。
 *
 * 本文件刻意保持零外部依赖：`FieldLike` 是结构类型，TanStack Form 的 FieldApi 天然满足它，
 * 共享 UI 包因此不感知任何表单库；zod 的 Standard Schema issues 由 `firstError` 提取。
 *
 * 用法（页面侧）——`error` 不要整体展开到 `<Input>`：Input 不接受 error，
 * 且 spread 不触发 TS 多余属性检查，error 会被静默透传到原生 DOM：
 * ```tsx
 * // 组合控件（推荐）：error 由控件内部渲染（见 apps/web/spikes/form-eval/controls.tsx）
 * <TextField {...fieldProps(field)} label="名称" testId="name" />
 *
 * // 原生控件：把 error 拆出来交给 FieldError
 * const { error, ...control } = fieldProps(field);
 * <Input {...control} id="name" />
 * <FieldError match={Boolean(error)}>{error}</FieldError>
 * ```
 */

/** 表单字段绑定的最小结构契约：值、错误列表与事件回调。`meta.errors` 运行时期望为数组。 */
export interface FieldLike<TValue> {
  state: {
    value: TValue;
    meta: { errors: unknown };
  };
  handleChange: (value: TValue) => void;
  handleBlur: () => void;
}

/** 从校验器错误列表中提取第一条可展示文案。 */
export function firstError(errors: readonly unknown[] | undefined): string | undefined {
  const first = errors?.[0];
  if (typeof first === "string") return first;
  if (first && typeof first === "object" && "message" in first) {
    return String((first as { message: unknown }).message);
  }
  return undefined;
}

/**
 * 把表单字段适配为共享控件 props：`value` / `onChange` / `onBlur` / `error`。
 * `onChange` 传回的值类型与字段值精确一致（TanStack Form 按 schema 推导字面量联合）。
 */
export function fieldProps<TValue>(field: FieldLike<TValue>) {
  return {
    value: field.state.value,
    onChange: (value: TValue) => field.handleChange(value),
    onBlur: field.handleBlur,
    error: firstError(field.state.meta.errors as readonly unknown[] | undefined),
  };
}
