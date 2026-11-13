import { useForm } from "@tanstack/react-form";
import { Button, fieldProps } from "@ontos/ui";

import type { SemanticAssetOptionsVO, SemanticValidationVO } from "@/service/ontology/types";

import { BooleanField, SelectField, TextField } from "../definitions/controls";
import { CatalogColumnField } from "./catalog-column";
import {
  VALUE_TYPES,
  VALUE_TYPE_LABEL,
  propertyFormSchema,
  toPropertyDefinition,
  valueTypeOfColumn,
  type PropertyFormValues,
} from "./schema";

export interface PropertyEditorProps {
  initial: PropertyFormValues;
  idLocked: boolean;
  options: SemanticAssetOptionsVO;
  busy: boolean;
  onSave: (definition: Record<string, unknown>) => Promise<void>;
  onValidate: (definition: Record<string, unknown>) => Promise<SemanticValidationVO>;
  onSubmitReview: (definition: Record<string, unknown>) => Promise<void>;
}

const VALUE_TYPE_OPTIONS = VALUE_TYPES.map((value) => ({
  value,
  label: VALUE_TYPE_LABEL[value],
}));

/**
 * 信息项草稿编辑器（B1/P2-B）：column 从数据源插件的真实目录三段选择
 * （共享 {@link CatalogColumnField}），选中列自动映射 valueType。
 */
export function PropertyEditor({
  initial,
  idLocked,
  options,
  busy,
  onSave,
  onValidate,
  onSubmitReview,
}: PropertyEditorProps) {
  const form = useForm({
    defaultValues: initial,
    canSubmitWhenInvalid: true,
    validators: { onChange: propertyFormSchema, onSubmit: propertyFormSchema },
    onSubmit: async ({ value }) => {
      await onSave(toPropertyDefinition(value));
    },
  });

  const definitionOf = (): Record<string, unknown> | null => {
    const parsed = propertyFormSchema.safeParse(form.state.values);
    if (!parsed.success) {
      void form.validateAllFields("change");
      return null;
    }
    return toPropertyDefinition(parsed.data);
  };

  return (
    <form
      className="space-y-3"
      onSubmit={(event) => {
        event.preventDefault();
        event.stopPropagation();
        void form.handleSubmit();
      }}
    >
      <form.Field name="id">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="信息项 ID"
            testId="property-id"
            required
            disabled={idLocked}
            placeholder="property.order.amount"
          />
        )}
      </form.Field>
      <form.Field name="name">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务名" testId="property-name" required />
        )}
      </form.Field>
      <form.Field name="conceptId">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="所属对象"
            testId="property-concept"
            options={options.concepts.map((item) => ({ value: item.id, label: item.name }))}
            required
          />
        )}
      </form.Field>

      <form.Field name="column">
        {(field) => (
          <CatalogColumnField
            label="物理列"
            testId="property-catalog-column"
            value={field.state.value}
            onChange={(column, dataType) => {
              field.handleChange(column);
              const mapped = dataType ? valueTypeOfColumn(dataType) : null;
              if (mapped) {
                form.setFieldValue("valueType", mapped);
              }
            }}
          />
        )}
      </form.Field>

      <form.Field name="valueType">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="值类型"
            testId="property-value-type"
            options={VALUE_TYPE_OPTIONS}
            required
          />
        )}
      </form.Field>
      <form.Field name="aliases">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="常见叫法"
            testId="property-aliases"
            placeholder="逗号分隔，如：金额, 成交额"
            required
          />
        )}
      </form.Field>
      <form.Field name="summary">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务说明" testId="property-summary" required />
        )}
      </form.Field>
      <form.Field name="filterable">
        {(field) => (
          <BooleanField
            label="可作过滤条件"
            testId="property-filterable"
            checked={field.state.value}
            onChange={(checked) => field.handleChange(checked)}
          />
        )}
      </form.Field>

      <form.Subscribe selector={(state) => state.isSubmitting}>
        {(isSubmitting) => (
          <div className="flex items-center gap-2 pt-1">
            <Button type="submit" variant="primary" size="small" loading={isSubmitting || busy}>
              保存草稿
            </Button>
            <Button
              type="button"
              size="small"
              disabled={busy}
              onClick={() => {
                const definition = definitionOf();
                if (definition) void onValidate(definition);
              }}
            >
              校验
            </Button>
            <Button
              type="button"
              size="small"
              disabled={busy}
              onClick={() => {
                const definition = definitionOf();
                if (definition) void onSubmitReview(definition);
              }}
            >
              保存并提交审核
            </Button>
          </div>
        )}
      </form.Subscribe>
    </form>
  );
}
