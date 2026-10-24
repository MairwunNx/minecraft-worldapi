import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { FileText, Share2, SquareTerminal } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, Loading, PageHeader } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useCan } from "@/lib/session";

/**
 * Every plugin with its version and config files; a config opens in Files for those who may read it. A plugin that
 * shares data through the expansion API shows its namespaces, and one with commands their count.
 */
export const PluginsPage = () => {
  const { t } = useTranslation();
  const can = useCan();
  const [filter, setFilter] = useState("");
  const plugins = useQuery({ queryKey: ["plugins"], queryFn: () => data(client.GET("/server/plugins")) });
  const server = useQuery({ queryKey: ["server"], queryFn: () => data(client.GET("/server")) });
  const extensions = useQuery({ queryKey: ["extensions"], queryFn: () => data(client.GET("/extensions")) });
  const commands = useQuery({
    queryKey: ["commands", "sources"],
    queryFn: () => data(client.GET("/server/commands", { params: { query: { limit: 1 } } })),
    enabled: can("commands:read") || can("server:console"),
    staleTime: 60_000,
  });
  const shares = (name: string) => (extensions.data ?? []).filter((extension) => extension.plugin === name);
  const commandCount = (name: string) => commands.data?.sources?.find((source) => source.source?.toLowerCase() === name.toLowerCase())?.commands ?? 0;
  const needle = filter.trim().toLowerCase();
  const rows = (plugins.data ?? []).filter((plugin) => !needle || plugin.name.toLowerCase().includes(needle)).sort((a, b) => a.name.localeCompare(b.name));
  const files = can("files:read");
  return (
    <div className="flex flex-col gap-5">
      <PageHeader title={t("plugins.title")} description={plugins.data && t("plugins.count", { count: plugins.data.length })} />
      <Input placeholder={t("plugins.filter")} aria-label={t("plugins.filter")} value={filter} onChange={(event) => setFilter(event.target.value)} className="sm:max-w-xs" />
      {plugins.error ? (
        <ErrorBox error={plugins.error} />
      ) : !plugins.data ? (
        <Loading rows={6} />
      ) : !rows.length ? (
        <Empty>{t("plugins.none")}</Empty>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          {rows.map((plugin) => (
            <Card key={plugin.name} className="min-w-0 gap-2 py-3">
              <CardContent className="flex min-w-0 flex-col gap-2 px-4">
                <div className="flex items-baseline justify-between gap-2">
                  <span className="min-w-0 truncate font-medium">{plugin.name}</span>
                  <span className="shrink-0 font-mono text-xs text-muted-foreground">{plugin.version}</span>
                </div>
                {!plugin.enabled && (
                  <Badge variant="destructive" className="self-start">
                    {t("plugins.disabled")}
                  </Badge>
                )}
                {plugin.description && <p className="line-clamp-2 text-xs text-muted-foreground">{plugin.description}</p>}
                {!!plugin.authors?.length && <p className="truncate text-xs text-muted-foreground">{t("plugins.by", { authors: plugin.authors.join(", ") })}</p>}
                {shares(plugin.name).map((extension) => (
                  <p key={extension.namespace} className="flex min-w-0 items-center gap-1.5 text-xs" title={extension.description}>
                    <Share2 className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                    <span className="truncate">
                      {t("plugins.shares", { namespace: extension.namespace })} · {t("plugins.resources", { count: extension.resources.length })} · {t("plugins.topics", { count: extension.topics.length })}
                    </span>
                  </p>
                ))}
                {commandCount(plugin.name) > 0 && (
                  <p className="flex min-w-0 items-center gap-1.5 text-xs">
                    <SquareTerminal className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                    <span className="truncate">{t("plugins.commands", { count: commandCount(plugin.name) })}</span>
                  </p>
                )}
                {!!plugin.configs?.length && (
                  <ul className="flex flex-col gap-0.5 border-t pt-2">
                    {plugin.configs.slice(0, 8).map((path) => (
                      <li key={path} className="min-w-0">
                        {files ? (
                          <Link to="/files" search={{ file: path }} className="flex min-w-0 items-center gap-1.5 text-xs hover:underline">
                            <FileText className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                            <span className="truncate font-mono">{path.split("/").slice(2).join("/") || path}</span>
                          </Link>
                        ) : (
                          <span className="block truncate font-mono text-xs text-muted-foreground">{path}</span>
                        )}
                      </li>
                    ))}
                    {plugin.configs.length > 8 && <li className="text-xs text-muted-foreground">{t("plugins.moreFiles", { count: plugin.configs.length - 8 })}</li>}
                  </ul>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
      {!!server.data?.datapacks?.length && (
        <section className="flex flex-col gap-2">
          <h2 className="text-sm font-medium">{t("plugins.datapacks")}</h2>
          <ul className="flex flex-col divide-y rounded-lg border bg-card text-sm">
            {server.data.datapacks.map((pack) => (
              <li key={pack.name} className="flex flex-wrap items-baseline justify-between gap-2 px-3 py-2">
                <span className="min-w-0 truncate">{pack.title}</span>
                <span className="flex gap-1.5">
                  {pack.source && <Badge variant="secondary">{pack.source}</Badge>}
                  <Badge variant="outline">{pack.enabled ? t("plugins.enabled") : t("plugins.off")}</Badge>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
};
