import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, Loading, PageHeader } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";

export const WorldsPage = () => {
  const { t } = useTranslation();
  const query = useQuery({ queryKey: ["worlds"], queryFn: () => data(client.GET("/worlds")), refetchInterval: 30_000 });
  return (
    <div>
      <PageHeader title={t("worlds.title")} description={t("worlds.text")} />
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !query.data ? (
        <Loading rows={3} />
      ) : !query.data.length ? (
        <Empty>{t("worlds.none")}</Empty>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {query.data.map((world) => (
            <Link key={world.name} to="/worlds/$world" params={{ world: world.name }} className="min-w-0 rounded-xl focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none">
              <Card className="h-full gap-1 py-4 transition-colors hover:bg-accent/50">
                <CardContent className="flex min-w-0 flex-col gap-1 px-4">
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate font-medium">{world.name}</span>
                    <Badge variant="outline" className="shrink-0 font-normal">
                      {t(`worlds.environments.${world.environment}`, { defaultValue: world.environment })}
                    </Badge>
                  </div>
                  <span className="truncate font-mono text-xs text-muted-foreground">{world.key}</span>
                  <span className="text-sm text-muted-foreground">{t("worlds.players", { count: world.players })}</span>
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
};
