import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Chips, ErrorBox, Loading, PageHeader, TableFrame } from "@/components/common";
import { groupsQuery } from "@/components/scope-picker";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/** Read only: groups live in config.yml (`auth.groups`). */
export const GroupsPage = () => {
  const { t } = useTranslation();
  const query = useQuery(groupsQuery);
  const groups = query.data;
  return (
    <div className="flex flex-col gap-5">
      <PageHeader title={t("groups.title")} description={t("groups.text")} />
      {query.error ? (
        <ErrorBox error={query.error} />
      ) : !groups ? (
        <Loading rows={4} />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            {Object.entries(groups.groups).map(([name, members]) => (
              <Card key={name} className="gap-2 py-3">
                <CardHeader className="px-4">
                  <CardTitle className="text-sm">{name}</CardTitle>
                </CardHeader>
                <CardContent className="flex flex-col gap-2 px-4">
                  <div className="text-xs text-muted-foreground">{t("groups.lists", { members: members.join(", ") })}</div>
                  <Chips items={groups.resolved[name] ?? []} />
                </CardContent>
              </Card>
            ))}
          </div>
          <section className="flex flex-col gap-2">
            <h2 className="text-sm font-medium">{t("groups.permissions")}</h2>
            <TableFrame>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("groups.permission")}</TableHead>
                    <TableHead>{t("groups.allows")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {groups.permissions.map((permission) => (
                    <TableRow key={permission.id}>
                      <TableCell className="align-top font-mono text-xs whitespace-nowrap">{permission.id}</TableCell>
                      <TableCell className="text-sm whitespace-normal">{permission.description}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableFrame>
          </section>
        </>
      )}
    </div>
  );
};
