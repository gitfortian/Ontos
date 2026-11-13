import {
  BookOpen,
  Boxes,
  CheckSquare,
  Database,
  FileText,
  GitCompare,
  History,
  Layers,
  MessageSquare,
  Network,
  Terminal,
  type LucideIcon,
} from "lucide-react";

export type NavigationGroup = "问数" | "语义建模" | "治理" | "调试";

export interface NavigationItem {
  label: string;
  path: string;
  hint: string;
  icon: LucideIcon;
  group: NavigationGroup;
}

export const NAVIGATION: readonly NavigationItem[] = [
  {
    group: "问数",
    label: "智能问数",
    path: "/ask",
    hint: "自然语言问业务数据",
    icon: MessageSquare,
  },

  {
    group: "语义建模",
    label: "数据源",
    path: "/data/sources",
    hint: "查询库与物理表引用",
    icon: Database,
  },
  {
    group: "语义建模",
    label: "本体建模",
    path: "/ontology/modeling",
    hint: "业务对象、信息项与关联",
    icon: Network,
  },
  {
    group: "语义建模",
    label: "指标与维度",
    path: "/ontology/definitions",
    hint: "草稿、校验与审核",
    icon: Boxes,
  },
  {
    group: "语义建模",
    label: "数据词典",
    path: "/ontology/assets",
    hint: "生效资产与引用反查",
    icon: BookOpen,
  },

  {
    group: "治理",
    label: "发布清单",
    path: "/ontology/manifests",
    hint: "不可变语义资产",
    icon: Layers,
  },
  {
    group: "治理",
    label: "版本对比",
    path: "/ontology/diff",
    hint: "两个清单的资产差异",
    icon: GitCompare,
  },
  {
    group: "治理",
    label: "发布记录",
    path: "/ontology/records",
    hint: "每次预检与发布的留痕",
    icon: History,
  },
  {
    group: "治理",
    label: "审批中心",
    path: "/approvals",
    hint: "敏感查询人工复核",
    icon: CheckSquare,
  },
  { group: "治理", label: "审计日志", path: "/audit", hint: "只追加合规留痕", icon: FileText },

  {
    group: "调试",
    label: "链路调试",
    path: "/debug",
    hint: "DSL、能力调用与事件流",
    icon: Terminal,
  },
];

export interface NavigationSection {
  group: NavigationGroup;
  items: NavigationItem[];
}

/** 按声明顺序分组，避免每个渲染点各写一份顺序。 */
export const NAVIGATION_SECTIONS: readonly NavigationSection[] = NAVIGATION.reduce<
  NavigationSection[]
>((sections, item) => {
  const last = sections.at(-1);
  if (last && last.group === item.group) {
    last.items.push(item);
    return sections;
  }
  sections.push({ group: item.group, items: [item] });
  return sections;
}, []);

export { isNavigationActive } from "./navigation-path";
