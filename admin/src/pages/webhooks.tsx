import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Send, Trash2 } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { client, data, type Schema } from "@/api/client";
import { Chips, Confirm, Empty, ErrorBox, Loading, PageHeader, Secret, TableFrame } from "@/components/common";
import { FormField } from "@/components/form-field";
import { ScopePicker } from "@/components/scope-picker";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { date } from "@/lib/format";
import { showError } from "@/lib/forms";

const NAME = /^[A-Za-z0-9_-]{1,32}$/;
const DEFAULT_PERMISSIONS = ["server:read", "players:read", "events:read"];
const LANGUAGES = ["en", "ru", "es", "pt", "de"] as const;

/** Ready-made sets of topics, as the game's wizard offers them. */
const PRESETS: Record<string, string[]> = {
  all: ["*"],
  players: ["player.join", "player.leave", "player.death", "player.advancement"],
  server: ["server.status", "raid.start", "raid.finish", "entity.boss_killed", "world.night_skipped"],
  chat: ["player.chat"],
};

const optionsQuery = { queryKey: ["webhook-options"], queryFn: () => data(client.GET("/webhooks/options")), staleTime: 60_000 };

/** Presets, then every topic as a checkbox: `*` and `player.*` stand for many. */
const TopicPicker = ({ value, onChange }: { value: string[]; onChange: (value: string[]) => void }) => {
  const { t } = useTranslation();
  const options = useQuery(optionsQuery).data;
  if (!options) return <Loading rows={3} />;
  const toggle = (id: string, on: boolean) => onChange(on ? [...value, id] : value.filter((item) => item !== id));
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-sm font-medium">{t("webhooks.topics")}</legend>
      <div className="flex flex-wrap gap-2">
        {Object.entries(PRESETS).map(([key, topics]) => (
          <Button key={key} type="button" size="sm" variant={topics.every((topic) => value.includes(topic)) && topics.length === value.length ? "default" : "outline"} onClick={() => onChange(topics)}>
            {t(`webhooks.presets.${key}`)}
          </Button>
        ))}
      </div>
      <details className="rounded-md border p-2">
        <summary className="cursor-pointer text-sm text-muted-foreground">{t("webhooks.pickTopics", { count: value.length })}</summary>
        <div className="grid max-h-64 grid-cols-1 gap-1 overflow-y-auto pt-2 sm:grid-cols-2">
          {options.topics.map((topic) => (
            <div key={topic.id} className="flex items-start gap-2">
              <Checkbox id={`topic-${topic.id}`} checked={value.includes(topic.id) || (value.includes("*") && topic.notable)} disabled={value.includes("*") && topic.notable} onCheckedChange={(on) => toggle(topic.id, on === true)} className="mt-0.5" />
              <Label htmlFor={`topic-${topic.id}`} className="flex flex-col items-start gap-0 font-mono text-xs font-normal">
                {topic.id}
                {(topic.needs !== "events:read" || topic.coordinates) && (
                  <span className="font-sans text-muted-foreground">{topic.coordinates ? t("webhooks.needsCoordinates") : t("webhooks.needs", { permission: topic.needs })}</span>
                )}
              </Label>
            </div>
          ))}
        </div>
      </details>
    </fieldset>
  );
};

/** Its own words for chat messages; empty keeps the language's. */
const MessagesEditor = ({ value, onChange }: { value: Record<string, string>; onChange: (value: Record<string, string>) => void }) => {
  const { t } = useTranslation();
  const options = useQuery(optionsQuery).data;
  if (!options) return null;
  return (
    <details className="rounded-md border p-2">
      <summary className="cursor-pointer text-sm text-muted-foreground">{t("webhooks.messages", { count: Object.keys(value).length })}</summary>
      <p className="pt-2 text-xs text-muted-foreground">{t("webhooks.messagesHint")}</p>
      <div className="flex flex-col gap-2 pt-2">
        {Object.entries(options.messages).map(([key, fallback]) => (
          <FormField key={key} id={`message-${key}`} label={<span className="font-mono text-xs">{key}</span>}>
            <Input
              id={`message-${key}`}
              value={value[key] ?? ""}
              placeholder={fallback}
              maxLength={500}
              onChange={(event) => {
                const next = { ...value };
                if (event.target.value) next[key] = event.target.value;
                else delete next[key];
                onChange(next);
              }}
            />
          </FormField>
        ))}
      </div>
    </details>
  );
};

const LanguagePicker = ({ value, onChange }: { value: string; onChange: (value: string) => void }) => {
  const { t } = useTranslation();
  return (
    <FormField id="webhook-language" label={t("webhooks.language")}>
      <Select value={value} onValueChange={onChange}>
        <SelectTrigger id="webhook-language" className="w-48">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {LANGUAGES.map((code) => (
            <SelectItem key={code} value={code}>
              {t(`webhooks.languages.${code}`)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </FormField>
  );
};

/** Telegram: the bot token, then the chats that wrote to the bot, to pick where it posts. */
const TelegramFields = ({ token, onToken, chat, onChat }: { token: string; onToken: (value: string) => void; chat: string; onChat: (value: string) => void }) => {
  const { t } = useTranslation();
  const [chats, setChats] = useState<Schema<"TelegramChatView">[] | null>(null);
  const [busy, setBusy] = useState(false);
  const find = async () => {
    setBusy(true);
    try {
      const found = await data(client.POST("/webhooks/telegram/chats", { body: { botToken: token.trim() } }));
      setChats(found);
      if (found.length) onChat(found[0].id);
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <FormField id="webhook-bot" label={t("webhooks.botToken")} hint={t("webhooks.botTokenHint")}>
        <Input id="webhook-bot" value={token} onChange={(event) => onToken(event.target.value)} autoComplete="off" spellCheck={false} className="font-mono" />
      </FormField>
      <div className="flex flex-col gap-2">
        <p className="text-sm text-muted-foreground">{t("webhooks.findChatsHint")}</p>
        <Button type="button" variant="outline" className="self-start" disabled={busy || !token.trim()} onClick={find}>
          {t("webhooks.findChats")}
        </Button>
        {chats && !chats.length && <p className="text-sm text-muted-foreground">{t("webhooks.noChats")}</p>}
        {chats?.map((item) => (
          <label key={item.id} className="flex items-center gap-2 text-sm">
            <input type="radio" name="telegram-chat" checked={chat === item.id} onChange={() => onChat(item.id)} />
            {item.title} <span className="font-mono text-xs text-muted-foreground">{item.id}</span>
          </label>
        ))}
      </div>
    </>
  );
};

const CreateWebhook = () => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const options = useQuery(optionsQuery).data;
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [format, setFormat] = useState("discord");
  const [url, setUrl] = useState("");
  const [botToken, setBotToken] = useState("");
  const [chatId, setChatId] = useState("");
  const [topics, setTopics] = useState<string[]>(PRESETS.all);
  const [language, setLanguage] = useState("en");
  const [permissions, setPermissions] = useState<string[]>(DEFAULT_PERMISSIONS);
  const [messages, setMessages] = useState<Record<string, string>>({});
  const [created, setCreated] = useState<Schema<"WebhookCreated"> | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reset = () => {
    setName("");
    setUrl("");
    setBotToken("");
    setChatId("");
    setTopics(PRESETS.all);
    setPermissions(DEFAULT_PERMISSIONS);
    setMessages({});
    setCreated(null);
    setError(null);
  };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!NAME.test(name.trim())) return setError(t("tokens.nameRule"));
    if (!topics.length) return setError(t("webhooks.pickTopic"));
    if (format === "telegram" ? !botToken.trim() || !chatId : !url.trim()) return setError(t(format === "telegram" ? "webhooks.needTelegram" : "webhooks.needUrl"));
    setBusy(true);
    try {
      const telegram = format === "telegram";
      const body = { name: name.trim(), format, topics, permissions, language, messages, url: telegram ? undefined : url.trim(), botToken: telegram ? botToken.trim() : undefined, chatId: telegram ? chatId : undefined };
      setCreated(await data(client.POST("/webhooks", { body })));
      await queryClient.invalidateQueries({ queryKey: ["webhooks"] });
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) reset();
      }}
    >
      <Button onClick={() => setOpen(true)} disabled={options?.enabled === false}>
        <Plus className="size-4" aria-hidden />
        {t("webhooks.create")}
      </Button>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl">
        {created ? (
          <>
            <DialogHeader>
              <DialogTitle>{t("webhooks.createdTitle", { name: created.webhook.name })}</DialogTitle>
              <DialogDescription>{created.test?.ok ? t("webhooks.testOk") : created.test ? t("webhooks.testFailed", { problem: created.test.problem }) : t("webhooks.createdText")}</DialogDescription>
            </DialogHeader>
            {created.secret && (
              <>
                <p className="text-sm">{t("webhooks.secretText")}</p>
                <Secret value={created.secret} />
              </>
            )}
            <DialogFooter>
              <Button onClick={() => setOpen(false)}>{t("common.done")}</Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={submit} className="flex flex-col gap-4">
            <DialogHeader>
              <DialogTitle>{t("webhooks.createTitle")}</DialogTitle>
              <DialogDescription>{t("webhooks.createText")}</DialogDescription>
            </DialogHeader>
            <FormField id="webhook-format" label={t("webhooks.service")}>
              <Select value={format} onValueChange={setFormat}>
                <SelectTrigger id="webhook-format" className="w-56">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {options?.formats.map((item) => (
                    <SelectItem key={item.id} value={item.id}>
                      {item.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </FormField>
            <FormField id="webhook-name" label={t("tokens.name")} hint={t("tokens.nameRule")}>
              <Input id="webhook-name" value={name} onChange={(event) => setName(event.target.value)} autoComplete="off" spellCheck={false} />
            </FormField>
            {format === "telegram" ? (
              <TelegramFields token={botToken} onToken={setBotToken} chat={chatId} onChat={setChatId} />
            ) : (
              <FormField id="webhook-url" label={t("webhooks.url")} hint={t(`webhooks.urlHints.${format}`)}>
                <Input id="webhook-url" type="url" value={url} onChange={(event) => setUrl(event.target.value)} autoComplete="off" spellCheck={false} className="font-mono" />
              </FormField>
            )}
            <TopicPicker value={topics} onChange={setTopics} />
            {format !== "json" && <LanguagePicker value={language} onChange={setLanguage} />}
            {format !== "json" && <MessagesEditor value={messages} onChange={setMessages} />}
            <details className="rounded-md border p-2">
              <summary className="cursor-pointer text-sm text-muted-foreground">{t("webhooks.access")}</summary>
              <p className="pt-2 pb-2 text-xs text-muted-foreground">{t("webhooks.accessHint")}</p>
              <ScopePicker id="webhook" value={permissions} onChange={setPermissions} />
            </details>
            {error && (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            )}
            <DialogFooter>
              <Button type="submit" disabled={busy}>
                {t("webhooks.create")}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
};

/** What changes after a webhook exists: its topics, words, language, and access. The address stays; make a new one for another. */
const EditWebhook = ({ hook }: { hook: Schema<"WebhookView"> }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [topics, setTopics] = useState(hook.topics);
  const [language, setLanguage] = useState(hook.language);
  const [messages, setMessages] = useState<Record<string, string>>(hook.messages ?? {});
  const [permissions, setPermissions] = useState(hook.permissions);
  const [busy, setBusy] = useState(false);
  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      await data(client.PATCH("/webhooks/{name}", { params: { path: { name: hook.name } }, body: { topics, language, messages, permissions } }));
      await queryClient.invalidateQueries({ queryKey: ["webhooks"] });
      toast.success(t("webhooks.saved", { name: hook.name }));
      setOpen(false);
    } catch (failure) {
      showError(failure);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button size="icon" variant="ghost" onClick={() => setOpen(true)} aria-label={t("webhooks.edit")} title={t("webhooks.edit")}>
        <Pencil className="size-4" />
      </Button>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl">
        <form onSubmit={save} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t("webhooks.editTitle", { name: hook.name })}</DialogTitle>
          </DialogHeader>
          <TopicPicker value={topics} onChange={setTopics} />
          {hook.format !== "json" && <LanguagePicker value={language} onChange={setLanguage} />}
          {hook.format !== "json" && <MessagesEditor value={messages} onChange={setMessages} />}
          <details className="rounded-md border p-2">
            <summary className="cursor-pointer text-sm text-muted-foreground">{t("webhooks.access")}</summary>
            <ScopePicker id={`webhook-${hook.name}`} value={permissions} onChange={setPermissions} />
          </details>
          <DialogFooter>
            <Button type="submit" disabled={busy || !topics.length}>
              {t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
};

export const WebhooksPage = () => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const hooks = useQuery({ queryKey: ["webhooks"], queryFn: () => data(client.GET("/webhooks")) });
  const options = useQuery(optionsQuery).data;
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["webhooks"] });
  const test = async (name: string) => {
    try {
      const result = await data(client.POST("/webhooks/{name}/test", { params: { path: { name } }, body: {} }));
      if (result.ok) toast.success(t("webhooks.testOk"));
      else toast.error(t("webhooks.testFailed", { problem: result.problem }));
    } catch (failure) {
      showError(failure);
    }
  };
  const toggle = async (name: string, enabled: boolean) => {
    try {
      await data(client.PATCH("/webhooks/{name}", { params: { path: { name } }, body: { enabled } }));
      await refresh();
    } catch (failure) {
      showError(failure);
    }
  };
  const remove = async (name: string) => {
    try {
      await data(client.DELETE("/webhooks/{name}", { params: { path: { name } } }));
      toast.success(t("webhooks.deleted", { name }));
      await refresh();
    } catch (failure) {
      showError(failure);
      throw failure;
    }
  };
  return (
    <div>
      <PageHeader title={t("webhooks.title")} description={t("webhooks.text")} actions={<CreateWebhook />} />
      {options?.enabled === false && (
        <Alert className="mb-4">
          <AlertDescription>{t("webhooks.off")}</AlertDescription>
        </Alert>
      )}
      {hooks.error ? (
        <ErrorBox error={hooks.error} />
      ) : !hooks.data ? (
        <Loading rows={4} />
      ) : !hooks.data.length ? (
        <Empty>{t("webhooks.none")}</Empty>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("tokens.name")}</TableHead>
                <TableHead>{t("webhooks.topics")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("tokens.created")}</TableHead>
                <TableHead>{t("webhooks.on")}</TableHead>
                <TableHead className="w-px" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {hooks.data.map((hook) => (
                <TableRow key={hook.name}>
                  <TableCell className="align-top">
                    <div className="font-medium">{hook.name}</div>
                    <div className="flex flex-wrap gap-1 pt-1">
                      <Badge variant="secondary">{options?.formats.find((item) => item.id === hook.format)?.title ?? hook.format}</Badge>
                      {hook.format !== "json" && <Badge variant="outline" className="font-normal">{hook.language}</Badge>}
                      {hook.source === "config" && <Badge variant="outline" className="font-normal">config.yml</Badge>}
                    </div>
                    <div className="pt-1 font-mono text-xs break-all text-muted-foreground">{hook.target}</div>
                  </TableCell>
                  <TableCell className="max-w-72 align-top whitespace-normal">
                    <Chips items={hook.topics} />
                  </TableCell>
                  <TableCell className="hidden align-top text-xs md:table-cell">
                    {hook.createdAt ? date(hook.createdAt) : "—"}
                    {hook.createdBy && <div className="text-muted-foreground">{hook.createdBy}</div>}
                  </TableCell>
                  <TableCell className="align-top">
                    <Switch checked={hook.enabled} disabled={hook.source === "config"} onCheckedChange={(on) => toggle(hook.name, on)} aria-label={t("webhooks.on")} />
                  </TableCell>
                  <TableCell className="align-top">
                    <div className="flex">
                      <Button size="icon" variant="ghost" onClick={() => test(hook.name)} aria-label={t("webhooks.test")} title={t("webhooks.test")}>
                        <Send className="size-4" />
                      </Button>
                      {hook.source === "made" && <EditWebhook hook={hook} />}
                      {hook.source === "made" && (
                        <Confirm
                          destructive
                          size="icon"
                          variant="ghost"
                          label={<Trash2 className="size-4" aria-label={t("webhooks.delete")} />}
                          title={t("webhooks.deleteTitle", { name: hook.name })}
                          text={t("webhooks.deleteText")}
                          action={t("webhooks.delete")}
                          onConfirm={() => remove(hook.name)}
                        />
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      <Alert className="mt-4">
        <AlertDescription>{t("webhooks.gameNote")}</AlertDescription>
      </Alert>
    </div>
  );
};
