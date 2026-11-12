import { useForm } from "@tanstack/react-form";
import { fieldProps, Button, Field, FieldError, FieldLabel } from "@ontos/ui";

import { CheckboxGroupField, IconButton, SelectField, TextField } from "../controls";
import {
  AGGREGATION_OPTIONS,
  DIMENSION_OPTIONS,
  OBJECT_OPTIONS,
  VARIANT_OPERATORS,
  metricDraftSchema,
  propertiesOf,
  type FormHarnessProps,
} from "../metric-schema";

const AGGREGATION_ITEMS = AGGREGATION_OPTIONS.map((value) => ({ value, label: value }));
const OPERATOR_ITEMS = VARIANT_OPERATORS.map((value) => ({ value, label: value }));

/** 三层化「原子指标」草稿编辑器 —— TanStack Form + zod（Standard Schema 直连）。 */
export function TanStackMetricForm({ initial, onSave }: FormHarnessProps) {
  const form = useForm({
    defaultValues: initial,
    canSubmitWhenInvalid: true,
    validators: { onChange: metricDraftSchema, onSubmit: metricDraftSchema },
    onSubmit: async ({ value }) => {
      await onSave(value);
    },
  });

  return (
    <form
      data-testid="form"
      onSubmit={(event) => {
        event.preventDefault();
        event.stopPropagation();
        void form.handleSubmit();
      }}
    >
      <form.Field name="id">
        {(field) => <TextField {...fieldProps(field)} label="指标 ID" testId="id" required />}
      </form.Field>
      <form.Field name="name">
        {(field) => <TextField {...fieldProps(field)} label="指标名称" testId="name" required />}
      </form.Field>
      <form.Field name="objectId">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="业务对象"
            testId="objectId"
            options={OBJECT_OPTIONS}
            required
            onChange={(value) => {
              field.handleChange(value);
              form.setFieldValue("propertyId", "");
            }}
          />
        )}
      </form.Field>
      <form.Field name="aggregation">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="聚合方式"
            testId="aggregation"
            options={AGGREGATION_ITEMS}
            required
          />
        )}
      </form.Field>
      <form.Field name="propertyId">
        {(field) => (
          <form.Subscribe selector={(state) => state.values.objectId}>
            {(objectId) => (
              <form.Subscribe selector={(state) => state.values.aggregation}>
                {(aggregation) => (
                  <SelectField
                    {...fieldProps(field)}
                    label="业务属性"
                    testId="propertyId"
                    options={propertiesOf(objectId)}
                    disabled={aggregation === "COUNT"}
                  />
                )}
              </form.Subscribe>
            )}
          </form.Subscribe>
        )}
      </form.Field>
      <form.Field name="allowedDimensions" mode="array">
        {(field) => (
          <CheckboxGroupField
            label="允许维度"
            testId="dimensions"
            values={field.state.value}
            options={DIMENSION_OPTIONS}
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

      <form.Field name="variants" mode="array">
        {(field) => (
          <Field>
            <FieldLabel>口径变体</FieldLabel>
            {field.state.value.map((_variant, index) => (
              <div key={`variant-${index}`} data-testid={`variant-${index}`}>
                <form.Field name={`variants[${index}].field`}>
                  {(sub) => (
                    <TextField
                      {...fieldProps(sub)}
                      label="变体字段"
                      testId={`variant-${index}-field`}
                    />
                  )}
                </form.Field>
                <form.Field name={`variants[${index}].op`}>
                  {(sub) => (
                    <SelectField
                      {...fieldProps(sub)}
                      label="运算符"
                      testId={`variant-${index}-op`}
                      options={OPERATOR_ITEMS}
                    />
                  )}
                </form.Field>
                <form.Field name={`variants[${index}].value`}>
                  {(sub) => (
                    <TextField
                      {...fieldProps(sub)}
                      label="变体取值"
                      testId={`variant-${index}-value`}
                    />
                  )}
                </form.Field>
                <IconButton
                  testId={`variant-${index}-remove`}
                  onClick={() => field.removeValue(index)}
                >
                  删除
                </IconButton>
              </div>
            ))}
            <IconButton
              testId="variant-add"
              onClick={() => field.pushValue({ field: "", op: "EQ", value: "" })}
            >
              添加变体
            </IconButton>
            <FieldError match={Boolean(fieldProps(field).error)}>
              {fieldProps(field).error}
            </FieldError>
          </Field>
        )}
      </form.Field>

      <form.Subscribe selector={(state) => state.isDirty}>
        {(isDirty) => <output data-testid="dirty">{String(isDirty)}</output>}
      </form.Subscribe>
      <form.Subscribe selector={(state) => state.isSubmitting}>
        {(isSubmitting) => (
          <Button
            type="submit"
            variant="primary"
            size="small"
            loading={isSubmitting}
            data-testid="submit"
          >
            保存
          </Button>
        )}
      </form.Subscribe>
      <form.Subscribe selector={(state) => state.values}>
        {(values) => <output data-testid="values">{JSON.stringify(values)}</output>}
      </form.Subscribe>
    </form>
  );
}
