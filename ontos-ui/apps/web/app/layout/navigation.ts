import {
  BookOpen,
  CheckSquare,
  FileText,
  GitCompare,
  History,
  Layers,
  MessageSquare,
  type LucideIcon,
} from "lucide-react";

export interface NavigationItem {
  label: string;
  path: string;
  hint: string;
  icon: LucideIcon;
}

export const NAVIGATION: readonly NavigationItem[] = [
  { label: "智能问数", path: "/ask", hint: "AG-UI 流式与确定性链路", icon: MessageSquare },
  { label: "发布清单", path: "/ontology/manifests", hint: "不可变语义资产", icon: Layers },
  { label: "数据词典", path: "/ontology/assets", hint: "当前生效的全部资产与引用", icon: BookOpen },
  { label: "版本对比", path: "/ontology/diff", hint: "两个清单的资产差异", icon: GitCompare },
  { label: "发布记录", path: "/ontology/records", hint: "每次预检与发布的留痕", icon: History },
  { label: "审批中心", path: "/approvals", hint: "敏感查询人工复核", icon: CheckSquare },
  { label: "审计日志", path: "/audit", hint: "只追加合规留痕", icon: FileText },
];

export { isNavigationActive } from "./navigation-path";
