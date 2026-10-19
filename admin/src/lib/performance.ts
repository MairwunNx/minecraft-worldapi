import { useEffect, useSyncExternalStore } from "react";
import type { Schema } from "@/api/client";
import { link } from "./ws";

export type Performance = Schema<"Performance">;

export interface Sample {
  at: number;
  tps: number;
  mspt: number;
  heapMb: number;
  cpu: number | null;
}

/** About an hour of samples: server.performance arrives every five seconds. */
const LIMIT = 720;

let latest: Performance | null = null;
let samples: Sample[] = [];
const listeners = new Set<() => void>();
let stop: (() => void) | null = null;

const record = (performance: Performance) => {
  latest = performance;
  const at = Date.parse(performance.at);
  if (samples.length && samples[samples.length - 1].at >= at) return notify();
  samples = [
    ...samples.slice(-(LIMIT - 1)),
    {
      at,
      tps: performance.tps.oneMinute,
      mspt: performance.mspt.average,
      heapMb: (performance.jvm?.heap.usedBytes ?? performance.memory.usedBytes) / 1_048_576,
      cpu: performance.cpu.process ?? null,
    },
  ];
  notify();
};

const notify = () => listeners.forEach((listener) => listener());

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => void listeners.delete(listener);
};

/** Keeps the history in this tab's memory from the first page that asks for it until sign-out. */
export const usePerformance = (enabled: boolean) => {
  useEffect(() => {
    if (!enabled) return;
    stop ??= link.subscribe("server.performance", (event) => record(event.data as unknown as Performance));
  }, [enabled]);
  const current = useSyncExternalStore(subscribe, () => latest);
  const history = useSyncExternalStore(subscribe, () => samples);
  return { current, history };
};

/** Forgets the history after signing out. */
export const resetPerformance = () => {
  stop?.();
  stop = null;
  latest = null;
  samples = [];
  notify();
};
