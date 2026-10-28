import { Check, Copy, ExternalLink } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { Schema } from "@/api/client";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { copy } from "@/lib/copy";

/** A block of text to copy: a command or a file's content. */
const Snippet = ({ text }: { text: string }) => {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  return (
    <div className="relative rounded-md border bg-muted/50">
      <pre className="max-h-64 overflow-auto p-3 pr-11 font-mono text-xs break-all whitespace-pre-wrap select-all">{text}</pre>
      <Button
        size="icon"
        variant="ghost"
        className="absolute top-1 right-1"
        aria-label={t("common.copy")}
        onClick={async () => {
          await copy(text);
          setCopied(true);
        }}
      >
        {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
      </Button>
    </div>
  );
};

/**
 * How to add minecraft-mcp to each AI client, with the new token inside: a tab per client, and a switch between every
 * project on the machine and this one alone.
 */
export const McpSetupView = ({ setup }: { setup: Schema<"McpSetup"> }) => {
  const { t } = useTranslation();
  const [project, setProject] = useState(false);
  const clients = [...new Set(setup.clients.map((client) => client.client))];
  const pick = (client: string) =>
    setup.clients.find((item) => item.client === client && item.scope === (project ? "project" : "user")) ?? setup.clients.find((item) => item.client === client)!;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Switch id="mcp-project" checked={project} onCheckedChange={setProject} />
        <Label htmlFor="mcp-project" className="flex flex-col items-start gap-0.5 font-normal">
          {t("mcp.project")}
          <span className="text-xs text-muted-foreground">{t("mcp.projectHint")}</span>
        </Label>
      </div>
      <Tabs defaultValue={clients[0]}>
        <TabsList className="flex w-full flex-wrap justify-start group-data-[orientation=horizontal]/tabs:h-auto">
          {clients.map((client) => (
            <TabsTrigger key={client} value={client} className="h-8 flex-none">
              {t(`mcp.clients.${client}`, { defaultValue: pick(client).title })}
            </TabsTrigger>
          ))}
        </TabsList>
        {clients.map((client) => {
          const item = pick(client);
          const own = item.scope === (project ? "project" : "user");
          return (
            <TabsContent key={client} value={client} className="flex flex-col gap-2 pt-2">
              {!own && <p className="text-xs text-muted-foreground">{t("mcp.noProject")}</p>}
              {item.command && (
                <>
                  <p className="text-sm">{t(item.scope === "project" ? "mcp.runInProject" : "mcp.run")}</p>
                  <Snippet text={item.command} />
                </>
              )}
              {item.content && (
                <>
                  <p className="text-sm">{item.client === "plain" ? t("mcp.plain") : t("mcp.file", { file: item.file })}</p>
                  <Snippet text={item.content} />
                </>
              )}
              {item.link && (
                <Button variant="outline" asChild className="self-start">
                  <a href={item.link}>
                    <ExternalLink className="size-4" aria-hidden />
                    {t("mcp.openIn", { client: item.title })}
                  </a>
                </Button>
              )}
              {item.gitignore && (
                <Alert>
                  <AlertDescription>{t("mcp.gitignore", { file: item.gitignore })}</AlertDescription>
                </Alert>
              )}
              {item.trust && <p className="text-xs text-muted-foreground">{t("mcp.trust")}</p>}
            </TabsContent>
          );
        })}
      </Tabs>
      <p className="text-xs text-muted-foreground">{t("mcp.address", { url: setup.url })}</p>
    </div>
  );
};
