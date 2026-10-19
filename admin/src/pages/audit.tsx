import { useInfiniteQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Empty, ErrorBox, Loading, PageHeader, TableFrame } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { dateTime, relative } from "@/lib/format";

export const AuditPage = () => {
  const { t } = useTranslation();
  const { actor } = useSearch({ from: "/app/access/audit" });
  const navigate = useNavigate({ from: "/access/audit" });
  const [input, setInput] = useState(actor ?? "");
  const query = useInfiniteQuery({
    queryKey: ["audit", actor],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => data(client.GET("/audit", { params: { query: { limit: 50, cursor: pageParam, actor } } })),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const rows = query.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={t("audit.title")} description={t("audit.text")} />
      <form
        className="flex gap-2 sm:max-w-sm"
        onSubmit={(event) => {
          event.preventDefault();
          void navigate({ search: { actor: input.trim() || undefined } });
        }}
      >
        <Input placeholder={t("audit.actor")} aria-label={t("audit.actor")} value={input} onChange={(event) => setInput(event.target.value)} />
        <Button type="submit" variant="outline">
          {t("audit.filter")}
        </Button>
      </form>
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : query.isPending ? (
        <Loading rows={8} />
      ) : !rows.length ? (
        <Empty>{t("audit.none")}</Empty>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("audit.when")}</TableHead>
                <TableHead>{t("audit.who")}</TableHead>
                <TableHead>{t("audit.what")}</TableHead>
                <TableHead className="text-right">{t("audit.status")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row) => (
                <TableRow key={row.id}>
                  <TableCell className="align-top text-xs whitespace-nowrap" title={dateTime(row.at)}>
                    {relative(row.at)}
                  </TableCell>
                  <TableCell className="max-w-32 truncate align-top text-sm">
                    <button type="button" className="truncate hover:underline" onClick={() => navigate({ search: { actor: row.actor } }).then(() => setInput(row.actor))}>
                      {row.actor}
                    </button>
                  </TableCell>
                  <TableCell className="max-w-96 align-top whitespace-normal">
                    <div className="font-mono text-xs break-all">
                      {row.method} {row.path}
                    </div>
                    {(row.target || row.detail) && <div className="text-xs break-words text-muted-foreground">{[row.target, row.detail].filter(Boolean).join(" — ")}</div>}
                  </TableCell>
                  <TableCell className="text-right align-top">
                    <Badge variant={row.status >= 400 ? "destructive" : "outline"} className="tabular">
                      {row.status}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      {query.hasNextPage && (
        <Button variant="outline" size="sm" className="self-center" disabled={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>
          {t("common.more")}
        </Button>
      )}
    </div>
  );
};
