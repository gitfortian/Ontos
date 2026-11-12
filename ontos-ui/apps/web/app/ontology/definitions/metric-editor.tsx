import { useForm } from "@tanstack/react-form";
import { Button, fieldProps } from "@ontos/ui";

import type { SemanticAssetOptionsVO, SemanticValidationVO } from "@/service/ontology/types";

import { BooleanField, CheckboxGroupField, SelectField, TextField } from "./controls";
import {
  AGGREGATIONS,
  ANALYSIS_MODES,
  metricFormSchema,
  toMetricDefinition,
  type MetricFormValues,
} from "./schema";

const AGGREGATION_OPTIONS = AGGREGATIONS.map((value) => ({ value, label: value }));
const MODE_OPTIONS = ANALYSIS_MODES.map((value) => ({ value, label: value }));

export interface MetricEditorProps {
  initial: MetricFormValues;
  idLocked: boolean;
  options: SemanticAssetOptionsVO;
  busy: boolean;
  onSave: (definition: Record<string, unknown>) => Promise<void>;
  onValidate: (definition: Record<string, unknown>) => Promise<SemanticValidationVO>;
  onSubmitReview: (definition: Record<string, unknown>) => Promise<void>;
}

export function MetricEditor({
  initial,
  idLocked,
  options,
  busy,
  onSave,
  onValidate,
  onSubmitReview,
}: MetricEditorProps) {
  const form = useForm({
    defaultValues: initial,
    canSubmitWhenInvalid: true,
    validators: { onChange: metricFormSchema, onSubmit: metricFormSchema },
    onSubmit: async ({ value }) => {
      await onSave(toMetricDefinition(value));
    },
  });

  const definitionOf = (): Record<string, unknown> | null => {
    const parsed = metricFormSchema.safeParse(form.state.values);
    if (!parsed.success) {
      void form.validateAllFields("change");
      return null;
    }
    return toMetricDefinition(parsed.data);
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
            label="指标 ID"
            testId="metric-id"
            required
            disabled={idLocked}
          />
        )}
      </form.Field>
      <form.Field name="name">
        {(field) => (
          <TextField {...fieldProps(field)} label="指标名称" testId="metric-name" required />
        )}
      </form.Field>
      <form.Field name="summary">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务说明" testId="metric-summary" required />
        )}
      </form.Field>
      <form.Field name="aliases">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="常见叫法"
            testId="metric-aliases"
            placeholder="逗号分隔，如：成交金额, 销售额"
            required
          />
        )}
      </form.Field>
      <form.Field name="objectId">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="业务对象"
            testId="metric-object"
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
          <form.Subscribe selector={(state) => [state.values.objectId, state.values.aggregation]}>
            {([objectId, aggregation]) => (
              <SelectField
                {...fieldProps(field)}
                label="业务属性"
                testId="metric-property"
                options={options.properties
                  .filter((item) => item.conceptId === objectId)
                  .map((item) => ({ value: item.id, label: `${item.name}（${item.valueType}）` }))}
                disabled={aggregation === "COUNT"}
              />
            )}
          </form.Subscribe>
        )}
      </form.Field>
      <form.Field name="aggregation">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="聚合方式"
            testId="metric-aggregation"
            options={AGGREGATION_OPTIONS}
            required
          />
        )}
      </form.Field>
      <form.Field name="allowedDimensions">
        {(field) => (
          <CheckboxGroupField
            label="允许维度"
            testId="metric-dimensions"
            values={field.state.value}
            options={options.dimensions.map((item) => ({ value: item.id, label: item.name }))}
            error={fieldProps(field).error}
            onToggle={(value) =>
              field.handleChange(
                field.state.value.includes(value)
                  ? field.state.value.filter((item) => item !== value)
                  : [...field.state.value, value],
              )
            }
          />
        )}
      </form.Field>
      <form.Field name="allowedModes">
        {(field) => (
          <CheckboxGroupField
            label="分析模式"
            testId="metric-modes"
            values={field.state.value}
            options={MODE_OPTIONS}
            error={fieldProps(field).error}
            onToggle={(value) =>
              field.handleChange(
                field.state.value.includes(value)
                  ? field.state.value.filter((item) => item !== value)
                  : [...field.state.value, value],
              )
            }
          />
        )}
      </form.Field>
      <form.Field name="sensitive">
        {(field) => (
          <BooleanField
            label="敏感指标"
            testId="metric-sensitive"
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
