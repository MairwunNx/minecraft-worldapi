import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Lock, MapPin, SendHorizontal } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, LiveBadge, Loading, PageHeader } from "@/components/common";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { dateTime, time } from "@/lib/format";
import { showError } from "@/lib/forms";
import { useCan } from "@/lib/session";
import { type LiveEvent, useLink, useTopic } from "@/lib/ws";

type Channel = "public" | "local" | "private";

interface Message {
  key: string;
  at: string;
  player: string;
  message: string;
  channel: Channel;
  /** A private message's recipient. */
  to?: string;
  /** Who got a local line, when a chat plugin kept it to some players. */
  heard?: string[];
  /** Sent from this panel as the server: shown here once, since a broadcast isn't a chat event. */
  server?: boolean;
}

interface Ref {
  uuid: string;
  name?: string | null;
}

const nameOf = (ref?: Ref | null) => ref?.name ?? ref?.uuid ?? "?";

const toMessage = (event: { type: string; at: string; atMillis: number; player?: Ref | null; data: unknown }): Message => {
  const body = event.data as { message?: unknown; channel?: unknown; recipient?: Ref; heard?: Ref[] };
  const whisper = event.type === "player.whisper";
  return {
    key: `${event.type}-${event.atMillis}-${event.player?.uuid ?? ""}`,
    at: event.at,
    player: nameOf(event.player),
    message: String(body.message ?? ""),
    channel: whisper ? "private" : body.channel === "local" ? "local" : "public",
    ...(whisper && { to: nameOf(body.recipient) }),
    ...(body.channel === "local" && { heard: body.heard?.map(nameOf) }),
  };
};

/** A value that settles a moment after the last change, so typing a name doesn't ask the server on every key. */
const useSettled = (value: string, ms = 400) => {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return settled;
};

/**
 * Newest at the top: history from GET /events, live lines from player.chat above it. With logs:read, private
 * messages (player.whisper) join them. A player narrows the history on the server to what they wrote and the
 * private messages sent to them; the text filter works on what's loaded.
 */
export const ChatPage = () => {
  const { t } = useTranslation();
  const can = useCan();
  const allowed = can("chat:read");
  const whispers = allowed && can("logs:read");
  const [live, setLive] = useState<Message[]>([]);
  const [filter, setFilter] = useState("");
  const [channel, setChannel] = useState<Channel | "all">("all");
  const [playerInput, setPlayerInput] = useState("");
  const player = useSettled(playerInput.trim());
  const { refused } = useLink("player.chat");
  const types = whispers ? "player.chat,player.whisper" : "player.chat";
  const history = useInfiniteQuery({
    queryKey: ["chat", types, player],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      data(client.GET("/events", { params: { query: { type: types, involving: player || undefined, limit: 100, cursor: pageParam } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: allowed,
    staleTime: Infinity,
    gcTime: 0,
  });
  const add = (event: LiveEvent) => setLive((current) => [toMessage(event), ...current].slice(0, 1000));
  useTopic("player.chat", add, allowed);
  useTopic("player.whisper", add, whispers);
  const past = history.data?.pages.flatMap((page) => page.items.map(toMessage)) ?? [];
  const seen = new Set(live.map((message) => message.key));
  const needle = filter.trim().toLowerCase();
  const who = player.toLowerCase();
  const messages = [...live, ...past.filter((message) => !seen.has(message.key))].filter(
    (message) =>
      (channel === "all" || message.channel === channel) &&
      (!who || message.server || message.player.toLowerCase() === who || message.to?.toLowerCase() === who) &&
      (!needle || [message.player, message.to ?? "", message.message].some((text) => text.toLowerCase().includes(needle))),
  );
  if (!allowed) return <Empty>{t("chat.noAccess")}</Empty>;
  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={t("chat.title")} description={t(whispers ? "chat.textPrivate" : "chat.text")} actions={<LiveBadge />} />
      {refused && (
        <Alert>
          <AlertDescription>{t("chat.off")}</AlertDescription>
        </Alert>
      )}
      {can("players:write") && <SendBox onSent={(message) => setLive((current) => [message, ...current].slice(0, 1000))} />}
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          className="sm:w-48"
          placeholder={t("chat.player")}
          aria-label={t("chat.playerHint")}
          title={t("chat.playerHint")}
          value={playerInput}
          onChange={(event) => setPlayerInput(event.target.value)}
        />
        <Input className="min-w-0 flex-1" placeholder={t("chat.filter")} aria-label={t("chat.filter")} value={filter} onChange={(event) => setFilter(event.target.value)} />
        <Select value={channel} onValueChange={(value) => setChannel(value as typeof channel)}>
          <SelectTrigger className="sm:w-36" aria-label={t("chat.channel")}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {(["all", "public", "local", ...(whispers ? (["private"] as const) : [])] as const).map((value) => (
              <SelectItem key={value} value={value}>
                {t(`chat.channels.${value}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
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
                {message.channel === "private" && (
                  <Badge variant="outline" className="mr-1.5 gap-1 font-normal">
                    <Lock className="size-3" aria-hidden />
                    {t("chat.channels.private")}
                  </Badge>
                )}
                {message.channel === "local" && (
                  <Badge variant="outline" className="mr-1.5 gap-1 font-normal" title={t("chat.heard", { players: message.heard?.join(", ") || "—" })}>
                    <MapPin className="size-3" aria-hidden />
                    {t("chat.channels.local")}
                  </Badge>
                )}
                {message.server ? (
                  <span className="font-medium text-primary">{t("chat.server")}</span>
                ) : (
                  <Link to="/players/$player" params={{ player: message.player }} className="font-medium hover:underline">
                    {message.player}
                  </Link>
                )}
                {message.to && (
                  <>
                    <span className="text-muted-foreground"> → </span>
                    <Link to="/players/$player" params={{ player: message.to }} className="font-medium hover:underline">
                      {message.to}
                    </Link>
                  </>
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
      onSent({ key: `sent-${at.getTime()}`, at: at.toISOString(), player: "", message, channel: "public", server: true });
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
