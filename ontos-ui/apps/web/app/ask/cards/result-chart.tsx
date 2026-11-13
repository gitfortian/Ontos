import { useEffect, useRef } from "react";

import * as echarts from "echarts/core";
import { BarChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";

import type { ChartData } from "../result-summary";

// 按需注册：bar + grid + tooltip + canvas，不引入全量 echarts（首屏体积约束）
echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

/** 主题色从 CSS 变量取——light/dark 自动跟随，不硬编码色值。 */
const themeColor = (name: string, fallback: string): string => {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
};

/**
 * 可视化区（ECharts）：分类维度 → 柱状图；点击柱子触发下钻
 * （提取该维度值拼成新话术自动发送——按批复的交互语义，不弹确认框）。
 */
export function ResultChart({
  data,
  disabled,
  onDrill,
}: {
  data: ChartData;
  disabled?: boolean;
  onDrill?: (utterance: string) => void;
}) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    const node = containerRef.current;
    if (!node) {
      return undefined;
    }
    const chart = echarts.init(node);
    chartRef.current = chart;
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(node);
    return () => {
      observer.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) {
      return;
    }
    const barColor = themeColor("--primary", "#4f46e5");
    const axisLabel = themeColor("--muted-foreground", "#64748b");
    const splitLine = themeColor("--border", "#e2e8f0");
    chart.setOption({
      grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
      tooltip: {
        trigger: "axis",
        axisPointer: { type: "shadow" },
        formatter: (params: unknown) => {
          const list = Array.isArray(params) ? params : [params];
          const first = list[0] as { name?: string; value?: number } | undefined;
          if (!first) {
            return "";
          }
          return `${first.name}<br/>${data.metricLabel}：${Number(first.value ?? 0).toLocaleString(
            "zh-CN",
            { maximumFractionDigits: 2 },
          )}`;
        },
      },
      xAxis: {
        type: "category",
        data: data.points.map((point) => point.label),
        axisLabel: { color: axisLabel, fontSize: 11, interval: 0, hideOverlap: true },
        axisLine: { lineStyle: { color: splitLine } },
        axisTick: { show: false },
      },
      yAxis: {
        type: "value",
        axisLabel: {
          color: axisLabel,
          fontSize: 11,
          formatter: (value: number) =>
            Math.abs(value) >= 10000
              ? `${(value / 10000).toLocaleString("zh-CN")}万`
              : String(value),
        },
        splitLine: { lineStyle: { color: splitLine } },
      },
      series: [
        {
          type: "bar",
          data: data.points.map((point) => point.value),
          barMaxWidth: 28,
          itemStyle: { color: barColor, borderRadius: [3, 3, 0, 0] },
        },
      ],
    });
    chart.off("click");
    if (onDrill && !disabled) {
      chart.on("click", (params) => {
        const point = data.points[params.dataIndex];
        if (point) {
          onDrill(`${data.dimensionLabel}为${point.rawLabel}的${data.metricLabel}是多少`);
        }
      });
    }
  }, [data, disabled, onDrill]);

  return (
    <div className="rounded-lg border border-border p-2">
      <div ref={containerRef} className="h-56 w-full" role="img" aria-label="结果柱状图" />
      {data.truncated ? (
        <p className="px-1.5 pb-1 text-[11px] text-muted-foreground">
          仅展示前 {data.points.length} 项，完整数据见「明细」
        </p>
      ) : (
        <p className="px-1.5 pb-1 text-[11px] text-muted-foreground">点击柱子可按该维度值下钻</p>
      )}
    </div>
  );
}
