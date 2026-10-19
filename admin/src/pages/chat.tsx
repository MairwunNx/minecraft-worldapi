import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { SendHorizontal } from "lucide-react";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, LiveBadge, Loading, PageHeader } from "@/components/common";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { dateTime, time } from "@/lib/format";
import { showError } from "@/lib/forms";
import { useCan } from "@/lib/session";
import { type LiveEvent, useLink, useTopic } from "@/lib/ws";

interface Message {
  key: string;
  at: string;
  player: string;
  message: string;
  /** Sent from this panel as the server: shown here once, since a broadcast isn't a chat event. */
  server?: boolean;
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
      {can("players:write") && <SendBox onSent={(message) => setLive((current) => [message, ...current].slice(0, 1000))} />}
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
                {message.server ? (
                  <span className="font-medium text-primary">{t("chat.server")}</span>
                ) : (
                  <Link to="/players/$player" params={{ player: message.player }} className="font-medium hover:underline">
                    {message.player}
                  </Link>
                )}
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

/**
 * Writes to everyone's chat as the server, through POST /server/broadcast. Enter sends; ↑ and ↓ walk
 * through this visit's messages. The server's broadcast raises no chat event, so the page adds the
 * line itself; it isn't kept in the history either.
 */
const SendBox = ({ onSent }: { onSent: (message: Message) => void }) => {
  const { t } = useTranslation();
  const [text, setText] = useState("");
  const [mini, setMini] = useState(false);
  const [busy, setBusy] = useState(false);
  const past = useRef<string[]>([]);
  const cursor = useRef(-1);
  const send = async () => {
    const message = text.trim();
    if (!message || busy) return;
    setBusy(true);
    try {
      await data(client.POST("/server/broadcast", { body: { message, kind: "chat", format: mini ? "minimessage" : "plain" } }));
      past.current = [message, ...past.current.filter((m) => m !== message)].slice(0, 50);
      cursor.current = -1;
      setText("");
      const at = new Date();
      onSent({ key: `sent-${at.getTime()}`, at: at.toISOString(), player: "", message, server: true });
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="flex flex-col gap-1.5">
      <form
        className="flex gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          void send();
        }}
      >
        <Input
          className="min-w-0 flex-1"
          placeholder={t("chat.send")}
          aria-label={t("chat.send")}
          autoComplete="off"
          maxLength={2000}
          value={text}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
            event.preventDefault();
            const next = event.key === "ArrowUp" ? Math.min(past.current.length - 1, cursor.current + 1) : Math.max(-1, cursor.current - 1);
            cursor.current = next;
            setText(next < 0 ? "" : past.current[next]);
          }}
        />
        <Button type="submit" disabled={busy || !text.trim()}>
          <SendHorizontal className="size-4" aria-hidden />
          <span className="hidden sm:inline">{t("chat.sendButton")}</span>
        </Button>
      </form>
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        <span>{t("chat.sendHint")}</span>
        <label className="flex items-center gap-1.5">
          <Checkbox checked={mini} onCheckedChange={(on) => setMini(on === true)} />
          {t("chat.minimessage")}
        </label>
      </div>
    </div>
  );
};
