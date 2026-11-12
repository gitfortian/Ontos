import type { InputHTMLAttributes } from "react";
import { useRef } from "react";
import { Button, Field, FieldError, FieldLabel, Input, cn, inputVariants } from "@ontos/ui";

/**
 * Spike 共用的展示控件：定档前两种表单实现共用同一套控件与同一组 data-testid 以保证对照可比，
 * 定档后仅保留 TanStack 实现（ADR-0024）。选择控件用原生 select（jsdom 可交互）；
 * Radix Select 的 `value/onValueChange` 绑定形态见 ADR-0024 D3，浏览器交互验证列入后续动作。
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
  const renders = useRef(0);
  renders.current += 1;
  return (
    <Field className="grid grid-cols-[104px_minmax(0,1fr)] items-start gap-2">
      <FieldLabel htmlFor={controlProps.id ?? `field-${testId}`} required={required}>
        {label}
      </FieldLabel>
      <div className="min-w-0">
        <Input
          {...controlProps}
          id={controlProps.id ?? `field-${testId}`}
          data-testid={testId}
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          onBlur={onBlur}
        />
        <FieldError match={Boolean(error)} data-testid={`error-${testId}`}>
          {error}
        </FieldError>
        <output data-testid={`render-${testId}`} className="hidden">
          {renders.current}
        </output>
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

/** 值类型泛型化：TanStack Form 的字段值是精确字面量联合，不能退化为 string。 */
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
        <FieldError match={Boolean(error)} data-testid={`error-${testId}`}>
          {error}
        </FieldError>
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
      <div className="flex min-w-0 flex-wrap gap-3">
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
        <FieldError match={Boolean(error)} data-testid={`error-${testId}`}>
          {error}
        </FieldError>
      </div>
    </Field>
  );
}

export interface IconButtonProps {
  testId: string;
  onClick: () => void;
  children: string;
}

export function IconButton({ testId, onClick, children }: IconButtonProps) {
  return (
    <Button type="button" size="small" data-testid={testId} onClick={onClick}>
      {children}
    </Button>
  );
}
