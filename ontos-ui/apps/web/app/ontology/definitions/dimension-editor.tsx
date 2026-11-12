import { useForm } from "@tanstack/react-form";
import { Button, fieldProps } from "@ontos/ui";

import type { SemanticAssetOptionsVO, SemanticValidationVO } from "@/service/ontology/types";

import { BooleanField, SelectField, TextField } from "./controls";
import { dimensionFormSchema, toDimensionDefinition, type DimensionFormValues } from "./schema";

export interface DimensionEditorProps {
  initial: DimensionFormValues;
  idLocked: boolean;
  options: SemanticAssetOptionsVO;
  busy: boolean;
  onSave: (definition: Record<string, unknown>) => Promise<void>;
  onValidate: (definition: Record<string, unknown>) => Promise<SemanticValidationVO>;
  onSubmitReview: (definition: Record<string, unknown>) => Promise<void>;
}

export function DimensionEditor({
  initial,
  idLocked,
  options,
  busy,
  onSave,
  onValidate,
  onSubmitReview,
}: DimensionEditorProps) {
  const form = useForm({
    defaultValues: initial,
    canSubmitWhenInvalid: true,
    validators: { onChange: dimensionFormSchema, onSubmit: dimensionFormSchema },
    onSubmit: async ({ value }) => {
      await onSave(toDimensionDefinition(value));
    },
  });

  const definitionOf = (): Record<string, unknown> | null => {
    const parsed = dimensionFormSchema.safeParse(form.state.values);
    if (!parsed.success) {
      void form.validateAllFields("change");
      return null;
    }
    return toDimensionDefinition(parsed.data);
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
            label="维度 ID"
            testId="dimension-id"
            required
            disabled={idLocked}
          />
        )}
      </form.Field>
      <form.Field name="name">
        {(field) => (
          <TextField {...fieldProps(field)} label="维度名称" testId="dimension-name" required />
        )}
      </form.Field>
      <form.Field name="summary">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务说明" testId="dimension-summary" required />
        )}
      </form.Field>
      <form.Field name="aliases">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="常见叫法"
            testId="dimension-aliases"
            placeholder="逗号分隔"
            required
          />
        )}
      </form.Field>
      <form.Field name="objectId">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="业务对象"
            testId="dimension-object"
            options={options.concepts.map((item) => ({ value: item.id, label: item.name }))}
            required
            onChange={(value) => {
              field.handleChange(value);
              form.setFieldValue("propertyId", "");
            }}
          />
        )}
      </form.Field>
      <form.Field name="propertyId">
        {(field) => (
          <form.Subscribe selector={(state) => state.values.objectId}>
            {(objectId) => (
              <SelectField
                {...fieldProps(field)}
                label="业务属性"
                testId="dimension-property"
                options={options.properties
                  .filter((item) => item.conceptId === objectId)
                  .map((item) => ({ value: item.id, label: `${item.name}（${item.valueType}）` }))}
                required
              />
            )}
          </form.Subscribe>
        )}
      </form.Field>
      <form.Field name="sensitive">
        {(field) => (
          <BooleanField
            label="敏感维度"
            testId="dimension-sensitive"
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
