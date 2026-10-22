import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useParams } from "@tanstack/react-router";
import { ArrowLeft, Search } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { client, data, type Schema } from "@/api/client";
import { Definition, Empty, ErrorBox, Loading, PageHeader, Secret, TableFrame } from "@/components/common";
import { FormField } from "@/components/form-field";
import { GiveButton, type Item, ItemGrid, ItemIcon, itemName, type SlotActions } from "@/components/items";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { bytes, number } from "@/lib/format";
import { showError } from "@/lib/forms";
import { useCan } from "@/lib/session";

type World = Schema<"WorldView">;

/** Ticks into the day as the clock in game reads them: tick 0 is 6:00. */
const clock = (dayTime: number) => {
  const hours = (Math.floor(dayTime / 1000) + 6) % 24;
  const minutes = Math.floor(((dayTime % 1000) * 60) / 1000);
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
};

const Section = ({ title, children, actions }: { title: string; children: React.ReactNode; actions?: React.ReactNode }) => (
  <Card className="min-w-0 gap-3 py-4">
    <CardHeader className="flex flex-row items-center justify-between gap-2 px-4">
      <CardTitle className="text-sm font-medium">{title}</CardTitle>
      {actions}
    </CardHeader>
    <CardContent className="flex min-w-0 flex-col gap-3 px-4">{children}</CardContent>
  </Card>
);

const usePatchWorld = (name: string) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  return async (body: Schema<"WorldPatch">) => {
    try {
      const world = await data(client.PATCH("/worlds/{world}", { params: { path: { world: name } }, body }));
      queryClient.setQueryData(["world", name], world);
      toast.success(t("world.saved"));
    } catch (error) {
      showError(error);
    }
  };
};

const TIMES: Array<[string, number]> = [
  ["morning", 1000],
  ["noon", 6000],
  ["evening", 12000],
  ["night", 13000],
  ["midnight", 18000],
];

/** Time, weather, moon, difficulty, and PvP; with world:write each one changes in place. */
const StateCard = ({ world, edit }: { world: World; edit: boolean }) => {
  const { t } = useTranslation();
  const patch = usePatchWorld(world.name);
  const [ticks, setTicks] = useState("");
  const overworld = world.environment === "normal";
  return (
    <Section title={t("world.state")}>
      <Definition
        items={[
          [t("world.day"), t("world.dayValue", { day: number(world.time.day), clock: clock(world.time.dayTime) })],
          [t("world.moon"), t(`world.moons.${world.time.moonPhase}`, { defaultValue: world.time.moonPhase })],
          [t("world.weather"), t(`world.weathers.${world.weather.state}`, { defaultValue: world.weather.state })],
          [t("world.difficulty"), t(`world.difficulties.${world.difficulty}`, { defaultValue: world.difficulty }) + (world.hardcore ? ` · ${t("world.hardcore")}` : "")],
          [t("world.pvp"), world.pvp ? t("world.on") : t("world.off")],
          [t("world.load"), t("world.loadValue", { players: world.players, chunks: number(world.loadedChunks), entities: number(world.entities) })],
          [t("world.spawn"), `${Math.round(world.spawn.x)} ${Math.round(world.spawn.y)} ${Math.round(world.spawn.z)}`],
          [t("world.heights"), t("world.heightsValue", { min: world.minHeight, max: world.maxHeight, sea: world.seaLevel })],
        ]}
      />
      {edit && (
        <div className="flex flex-col gap-3 border-t pt-3">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs text-muted-foreground">{t("world.setTime")}</span>
            <div className="flex flex-wrap gap-1.5">
              {TIMES.map(([name, value]) => (
                <Button key={name} size="sm" variant="outline" onClick={() => patch({ dayTime: value })}>
                  {t(`world.times.${name}`)}
                </Button>
              ))}
              <form
                className="flex gap-1.5"
                onSubmit={(event) => {
                  event.preventDefault();
                  const value = Number.parseInt(ticks, 10);
                  if (Number.isFinite(value)) void patch({ dayTime: ((value % 24000) + 24000) % 24000 });
                }}
              >
                <Input className="h-8 w-24" type="number" inputMode="numeric" placeholder={t("world.ticks")} aria-label={t("world.ticks")} value={ticks} onChange={(event) => setTicks(event.target.value)} />
                <Button size="sm" variant="outline" type="submit" disabled={!ticks}>
                  {t("world.set")}
                </Button>
              </form>
            </div>
          </div>
          <div className="flex flex-wrap items-end gap-3">
            {overworld && (
              <FormField id="weather" label={t("world.weather")}>
                <Select value={world.weather.state} onValueChange={(weather) => patch({ weather })}>
                  <SelectTrigger id="weather" className="w-36">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {["clear", "rain", "thunder"].map((value) => (
                      <SelectItem key={value} value={value}>
                        {t(`world.weathers.${value}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormField>
            )}
            <FormField id="difficulty" label={t("world.difficulty")}>
              <Select value={world.difficulty} onValueChange={(difficulty) => patch({ difficulty })}>
                <SelectTrigger id="difficulty" className="w-36">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {["peaceful", "easy", "normal", "hard"].map((value) => (
                    <SelectItem key={value} value={value}>
                      {t(`world.difficulties.${value}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </FormField>
            <label className="flex h-9 items-center gap-2 text-sm">
              <Switch checked={world.pvp} onCheckedChange={(pvp) => patch({ pvp })} />
              {t("world.pvp")}
            </label>
          </div>
        </div>
      )}
    </Section>
  );
};

/** Every rule with its default; booleans toggle at once, numbers save with their own button. */
const RulesCard = ({ name, edit }: { name: string; edit: boolean }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["world", name, "rules"], queryFn: () => data(client.GET("/worlds/{world}/gamerules", { params: { path: { world: name } } })) });
  const [filter, setFilter] = useState("");
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const save = async (rule: string, value: string | number | boolean) => {
    try {
      await data(client.PATCH("/worlds/{world}/gamerules", { params: { path: { world: name } }, body: { [rule]: value } }));
      toast.success(t("world.ruleSaved", { rule }));
      setDrafts(({ [rule]: _, ...rest }) => rest);
      await queryClient.invalidateQueries({ queryKey: ["world", name] });
    } catch (error) {
      showError(error);
    }
  };
  const needle = filter.trim().toLowerCase().replace(/\s+/g, "_");
  const rules = (query.data ?? []).filter((rule) => !needle || rule.name.toLowerCase().includes(needle));
  return (
    <Section title={t("world.rules")}>
      <Input placeholder={t("world.findRule")} aria-label={t("world.findRule")} value={filter} onChange={(event) => setFilter(event.target.value)} />
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !query.data ? (
        <Loading rows={5} />
      ) : (
        <div className="max-h-[28rem] overflow-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("world.rule")}</TableHead>
                <TableHead>{t("world.value")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rules.map((rule) => {
                const changed = rule.value !== rule.default;
                const draft = drafts[rule.name];
                return (
                  <TableRow key={rule.name}>
                    <TableCell className="max-w-56 align-middle whitespace-normal">
                      <div className="font-mono text-xs break-all">{rule.name}</div>
                      {changed && <div className="text-xs text-muted-foreground">{t("world.default", { value: String(rule.default) })}</div>}
                    </TableCell>
                    <TableCell className="align-middle">
                      {rule.type === "boolean" || typeof rule.value === "boolean" ? (
                        <Switch checked={rule.value === true} disabled={!edit} aria-label={rule.name} onCheckedChange={(on) => save(rule.name, on)} />
                      ) : edit ? (
                        <form
                          className="flex gap-1.5"
                          onSubmit={(event) => {
                            event.preventDefault();
                            if (draft === undefined) return;
                            const value = typeof rule.value === "number" ? Number(draft) : draft;
                            void save(rule.name, value);
                          }}
                        >
                          <Input
                            className="h-8 w-24"
                            inputMode={typeof rule.value === "number" ? "numeric" : undefined}
                            aria-label={rule.name}
                            value={draft ?? String(rule.value)}
                            onChange={(event) => setDrafts((current) => ({ ...current, [rule.name]: event.target.value }))}
                          />
                          {draft !== undefined && draft !== String(rule.value) && (
                            <Button size="sm" type="submit" className="h-8">
                              {t("common.save")}
                            </Button>
                          )}
                        </form>
                      ) : (
                        <span className="tabular">{String(rule.value)}</span>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </Section>
  );
};

const BorderCard = ({ name, edit }: { name: string; edit: boolean }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["world", name, "border"], queryFn: () => data(client.GET("/worlds/{world}/border", { params: { path: { world: name } } })) });
  const border = query.data;
  const [form, setForm] = useState({ size: "", seconds: "", x: "", z: "" });
  useEffect(() => {
    if (border) setForm({ size: String(Math.round(border.size)), seconds: "0", x: String(border.center.x), z: String(border.center.z) });
  }, [border]);
  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    try {
      await data(
        client.PATCH("/worlds/{world}/border", {
          params: { path: { world: name } },
          body: { size: Number(form.size), transitionSeconds: Number(form.seconds) || undefined, centerX: Number(form.x), centerZ: Number(form.z) },
        }),
      );
      toast.success(t("world.borderSaved"));
      await queryClient.invalidateQueries({ queryKey: ["world", name] });
    } catch (error) {
      showError(error);
    }
  };
  return (
    <Section title={t("world.border")}>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !border ? (
        <Loading rows={2} />
      ) : (
        <>
          <Definition
            items={[
              [t("world.borderSize"), t("world.blocks", { count: Math.round(border.size), value: number(border.size) })],
              [t("world.borderCenter"), `${number(border.center.x, 1)} ${number(border.center.z, 1)}`],
              [t("world.borderDamage"), t("world.borderDamageValue", { amount: number(border.damageAmount, 2), buffer: number(border.damageBuffer, 1) })],
              [t("world.borderWarning"), t("world.borderWarningValue", { distance: border.warningDistance, seconds: number(border.warningTimeTicks / 20) })],
            ]}
          />
          {edit && (
            <form onSubmit={save} className="grid grid-cols-2 gap-3 border-t pt-3 sm:grid-cols-4">
              {(
                [
                  ["size", t("world.borderSize")],
                  ["seconds", t("world.borderSeconds")],
                  ["x", t("world.centerX")],
                  ["z", t("world.centerZ")],
                ] as const
              ).map(([key, label]) => (
                <FormField key={key} id={`border-${key}`} label={label}>
                  <Input id={`border-${key}`} type="number" inputMode="decimal" value={form[key]} onChange={(event) => setForm((current) => ({ ...current, [key]: event.target.value }))} />
                </FormField>
              ))}
              <Button type="submit" size="sm" className="col-span-2 justify-self-start sm:col-span-4">
                {t("world.borderApply")}
              </Button>
            </form>
          )}
        </>
      )}
    </Section>
  );
};

/** The respawn point when players spawn in this world; otherwise the world's own point, which teleports use. */
const pointOf = (name: string, own: World["spawn"], spawn: Schema<"ServerSpawn"> | undefined) =>
  spawn?.world === name ? spawn : { x: Math.floor(own.x), y: Math.floor(own.y), z: Math.floor(own.z), yaw: own.yaw ?? 0, pitch: own.pitch ?? 0 };

const SpawnCard = ({ world, edit }: { world: World; edit: boolean }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["spawn"], queryFn: () => data(client.GET("/spawn")) });
  const spawn = query.data;
  const here = spawn?.world === world.name;
  const point = pointOf(world.name, world.spawn, spawn);
  const [form, setForm] = useState({ x: "", y: "", z: "", yaw: "", pitch: "", radius: "" });
  // The world refetches every 15 s; its spawn keeps its identity until it changes, so typing isn't reset.
  useEffect(() => {
    if (!spawn) return;
    const at = pointOf(world.name, world.spawn, spawn);
    const tenth = (n: number) => String(Math.round(n * 10) / 10);
    // The respawn radius is a game rule of each world; moving the respawn takes this world's own.
    const radius = spawn.world === world.name ? spawn.respawnRadius : (world.gameRules.respawn_radius ?? spawn.respawnRadius);
    setForm({ x: String(at.x), y: String(at.y), z: String(at.z), yaw: tenth(at.yaw), pitch: tenth(at.pitch), radius: String(radius) });
  }, [spawn, world.name, world.spawn, world.gameRules]);
  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    try {
      const body = { world: world.name, x: Number(form.x), y: Number(form.y), z: Number(form.z), yaw: Number(form.yaw), pitch: Number(form.pitch), respawnRadius: Number(form.radius) };
      queryClient.setQueryData(["spawn"], await data(client.PATCH("/spawn", { body })));
      toast.success(t("world.spawnSaved"));
      await queryClient.invalidateQueries({ queryKey: ["world", world.name] });
    } catch (error) {
      showError(error);
    }
  };
  return (
    <Section title={t("world.spawn")}>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !spawn ? (
        <Loading rows={2} />
      ) : (
        <>
          <p className="text-sm text-muted-foreground">{here ? t("world.spawnHere") : t("world.spawnElsewhere", { world: spawn.world })}</p>
          <Definition
            items={[
              [t("world.spawnPoint"), `${point.x} ${point.y} ${point.z}`],
              [t("world.spawnFacing"), t("world.spawnFacingValue", { yaw: number(point.yaw, 1), pitch: number(point.pitch, 1) })],
              ...(here
                ? ([
                    [t("world.respawnRadius"), t("world.blocks", { count: spawn.respawnRadius, value: number(spawn.respawnRadius) })],
                    [t("world.spawnProtection"), t("world.blocks", { count: spawn.spawnProtection, value: number(spawn.spawnProtection) })],
                  ] as Array<[string, string]>)
                : []),
            ]}
          />
          {edit && (
            <form onSubmit={save} className="grid grid-cols-3 gap-3 border-t pt-3">
              {(
                [
                  ["x", "X"],
                  ["y", "Y"],
                  ["z", "Z"],
                  ["yaw", t("world.yaw")],
                  ["pitch", t("world.pitch")],
                  ["radius", t("world.respawnRadius")],
                ] as const
              ).map(([key, label]) => (
                <FormField key={key} id={`spawn-${key}`} label={label}>
                  <Input id={`spawn-${key}`} type="number" inputMode="decimal" value={form[key]} onChange={(event) => setForm((current) => ({ ...current, [key]: event.target.value }))} />
                </FormField>
              ))}
              <Button type="submit" size="sm" className="col-span-3 justify-self-start">
                {here ? t("world.spawnApply") : t("world.spawnMove")}
              </Button>
            </form>
          )}
        </>
      )}
    </Section>
  );
};

const StatsCard = ({ name }: { name: string }) => {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["world", name, "stats"], queryFn: () => data(client.GET("/worlds/{world}/stats", { params: { path: { world: name } } })), staleTime: 300_000 });
  const stats = query.data;
  return (
    <Section title={t("world.stats")}>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !stats ? (
        <Loading rows={2} />
      ) : (
        <Definition
          items={[
            [t("world.generated"), number(stats.generatedChunks)],
            [t("world.explored"), t("world.km2", { value: number(stats.exploredKm2, 2) })],
            [t("world.regions"), number(stats.regionFiles)],
            [t("world.onDisk"), bytes(stats.sizeOnDiskBytes)],
          ]}
        />
      )}
    </Section>
  );
};

/** What lives in the world: counts per type, and the ten busiest chunks. */
const EntitiesCard = ({ name }: { name: string }) => {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["world", name, "census"], queryFn: () => data(client.GET("/worlds/{world}/entities/summary", { params: { path: { world: name } } })), refetchInterval: 30_000 });
  const census = query.data;
  const types = Object.entries(census?.byType ?? {}).sort(([, a], [, b]) => b - a);
  const max = Math.max(1, ...types.map(([, count]) => count));
  return (
    <Section title={t("world.entities")}>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !census ? (
        <Loading rows={4} />
      ) : (
        <>
          <Definition
            items={[
              [t("world.entityTotal"), number(census.total)],
              [t("world.droppedItems"), t("world.droppedValue", { entities: number(census.droppedItems), items: number(census.droppedItemCount) })],
              [t("world.villagers"), number(census.villagers)],
              [t("world.namedTamed"), `${number(census.named)} · ${number(census.tamed)}`],
            ]}
          />
          {!!types.length && (
            <ul className="flex flex-col gap-2">
              {types.slice(0, 12).map(([type, count]) => (
                <li key={type} className="flex flex-col gap-1">
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="min-w-0 truncate">{itemName(type)}</span>
                    <span className="tabular shrink-0 text-xs text-muted-foreground">{number(count)}</span>
                  </div>
                  <div className="h-1.5 rounded-full bg-muted" aria-hidden>
                    <div className="h-full rounded-full bg-chart-1" style={{ width: `${Math.max(2, (count / max) * 100)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          )}
          {!!census.topChunks.length && (
            <>
              <h3 className="pt-1 text-xs font-medium text-muted-foreground">{t("world.busiest")}</h3>
              <TableFrame>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("world.chunk")}</TableHead>
                      <TableHead className="text-right">{t("world.entityCount")}</TableHead>
                      <TableHead className="text-right">{t("world.tileCount")}</TableHead>
                      <TableHead className="hidden text-right sm:table-cell">{t("world.hoppers")}</TableHead>
                      <TableHead className="hidden md:table-cell">{t("world.mostly")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {census.topChunks.map((chunk) => (
                      <TableRow key={`${chunk.x},${chunk.z}`}>
                        <TableCell className="tabular font-mono text-xs" title={t("world.blocksAt", { x: chunk.x * 16, z: chunk.z * 16 })}>
                          {chunk.x}, {chunk.z}
                        </TableCell>
                        <TableCell className="tabular text-right">{number(chunk.entities)}</TableCell>
                        <TableCell className="tabular text-right">{number(chunk.tileEntities)}</TableCell>
                        <TableCell className="tabular hidden text-right sm:table-cell">{number(chunk.hoppers)}</TableCell>
                        <TableCell className="hidden max-w-64 truncate text-xs text-muted-foreground md:table-cell">
                          {Object.entries(chunk.top)
                            .slice(0, 3)
                            .map(([type, count]) => `${itemName(type)} ${count}`)
                            .join(", ")}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableFrame>
            </>
          )}
        </>
      )}
    </Section>
  );
};

const ChunksCard = ({ name }: { name: string }) => {
  const { t } = useTranslation();
  const query = useInfiniteQuery({
    queryKey: ["world", name, "chunks"],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => data(client.GET("/worlds/{world}/chunks", { params: { path: { world: name }, query: { sort: "entities", limit: 20, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const chunks = query.data?.pages.flatMap((page) => page.items) ?? [];
  const total = query.data?.pages[0]?.total;
  return (
    <Section title={total == null ? t("world.chunks") : t("world.chunksCount", { count: total })}>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : query.isPending ? (
        <Loading rows={4} />
      ) : !chunks.length ? (
        <Empty>{t("world.noChunks")}</Empty>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("world.chunk")}</TableHead>
                <TableHead className="text-right">{t("world.entityCount")}</TableHead>
                <TableHead className="text-right">{t("world.tileCount")}</TableHead>
                <TableHead className="hidden sm:table-cell">{t("world.forced")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {chunks.map((chunk) => (
                <TableRow key={`${chunk.x},${chunk.z}`}>
                  <TableCell className="tabular font-mono text-xs" title={t("world.blocksAt", { x: chunk.x * 16, z: chunk.z * 16 })}>
                    {chunk.x}, {chunk.z}
                  </TableCell>
                  <TableCell className="tabular text-right">{number(chunk.entities)}</TableCell>
                  <TableCell className="tabular text-right">{number(chunk.tileEntities)}</TableCell>
                  <TableCell className="hidden sm:table-cell">{chunk.forceLoaded ? t("world.on") : ""}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      {query.hasNextPage && (
        <Button variant="outline" size="sm" className="self-center" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          {t("common.more")}
        </Button>
      )}
    </Section>
  );
};

const Coordinates = ({ value, onChange, prefix }: { value: { x: string; y: string; z: string }; onChange: (value: { x: string; y: string; z: string }) => void; prefix: string }) => (
  <div className="grid grid-cols-3 gap-2">
    {(["x", "y", "z"] as const).map((axis) => (
      <FormField key={axis} id={`${prefix}-${axis}`} label={axis.toUpperCase()}>
        <Input id={`${prefix}-${axis}`} type="number" inputMode="numeric" value={value[axis]} onChange={(event) => onChange({ ...value, [axis]: event.target.value })} />
      </FormField>
    ))}
  </div>
);

/** The nearest blocks, biomes, or structures around a point, as /locate does. */
const LocateCard = ({ world }: { world: World }) => {
  const { t } = useTranslation();
  const [kind, setKind] = useState<"block" | "biome" | "structure">("structure");
  const [target, setTarget] = useState("");
  const [at, setAt] = useState({ x: String(Math.round(world.spawn.x)), y: String(Math.round(world.spawn.y)), z: String(Math.round(world.spawn.z)) });
  const [radius, setRadius] = useState("");
  const [result, setResult] = useState<Schema<"LocateResult"> | null>(null);
  const [busy, setBusy] = useState(false);
  const locate = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      setResult(
        await data(
          client.GET("/worlds/{world}/locate", {
            params: {
              path: { world: world.name },
              query: { kind, target: target.trim(), x: Number(at.x), y: Number(at.y), z: Number(at.z), radius: Number(radius) || undefined },
            },
          }),
        ),
      );
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Section title={t("world.locate")}>
      <form onSubmit={locate} className="flex flex-col gap-3">
        <div className="flex flex-wrap items-end gap-2">
          <FormField id="locate-kind" label={t("world.kind")}>
            <Select value={kind} onValueChange={(value) => setKind(value as typeof kind)}>
              <SelectTrigger id="locate-kind" className="w-32">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(["structure", "biome", "block"] as const).map((value) => (
                  <SelectItem key={value} value={value}>
                    {t(`world.kinds.${value}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </FormField>
          <div className="min-w-0 flex-1 basis-48">
            <FormField id="locate-target" label={t("world.target")} hint={t(`world.targetHints.${kind}`)}>
              <Input id="locate-target" className="font-mono text-sm" spellCheck={false} value={target} onChange={(event) => setTarget(event.target.value)} />
            </FormField>
          </div>
        </div>
        <Coordinates value={at} onChange={setAt} prefix="locate" />
        <div className="flex items-end gap-2">
          <FormField id="locate-radius" label={t("world.radius")} hint={t(`world.radiusHints.${kind}`)}>
            <Input id="locate-radius" type="number" inputMode="numeric" className="w-28" value={radius} onChange={(event) => setRadius(event.target.value)} />
          </FormField>
          <Button type="submit" disabled={busy || !target.trim()} className="mb-5">
            <Search className="size-4" aria-hidden />
            {t("world.find")}
          </Button>
        </div>
      </form>
      {result &&
        (result.found.length ? (
          <TableFrame>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("world.what")}</TableHead>
                  <TableHead>{t("world.where")}</TableHead>
                  <TableHead className="text-right">{t("world.distance")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {result.found.map((found) => (
                  <TableRow key={`${found.type}-${found.x}-${found.y}-${found.z}`}>
                    <TableCell className="max-w-40 truncate">{itemName(found.type)}</TableCell>
                    <TableCell className="tabular font-mono text-xs">
                      {found.x} {found.y} {found.z}
                    </TableCell>
                    <TableCell className="tabular text-right">{number(found.distance)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableFrame>
        ) : (
          <Empty>{result.unloadedChunks ? t("world.notFoundLoaded", { count: result.unloadedChunks }) : t("world.notFound")}</Empty>
        ))}
    </Section>
  );
};

/** One container's slots, editable with world:write. */
const ContainerDialog = ({ world, at, onClose, edit }: { world: string; at: { x: number; y: number; z: number } | null; onClose: () => void; edit: boolean }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const path = at ? { world, x: at.x, y: at.y, z: at.z } : null;
  const query = useQuery({
    queryKey: ["block", world, at?.x, at?.y, at?.z],
    enabled: !!path,
    queryFn: () => data(client.GET("/worlds/{world}/blocks/{x}/{y}/{z}", { params: { path: path! } })),
  });
  const tile = query.data?.tileEntity;
  const done = async (message: string) => {
    toast.success(message);
    await queryClient.invalidateQueries({ queryKey: ["block", world] });
    await queryClient.invalidateQueries({ queryKey: ["world", world, "containers"] });
  };
  const actions: SlotActions | undefined =
    edit && path
      ? {
          put: async (slot, item) => {
            await data(client.PATCH("/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}", { params: { path: { ...path, slot } }, body: item }));
            await done(t("items.putDone", { name: itemName(item.type ?? ""), slot }));
          },
          clear: async (slot) => {
            await data(client.DELETE("/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}", { params: { path: { ...path, slot } } }));
            await done(t("items.cleared", { slot }));
          },
          take: async (slot, amount, item: Item) => {
            const result = await data(client.POST("/worlds/{world}/blocks/{x}/{y}/{z}/container/slots/{slot}/take", { params: { path: { ...path, slot } }, body: { amount } }));
            await done(t("items.taken", { count: result.taken, name: item.name ?? itemName(item.type) }));
          },
        }
      : undefined;
  return (
    <Dialog open={!!at} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{tile?.customName ?? (query.data ? itemName(query.data.type) : t("world.container"))}</DialogTitle>
          <DialogDescription className="font-mono">{at && `${world} ${at.x} ${at.y} ${at.z}`}</DialogDescription>
        </DialogHeader>
        {query.error ? (
          <ErrorBox error={query.error} />
        ) : !query.data ? (
          <Loading rows={3} />
        ) : tile?.items ? (
          <div className="flex flex-col gap-3">
            <ItemGrid items={tile.items} size={tile.size ?? tile.items.length} actions={actions} />
            {actions && path && (
              <GiveButton
                onGive={async (item) => {
                  const result = await data(client.POST("/worlds/{world}/blocks/{x}/{y}/{z}/container/give", { params: { path }, body: { item } }));
                  await done(result.dropped ? t("items.givenDropped", { given: result.given, dropped: result.dropped }) : t("items.given", { count: result.given }));
                }}
              />
            )}
          </div>
        ) : (
          <Empty>{t("world.notContainer")}</Empty>
        )}
      </DialogContent>
    </Dialog>
  );
};

/** Chests, barrels, and the rest near a point, in loaded chunks only. */
const ContainersCard = ({ world, edit }: { world: World; edit: boolean }) => {
  const { t } = useTranslation();
  const [at, setAt] = useState({ x: String(Math.round(world.spawn.x)), y: String(Math.round(world.spawn.y)), z: String(Math.round(world.spawn.z)) });
  const [radius, setRadius] = useState("16");
  const [item, setItem] = useState("");
  const [search, setSearch] = useState<null | { x: number; y: number; z: number; radius: number; item?: string }>(null);
  const [open, setOpen] = useState<{ x: number; y: number; z: number } | null>(null);
  const query = useQuery({
    queryKey: ["world", world.name, "containers", search],
    enabled: !!search,
    queryFn: () => data(client.GET("/worlds/{world}/containers", { params: { path: { world: world.name }, query: { ...search!, limit: 50 } } })),
  });
  return (
    <Section title={t("world.containers")}>
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault();
          setSearch({ x: Number(at.x), y: Number(at.y), z: Number(at.z), radius: Number(radius) || 16, item: item.trim() || undefined });
        }}
      >
        <Coordinates value={at} onChange={setAt} prefix="containers" />
        <div className="flex flex-wrap items-end gap-2">
          <FormField id="containers-radius" label={t("world.radius")}>
            <Input id="containers-radius" type="number" inputMode="numeric" className="w-24" value={radius} onChange={(event) => setRadius(event.target.value)} />
          </FormField>
          <div className="min-w-0 flex-1 basis-40">
            <FormField id="containers-item" label={t("world.holding")}>
              <Input id="containers-item" className="font-mono text-sm" placeholder="minecraft:diamond" spellCheck={false} value={item} onChange={(event) => setItem(event.target.value)} />
            </FormField>
          </div>
          <Button type="submit">
            <Search className="size-4" aria-hidden />
            {t("world.find")}
          </Button>
        </div>
      </form>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : query.isFetching && !query.data ? (
        <Loading rows={3} />
      ) : query.data && !query.data.items.length ? (
        <Empty>{t("world.noContainers")}</Empty>
      ) : (
        query.data && (
          <ul className="flex flex-col divide-y rounded-lg border bg-card text-sm">
            {query.data.items.map((container) => (
              <li key={`${container.x},${container.y},${container.z}`}>
                <button type="button" className="flex w-full min-w-0 items-center gap-3 p-2.5 text-left hover:bg-accent" onClick={() => setOpen({ x: container.x, y: container.y, z: container.z })}>
                  <ItemIcon type={container.type} size={24} />
                  <span className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate font-medium">{container.customName ?? itemName(container.type)}</span>
                    <span className="truncate text-xs text-muted-foreground">
                      {t("world.containerLine", { x: container.x, y: container.y, z: container.z, used: container.usedSlots, size: container.size, distance: number(container.distance) })}
                    </span>
                  </span>
                  <span className="flex shrink-0 gap-0.5">
                    {Object.keys(container.items)
                      .slice(0, 3)
                      .map((type) => (
                        <ItemIcon key={type} type={type} size={16} />
                      ))}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )
      )}
      <ContainerDialog world={world.name} at={open} onClose={() => setOpen(null)} edit={edit} />
    </Section>
  );
};

export const WorldPage = () => {
  const { t } = useTranslation();
  const { world: name } = useParams({ from: "/app/worlds/$world" });
  const can = useCan();
  const edit = can("world:write");
  const query = useQuery({ queryKey: ["world", name], queryFn: () => data(client.GET("/worlds/{world}", { params: { path: { world: name } } })), refetchInterval: 15_000 });
  const world = query.data;
  const back = (
    <Link to="/worlds" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" aria-hidden />
      {t("worlds.title")}
    </Link>
  );
  if (query.error)
    return (
      <div>
        {back}
        <ErrorBox error={query.error} />
      </div>
    );
  if (!world)
    return (
      <div>
        {back}
        <Loading rows={6} />
      </div>
    );
  return (
    <div className="flex flex-col gap-3">
      <div>
        {back}
        <PageHeader
          title={world.name}
          description={
            <span className="flex flex-wrap items-center gap-1.5">
              <Badge variant="outline" className="font-normal">
                {t(`worlds.environments.${world.environment}`, { defaultValue: world.environment })}
              </Badge>
              <span className="font-mono text-xs">{world.key}</span>
            </span>
          }
        />
      </div>
      {can("server:control") && world.seed && (
        <div className="flex max-w-md flex-col gap-1">
          <span className="text-xs text-muted-foreground">{t("world.seed")}</span>
          <Secret value={world.seed} />
        </div>
      )}
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <StateCard world={world} edit={edit} />
        <BorderCard name={world.name} edit={edit} />
        <SpawnCard world={world} edit={edit} />
        <RulesCard name={world.name} edit={edit} />
        <div className="flex min-w-0 flex-col gap-3">
          <StatsCard name={world.name} />
          <EntitiesCard name={world.name} />
        </div>
        <ChunksCard name={world.name} />
        <LocateCard world={world} />
        {can("players:private") && <ContainersCard world={world} edit={edit} />}
      </div>
    </div>
  );
};
