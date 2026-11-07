import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "../cn";

export type CardProps = HTMLAttributes<HTMLDivElement>;

export function Card({ className, ...props }: CardProps) {
  return (
    <div
      className={cn(
        "overflow-hidden rounded-[var(--radius)] border border-[var(--border)] bg-[var(--card)] text-[var(--card-foreground)]",
        className,
      )}
      {...props}
    />
  );
}

// `title` 在原生 div 上是字符串提示，此处用作标题内容，故先剔除
export interface CardHeaderProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  title?: ReactNode;
  description?: ReactNode;
  extra?: ReactNode;
}

export function CardHeader({
  children,
  className,
  description,
  extra,
  title,
  ...props
}: CardHeaderProps) {
  return (
    <div
      className={cn(
        "flex min-h-12 items-start justify-between gap-4 border-b border-[var(--border)] px-4 py-3",
        className,
      )}
      {...props}
    >
      <div className="min-w-0">
        {title ? <div className="text-sm font-medium text-[var(--foreground)]">{title}</div> : null}
        {description ? (
          <div className="mt-1 text-xs text-[var(--muted-foreground)]">{description}</div>
        ) : null}
        {children}
      </div>
      {extra ? <div className="flex shrink-0 items-center gap-2">{extra}</div> : null}
    </div>
  );
}

export interface SectionCardProps {
  title: ReactNode;
  children: ReactNode;
  className?: string;
}

export function SectionCard({ title, children, className }: SectionCardProps) {
  return (
    <Card className={cn("p-4", className)}>
      <div className="mb-4 flex items-center gap-2">
        <span aria-hidden="true" className="h-4 w-[3px] shrink-0 bg-[var(--primary)]" />
        <div className="text-sm font-medium text-[var(--foreground)]">{title}</div>
      </div>
      {children}
    </Card>
  );
}
