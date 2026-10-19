import { useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Trash2, UserPlus } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ApiError, client, data, type Schema } from "@/api/client";
import { Confirm, Empty, ErrorBox, Loading, PageHeader, Secret, TableFrame } from "@/components/common";
import { FormField } from "@/components/form-field";
import { ScopePicker } from "@/components/scope-picker";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { dateTime, relative, time } from "@/lib/format";
import { showError } from "@/lib/forms";
import { useMe } from "@/lib/session";

/** A code for someone else: they enter it on the sign-in page with a password within 30 minutes. */
const Invite = () => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [groups, setGroups] = useState<string[]>([]);
  const [name, setName] = useState("");
  const [code, setCode] = useState<Schema<"InviteCode"> | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      setCode(await data(client.POST("/accounts/invite", { body: { groups, name: name.trim() || undefined } })));
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          setCode(null);
          setGroups([]);
          setName("");
        }
      }}
    >
      <Button onClick={() => setOpen(true)}>
        <UserPlus className="size-4" aria-hidden />
        {t("accounts.invite")}
      </Button>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        {code ? (
          <>
            <DialogHeader>
              <DialogTitle>{t("accounts.codeTitle")}</DialogTitle>
              <DialogDescription>{t("accounts.codeText", { time: time(code.expiresAt) })}</DialogDescription>
            </DialogHeader>
            <Secret value={code.code} />
            <DialogFooter>
              <Button onClick={() => setOpen(false)}>{t("common.done")}</Button>
            </DialogFooter>
          </>
        ) : (
          <form onSubmit={submit} className="flex flex-col gap-4">
            <DialogHeader>
              <DialogTitle>{t("accounts.inviteTitle")}</DialogTitle>
              <DialogDescription>{t("accounts.inviteText")}</DialogDescription>
            </DialogHeader>
            <FormField id="invite-name" label={t("accounts.name")} hint={t("accounts.nameHint")}>
              <Input id="invite-name" value={name} onChange={(event) => setName(event.target.value)} autoComplete="off" />
            </FormField>
            <ScopePicker id="invite" value={groups} onChange={setGroups} groupsOnly />
            <DialogFooter>
              <Button type="submit" disabled={busy || !groups.length}>
                {t("accounts.makeCode")}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
};

const EditGroups = ({ account }: { account: Schema<"AccountView"> }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [groups, setGroups] = useState(account.groups);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await data(client.PATCH("/accounts/{name}", { params: { path: { name: account.name } }, body: { groups } }));
      toast.success(t("accounts.groupsSaved", { name: account.name }));
      await queryClient.invalidateQueries({ queryKey: ["accounts"] });
      setOpen(false);
    } catch (error) {
      showError(error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) setGroups(account.groups);
      }}
    >
      <Button size="icon" variant="ghost" onClick={() => setOpen(true)} aria-label={t("accounts.editGroups", { name: account.name })}>
        <Pencil className="size-4" />
      </Button>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("accounts.editGroups", { name: account.name })}</DialogTitle>
          <DialogDescription>{t("accounts.editText")}</DialogDescription>
        </DialogHeader>
        <ScopePicker id={`edit-${account.name}`} value={groups} onChange={setGroups} groupsOnly />
        <DialogFooter>
          <Button onClick={save} disabled={busy}>
            {t("common.save")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

/**
 * A one-time code that sets a new password for someone else: shown once, with a link that opens the
 * sign-in page with the code filled in. Using it ends their sessions; it expires in 30 minutes.
 */
const ResetPassword = ({ name }: { name: string }) => {
  const { t } = useTranslation();
  const [code, setCode] = useState<Schema<"InviteCode"> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const reset = async () => {
    setBusy(true);
    try {
      setCode(await data(client.POST("/accounts/{name}/reset", { params: { path: { name } } })));
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === "cannot_grant") setError(failure.message);
      else showError(failure);
    } finally {
      setBusy(false);
    }
  };
  const link = code ? new URL(`./?code=${encodeURIComponent(code.code)}`, document.baseURI).toString() : "";
  return (
    <>
      <Confirm
        size="icon"
        variant="ghost"
        disabled={busy}
        label={<KeyRound className="size-4" aria-label={t("accounts.reset")} />}
        title={t("accounts.resetTitle", { name })}
        text={t("accounts.resetText")}
        action={t("accounts.reset")}
        onConfirm={reset}
      />
      <Dialog open={!!code || !!error} onOpenChange={(open) => !open && (setCode(null), setError(null))}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{t("accounts.resetDoneTitle", { name })}</DialogTitle>
            <DialogDescription>{error ? "" : t("accounts.resetDoneText", { time: code ? time(code.expiresAt) : "" })}</DialogDescription>
          </DialogHeader>
          {error ? (
            <p className="text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : (
            code && (
              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-1">
                  <span className="text-xs text-muted-foreground">{t("accounts.code")}</span>
                  <Secret value={code.code} />
                </div>
                <div className="flex flex-col gap-1">
                  <span className="text-xs text-muted-foreground">{t("accounts.link")}</span>
                  <Secret value={link} />
                </div>
              </div>
            )
          )}
          <DialogFooter>
            <Button onClick={() => (setCode(null), setError(null))}>{t("common.done")}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
};

export const AccountsPage = () => {
  const { t } = useTranslation();
  const me = useMe();
  const queryClient = useQueryClient();
  const accounts = useQuery({ queryKey: ["accounts"], queryFn: () => data(client.GET("/accounts")) });
  const remove = async (name: string) => {
    try {
      await data(client.DELETE("/accounts/{name}", { params: { path: { name } } }));
      toast.success(t("accounts.deleted", { name }));
      await queryClient.invalidateQueries({ queryKey: ["accounts"] });
    } catch (error) {
      showError(error);
      throw error;
    }
  };
  return (
    <div>
      <PageHeader title={t("accounts.title")} description={t("accounts.text")} actions={<Invite />} />
      {accounts.error ? (
        <ErrorBox error={accounts.error} />
      ) : !accounts.data ? (
        <Loading rows={3} />
      ) : !accounts.data.length ? (
        <Empty>{t("accounts.none")}</Empty>
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("accounts.name")}</TableHead>
                <TableHead>{t("accounts.groups")}</TableHead>
                <TableHead className="hidden md:table-cell">{t("accounts.lastLogin")}</TableHead>
                <TableHead className="hidden lg:table-cell">{t("accounts.created")}</TableHead>
                <TableHead className="w-px" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {accounts.data.map((account) => (
                <TableRow key={account.name}>
                  <TableCell>
                    <div className="font-medium">{account.name}</div>
                    {account.player && <div className="text-xs text-muted-foreground">{t("accounts.player", { player: account.player })}</div>}
                  </TableCell>
                  <TableCell className="text-sm">{account.groups.join(", ") || "—"}</TableCell>
                  <TableCell className="hidden text-sm md:table-cell" title={dateTime(account.lastLoginAt)}>
                    {account.lastLoginAt ? relative(account.lastLoginAt) : t("accounts.never")}
                  </TableCell>
                  <TableCell className="hidden text-xs lg:table-cell">
                    {dateTime(account.createdAt)}
                    <div className="text-muted-foreground">{account.createdBy}</div>
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <EditGroups account={account} />
                      {account.name !== me?.name && <ResetPassword name={account.name} />}
                      {account.name !== me?.name && (
                        <Confirm
                          destructive
                          size="icon"
                          variant="ghost"
                          label={<Trash2 className="size-4" aria-label={t("accounts.delete")} />}
                          title={t("accounts.deleteTitle", { name: account.name })}
                          text={t("accounts.deleteText")}
                          action={t("accounts.delete")}
                          onConfirm={() => remove(account.name)}
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
    </div>
  );
};
