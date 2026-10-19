import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Circle } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { client, data, type Schema } from "@/api/client";
import { Empty, ErrorBox, Loading } from "@/components/common";
import { GiveButton, type Item, type ItemInput, itemName, type SlotActions } from "@/components/items";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { dateTime, number } from "@/lib/format";
import { cn } from "@/lib/utils";

type Location = Schema<"LocationView">;

export const where = (location?: Partial<Location> | null) =>
  location && location.x !== undefined ? `${location.world ?? ""} ${Math.round(location.x)} ${Math.round(location.y ?? 0)} ${Math.round(location.z ?? 0)}`.trim() : "—";

// ---------- Inventories ----------

type Store = "inventory" | "enderchest";

/**
 * Slot edits for a player's inventory or ender chest: put and clear through PATCH and DELETE,
 * take through …/slots/{slot}/take. WorldAPI needs the player online for each.
 */
export const usePlayerSlots = (player: Schema<"PlayerView"> | undefined, store: Store, allowed: boolean): SlotActions | undefined => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  if (!allowed || !player) return undefined;
  const at = (slot: number) => ({ params: { path: { player: player.uuid, slot } } });
  const done = async (message: string) => {
    toast.success(message);
    await queryClient.invalidateQueries({ queryKey: ["player"] });
  };
  return {
    disabled: player.online ? undefined : t("items.offline"),
    put: async (slot, item) => {
      if (store === "inventory") await data(client.PATCH("/players/{player}/inventory/slots/{slot}", { ...at(slot), body: item }));
      else await data(client.PATCH("/players/{player}/enderchest/slots/{slot}", { ...at(slot), body: item }));
      await done(t("items.putDone", { name: itemName(item.type ?? ""), slot }));
    },
    clear: async (slot) => {
      if (store === "inventory") await data(client.DELETE("/players/{player}/inventory/slots/{slot}", at(slot)));
      else await data(client.DELETE("/players/{player}/enderchest/slots/{slot}", at(slot)));
      await done(t("items.cleared", { slot }));
    },
    take: async (slot, amount, item: Item) => {
      const result =
        store === "inventory"
          ? await data(client.POST("/players/{player}/inventory/slots/{slot}/take", { ...at(slot), body: { amount } }))
          : await data(client.POST("/players/{player}/enderchest/slots/{slot}/take", { ...at(slot), body: { amount } }));
      await done(t("items.taken", { count: result.taken, name: item.name ?? itemName(item.type) }));
    },
  };
};

export const PlayerGive = ({ player, store }: { player: Schema<"PlayerView">; store: Store }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const give = async (item: ItemInput) => {
    const result =
      store === "inventory"
        ? await data(client.POST("/players/{player}/inventory/give", { params: { path: { player: player.uuid } }, body: { item } }))
        : await data(client.POST("/players/{player}/enderchest/give", { params: { path: { player: player.uuid } }, body: { item } }));
    toast.success(result.dropped ? t("items.givenDropped", { given: result.given, dropped: result.dropped }) : t("items.given", { count: result.given }));
    await queryClient.invalidateQueries({ queryKey: ["player"] });
  };
  return <GiveButton onGive={give} />;
};

// ---------- Deaths ----------

interface DeathData {
  message?: string;
  cause?: string;
  killer?: { type?: string; name?: string | null } | null;
  level?: number;
  location?: Partial<Location>;
}

/** Every recorded death from the event history, newest first, 25 at a time. */
export const DeathsTab = ({ player, lastLocation }: { player: Schema<"PlayerView">; lastLocation?: Location | null }) => {
  const { t } = useTranslation();
  const query = useInfiniteQuery({
    queryKey: ["player", player.uuid, "deaths"],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => data(client.GET("/events", { params: { query: { type: "player.death", player: player.uuid, limit: 25, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const deaths = query.data?.pages.flatMap((page) => page.items) ?? [];
  const total = query.data?.pages[0]?.total;
  return (
    <div className="flex flex-col gap-3">
      {lastLocation && <p className="text-sm text-muted-foreground">{t("player.lastDeath", { where: where(lastLocation) })}</p>}
      {total != null && <p className="text-sm">{t("player.deathCount", { count: total })}</p>}
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : query.isPending ? (
        <Loading rows={4} />
      ) : !deaths.length ? (
        <Empty>{t("player.noDeaths")}</Empty>
      ) : (
        <ul className="flex flex-col divide-y rounded-lg border bg-card text-sm">
          {deaths.map((event) => {
            const death = event.data as DeathData;
            const killer = death.killer?.name ?? (death.killer?.type ? itemName(death.killer.type) : null);
            return (
              <li key={`${event.atMillis}-${event.id ?? ""}`} className="flex flex-col gap-0.5 p-3">
                <span className="break-words">{death.message ?? death.cause ?? "—"}</span>
                <span className="text-xs text-muted-foreground">
                  {[
                    dateTime(event.at),
                    death.location && where(death.location),
                    death.level != null && t("player.levelAt", { level: death.level }),
                    killer && t("player.killer", { killer }),
                    death.cause && death.cause.replace(/^minecraft:/, ""),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </li>
            );
          })}
        </ul>
      )}
      {query.hasNextPage && (
        <Button variant="outline" size="sm" className="self-center" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          {t("common.more")}
        </Button>
      )}
    </div>
  );
};

// ---------- Advancements ----------

const TABS = ["story", "nether", "end", "adventure", "husbandry"];
const tabOf = (key: string) => {
  const [namespace, path = ""] = key.split(":");
  const tab = path.split("/")[0];
  return namespace === "minecraft" && TABS.includes(tab) ? tab : "other";
};

/** Every advancement on the server, grouped by its tab in the game, with dates and progress. */
export const AdvancementsTab = ({ player }: { player: string }) => {
  const { t } = useTranslation();
  const [filter, setFilter] = useState<"all" | "done" | "left">("all");
  const [search, setSearch] = useState("");
  const query = useQuery({ queryKey: ["player", player, "advancements"], queryFn: () => data(client.GET("/players/{player}/advancements", { params: { path: { player } } })) });
  if (query.error) return <ErrorBox error={query.error} />;
  if (!query.data) return <Loading rows={6} />;
  const all = query.data.advancements;
  const needle = search.trim().toLowerCase();
  const shown = all.filter(
    (advancement) =>
      (filter === "all" || (filter === "done") === advancement.done) &&
      (!needle || (advancement.title ?? advancement.key).toLowerCase().includes(needle) || (advancement.description ?? "").toLowerCase().includes(needle)),
  );
  const groups = [...TABS, "other"]
    .map((tab) => ({ tab, items: shown.filter((advancement) => tabOf(advancement.key) === tab), total: all.filter((advancement) => tabOf(advancement.key) === tab) }))
    .filter((group) => group.items.length);
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm">{t("player.advancementsDone", { done: query.data.done, total: all.length })}</p>
      <div className="flex flex-wrap gap-2">
        <div className="flex rounded-md border p-0.5" role="group" aria-label={t("player.show")}>
          {(["all", "done", "left"] as const).map((value) => (
            <Button key={value} size="sm" variant={filter === value ? "secondary" : "ghost"} className="h-7" aria-pressed={filter === value} onClick={() => setFilter(value)}>
              {t(`player.advancementFilter.${value}`)}
            </Button>
          ))}
        </div>
        <Input className="min-w-0 flex-1 basis-40" placeholder={t("player.findAdvancement")} aria-label={t("player.findAdvancement")} value={search} onChange={(event) => setSearch(event.target.value)} />
      </div>
      {!groups.length && <Empty>{t("player.noAdvancementMatch")}</Empty>}
      {groups.map((group) => (
        <section key={group.tab} className="flex flex-col gap-2">
          <h3 className="flex items-baseline gap-2 text-sm font-medium">
            {t(`player.advancementTabs.${group.tab}`)}
            <span className="tabular text-xs font-normal text-muted-foreground">
              {number(group.total.filter((advancement) => advancement.done).length)} / {number(group.total.length)}
            </span>
          </h3>
          <ul className="flex flex-col divide-y rounded-lg border bg-card text-sm">
            {group.items.map((advancement) => (
              <li key={advancement.key} className="flex items-start gap-2.5 p-2.5">
                {advancement.done ? <Check className="mt-0.5 size-4 shrink-0 text-success" aria-label={t("player.done")} /> : <Circle className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-label={t("player.notDone")} />}
                <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <span className={cn("font-medium", !advancement.done && "text-muted-foreground")}>{advancement.title ?? advancement.key}</span>
                    {advancement.frame && advancement.frame !== "task" && (
                      <Badge variant="outline" className="font-normal">
                        {t(`player.frames.${advancement.frame}`, { defaultValue: advancement.frame })}
                      </Badge>
                    )}
                  </div>
                  {advancement.description && <span className="text-xs break-words text-muted-foreground">{advancement.description}</span>}
                </div>
                <span className="tabular shrink-0 text-right text-xs text-muted-foreground">
                  {advancement.done ? dateTime(advancement.doneAt) : advancement.criteriaTotal > 1 ? `${advancement.criteriaDone} / ${advancement.criteriaTotal}` : ""}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
};
