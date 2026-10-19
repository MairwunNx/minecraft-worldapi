import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, LiveBadge, Loading, PageHeader } from "@/components/common";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { dateTime, time } from "@/lib/format";
import { useCan } from "@/lib/session";
import { type LiveEvent, useLink, useTopic } from "@/lib/ws";

interface Message {
  key: string;
  at: string;
  player: string;
  message: string;
}

const toMessage = (event: { at: string; atMillis: number; player?: { uuid: string; name?: string | null } | null; data: unknown }): Message => ({
  key: `${event.atMillis}-${event.player?.uuid ?? ""}`,
  at: event.at,
  player: event.player?.name ?? event.player?.uuid ?? "?",
  message: String((event.data as { message?: unknown }).message ?? ""),
});

/** Newest at the top: history from GET /events, live lines from player.chat above it. */
export const ChatPage = () => {
  const { t } = useTranslation();
  const can = useCan();
  const allowed = can("chat:read");
  const [live, setLive] = useState<Message[]>([]);
  const [filter, setFilter] = useState("");
  const { refused } = useLink("player.chat");
  const history = useInfiniteQuery({
    queryKey: ["chat"],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => data(client.GET("/events", { params: { query: { type: "player.chat", limit: 100, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: allowed,
    staleTime: Infinity,
    gcTime: 0,
  });
  useTopic("player.chat", (event: LiveEvent) => setLive((current) => [toMessage(event), ...current].slice(0, 1000)), allowed);
  const past = history.data?.pages.flatMap((page) => page.items.map(toMessage)) ?? [];
  const seen = new Set(live.map((message) => message.key));
  const needle = filter.trim().toLowerCase();
  const messages = [...live, ...past.filter((message) => !seen.has(message.key))].filter(
    (message) => !needle || message.player.toLowerCase().includes(needle) || message.message.toLowerCase().includes(needle),
  );
  if (!allowed) return <Empty>{t("chat.noAccess")}</Empty>;
  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={t("chat.title")} description={t("chat.text")} actions={<LiveBadge />} />
      {refused && (
        <Alert>
          <AlertDescription>{t("chat.off")}</AlertDescription>
        </Alert>
      )}
      <Input placeholder={t("chat.filter")} aria-label={t("chat.filter")} value={filter} onChange={(event) => setFilter(event.target.value)} />
      {history.error ? (
        <ErrorBox error={history.error} />
      ) : history.isPending ? (
        <Loading rows={8} />
      ) : !messages.length ? (
        <Empty>{t("chat.none")}</Empty>
      ) : (
        <ul className="flex flex-col divide-y rounded-lg border bg-card text-sm">
          {messages.map((message) => (
            <li key={message.key} className="flex flex-col gap-0.5 px-3 py-2 sm:flex-row sm:gap-3">
              <span className="tabular shrink-0 text-xs text-muted-foreground sm:w-36 sm:pt-0.5" title={dateTime(message.at)}>
                {new Date(message.at).toDateString() === new Date().toDateString() ? time(message.at) : dateTime(message.at)}
              </span>
              <span className="min-w-0 break-words">
                <Link to="/players/$player" params={{ player: message.player }} className="font-medium hover:underline">
                  {message.player}
                </Link>
                <span className="text-muted-foreground">: </span>
                {message.message}
              </span>
            </li>
          ))}
        </ul>
      )}
      {history.hasNextPage && (
        <Button variant="outline" size="sm" className="self-center" disabled={history.isFetchingNextPage} onClick={() => history.fetchNextPage()}>
          {t("chat.older")}
        </Button>
      )}
    </div>
  );
};
