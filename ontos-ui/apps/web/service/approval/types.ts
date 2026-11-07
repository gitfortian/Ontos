export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";

export interface ApprovalVO {
  id: string;
  tenantId: string;
  userId: string;
  sessionId?: string;
  turnIndex?: number;
  kind: string;
  status: ApprovalStatus;
  payload?: string;
  reason?: string;
  requestedAt?: string;
  decidedAt?: string;
  decidedBy?: string;
}

export interface PageResult<T> {
  total: number;
  list: T[];
}

export type ApprovalDecision = "APPROVED" | "REJECTED";
