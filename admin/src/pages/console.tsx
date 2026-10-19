import { useQuery } from "@tanstack/react-query";
import { ArrowDownToLine, Pause, Play, SendHorizontal, Trash2 } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { ErrorBox, LiveBadge, PageHeader } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { time } from "@/lib/format";
import { showError } from "@/lib/forms";
import { useCan } from "@/lib/session";
import { cn } from "@/lib/utils";
import { useTopic } from "@/lib/ws";

interface LogLine {
  at: string;
  level: string;
  logger?: string | null;
  message: string;
}

type Entry =
  | ({ kind: "line"; id: number } & LogLine)
  | { kind: "command"; id: number; at: string; command: string; output: string[]; success: boolean | null };

/** The console keeps this many entries; older ones fall off the top. */
const LIMIT = 3000;
const LEVELS = ["all", "info", "warn", "error"] as const;
const RANK: Record<string, number> = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, WARNING: 3, ERROR: 4, FATAL: 5 };

const levelClass = (level: string) => {
  const rank = RANK[level.toUpperCase()] ?? 2;
  if (rank >= 4) return "text-destructive";
  if (rank === 3) return "text-warning";
  if (rank <= 1) return "text-muted-foreground";
  return "";
};

let ids = 0;

export const ConsolePage = () => {
  const { t } = useTranslation();
  const can = useCan();
  const reads = can("logs:read");
  const runs = can("server:console");
  const [entries, setEntries] = useState<Entry[]>([]);
  const [paused, setPaused] = useState(false);
  const held = useRef<Entry[]>([]);
  const [follow, setFollow] = useState(true);
  const [filter, setFilter] = useState("");
  const [level, setLevel] = useState<(typeof LEVELS)[number]>("all");
  const box = useRef<HTMLDivElement>(null);

  const history = useQuery({
    queryKey: ["console", "history"],
    queryFn: () => data(client.GET("/server/logs", { params: { query: { limit: 500 } } })),
    enabled: reads,
    staleTime: Infinity,
    gcTime: 0,
  });
  useEffect(() => {
    if (history.data) setEntries((live) => [...history.data.lines.map((line): Entry => ({ kind: "line", id: ids++, ...line })), ...live].slice(-LIMIT));
  }, [history.data]);

  const add = (entry: Entry) => {
    if (paused) held.current.push(entry);
    else setEntries((current) => [...current, entry].slice(-LIMIT));
  };
  // Live lines start only after the history arrived, so the two don't overlap twice.
  useTopic<LogLine>("server.console", (event) => add({ kind: "line", id: ids++, ...event.data }), reads && !!history.data);

  const resume = () => {
    setPaused(false);
    const pending = held.current;
    held.current = [];
    setEntries((current) => [...current, ...pending].slice(-LIMIT));
  };

  useLayoutEffect(() => {
    if (follow && box.current) box.current.scrollTop = box.current.scrollHeight;
  }, [entries, follow, filter, level]);

  const minimum = level === "all" ? 0 : RANK[level.toUpperCase()];
  const needle = filter.trim().toLowerCase();
  const shown = entries.filter((entry) => {
    if (entry.kind === "command") return !needle || entry.command.toLowerCase().includes(needle);
    if ((RANK[entry.level.toUpperCase()] ?? 2) < minimum) return false;
    return !needle || entry.message.toLowerCase().includes(needle) || (entry.logger ?? "").toLowerCase().includes(needle);
  });

  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={t("console.title")} description={reads ? t("console.text") : t("console.noLogs")} actions={reads && <LiveBadge paused={paused} />} />
      {history.error && <ErrorBox error={history.error} />}
      <div className="flex flex-wrap gap-2">
        <Input className="min-w-0 flex-1 basis-48" placeholder={t("console.filter")} aria-label={t("console.filter")} value={filter} onChange={(event) => setFilter(event.target.value)} />
        <Select value={level} onValueChange={(value) => setLevel(value as typeof level)}>
          <SelectTrigger className="w-32" aria-label={t("console.level")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {LEVELS.map((value) => (
              <SelectItem key={value} value={value}>
                {t(`console.levels.${value}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button variant="outline" size="icon" onClick={() => (paused ? resume() : setPaused(true))} aria-label={paused ? t("console.resume") : t("console.pause")} title={paused ? t("console.resume") : t("console.pause")}>
          {paused ? <Play className="size-4" /> : <Pause className="size-4" />}
        </Button>
        <Button variant={follow ? "secondary" : "outline"} size="icon" onClick={() => setFollow(!follow)} aria-pressed={follow} aria-label={t("console.follow")} title={t("console.follow")}>
          <ArrowDownToLine className="size-4" />
        </Button>
        <Button variant="outline" size="icon" onClick={() => setEntries([])} aria-label={t("console.clear")} title={t("console.clear")}>
          <Trash2 className="size-4" />
        </Button>
      </div>
      <div
        ref={box}
        onScroll={(event) => {
          const element = event.currentTarget;
          const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
          if (atBottom !== follow) setFollow(atBottom);
        }}
        className="h-[60dvh] min-h-64 overflow-auto rounded-lg border bg-card p-2 font-mono text-xs leading-relaxed"
        role="log"
        aria-live="off"
      >
        {!shown.length && <p className="p-2 text-muted-foreground">{entries.length ? t("console.noMatch") : t("console.empty")}</p>}
        {shown.map((entry) =>
          entry.kind === "line" ? (
            <div key={entry.id} className={cn("break-words whitespace-pre-wrap", levelClass(entry.level))}>
              <span className="text-muted-foreground select-none">{time(entry.at)} </span>
              {entry.level.toUpperCase() !== "INFO" && <span className="font-semibold">{entry.level.toUpperCase()} </span>}
              {entry.logger && <span className="text-muted-foreground">[{entry.logger}] </span>}
              {entry.message}
            </div>
          ) : (
            <div key={entry.id} className="my-1 rounded border-l-2 border-primary bg-primary/5 px-2 py-1">
              <div className="font-semibold text-primary">
                <span className="font-normal text-muted-foreground select-none">{time(entry.at)} </span>&gt; {entry.command}
              </div>
              {entry.success === null ? (
                <div className="text-muted-foreground">{t("console.running")}</div>
              ) : entry.output.length ? (
                entry.output.map((line, index) => (
                  <div key={index} className={cn("break-words whitespace-pre-wrap", !entry.success && "text-destructive")}>
                    {line}
                  </div>
                ))
              ) : (
                <div className="text-muted-foreground">{entry.success ? t("console.noOutput") : t("console.failed")}</div>
              )}
            </div>
          ),
        )}
      </div>
      {runs && <CommandBox onRun={(entry) => setEntries((current) => [...current, entry].slice(-LIMIT))} onUpdate={(entry) => setEntries((current) => current.map((e) => (e.id === entry.id ? entry : e)))} />}
    </div>
  );
};

/** One command at a time; ↑ and ↓ walk through this visit's commands, like a terminal. */
const CommandBox = ({ onRun, onUpdate }: { onRun: (entry: Entry) => void; onUpdate: (entry: Entry) => void }) => {
  const { t } = useTranslation();
  const [command, setCommand] = useState("");
  const [busy, setBusy] = useState(false);
  const past = useRef<string[]>([]);
  const cursor = useRef(-1);
  const run = async () => {
    const text = command.trim().replace(/^\//, "");
    if (!text || busy) return;
    past.current = [text, ...past.current.filter((c) => c !== text)].slice(0, 50);
    cursor.current = -1;
    setCommand("");
    setBusy(true);
    const entry = { kind: "command" as const, id: ids++, at: new Date().toISOString(), command: text, output: [], success: null };
    onRun(entry);
    try {
      const result = await data(client.POST("/server/commands", { body: { command: text } }));
      onUpdate({ ...entry, output: result.output, success: result.success });
    } catch (error) {
      onUpdate({ ...entry, output: [], success: false });
      showError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <form
      className="flex gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        void run();
      }}
    >
      <Input
        className="min-w-0 flex-1 font-mono"
        placeholder={t("console.command")}
        aria-label={t("console.command")}
        autoComplete="off"
        spellCheck={false}
        value={command}
        onChange={(event) => setCommand(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
          event.preventDefault();
          const next = event.key === "ArrowUp" ? Math.min(past.current.length - 1, cursor.current + 1) : Math.max(-1, cursor.current - 1);
          cursor.current = next;
          setCommand(next < 0 ? "" : past.current[next]);
        }}
      />
      <Button type="submit" disabled={busy || !command.trim()}>
        <SendHorizontal className="size-4" aria-hidden />
        <span className="hidden sm:inline">{t("console.run")}</span>
      </Button>
    </form>
  );
};
