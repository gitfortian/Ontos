import { useForm } from "@tanstack/react-form";
import { Button, fieldProps } from "@ontos/ui";

import type { SemanticAssetOptionsVO, SemanticValidationVO } from "@/service/ontology/types";

import { SelectField, TextField } from "../definitions/controls";
import { conceptFormSchema, toConceptDefinition, type ConceptFormValues } from "./schema";

export interface ConceptEditorProps {
  initial: ConceptFormValues;
  idLocked: boolean;
  options: SemanticAssetOptionsVO;
  busy: boolean;
  onSave: (definition: Record<string, unknown>) => Promise<void>;
  onValidate: (definition: Record<string, unknown>) => Promise<SemanticValidationVO>;
  onSubmitReview: (definition: Record<string, unknown>) => Promise<void>;
}

/** 业务对象草稿编辑器（B2/P2-B）：identityProperty 从既有信息项选择，由校验器验证归属。 */
export function ConceptEditor({
  initial,
  idLocked,
  options,
  busy,
  onSave,
  onValidate,
  onSubmitReview,
}: ConceptEditorProps) {
  const form = useForm({
    defaultValues: initial,
    canSubmitWhenInvalid: true,
    validators: { onChange: conceptFormSchema, onSubmit: conceptFormSchema },
    onSubmit: async ({ value }) => {
      await onSave(toConceptDefinition(value));
    },
  });

  const definitionOf = (): Record<string, unknown> | null => {
    const parsed = conceptFormSchema.safeParse(form.state.values);
    if (!parsed.success) {
      void form.validateAllFields("change");
      return null;
    }
    return toConceptDefinition(parsed.data);
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
            label="对象 ID"
            testId="concept-id"
            required
            disabled={idLocked}
            placeholder="concept.customer"
          />
        )}
      </form.Field>
      <form.Field name="name">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务名" testId="concept-name" required />
        )}
      </form.Field>
      <form.Field name="domain">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="领域"
            testId="concept-domain"
            required
            placeholder="如 customer / order"
          />
        )}
      </form.Field>
      <form.Field name="aliases">
        {(field) => (
          <TextField
            {...fieldProps(field)}
            label="常见叫法"
            testId="concept-aliases"
            placeholder="逗号分隔，如：客户, 买家"
            required
          />
        )}
      </form.Field>
      <form.Field name="summary">
        {(field) => (
          <TextField {...fieldProps(field)} label="业务说明" testId="concept-summary" required />
        )}
      </form.Field>
      <form.Field name="identityProperty">
        {(field) => (
          <SelectField
            {...fieldProps(field)}
            label="主标识属性"
            testId="concept-identity"
            options={options.properties.map((item) => ({
              value: item.id,
              label: `${item.name}（${item.conceptId}）`,
            }))}
            required
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
