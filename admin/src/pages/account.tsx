import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { z } from "zod";
import { ApiError, auth, type Me } from "@/api/client";
import { Chips, Definition, PageHeader } from "@/components/common";
import { FormField } from "@/components/form-field";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { showError } from "@/lib/forms";
import { meQuery, useMe } from "@/lib/session";

const MIN_PASSWORD = 10;

export const AccountPage = () => {
  const { t } = useTranslation();
  const me = useMe();
  const client = useQueryClient();
  const schema = z
    .object({
      current: z.string().min(1, t("login.errors.password")),
      password: z.string().min(MIN_PASSWORD, t("login.errors.short", { count: MIN_PASSWORD })),
      repeat: z.string(),
    })
    .refine((values) => values.password === values.repeat, { path: ["repeat"], message: t("login.errors.mismatch") });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { current: "", password: "", repeat: "" } });
  const submit = form.handleSubmit(async ({ current, password }) => {
    try {
      const fresh = await auth<Me>("/password", { current, password });
      client.setQueryData(meQuery.queryKey, (old: Me | null | undefined) => ({ ...old, ...fresh }));
      form.reset();
      toast.success(t("account.changed"));
    } catch (error) {
      if (error instanceof ApiError && error.code === "invalid_login") form.setError("current", { message: error.message });
      else if (error instanceof ApiError && error.code === "weak_password") form.setError("password", { message: error.message });
      else showError(error);
    }
  });
  const { errors, isSubmitting } = form.formState;
  if (!me) return null;
  return (
    <div>
      <PageHeader title={t("account.title")} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{t("account.who")}</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <Definition
              items={[
                [t("account.name"), me.name],
                [t("account.player"), me.player ?? "—"],
                [t("account.groups"), me.groups.join(", ") || "—"],
              ]}
            />
            <div className="flex flex-col gap-1.5">
              <span className="text-xs text-muted-foreground">{t("account.permissions")}</span>
              <Chips items={me.scopes} />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("account.password")}</CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
              <input type="text" autoComplete="username" value={me.name} readOnly hidden />
              <FormField id="current" label={t("account.current")} error={errors.current?.message}>
                <Input id="current" type="password" autoComplete="current-password" {...form.register("current")} />
              </FormField>
              <FormField id="new" label={t("login.newPassword")} error={errors.password?.message} hint={t("account.passwordHint", { count: MIN_PASSWORD })}>
                <Input id="new" type="password" autoComplete="new-password" {...form.register("password")} />
              </FormField>
              <FormField id="repeat" label={t("login.repeat")} error={errors.repeat?.message}>
                <Input id="repeat" type="password" autoComplete="new-password" {...form.register("repeat")} />
              </FormField>
              <Button type="submit" className="self-start" disabled={isSubmitting}>
                {t("account.change")}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};
