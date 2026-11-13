import { useForm } from "@tanstack/react-form";
import { Button, fieldProps } from "@ontos/ui";

import type { SemanticAssetOptionsVO, SemanticValidationVO } from "@/service/ontology/types";

import { SelectField, TextField } from "../definitions/controls";
import { CatalogColumnField } from "./catalog-column";
import { relationFormSchema, toRelationDefinition, type RelationFormValues } from "./schema";

export interface RelationEditorProps {
  initial: RelationFormValues;
  idLocked: boolean;
  options: SemanticAssetOptionsVO;
  busy: boolean;
  onSave: (definition: Record<string, unknown>) => Promise<void>;
  onValidate: (definition: Record<string, unknown>) => Promise<SemanticValidationVO>;
  onSubmitReview: (definition: Record<string, unknown>) => Promise<void>;
}

/** 业务关联草稿编辑器（B1/P2-B）：外键列与被引用列从数据源插件真实目录三段选择。 */
export function RelationEditor({
  initial,
  idLocked,
  options,
  busy,
  onSave,
  onValidate,
  onSubmitReview,
}: RelationEditorProps) {
  const form = useForm({
    defaultValues: initial,
    canSubmitWhenInvalid: true,
    validators: { onChange: relationFormSchema, onSubmit: relationFormSchema },
    onSubmit: async ({ value }) => {
      await onSave(toRelationDefinition(value));
    },
  });

  const conceptOptions = options.concepts.map((item) => ({ value: item.id, label: item.name }));

  const definitionOf = (): Record<string, unknown> | null => {
    const parsed = relationFormSchema.safeParse(form.state.values);
    if (!parsed.success) {
      void form.validateAllFields("change");
      return null;
    }
    return toRelationDefinition(parsed.data);
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
            label="关联 ID"
            testId="relation-id"
            required
            disabled={idLocked}
            placeholder="ontology.Customer.placesOrder"
          />
        )}
      </form.Field>
      <form.Field name="name">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="关系动词"
            testId="relation-name"
            required
            placeholder="如：下了"
          />
        )}
      </form.Field>
      <form.Field name="aliases">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="常见叫法"
            testId="relation-aliases"
            placeholder="逗号分隔，如：购买, 下单"
            required
          />
        )}
      </form.Field>
      <form.Field name="summary">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务说明" testId="relation-summary" required />
        )}
      </form.Field>
      <form.Field name="from">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="起点对象"
            testId="relation-from"
            options={conceptOptions}
            required
          />
        )}
      </form.Field>
      <form.Field name="to">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="终点对象"
            testId="relation-to"
            options={conceptOptions}
            required
          />
        )}
      </form.Field>
      <form.Field name="fromColumn">
        {(field) => (
          <CatalogColumnField
            label="外键列"
            testId="relation-from-column"
            value={field.state.value}
            onChange={(column) => field.handleChange(column)}
          />
        )}
      </form.Field>
      <form.Field name="toColumn">
        {(field) => (
          <CatalogColumnField
            label="被引用列"
            testId="relation-to-column"
            value={field.state.value}
            onChange={(column) => field.handleChange(column)}
          />
        )}
      </form.Field>
      <form.Field name="reverseLabel">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="反向说法"
            testId="relation-reverse"
            required
            placeholder="如：客户下的订单"
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
