export { Alert, type AlertProps } from "./alert/Alert";
export { Badge, type BadgeProps } from "./badge/Badge";
export { Button, buttonVariants, type ButtonProps } from "./button/Button";
export {
  Card,
  CardHeader,
  SectionCard,
  type CardHeaderProps,
  type CardProps,
  type SectionCardProps,
} from "./card/Card";
export { cn } from "./cn";
export {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
  type FieldDescriptionProps,
  type FieldErrorProps,
  type FieldLabelProps,
  type FieldProps,
} from "./field/Field";
export { fieldProps, firstError, type FieldLike } from "./form/FormBinding";
export { Input, inputVariants, type InputProps } from "./input/Input";
export { PageHeader, type PageHeaderProps } from "./page-header/PageHeader";
export {
  Select,
  SelectContent,
  SelectItem,
  SelectItemIndicator,
  SelectItemText,
  SelectTrigger,
  SelectValue,
  selectTriggerVariants,
  type SelectContentProps,
  type SelectItemIndicatorProps,
  type SelectItemProps,
  type SelectItemTextProps,
  type SelectProps,
  type SelectTriggerProps,
  type SelectValueProps,
} from "./select/Select";
export { Spinner, type SpinnerProps } from "./spinner/Spinner";
export { Table } from "./table/Table";
export type {
  TableAlign,
  TableColumn,
  TableColumns,
  TablePaginationConfig,
  TableProps,
  TableScroll,
  TableSize,
} from "./table/interface";
export { Textarea, textareaVariants, type TextareaProps } from "./textarea/Textarea";
export { ToastProvider, toast, type ToastOptions, type ToastTone } from "./toast/Toast";

// shadcn/ui CLI 产物（components.json 于本包根；新组件一律走 CLI 引入，旧组件不动）
export {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "./components/ui/accordion";
export { Badge as UiBadge, badgeVariants as uiBadgeVariants } from "./components/ui/badge";
export { ScrollArea } from "./components/ui/scroll-area";
export {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "./components/ui/sheet";
export { Tabs, TabsContent, TabsList, TabsTrigger } from "./components/ui/tabs";
export { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "./components/ui/tooltip";
