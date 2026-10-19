import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
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
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { date, dateTime } from "@/lib/format";
import { showError } from "@/lib/forms";

const NAME = /^[A-Za-z0-9_-]{1,32}$/;

/** Name, permissions, expiry; then the token, once, with a copy button. */
const CreateToken = () => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [scopes, setScopes] = useState<string[]>([]);
  const [days, setDays] = useState("");
  const [created, setCreated] = useState<Schema<"TokenCreated"> | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reset = () => {
    setName("");
    setScopes([]);
    setDays("");
    setCreated(null);
    setError(null);
  };
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!NAME.test(name.trim())) return setError(t("tokens.nameRule"));
    if (!scopes.length) return setError(t("tokens.pickScopes"));
    setBusy(true);
    try {
      const expiresInDays = Number.parseInt(days, 10) || undefined;
      setCreated(await data(client.POST("/tokens", { body: { name: name.trim(), scopes, expiresInDays } })));
      await queryClient.invalidateQueries({ queryKey: ["tokens"] });
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
      <Button onClick={() => setOpen(true)}>
        <Plus className="size-4" aria-hidden />
        {t("tokens.create")}
      </Button>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        {created ? (
          <>
            <DialogHeader>
              <DialogTitle>{t("tokens.createdTitle", { name: created.name })}</DialogTitle>
              <DialogDescription>{t("tokens.createdText")}</DialogDescription>
            </DialogHeader>
            <Secret value={created.token} />
            <Chips items={created.scopes} />
            <DialogFooter>
              <Button onClick={() => setOpen(false)}>{t("common.done")}</Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={submit} className="flex flex-col gap-4">
            <DialogHeader>
              <DialogTitle>{t("tokens.createTitle")}</DialogTitle>
              <DialogDescription>{t("tokens.createText")}</DialogDescription>
            </DialogHeader>
            <FormField id="token-name" label={t("tokens.name")} hint={t("tokens.nameRule")}>
              <Input id="token-name" value={name} onChange={(event) => setName(event.target.value)} autoComplete="off" spellCheck={false} />
            </FormField>
            <FormField id="token-days" label={t("tokens.expires")} hint={t("tokens.expiresHint")}>
              <Input id="token-days" type="number" min={0} inputMode="numeric" value={days} onChange={(event) => setDays(event.target.value)} className="w-32" />
            </FormField>
            <ScopePicker id="token" value={scopes} onChange={setScopes} />
            {error && (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            )}
            <DialogFooter>
              <Button type="submit" disabled={busy}>
                {t("tokens.create")}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
};

export const TokensPage = () => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const tokens = useQuery({ queryKey: ["tokens"], queryFn: () => data(client.GET("/tokens")) });
  const revoke = async (name: string) => {
    try {
      await data(client.DELETE("/tokens/{name}", { params: { path: { name } } }));
      toast.success(t("tokens.revoked", { name }));
      await queryClient.invalidateQueries({ queryKey: ["tokens"] });
    } catch (error) {
      showError(error);
      throw error;
    }
  };
  return (
    <div>
      <PageHeader title={t("tokens.title")} description={t("tokens.text")} actions={<CreateToken />} />
      {tokens.error ? (
        <ErrorBox error={tokens.error} />
      ) : !tokens.data ? (
        <Loading rows={4} />
      ) : !tokens.data.length ? (
        <Empty>{t("tokens.none")}</Empty>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("tokens.name")}</TableHead>
                <TableHead>{t("tokens.scopes")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("tokens.created")}</TableHead>
                <TableHead className="hidden sm:table-cell">{t("tokens.expiresColumn")}</TableHead>
                <TableHead className="w-px" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {tokens.data.map((token) => (
                <TableRow key={token.name}>
                  <TableCell className="align-top">
                    <div className="font-medium">{token.name}</div>
                    <div className="flex gap-1 pt-1">
                      <Badge variant="outline" className="font-normal">
                        {t(`tokens.sources.${token.source}`, { defaultValue: token.source })}
                      </Badge>
                      {token.expired && <Badge variant="destructive">{t("tokens.expired")}</Badge>}
                    </div>
                  </TableCell>
                  <TableCell className="max-w-72 align-top whitespace-normal">
                    <Chips items={token.scopes} />
                  </TableCell>
                  <TableCell className="hidden align-top text-xs md:table-cell" title={dateTime(token.createdAt)}>
                    {date(token.createdAt)}
                    {token.createdBy && <div className="text-muted-foreground">{token.createdBy}</div>}
                  </TableCell>
                  <TableCell className="hidden align-top text-xs sm:table-cell">{token.expiresAt ? date(token.expiresAt) : t("tokens.never")}</TableCell>
                  <TableCell className="align-top">
                    {token.source === "issued" && (
                      <Confirm
                        destructive
                        size="icon"
                        variant="ghost"
                        label={<Trash2 className="size-4" aria-label={t("tokens.revoke")} />}
                        title={t("tokens.revokeTitle", { name: token.name })}
                        text={t("tokens.revokeText")}
                        action={t("tokens.revoke")}
                        onConfirm={() => revoke(token.name)}
                      />
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
      <Alert className="mt-4">
        <AlertDescription>{t("tokens.configNote")}</AlertDescription>
      </Alert>
    </div>
  );
};
