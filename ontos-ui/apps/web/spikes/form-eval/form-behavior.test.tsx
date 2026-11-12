import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ComponentType } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { TanStackMetricForm } from "./tanstack/MetricForm";
import { EMPTY_DRAFT, VALID_DRAFT, type FormHarnessProps, type MetricDraft } from "./metric-schema";

type Harness = ComponentType<FormHarnessProps>;

// ADR-0024 定档后对照实现已移除；保留参数化结构便于后续按需追加替代方案回归。
const implementations: ReadonlyArray<{ name: string; Component: Harness }> = [
  { name: "TanStack Form", Component: TanStackMetricForm },
];

afterEach(() => cleanup());

function renderForm(Component: Harness, initial: MetricDraft = EMPTY_DRAFT) {
  const onSave = vi.fn((_draft: MetricDraft) => Promise.resolve());
  render(<Component initial={initial} onSave={onSave} />);
  return onSave;
}

function currentValues(): MetricDraft {
  return JSON.parse(screen.getByTestId("values").textContent ?? "{}") as MetricDraft;
}

function fillRequired() {
  fireEvent.change(screen.getByTestId("id"), { target: { value: "metric.order_amount" } });
  fireEvent.change(screen.getByTestId("name"), { target: { value: "订单金额" } });
  fireEvent.change(screen.getByTestId("propertyId"), {
    target: { value: "property.order.amount" },
  });
  fireEvent.click(screen.getByTestId("dimensions-dim.region"));
}

describe.each(implementations)("$name · 原子指标草稿", ({ name, Component }) => {
  it("初始状态与默认值一致，dirty=false", () => {
    renderForm(Component);
    expect(screen.getByTestId("dirty").textContent).toBe("false");
    expect(currentValues().objectId).toBe("concept.order");
    expect(currentValues().variants).toEqual([]);
  });

  it("输入后 dirty=true 且值同步", async () => {
    renderForm(Component);
    fireEvent.change(screen.getByTestId("id"), { target: { value: "metric.gmv" } });
    await waitFor(() => expect(currentValues().id).toBe("metric.gmv"));
    expect(screen.getByTestId("dirty").textContent).toBe("true");
  });

  it("提交空草稿：显示字段错误且不落库", async () => {
    const onSave = renderForm(Component);
    fireEvent.click(screen.getByTestId("submit"));
    await waitFor(() =>
      expect(screen.getByTestId("error-name").textContent).toContain("指标名称必填"),
    );
    expect(onSave).not.toHaveBeenCalled();
  });

  it("完整有效草稿：提交 payload 结构正确", async () => {
    const onSave = renderForm(Component);
    fillRequired();
    fireEvent.click(screen.getByTestId("submit"));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const payload = onSave.mock.calls[0][0];
    expect(payload).toMatchObject({
      id: "metric.order_amount",
      name: "订单金额",
      objectId: "concept.order",
      propertyId: "property.order.amount",
      aggregation: "SUM",
      allowedDimensions: ["dim.region"],
    });
  });

  it("切换业务对象会清空属性（联动）", async () => {
    renderForm(Component);
    fireEvent.change(screen.getByTestId("propertyId"), {
      target: { value: "property.order.amount" },
    });
    fireEvent.change(screen.getByTestId("objectId"), {
      target: { value: "concept.customer" },
    });
    await waitFor(() => expect(currentValues().objectId).toBe("concept.customer"));
    expect(currentValues().propertyId).toBe("");
  });

  it("COUNT 对象级计数：propertyId 为空仍合法", async () => {
    const onSave = renderForm(Component, {
      ...VALID_DRAFT,
      aggregation: "COUNT",
      propertyId: "",
    });
    fireEvent.click(screen.getByTestId("submit"));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave.mock.calls[0][0].propertyId).toBe("");
  });

  it("非 COUNT 且未选属性：跨字段校验拦截", async () => {
    const onSave = renderForm(Component, { ...VALID_DRAFT, propertyId: "" });
    fireEvent.click(screen.getByTestId("submit"));
    await waitFor(() =>
      expect(screen.getByTestId("error-propertyId").textContent).toContain(
        "非 COUNT 聚合必须选择属性",
      ),
    );
    expect(onSave).not.toHaveBeenCalled();
  });

  it("动态嵌套数组：新增、填写、删除", async () => {
    renderForm(Component);
    fireEvent.click(screen.getByTestId("variant-add"));
    await waitFor(() => expect(currentValues().variants).toHaveLength(1));
    fireEvent.change(screen.getByTestId("variant-0-field"), {
      target: { value: "dim.channel" },
    });
    fireEvent.change(screen.getByTestId("variant-0-value"), { target: { value: "线上" } });
    await waitFor(() => expect(currentValues().variants[0].field).toBe("dim.channel"));
    fireEvent.click(screen.getByTestId("variant-0-remove"));
    await waitFor(() => expect(currentValues().variants).toHaveLength(0));
  });

  it("字段级订阅：改动 id 时 name 控件的重渲染次数", async () => {
    renderForm(Component);
    const before = Number(screen.getByTestId("render-name").textContent);
    fireEvent.change(screen.getByTestId("id"), { target: { value: "metric.gmv" } });
    await waitFor(() => expect(currentValues().id).toBe("metric.gmv"));
    const after = Number(screen.getByTestId("render-name").textContent);
    process.stdout.write(`[render-count] ${name}: name-field ${before} -> ${after}\n`);
    // 断言只保证计数可读；差异结论在 README 里按实测数字给。
    expect(after).toBeGreaterThanOrEqual(before);
  });
});
