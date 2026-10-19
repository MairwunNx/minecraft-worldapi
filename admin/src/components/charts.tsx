import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from "@/components/ui/chart";
import { time } from "@/lib/format";

const axis = { tickLine: false, axisLine: false, tickMargin: 8, fontSize: 11 } as const;

/**
 * One live measure over time: a 2px line over a faint wash, x in epoch milliseconds. The card's title
 * names the measure, so there's no legend; one chart per measure, never two axes.
 */
export const LiveChart = <T extends { at: number }>({
  data,
  y,
  label,
  format,
  color = 1,
  domain,
}: {
  data: T[];
  y: keyof T & string;
  label: string;
  format: (value: number) => string;
  /** chart-1 … chart-5, the validated categorical order. */
  color?: 1 | 2 | 3 | 4 | 5;
  domain?: [number | "auto" | "dataMin" | "dataMax", number | "auto" | "dataMin" | "dataMax"];
}) => {
  const config = { [y]: { label, color: `var(--chart-${color})` } } satisfies ChartConfig;
  const id = `fill-${y}-${color}`;
  return (
    <ChartContainer config={config} className="aspect-auto h-36 w-full">
      <AreaChart data={data} margin={{ left: 0, right: 4, top: 6 }}>
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={`var(--color-${y})`} stopOpacity={0.16} />
            <stop offset="100%" stopColor={`var(--color-${y})`} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="at" type="number" scale="time" domain={["dataMin", "dataMax"]} {...axis} tickFormatter={(v: number) => time(v).slice(0, 5)} minTickGap={32} />
        <YAxis {...axis} width={44} domain={domain ?? [0, "auto"]} tickFormatter={(v: number) => format(v)} />
        <ChartTooltip
          content={
            <ChartTooltipContent
              labelFormatter={(_, payload) => time(Number(payload?.[0]?.payload?.at ?? 0))}
              formatter={(value) => <span className="tabular font-medium">{format(Number(value))}</span>}
              hideIndicator
            />
          }
        />
        <Area dataKey={y as never} type="monotone" isAnimationActive={false} stroke={`var(--color-${y})`} strokeWidth={2} fill={`url(#${id})`} activeDot={{ r: 4, strokeWidth: 2, stroke: "var(--card)" }} />
      </AreaChart>
    </ChartContainer>
  );
};

/** A share of a whole as a thin bar with its numbers in text ink beside it. */
export const Meter = ({ label, value, max, text }: { label: string; value: number; max: number; text: string }) => {
  const share = max > 0 ? Math.min(1, value / max) : 0;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="min-w-0 truncate">{label}</span>
        <span className="tabular shrink-0 text-xs text-muted-foreground">{text}</span>
      </div>
      <div className="h-1.5 rounded-full bg-muted" role="meter" aria-label={label} aria-valuenow={value} aria-valuemin={0} aria-valuemax={max}>
        <div className="h-full rounded-full bg-chart-1" style={{ width: `${Math.max(1, share * 100)}%` }} />
      </div>
    </div>
  );
};
