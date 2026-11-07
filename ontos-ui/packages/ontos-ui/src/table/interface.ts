import type { CSSProperties, Key, ReactNode } from "react";

export type TableSize = "small" | "medium" | "large";
export type TableAlign = "left" | "center" | "right";

export interface TableColumn<RecordType extends object> {
  key?: Key;
  title?: ReactNode;
  dataIndex?: keyof RecordType;
  width?: CSSProperties["width"];
  minWidth?: CSSProperties["minWidth"];
  align?: TableAlign;
  ellipsis?: boolean;
  render?: (value: unknown, record: RecordType, index: number) => ReactNode;
}

export type TableColumns<RecordType extends object> = TableColumn<RecordType>[];

export interface TablePaginationConfig {
  current?: number;
  pageSize?: number;
  total?: number;
}

export interface TableScroll {
  x?: number | string;
  y?: number | string;
}

export interface TableProps<RecordType extends object> {
  columns: TableColumns<RecordType>;
  dataSource?: readonly RecordType[];
  rowKey?: keyof RecordType | ((record: RecordType) => Key);
  loading?: boolean;
  emptyText?: ReactNode;
  pagination?: false | TablePaginationConfig;
  size?: TableSize;
  className?: string;
  scroll?: TableScroll;
  bordered?: boolean;
  sticky?: boolean;
  rowHoverable?: boolean;
}
