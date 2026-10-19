import { useInfiniteQuery } from "@tanstack/react-query";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import { ArrowRight, Search } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, Loading, PageHeader, TableFrame } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { dateTime, number, relative, span } from "@/lib/format";

const PAGE = 100;

export const PlayersPage = () => {
  const { t } = useTranslation();
  const search = useSearch({ from: "/app/players" });
  const navigate = useNavigate({ from: "/players" });
  const [filter, setFilter] = useState("");
  const sort = search.sort ?? "last_seen";
  const online = search.online === undefined ? undefined : search.online === "online";
  const query = useInfiniteQuery({
    queryKey: ["players", sort, online],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => data(client.GET("/players", { params: { query: { limit: PAGE, cursor: pageParam, sort, online } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const all = query.data?.pages.flatMap((page) => page.items) ?? [];
  const needle = filter.trim().toLowerCase();
  const rows = needle ? all.filter((player) => player.name.toLowerCase().includes(needle) || player.uuid.startsWith(needle)) : all;
  const total = query.data?.pages[0]?.total;
  const open = () => needle && void navigate({ to: "/players/$player", params: { player: filter.trim() } });
  return (
    <div>
      <PageHeader title={t("players.title")} description={total == null ? undefined : t("players.total", { count: total })} />
      <div className="mb-3 flex flex-col gap-2 sm:flex-row">
        <form
          className="flex min-w-0 flex-1 gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            open();
          }}
        >
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground" aria-hidden />
            <Input className="pl-8" placeholder={t("players.search")} aria-label={t("players.search")} value={filter} onChange={(event) => setFilter(event.target.value)} />
          </div>
          <Button type="submit" variant="outline" disabled={!needle} aria-label={t("players.open")}>
            <ArrowRight className="size-4" aria-hidden />
            <span className="hidden sm:inline">{t("players.open")}</span>
          </Button>
        </form>
        <div className="flex gap-2">
          <Select value={sort} onValueChange={(value) => navigate({ search: (prev) => ({ ...prev, sort: value as typeof sort }) })}>
            <SelectTrigger className="w-full sm:w-40" aria-label={t("players.sort")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="last_seen">{t("players.sorts.last_seen")}</SelectItem>
              <SelectItem value="name">{t("players.sorts.name")}</SelectItem>
              <SelectItem value="playtime">{t("players.sorts.playtime")}</SelectItem>
            </SelectContent>
          </Select>
          <Select value={search.online ?? "all"} onValueChange={(value) => navigate({ search: (prev) => ({ ...prev, online: value === "all" ? undefined : (value as "online" | "offline") }) })}>
            <SelectTrigger className="w-full sm:w-32" aria-label={t("players.show")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("players.all")}</SelectItem>
              <SelectItem value="online">{t("players.onlineOnly")}</SelectItem>
              <SelectItem value="offline">{t("players.offlineOnly")}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : query.isPending ? (
        <Loading rows={8} />
      ) : !rows.length ? (
        <Empty>{needle ? t("players.noMatch", { name: filter.trim() }) : t("players.none")}</Empty>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("players.name")}</TableHead>
                <TableHead>{t("players.lastSeen")}</TableHead>
                <TableHead className="text-right">{t("players.playtime")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("players.firstPlayed")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((player) => (
                <TableRow key={player.uuid}>
                  <TableCell className="max-w-48">
                    <Link to="/players/$player" params={{ player: player.uuid }} className="flex min-w-0 items-center gap-2 font-medium hover:underline">
                      <span className="truncate">{player.name}</span>
                      {player.online && (
                        <Badge variant="outline" className="gap-1 border-success/40 font-normal">
                          <span className="size-1.5 rounded-full bg-success" aria-hidden />
                          {t("players.online")}
                        </Badge>
                      )}
                    </Link>
                  </TableCell>
                  <TableCell className="whitespace-nowrap" title={dateTime(player.lastSeen)}>
                    {player.online ? t("players.now") : relative(player.lastSeen)}
                  </TableCell>
                  <TableCell className="tabular text-right whitespace-nowrap">{span(player.playtimeSeconds)}</TableCell>
                  <TableCell className="hidden whitespace-nowrap md:table-cell">{dateTime(player.firstPlayed)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      {query.hasNextPage && (
        <div className="mt-3 flex items-center justify-between gap-2 text-sm text-muted-foreground">
          <span>{t("players.shown", { shown: number(all.length), total: total == null ? "…" : number(total) })}</span>
          <Button variant="outline" size="sm" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
            {t("common.more")}
          </Button>
        </div>
      )}
    </div>
  );
};
