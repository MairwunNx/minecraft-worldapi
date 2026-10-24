import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { ArrowLeft, Ban, Crown, DoorOpen, ListChecks } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { client, data, type Schema } from "@/api/client";
import { Confirm, Definition, Empty, ErrorBox, Loading, PageHeader, TableFrame } from "@/components/common";
import { FormField } from "@/components/form-field";
import { Inventory, ItemGrid, itemName } from "@/components/items";
import { AdvancementsTab, DeathsTab, PlayerGive, usePlayerSlots, where } from "@/pages/player-parts";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { dateTime, number, relative, span } from "@/lib/format";
import { showError } from "@/lib/forms";
import { useCan } from "@/lib/session";

type Player = Schema<"PlayerView">;

/** Kick, ban, operator, whitelist: each asks first, and the page reloads the player after. */
const Moderation = ({ player, canOp }: { player: Player; canOp: boolean }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["player"] });
  const run = async (action: () => Promise<unknown>, done: string) => {
    try {
      await action();
      toast.success(done);
      setReason("");
      await refresh();
    } catch (error) {
      showError(error);
      throw error;
    }
  };
  const reasonField = (
    <FormField id="reason" label={t("moderation.reason")}>
      <Input id="reason" value={reason} onChange={(event) => setReason(event.target.value)} placeholder={t("moderation.reasonPlaceholder")} />
    </FormField>
  );
  return (
    <div className="flex flex-wrap gap-2">
      {player.online && (
        <Confirm
          label={
            <>
              <DoorOpen className="size-4" aria-hidden />
              {t("moderation.kick")}
            </>
          }
          title={t("moderation.kickTitle", { name: player.name })}
          text={t("moderation.kickText")}
          action={t("moderation.kick")}
          onConfirm={() =>
            run(() => data(client.POST("/players/{player}/kick", { params: { path: { player: player.uuid } }, body: { reason: reason.trim() || undefined } })), t("moderation.kicked", { name: player.name }))
          }
        >
          {reasonField}
        </Confirm>
      )}
      {player.banned ? (
        <Confirm
          label={t("moderation.pardon")}
          title={t("moderation.pardonTitle", { name: player.name })}
          text={t("moderation.pardonText")}
          action={t("moderation.pardon")}
          onConfirm={() => run(() => data(client.PATCH("/server/bans", { body: { pardon: [player.uuid] } })), t("moderation.pardoned", { name: player.name }))}
        />
      ) : (
        <Confirm
          destructive
          label={
            <>
              <Ban className="size-4" aria-hidden />
              {t("moderation.ban")}
            </>
          }
          title={t("moderation.banTitle", { name: player.name })}
          text={t("moderation.banText")}
          action={t("moderation.ban")}
          onConfirm={() => run(() => data(client.PATCH("/server/bans", { body: { ban: [{ player: player.uuid, reason: reason.trim() || undefined }] } })), t("moderation.banned", { name: player.name }))}
        >
          {reasonField}
        </Confirm>
      )}
      {/* An operator runs every command, so only those with the console may make one. */}
      {canOp && (
        <Confirm
          label={
            <>
              <Crown className="size-4" aria-hidden />
              {player.op ? t("moderation.deop") : t("moderation.op")}
            </>
          }
          title={player.op ? t("moderation.deopTitle", { name: player.name }) : t("moderation.opTitle", { name: player.name })}
          text={player.op ? t("moderation.deopText") : t("moderation.opText")}
          action={player.op ? t("moderation.deop") : t("moderation.op")}
          onConfirm={() =>
            run(() => data(client.PATCH("/players/{player}/permissions", { params: { path: { player: player.uuid } }, body: { op: !player.op } })), player.op ? t("moderation.deopped") : t("moderation.opped"))
          }
        />
      )}
      <Confirm
        label={
          <>
            <ListChecks className="size-4" aria-hidden />
            {player.whitelisted ? t("moderation.unlist") : t("moderation.list")}
          </>
        }
        title={player.whitelisted ? t("moderation.unlistTitle", { name: player.name }) : t("moderation.listTitle", { name: player.name })}
        text={player.whitelisted ? t("moderation.unlistText") : t("moderation.listText")}
        action={player.whitelisted ? t("moderation.unlist") : t("moderation.list")}
        onConfirm={() =>
          run(
            () => data(client.PATCH("/server/whitelist", { body: player.whitelisted ? { remove: [player.name] } : { add: [player.name] } })),
            player.whitelisted ? t("moderation.unlisted") : t("moderation.listed"),
          )
        }
      />
    </div>
  );
};

const Summary = ({ player, priv }: { player: Player; priv: boolean }) => {
  const { t } = useTranslation();
  const state = player.state;
  const items: Array<[string, React.ReactNode]> = [
    [t("player.uuid"), <span className="font-mono text-xs">{player.uuid}</span>],
    [t("player.firstPlayed"), dateTime(player.firstPlayed)],
    [t("player.lastSeen"), player.online ? t("players.now") : relative(player.lastSeen)],
  ];
  if (state)
    items.push(
      [t("player.gameMode"), state.gameMode.toLowerCase()],
      [t("player.health"), `${number(state.health, 1)} / ${number(state.maxHealth)}`],
      [t("player.food"), `${state.food} / 20`],
      [t("player.level"), number(state.level)],
      [t("player.ping"), `${state.ping} ms`],
      [t("player.idle"), span(state.idleSeconds)],
      [t("player.client"), state.clientBrand ?? "—"],
      [t("player.locale"), state.locale],
    );
  if (state?.address) items.push([t("player.address"), <span className="font-mono text-xs">{state.address}</span>]);
  if (priv) items.push([t("player.location"), where(player.location)], [t("player.respawn"), where(player.respawnLocation)]);
  return <Definition items={items} />;
};

const Sessions = ({ sessions }: { sessions: Schema<"SessionView">[] }) => {
  const { t } = useTranslation();
  if (!sessions.length) return <Empty>{t("player.noSessions")}</Empty>;
  return (
    <TableFrame>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("player.joined")}</TableHead>
            <TableHead>{t("player.left")}</TableHead>
            <TableHead className="text-right">{t("player.length")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {sessions.map((session) => (
            <TableRow key={session.joinedAt}>
              <TableCell className="whitespace-nowrap">{dateTime(session.joinedAt)}</TableCell>
              <TableCell className="whitespace-nowrap">{session.leftAt ? dateTime(session.leftAt) : t("players.now")}</TableCell>
              <TableCell className="tabular text-right">{span(session.seconds)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableFrame>
  );
};

const Statistics = ({ statistics }: { statistics: Record<string, number> }) => {
  const { t } = useTranslation();
  const [filter, setFilter] = useState("");
  const rows = Object.entries(statistics)
    .filter(([key]) => key.includes(filter.trim().toLowerCase()))
    .sort(([a], [b]) => a.localeCompare(b));
  return (
    <div className="flex flex-col gap-3">
      <Input placeholder={t("player.filterStats")} aria-label={t("player.filterStats")} value={filter} onChange={(event) => setFilter(event.target.value)} className="max-w-xs" />
      <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2 lg:grid-cols-3">
        {rows.map(([key, value]) => (
          <div key={key} className="flex items-baseline justify-between gap-3 border-b py-1.5 text-sm">
            <span className="min-w-0 truncate">{itemName(key)}</span>
            <span className="tabular shrink-0 text-muted-foreground">{key.endsWith("_time") || key === "minecraft:play_time" ? span(value / 20) : number(value)}</span>
          </div>
        ))}
      </div>
    </div>
  );
};

export const PlayerPage = () => {
  const { t } = useTranslation();
  const { player: id } = useParams({ from: "/app/players/$player" });
  const { tab } = useSearch({ from: "/app/players/$player" });
  const navigate = useNavigate({ from: "/players/$player" });
  const can = useCan();
  const priv = can("players:private");
  const include = ["effects", "deaths", "sessions", "statistics", ...(priv ? ["inventory", "enderchest"] : [])].join(",");
  const query = useQuery({
    queryKey: ["player", id, include],
    queryFn: () => data(client.GET("/players/{player}", { params: { path: { player: id }, query: { include } } })),
    refetchInterval: 20_000,
  });
  const player = query.data;
  const inventoryActions = usePlayerSlots(player, "inventory", can("players:write"));
  const enderActions = usePlayerSlots(player, "enderchest", can("players:write"));
  const back = (
    <Link to="/players" className="mb-3 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" aria-hidden />
      {t("players.title")}
    </Link>
  );
  if (query.error)
    return (
      <div>
        {back}
        <ErrorBox error={query.error} />
      </div>
    );
  if (!player)
    return (
      <div>
        {back}
        <Loading rows={6} />
      </div>
    );
  const tabs = [
    "summary",
    ...(priv ? ["inventory", "enderchest"] : []),
    "effects",
    "deaths",
    "sessions",
    "statistics",
    "advancements",
  ];
  const current = tab && tabs.includes(tab) ? tab : "summary";
  return (
    <div>
      {back}
      <PageHeader
        title={player.name}
        description={
          <span className="flex flex-wrap gap-1.5">
            <Badge variant="outline" className={player.online ? "border-success/40" : ""}>
              {player.online ? t("players.online") : t("players.offline")}
            </Badge>
            {player.op && <Badge variant="outline">{t("player.op")}</Badge>}
            {player.whitelisted && <Badge variant="outline">{t("player.whitelisted")}</Badge>}
            {player.banned && <Badge variant="destructive">{t("player.banned")}</Badge>}
          </span>
        }
        actions={can("players:moderate") && <Moderation player={player} canOp={can("server:console")} />}
      />
      <Tabs value={current} onValueChange={(value) => navigate({ search: { tab: value === "summary" ? undefined : value }, replace: true })}>
        <div className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0">
          <TabsList>
            {tabs.map((name) => (
              <TabsTrigger key={name} value={name}>
                {t(`player.tabs.${name}`)}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>
        <Card className="mt-2 py-4">
          <CardHeader className="sr-only px-4">
            <CardTitle>{t(`player.tabs.${current}`)}</CardTitle>
          </CardHeader>
          <CardContent className="px-4">
            <TabsContent value="summary">
              <Summary player={player} priv={priv} />
            </TabsContent>
            <TabsContent value="inventory" className="flex flex-col gap-3">
              {player.inventory ? <Inventory inventory={player.inventory} actions={inventoryActions} /> : <Empty>{t("player.noInventory")}</Empty>}
              {inventoryActions && player.online && <PlayerGive player={player} store="inventory" />}
            </TabsContent>
            <TabsContent value="enderchest">
              {player.enderChest ? <ItemGrid items={player.enderChest.items} size={player.enderChest.size} actions={enderActions} /> : <Empty>{t("player.noInventory")}</Empty>}
              {enderActions && player.online && (
                <div className="mt-3">
                  <PlayerGive player={player} store="enderchest" />
                </div>
              )}
            </TabsContent>
            <TabsContent value="effects">
              {!player.online ? (
                <Empty>{t("player.effectsOffline")}</Empty>
              ) : !player.effects?.length ? (
                <Empty>{t("player.noEffects")}</Empty>
              ) : (
                <ul className="flex flex-col divide-y rounded-lg border text-sm">
                  {player.effects.map((effect) => (
                    <li key={effect.type} className="flex items-center justify-between gap-3 p-3">
                      <span>
                        {itemName(effect.type)} {effect.amplifier + 1}
                      </span>
                      <span className="tabular text-xs text-muted-foreground">{effect.durationTicks < 0 ? "∞" : span(effect.durationTicks / 20)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </TabsContent>
            <TabsContent value="deaths">{can("events:read") ? <DeathsTab player={player} lastLocation={player.deaths?.lastLocation} /> : <Empty>{t("player.noDeathHistory")}</Empty>}</TabsContent>
            <TabsContent value="sessions">
              <Sessions sessions={player.sessions ?? []} />
            </TabsContent>
            <TabsContent value="statistics">{player.statistics ? <Statistics statistics={player.statistics} /> : <Empty>{t("player.noStats")}</Empty>}</TabsContent>
            <TabsContent value="advancements">{current === "advancements" && <AdvancementsTab player={player.uuid} />}</TabsContent>
          </CardContent>
        </Card>
      </Tabs>
    </div>
  );
};
