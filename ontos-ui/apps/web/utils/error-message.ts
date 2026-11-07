/** Returns the server-facing business message without exposing transport details. */
export const failureMessage = (error: unknown, fallback = "操作失败，请稍后重试"): string =>
  error instanceof Error && error.message ? error.message : fallback;
