import type { PrototypeSession, RegionResult } from "./types";

export const PROTOTYPE_SESSIONS: readonly PrototypeSession[] = [
  {
    id: "revenue",
    title: "上月各地区成交金额",
    time: "刚刚",
    scenario: "query",
    question: "上个月各地区的成交金额是多少？",
  },
  {
    id: "composite",
    title: "区域客户数与订单量对比",
    time: "12 分钟前",
    scenario: "composite",
    question: "对比各区域客户数和订单量，并列出客户联系方式",
  },
  {
    id: "approval",
    title: "高价值客户最近订单",
    time: "昨天 18:42",
    scenario: "approval",
    question: "查看高价值客户的最近订单明细",
  },
  {
    id: "clarification",
    title: "本月销售情况",
    time: "昨天 16:20",
    scenario: "clarification",
    question: "这个月销售怎么样？",
  },
  {
    id: "discovery",
    title: "我能查哪些数据",
    time: "周一",
    scenario: "discovery",
    question: "我能查哪些数据？",
  },
  {
    id: "prediction",
    title: "预测下个月销量",
    time: "周一",
    scenario: "out_of_capability",
    question: "预测一下下个月各地区的销量",
  },
];

export const REGION_RESULTS: readonly RegionResult[] = [
  { region: "华东", orders: 3821, amount: 4728400, mom: 12.6 },
  { region: "华南", orders: 2648, amount: 3156800, mom: 9.8 },
  { region: "华北", orders: 2196, amount: 2487600, mom: 6.2 },
  { region: "西南", orders: 1284, amount: 1462300, mom: 3.4 },
  { region: "华中", orders: 1065, amount: 1011100, mom: -1.8 },
];

export const EXAMPLE_PROMPTS = [
  {
    title: "看经营表现",
    prompt: "上个月各地区的成交金额是多少？",
    description: "成交金额 · 地区对比",
    scenario: "query",
  },
  {
    title: "找结构差异",
    prompt: "对比各区域客户数和订单量，并列出客户联系方式",
    description: "客户数 · 订单量 · 部分失败同屏",
    scenario: "composite",
  },
  {
    title: "了解可查范围",
    prompt: "我能查哪些数据？",
    description: "指标 · 维度 · 常用分析",
    scenario: "discovery",
  },
] as const;
