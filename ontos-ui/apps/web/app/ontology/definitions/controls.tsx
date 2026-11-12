import type { InputHTMLAttributes } from "react";
import { Field, FieldError, FieldLabel, Input, cn, inputVariants } from "@ontos/ui";

/**
 * 语义定义表单的组合控件（B1/P1）。
 *
 * <p>共享 UI 包只提供原子控件；这里按「104px 标签列 + 控件」的管理表单范式组合，
 * 并统一把错误放在控件下方。不引入新的表单库抽象。
 */

export type TextFieldProps = Omit<
  InputHTMLAttributes<HTMLInputElement>,
  "value" | "onChange" | "onBlur" | "disabled" | "children" | "size"
> & {
  label: string;
  testId: string;
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  error?: string;
  required?: boolean;
  disabled?: boolean;
};

export function TextField({
  label,
  testId,
  value,
  onChange,
  onBlur,
  error,
  required,
  disabled,
  ...controlProps
}: TextFieldProps) {
  return (
    <Field className="grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2">
      <FieldLabel htmlFor={`field-${testId}`} required={required}>
        {label}
      </FieldLabel>
      <div className="min-w-0">
        <Input
          {...controlProps}
          id={`field-${testId}`}
          data-testid={testId}
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          onBlur={onBlur}
        />
        <FieldError match={Boolean(error)}>{error}</FieldError>
      </div>
    </Field>
  );
}

export interface Option {
  value: string;
  label: string;
}

export interface SelectFieldProps<TValue extends string = string> {
  label: string;
  testId: string;
  value: TValue;
  options: readonly Option[];
  onChange: (value: TValue) => void;
  error?: string;
  required?: boolean;
  disabled?: boolean;
}

export function SelectField<TValue extends string>({
  label,
  testId,
  value,
  options,
  onChange,
  error,
  required,
  disabled,
}: SelectFieldProps<TValue>) {
  return (
    <Field className="grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2">
      <FieldLabel htmlFor={`field-${testId}`} required={required}>
        {label}
      </FieldLabel>
      <div className="min-w-0">
        <select
          id={`field-${testId}`}
          data-testid={testId}
          className={cn(inputVariants({ size: "small" }), "cursor-pointer")}
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value as TValue)}
        >
          <option value="">未选择</option>
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <FieldError match={Boolean(error)}>{error}</FieldError>
      </div>
    </Field>
  );
}

export interface CheckboxGroupFieldProps {
  label: string;
  testId: string;
  values: readonly string[];
  options: readonly Option[];
  onToggle: (value: string) => void;
  error?: string;
}

export function CheckboxGroupField({
  label,
  testId,
  values,
  options,
  onToggle,
  error,
}: CheckboxGroupFieldProps) {
  return (
    <Field className="grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2">
      <FieldLabel>{label}</FieldLabel>
      <div className="flex min-w-0 flex-wrap gap-x-3 gap-y-1">
        {options.map((option) => (
          <label key={option.value} className="flex items-center gap-1 text-xs">
            <input
              type="checkbox"
              data-testid={`${testId}-${option.value}`}
              checked={values.includes(option.value)}
              onChange={() => onToggle(option.value)}
            />
            {option.label}
          </label>
        ))}
        <FieldError match={Boolean(error)}>{error}</FieldError>
      </div>
    </Field>
  );
}

export interface BooleanFieldProps {
  label: string;
  testId: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

export function BooleanField({ label, testId, checked, onChange }: BooleanFieldProps) {
  return (
    <Field className="grid grid-cols-[104px_minmax(0,1fr)] items-center gap-2">
      <FieldLabel>{label}</FieldLabel>
      <label className="flex items-center gap-1 text-xs">
        <input
          type="checkbox"
          data-testid={testId}
          checked={checked}
          onChange={(event) => onChange(event.target.checked)}
        />
        是
      </label>
    </Field>
  );
}
