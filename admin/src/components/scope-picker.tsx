import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { client, data } from "@/api/client";
import { Loading } from "@/components/common";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { useMe } from "@/lib/session";

export const groupsQuery = { queryKey: ["groups"], queryFn: () => data(client.GET("/groups")), staleTime: 60_000 };

/**
 * Groups and single permissions as checkboxes. One the signed-in account doesn't have itself stays
 * off: WorldAPI refuses to hand it out (403 cannot_grant).
 */
export const ScopePicker = ({ value, onChange, groupsOnly, id }: { value: string[]; onChange: (value: string[]) => void; groupsOnly?: boolean; id: string }) => {
  const { t } = useTranslation();
  const me = useMe();
  const groups = useQuery(groupsQuery).data;
  if (!groups || !me) return <Loading rows={3} />;
  const mine = new Set(me.scopes);
  const grantable = (permissions: string[]) => permissions.every((permission) => mine.has(permission));
  const toggle = (name: string, on: boolean) => onChange(on ? [...value, name] : value.filter((item) => item !== name));
  const box = (name: string, label: React.ReactNode, hint: string, allowed: boolean) => (
    <div key={name} className="flex items-start gap-2.5 py-1">
      <Checkbox id={`${id}-${name}`} checked={value.includes(name)} disabled={!allowed} onCheckedChange={(on) => toggle(name, on === true)} className="mt-0.5" />
      <Label htmlFor={`${id}-${name}`} className="flex min-w-0 flex-col items-start gap-0.5 font-normal">
        {label}
        <span className="text-xs text-muted-foreground">{allowed ? hint : t("access.notYours")}</span>
      </Label>
    </div>
  );
  return (
    <div className="flex flex-col gap-3">
      <fieldset className="flex flex-col">
        <legend className="mb-1 text-xs font-medium text-muted-foreground">{t("access.groups")}</legend>
        {Object.entries(groups.resolved).map(([name, permissions]) =>
          box(name, <span className="font-medium">{name}</span>, t("access.groupHint", { count: permissions.length }), grantable(permissions)),
        )}
      </fieldset>
      {!groupsOnly && (
        <fieldset className="flex flex-col">
          <legend className="mb-1 text-xs font-medium text-muted-foreground">{t("access.permissions")}</legend>
          {groups.permissions.map((permission) => box(permission.id, <span className="font-mono text-xs">{permission.id}</span>, permission.description, mine.has(permission.id)))}
        </fieldset>
      )}
    </div>
  );
};
