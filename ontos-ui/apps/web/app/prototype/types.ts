export type PrototypeScenario =
  | "query"
  | "clarification"
  | "approval"
  | "composite"
  | "discovery"
  | "out_of_capability";

export interface PrototypeSession {
  id: string;
  title: string;
  time: string;
  scenario: PrototypeScenario;
  question: string;
}

export type RunPhase = "idle" | "running" | "ready";
export type ApprovalDecision = "pending" | "approved" | "rejected";
export type MetricName = "成交金额" | "订单量" | "客户数";
export type ClarificationChoice = MetricName | "skip";

export interface RegionResult {
  region: string;
  orders: number;
  amount: number;
  mom: number;
}
