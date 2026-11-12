import {
  QueryClient,
  QueryClientProvider,
  useMutation,
  useQueryClient,
} from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ComponentType } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { TanStackMetricForm } from "./tanstack/MetricForm";
import { VALID_DRAFT, type FormHarnessProps, type MetricDraft } from "./metric-schema";

type Harness = ComponentType<FormHarnessProps>;

const implementations: ReadonlyArray<{ name: string; Component: Harness }> = [
  { name: "TanStack Form", Component: TanStackMetricForm },
];

afterEach(() => cleanup());

function QueryHarness({ Component }: { Component: Harness }) {
  const client = useQueryClient();
  const save = useMutation({
    mutationFn: async (draft: MetricDraft): Promise<MetricDraft> => draft,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["metric-drafts"] });
    },
  });
  return (
    <Component
      initial={VALID_DRAFT}
      onSave={(draft) => {
        void save.mutateAsync(draft);
      }}
    />
  );
}

function renderQuery(Component: Harness) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const invalidate = vi.spyOn(client, "invalidateQueries");
  render(
    <QueryClientProvider client={client}>
      <QueryHarness Component={Component} />
    </QueryClientProvider>,
  );
  return invalidate;
}

describe.each(implementations)("$name · 与 TanStack Query 联动", ({ Component }) => {
  it("提交成功触发 mutation 后按 queryKey 失效缓存", async () => {
    const invalidate = renderQuery(Component);
    fireEvent.click(screen.getByTestId("submit"));
    await waitFor(() => expect(invalidate).toHaveBeenCalled());
    expect(invalidate.mock.calls[0][0]).toMatchObject({ queryKey: ["metric-drafts"] });
  });
});
