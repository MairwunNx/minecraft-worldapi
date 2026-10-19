import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Power, RotateCcw, Save, X } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { client, data, type Schema } from "@/api/client";
import { LiveChart, Meter } from "@/components/charts";
import { Confirm, Definition, Empty, ErrorBox, LiveBadge, Loading, PageHeader, StatTile, TableFrame } from "@/components/common";
import { FormField } from "@/components/form-field";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { bytes, number, percent, span, time } from "@/lib/format";
import { showError } from "@/lib/forms";
import { type Performance, usePerformance } from "@/lib/performance";
import { useCan } from "@/lib/session";

const Tiles = ({ server, live }: { server?: Schema<"ServerInfo">; live: Performance | null }) => {
  const { t } = useTranslation();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const tps = live?.tps.oneMinute ?? server?.tps;
  const mspt = live?.mspt.average ?? server?.mspt;
  const heapUsed = live ? (live.jvm?.heap.usedBytes ?? live.memory.usedBytes) : server ? server.memoryUsedMb * 1_048_576 : undefined;
  const heapMax = live ? (live.jvm?.heap.maxBytes ?? live.memory.maxBytes) : server ? server.memoryMaxMb * 1_048_576 : undefined;
  const players = live ? live.worlds.reduce((sum, world) => sum + world.players, 0) : server?.onlinePlayers;
  const uptime = server ? (now - Date.parse(server.startedAt)) / 1000 : undefined;
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
      <StatTile label={t("overview.tps")} value={tps === undefined ? "—" : number(tps, 1)} hint={live && t("overview.tpsHint", { five: number(live.tps.fiveMinutes, 1), fifteen: number(live.tps.fifteenMinutes, 1) })} />
      <StatTile label={t("overview.mspt")} value={mspt === undefined ? "—" : `${number(mspt, 1)} ms`} hint={live && t("overview.msptHint", { p95: number(live.mspt.p95, 1) })} />
      <StatTile label={t("overview.heap")} value={bytes(heapUsed)} hint={heapMax ? t("overview.of", { max: bytes(heapMax) }) : undefined} />
      <StatTile
        label={t("overview.cpu")}
        value={live?.cpu.process == null ? "—" : percent(live.cpu.process)}
        hint={live && t("overview.cores", { count: live.cpu.cores, system: live.cpu.system == null ? "—" : percent(live.cpu.system) })}
      />
      <StatTile label={t("overview.players")} value={players === undefined ? "—" : number(players)} hint={server && t("overview.of", { max: number(server.maxPlayers) })} />
      <StatTile label={t("overview.uptime")} value={span(uptime)} hint={server && `${server.brand} ${server.minecraftVersion}`} />
    </div>
  );
};

const Charts = ({ history }: { history: ReturnType<typeof usePerformance>["history"] }) => {
  const { t } = useTranslation();
  if (history.length < 2) return <Empty>{t("overview.collecting")}</Empty>;
  const hasCpu = history.some((sample) => sample.cpu !== null);
  const cards: Array<[string, React.ReactNode]> = [
    [t("overview.tps"), <LiveChart key="tps" data={history} y="tps" label={t("overview.tps")} format={(v) => number(v, 1)} domain={[0, 20]} color={1} />],
    [t("overview.mspt"), <LiveChart key="mspt" data={history} y="mspt" label={t("overview.mspt")} format={(v) => `${number(v, 1)}`} color={2} />],
    [t("overview.heapMb"), <LiveChart key="heap" data={history} y="heapMb" label={t("overview.heapMb")} format={(v) => number(v)} color={3} />],
  ];
  if (hasCpu)
    cards.push([t("overview.cpu"), <LiveChart key="cpu" data={history.map((s) => ({ at: s.at, cpu: (s.cpu ?? 0) * 100 }))} y="cpu" label={t("overview.cpuPercent")} format={(v) => number(v)} color={4} />]);
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
      {cards.map(([title, chart]) => (
        <Card key={title} className="gap-2 py-3">
          <CardHeader className="px-4">
            <CardTitle className="text-sm font-medium">{title}</CardTitle>
          </CardHeader>
          <CardContent className="px-2">{chart}</CardContent>
        </Card>
      ))}
    </div>
  );
};

const Worlds = ({ live }: { live: Performance }) => {
  const { t } = useTranslation();
  return (
    <TableFrame>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("overview.world")}</TableHead>
            <TableHead className="text-right">{t("overview.players")}</TableHead>
            <TableHead className="text-right">{t("overview.chunks")}</TableHead>
            <TableHead className="text-right">{t("overview.entities")}</TableHead>
            <TableHead className="hidden text-right sm:table-cell">{t("overview.tiles")}</TableHead>
            <TableHead className="hidden md:table-cell">{t("overview.common")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {live.worlds.map((world) => (
            <TableRow key={world.world}>
              <TableCell className="font-medium">
                <Link to="/worlds/$world" params={{ world: world.world }} className="hover:underline">
                  {world.world}
                </Link>
              </TableCell>
              <TableCell className="tabular text-right">{number(world.players)}</TableCell>
              <TableCell className="tabular text-right">{number(world.loadedChunks)}</TableCell>
              <TableCell className="tabular text-right">{number(world.entities)}</TableCell>
              <TableCell className="tabular hidden text-right sm:table-cell">{number(world.tileEntities)}</TableCell>
              <TableCell className="hidden max-w-80 truncate text-xs text-muted-foreground md:table-cell">
                {Object.entries(world.entityTypes ?? {})
                  .slice(0, 4)
                  .map(([type, count]) => `${type.replace("minecraft:", "")} ${number(count)}`)
                  .join(", ")}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableFrame>
  );
};

/** The live topic leaves out Java's details, so this card asks GET /server/performance now and then. */
const JvmCard = () => {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["performance"], queryFn: () => data(client.GET("/server/performance")), refetchInterval: 15_000 });
  const jvm = query.data?.jvm;
  if (!jvm) return null;
  return (
    <Card className="gap-3 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-sm font-medium">{t("overview.jvm")}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-4">
        <Meter label={t("overview.heapLabel")} value={jvm.heap.usedBytes} max={jvm.heap.maxBytes ?? jvm.heap.committedBytes} text={`${bytes(jvm.heap.usedBytes)} / ${bytes(jvm.heap.maxBytes ?? jvm.heap.committedBytes)}`} />
        <Meter label={t("overview.nonHeap")} value={jvm.nonHeap.usedBytes} max={jvm.nonHeap.committedBytes} text={`${bytes(jvm.nonHeap.usedBytes)} / ${bytes(jvm.nonHeap.committedBytes)}`} />
        <Definition
          items={[
            [t("overview.threads"), t("overview.threadsValue", { live: jvm.threads.live, daemon: jvm.threads.daemon, peak: jvm.threads.peak })],
            [t("overview.classes"), number(jvm.classesLoaded)],
            ...jvm.gc.map((gc): [string, string] => [gc.name, t("overview.gcValue", { count: gc.count, time: number(gc.timeMillis / 1000, 1) })]),
          ]}
        />
      </CardContent>
    </Card>
  );
};

const SystemCard = () => {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["system"], queryFn: () => data(client.GET("/server/system")), refetchInterval: 30_000 });
  const system = query.data;
  return (
    <Card className="gap-3 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-sm font-medium">{t("overview.system")}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-4">
        {query.error ? (
          <ErrorBox error={query.error} />
        ) : !system ? (
          <Loading rows={3} />
        ) : (
          <>
            {system.memory.totalBytes != null && system.memory.availableBytes != null && (
              <Meter
                label={t("overview.ram")}
                value={system.memory.totalBytes - system.memory.availableBytes}
                max={system.memory.totalBytes}
                text={`${bytes(system.memory.totalBytes - system.memory.availableBytes)} / ${bytes(system.memory.totalBytes)}`}
              />
            )}
            {system.container?.memoryLimitBytes != null && (
              <Meter
                label={t("overview.containerMemory")}
                value={system.container.memoryUsedBytes ?? 0}
                max={system.container.memoryLimitBytes}
                text={`${bytes(system.container.memoryUsedBytes)} / ${bytes(system.container.memoryLimitBytes)}`}
              />
            )}
            <Meter
              label={t("overview.disk")}
              value={system.disk.totalBytes - system.disk.usableBytes}
              max={system.disk.totalBytes}
              text={t("overview.diskFree", { free: bytes(system.disk.usableBytes), total: bytes(system.disk.totalBytes) })}
            />
            <Definition
              items={[
                [t("overview.os"), `${system.os} ${system.osVersion} (${system.arch})`],
                [t("overview.cpus"), system.container?.cpuLimit ? t("overview.cpusLimited", { count: system.cpus, limit: number(system.container.cpuLimit, 2) }) : number(system.cpus)],
                [t("overview.load"), system.loadAverage?.map((v) => number(v, 2)).join(" · ") ?? "—"],
                [t("overview.swap"), system.memory.swapTotalBytes ? `${bytes(system.memory.swapTotalBytes - (system.memory.swapFreeBytes ?? 0))} / ${bytes(system.memory.swapTotalBytes)}` : "—"],
                [t("overview.openFiles"), system.openFiles == null ? "—" : `${number(system.openFiles)} / ${system.maxOpenFiles == null ? "—" : number(system.maxOpenFiles)}`],
                [t("overview.java"), `${system.java.name} ${system.java.version}`],
              ]}
            />
          </>
        )}
      </CardContent>
    </Card>
  );
};

type RestartStatus = Schema<"RestartStatus">;

/** Restart and stop with a countdown in chat; the pending one shows here until it happens or someone calls it off. */
const ControlCard = () => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const pending = useQuery<RestartStatus | null>({ queryKey: ["restart"], queryFn: () => null, staleTime: Infinity, gcTime: Infinity }).data;
  const [seconds, setSeconds] = useState("60");
  const [message, setMessage] = useState("");
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!pending?.scheduled) return;
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => window.clearInterval(timer);
  }, [pending?.scheduled]);
  const left = pending?.at ? Math.max(0, Math.ceil((Date.parse(pending.at) - now) / 1000)) : null;
  const countdown = Math.max(0, Math.min(3600, Number.parseInt(seconds, 10) || 0));
  const schedule = async (kind: "restart" | "stop") => {
    try {
      const body = { countdownSeconds: countdown, message: message.trim() || undefined };
      const status = kind === "restart" ? await data(client.POST("/server/restart", { body })) : await data(client.POST("/server/stop", { body }));
      queryClient.setQueryData(["restart"], { ...status, kind: status.kind ?? kind });
      toast.success(t(kind === "restart" ? "control.restartScheduled" : "control.stopScheduled", { seconds: countdown }));
    } catch (error) {
      showError(error);
      throw error;
    }
  };
  const cancel = async () => {
    try {
      await data(client.DELETE("/server/restart"));
      queryClient.setQueryData(["restart"], null);
      toast.success(t("control.cancelled"));
    } catch (error) {
      showError(error);
    }
  };
  const save = async () => {
    try {
      await data(client.POST("/server/save", { body: {} }));
      toast.success(t("control.saved"));
    } catch (error) {
      showError(error);
    }
  };
  return (
    <Card className="gap-3 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-sm font-medium">{t("control.title")}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-4">
        {pending?.scheduled && left !== null && (
          <div className="flex items-center justify-between gap-3 rounded-md border border-warning/50 bg-warning/10 p-3 text-sm" role="status">
            <span>{t(pending.kind === "stop" ? "control.stopIn" : "control.restartIn", { seconds: left })}</span>
            <Button size="sm" variant="outline" onClick={cancel}>
              <X className="size-4" aria-hidden />
              {t("control.cancel")}
            </Button>
          </div>
        )}
        <div className="grid grid-cols-[6rem_minmax(0,1fr)] gap-3">
          <FormField id="countdown" label={t("control.countdown")}>
            <Input id="countdown" type="number" min={0} max={3600} inputMode="numeric" value={seconds} onChange={(event) => setSeconds(event.target.value)} />
          </FormField>
          <FormField id="restart-message" label={t("control.message")}>
            <Input id="restart-message" value={message} placeholder={t("control.messagePlaceholder")} onChange={(event) => setMessage(event.target.value)} />
          </FormField>
        </div>
        <div className="flex flex-wrap gap-2">
          <Confirm
            label={
              <>
                <RotateCcw className="size-4" aria-hidden />
                {t("control.restart")}
              </>
            }
            title={t("control.restartTitle")}
            text={t("control.restartText", { seconds: countdown })}
            action={t("control.restart")}
            onConfirm={() => schedule("restart")}
          />
          <Confirm
            destructive
            label={
              <>
                <Power className="size-4" aria-hidden />
                {t("control.stop")}
              </>
            }
            title={t("control.stopTitle")}
            text={t("control.stopText", { seconds: countdown })}
            action={t("control.stop")}
            onConfirm={() => schedule("stop")}
          />
          <Button size="sm" variant="ghost" onClick={save}>
            <Save className="size-4" aria-hidden />
            {t("control.save")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
};

const OnlineCard = () => {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["players", "online"], queryFn: () => data(client.GET("/players/online")), refetchInterval: 15_000 });
  return (
    <Card className="gap-3 py-4">
      <CardHeader className="px-4">
        <CardTitle className="text-sm font-medium">{t("overview.online")}</CardTitle>
      </CardHeader>
      <CardContent className="px-4">
        {query.error ? (
          <ErrorBox error={query.error} />
        ) : !query.data ? (
          <Loading rows={2} />
        ) : !query.data.players.length ? (
          <p className="text-sm text-muted-foreground">{t("overview.nobody")}</p>
        ) : (
          <ul className="flex flex-col divide-y text-sm">
            {query.data.players.map((player) => (
              <li key={player.uuid} className="flex items-center justify-between gap-3 py-1.5">
                <Link to="/players/$player" params={{ player: player.name }} className="min-w-0 truncate font-medium hover:underline">
                  {player.name}
                </Link>
                <span className="tabular shrink-0 text-xs text-muted-foreground">
                  {player.world} · {t("overview.ping", { ms: player.ping })}
                </span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
};

export const OverviewPage = () => {
  const { t } = useTranslation();
  const can = useCan();
  const allowed = can("server:read");
  const server = useQuery({ queryKey: ["server"], queryFn: () => data(client.GET("/server")), refetchInterval: 60_000, enabled: allowed });
  const { current, history } = usePerformance(allowed);
  if (!allowed) return <Empty>{t("overview.noAccess")}</Empty>;
  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={server.data?.name || t("overview.title")}
        description={server.data && t("overview.subtitle", { brand: server.data.brand, version: server.data.minecraftVersion, worldapi: server.data.worldApiVersion })}
        actions={
          <>
            {current && <span className="text-xs text-muted-foreground">{t("overview.updated", { time: time(current.at) })}</span>}
            <LiveBadge />
          </>
        }
      />
      {server.error && <ErrorBox error={server.error} />}
      <Tiles server={server.data} live={current} />
      <Charts history={history} />
      {current && (
        <section className="flex flex-col gap-2">
          <h2 className="text-sm font-medium">{t("overview.worlds")}</h2>
          <Worlds live={current} />
        </section>
      )}
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        {can("server:control") && <ControlCard />}
        {can("players:read") && <OnlineCard />}
        <JvmCard />
        {can("system:read") && <SystemCard />}
      </div>
      {server.data?.frozen && <Badge variant="outline">{t("overview.frozen")}</Badge>}
    </div>
  );
};
