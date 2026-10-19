import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { Gamepad2 } from "lucide-react";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { z } from "zod";
import { ApiError, auth, type Me } from "@/api/client";
import { AuthFrame } from "@/components/auth-frame";
import { FormField } from "@/components/form-field";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { showError } from "@/lib/forms";
import { meQuery, resetSession, statusQuery } from "@/lib/session";

const MIN_PASSWORD = 10;

/** Back to where the visitor was going, but only inside the panel. */
const useFinish = () => {
  const client = useQueryClient();
  const navigate = useNavigate();
  const { next } = useSearch({ from: "/login" });
  return async (me: Me) => {
    await resetSession(client);
    client.setQueryData(meQuery.queryKey, me);
    if (next?.startsWith("/") && !next.startsWith("//")) await navigate({ href: next });
    else await navigate({ to: "/" });
  };
};

const PasswordForm = () => {
  const { t } = useTranslation();
  const finish = useFinish();
  const schema = z.object({ name: z.string().trim().min(1, t("login.errors.name")), password: z.string().min(1, t("login.errors.password")) });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { name: "", password: "" } });
  const submit = form.handleSubmit(async (values) => {
    try {
      await finish(await auth<Me>("/login", values));
    } catch (error) {
      if (error instanceof ApiError && error.code === "invalid_login") form.setError("password", { message: t("login.errors.wrong") });
      else showError(error);
    }
  });
  const { errors, isSubmitting } = form.formState;
  return (
    <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
      <FormField id="login-name" label={t("login.name")} error={errors.name?.message}>
        <Input id="login-name" autoComplete="username" autoFocus aria-invalid={!!errors.name} {...form.register("name")} />
      </FormField>
      <FormField id="login-password" label={t("login.password")} error={errors.password?.message}>
        <Input id="login-password" type="password" autoComplete="current-password" aria-invalid={!!errors.password} {...form.register("password")} />
      </FormField>
      <Button type="submit" disabled={isSubmitting}>
        {t("login.signIn")}
      </Button>
    </form>
  );
};

const CodeForm = () => {
  const { t } = useTranslation();
  const finish = useFinish();
  const schema = z
    .object({
      code: z.string().trim().min(1, t("login.errors.code")),
      name: z.string().trim(),
      password: z.string().min(MIN_PASSWORD, t("login.errors.short", { count: MIN_PASSWORD })),
      repeat: z.string(),
    })
    .refine((values) => values.password === values.repeat, { path: ["repeat"], message: t("login.errors.mismatch") });
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { code: "", name: "", password: "", repeat: "" } });
  const submit = form.handleSubmit(async ({ code, name, password }) => {
    try {
      await finish(await auth<Me>("/setup", { code, name: name || undefined, password }));
    } catch (error) {
      if (!(error instanceof ApiError)) return showError(error);
      if (error.code === "invalid_code") form.setError("code", { message: error.message });
      else if (["name_required", "invalid_name", "account_exists"].includes(error.code)) form.setError("name", { message: error.message });
      else if (error.code === "weak_password") form.setError("password", { message: error.message });
      else showError(error);
    }
  });
  const { errors, isSubmitting } = form.formState;
  return (
    <form onSubmit={submit} className="flex flex-col gap-4" noValidate>
      <FormField id="code" label={t("login.code")} error={errors.code?.message}>
        <Input id="code" autoComplete="one-time-code" spellCheck={false} className="font-mono" aria-invalid={!!errors.code} {...form.register("code")} />
      </FormField>
      <FormField id="code-name" label={t("login.newName")} error={errors.name?.message} hint={t("login.newNameHint")}>
        <Input id="code-name" autoComplete="username" aria-invalid={!!errors.name} {...form.register("name")} />
      </FormField>
      <FormField id="code-password" label={t("login.newPassword")} error={errors.password?.message} hint={t("login.passwordHint", { count: MIN_PASSWORD })}>
        <Input id="code-password" type="password" autoComplete="new-password" aria-invalid={!!errors.password} {...form.register("password")} />
      </FormField>
      <FormField id="code-repeat" label={t("login.repeat")} error={errors.repeat?.message}>
        <Input id="code-repeat" type="password" autoComplete="new-password" aria-invalid={!!errors.repeat} {...form.register("repeat")} />
      </FormField>
      <Button type="submit" disabled={isSubmitting}>
        {t("login.useCode")}
      </Button>
    </form>
  );
};

export const LoginPage = () => {
  const { t } = useTranslation();
  const status = useQuery(statusQuery).data;
  const first = status?.accounts === false;
  return (
    <AuthFrame title={first ? t("login.firstTitle") : t("login.title")} description={first ? t("login.firstText") : t("login.text")}>
      {first && (
        <Alert className="mb-4">
          <Gamepad2 className="size-4" aria-hidden />
          <AlertDescription>
            <span>
              {t("login.hintBefore")} <code className="font-mono text-foreground">/worldapi admin</code>
            </span>
          </AlertDescription>
        </Alert>
      )}
      <Tabs key={first ? "code" : "password"} defaultValue={first ? "code" : "password"}>
        <TabsList className="w-full">
          <TabsTrigger value="password">{t("login.tabPassword")}</TabsTrigger>
          <TabsTrigger value="code">{t("login.tabCode")}</TabsTrigger>
        </TabsList>
        <TabsContent value="password" className="pt-3">
          <PasswordForm />
        </TabsContent>
        <TabsContent value="code" className="pt-3">
          <CodeForm />
        </TabsContent>
      </Tabs>
    </AuthFrame>
  );
};
