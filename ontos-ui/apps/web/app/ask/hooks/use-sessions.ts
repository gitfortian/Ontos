import { useQuery } from "@tanstack/react-query";

import { listAskSessions, type AskSessionVO } from "@/service/sessions";

/** 侧栏会话列表：REST 数据归 TanStack Query（流式运行态归 Zustand，边界见 store 注释）。 */
export function useSessions(limit = 20) {
  return useQuery({
    queryKey: ["ask", "sessions", limit],
    queryFn: ({ signal }) => listAskSessions(limit, signal),
    staleTime: 30_000,
  });
}

export type { AskSessionVO };
