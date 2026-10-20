"use client";

import * as React from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
  type TooltipValueType,
} from "recharts";

import { cn } from "@/lib/utils";

/** Series colors from the design tokens (light/dark aware through CSS variables). */
export const CHART_COLORS = ["var(--chart-1)", "var(--chart-2)", "var(--chart-3)", "var(--chart-4)", "var(--chart-5)"];

const AXIS_TICK = { fill: "var(--chart-axis)", fontSize: 11 };

export interface ChartSeries<K extends string = string> {
  key: K;
  label: string;
  color?: string;
}

export type ChartDatum = Record<string, string | number | null>;

interface TooltipEntry {
  dataKey?: unknown;
  name?: unknown;
  value?: unknown;
  color?: string;
}

function ChartTooltip({
  active,
  payload,
  label,
  formatValue,
  labelFormatter,
}: {
  active?: boolean;
  payload?: readonly TooltipEntry[];
  label?: string | number;
  formatValue?: (value: number, key: string) => string;
  labelFormatter?: (label: string) => string;
}) {
  if (!active || !payload?.length) return null;
  const heading = label === undefined ? null : labelFormatter ? labelFormatter(String(label)) : String(label);
  return (
    <div className="min-w-36 rounded-md border border-border bg-popover px-2.5 py-2 text-xs text-popover-foreground shadow-md">
      {heading ? <p className="mb-1 font-medium">{heading}</p> : null}
      <ul className="flex flex-col gap-0.5">
        {payload.map((entry) => (
          <li key={String(entry.dataKey)} className="flex items-center gap-2">
            <span aria-hidden className="size-2 rounded-[2px]" style={{ background: entry.color }} />
            <span className="text-muted-foreground">{String(entry.name ?? "")}</span>
            <span className="ml-auto pl-3 font-medium tabular">
              {typeof entry.value === "number" ? (formatValue ? formatValue(entry.value, String(entry.dataKey)) : entry.value.toLocaleString("en-IN")) : String(entry.value ?? "–")}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Fixed-height responsive frame; renders an accessible summary for screen readers. */
export function ChartFrame({
  height = 240,
  label,
  className,
  children,
}: {
  height?: number;
  /** Text alternative for the chart. */
  label: string;
  className?: string;
  children: React.ReactElement;
}) {
  return (
    <div role="img" aria-label={label} className={cn("w-full", className)} style={{ height }}>
      <ResponsiveContainer width="100%" height="100%" minWidth={0}>
        {children}
      </ResponsiveContainer>
    </div>
  );
}

function legendText(value: string) {
  return <span className="text-muted-foreground">{value}</span>;
}

function tooltipProps(props: TooltipContentProps<TooltipValueType, string | number>) {
  return { active: props.active, payload: props.payload as readonly TooltipEntry[] | undefined, label: props.label };
}

export function SimpleBarChart({
  data,
  xKey,
  series,
  height,
  label,
  layout = "horizontal",
  formatValue,
  colorByIndex = false,
  stacked = false,
  showLegend,
  yWidth,
}: {
  data: ChartDatum[];
  xKey: string;
  series: ChartSeries[];
  height?: number;
  label: string;
  /** `vertical` draws horizontal bars (categories on the y axis). */
  layout?: "horizontal" | "vertical";
  formatValue?: (value: number, key: string) => string;
  /** One color per category (single series). */
  colorByIndex?: boolean;
  stacked?: boolean;
  showLegend?: boolean;
  yWidth?: number;
}) {
  const vertical = layout === "vertical";
  return (
    <ChartFrame height={height} label={label}>
      <BarChart data={data} layout={layout} margin={{ top: 8, right: 12, bottom: 0, left: vertical ? 4 : -12 }} barCategoryGap="22%">
        <CartesianGrid stroke="var(--chart-grid)" vertical={vertical} horizontal={!vertical} />
        {vertical ? (
          <>
            <XAxis type="number" tick={AXIS_TICK} axisLine={false} tickLine={false} allowDecimals={false} />
            <YAxis
              type="category"
              dataKey={xKey}
              tick={AXIS_TICK}
              axisLine={false}
              tickLine={false}
              width={yWidth ?? 110}
              interval={0}
            />
          </>
        ) : (
          <>
            <XAxis dataKey={xKey} tick={AXIS_TICK} axisLine={false} tickLine={false} interval="preserveStartEnd" />
            <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} allowDecimals={false} width={yWidth ?? 44} />
          </>
        )}
        <Tooltip
          cursor={{ fill: "var(--accent)", opacity: 0.6 }}
          content={(props) => <ChartTooltip {...tooltipProps(props)} formatValue={formatValue} />}
        />
        {showLegend ?? series.length > 1 ? (
          <Legend iconType="square" iconSize={8} itemSorter={null} formatter={legendText} wrapperStyle={{ fontSize: 11 }} />
        ) : null}
        {series.map((s, index) => (
          <Bar
            key={s.key}
            dataKey={s.key}
            name={s.label}
            fill={s.color ?? CHART_COLORS[index % CHART_COLORS.length]}
            radius={stacked ? 0 : vertical ? [0, 3, 3, 0] : [3, 3, 0, 0]}
            stackId={stacked ? "stack" : undefined}
            maxBarSize={vertical ? 18 : 40}
            isAnimationActive={false}
          >
            {colorByIndex
              ? data.map((_, i) => <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />)
              : null}
          </Bar>
        ))}
      </BarChart>
    </ChartFrame>
  );
}

export function SimpleLineChart({
  data,
  xKey,
  series,
  height,
  label,
  formatX,
}: {
  data: ChartDatum[];
  xKey: string;
  series: ChartSeries[];
  height?: number;
  label: string;
  formatX?: (value: string) => string;
}) {
  return (
    <ChartFrame height={height} label={label}>
      <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
        <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
        <XAxis
          dataKey={xKey}
          tick={AXIS_TICK}
          axisLine={false}
          tickLine={false}
          tickFormatter={formatX ? (value: string) => formatX(String(value)) : undefined}
          interval="preserveStartEnd"
        />
        <YAxis tick={AXIS_TICK} axisLine={false} tickLine={false} allowDecimals={false} width={44} />
        <Tooltip
          cursor={{ stroke: "var(--border-strong)" }}
          content={(props) => <ChartTooltip {...tooltipProps(props)} labelFormatter={formatX} />}
        />
        <Legend iconType="plainline" iconSize={12} itemSorter={null} formatter={legendText} wrapperStyle={{ fontSize: 11 }} />
        {series.map((s, index) => (
          <Line
            key={s.key}
            type="monotone"
            dataKey={s.key}
            name={s.label}
            stroke={s.color ?? CHART_COLORS[index % CHART_COLORS.length]}
            strokeWidth={2}
            dot={false}
            activeDot={{ r: 3.5 }}
            isAnimationActive={false}
          />
        ))}
      </LineChart>
    </ChartFrame>
  );
}

/** Thin horizontal proportion bar (inline, no Recharts) for dense tables and lists. */
export function MeterBar({ value, max, className, color = "var(--chart-1)" }: { value: number; max: number; className?: string; color?: string }) {
  const percent = max > 0 ? Math.min(100, (value / max) * 100) : 0;
  return (
    <div className={cn("h-1.5 w-full overflow-hidden rounded-full bg-muted", className)} aria-hidden>
      <div className="h-full rounded-full transition-[width] duration-300" style={{ width: `${percent}%`, background: color }} />
    </div>
  );
}
