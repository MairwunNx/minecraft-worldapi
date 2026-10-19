import { AlertTriangle, Check, Copy } from "lucide-react";
import type { ReactNode } from "react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { copy } from "@/lib/copy";
import { errorText } from "@/lib/forms";
import { cn } from "@/lib/utils";
import { type LinkState, useLink } from "@/lib/ws";

export const PageHeader = ({ title, description, actions }: { title: string; description?: ReactNode; actions?: ReactNode }) => (
  <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
    <div className="min-w-0">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
    </div>
    {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
  </div>
);

export const StatTile = ({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) => (
  <Card className="gap-0 py-3">
    <CardContent className="flex min-w-0 flex-col gap-0.5 px-4">
      <span className="truncate text-xs font-medium text-muted-foreground">{label}</span>
      <span className="tabular truncate text-xl font-semibold tracking-tight">{value}</span>
      {hint && <span className="truncate text-xs text-muted-foreground">{hint}</span>}
    </CardContent>
  </Card>
);

export const Loading = ({ rows = 4 }: { rows?: number }) => (
  <div className="flex flex-col gap-2" aria-busy>
    {Array.from({ length: rows }, (_, i) => (
      <Skeleton key={i} className="h-9 w-full" />
    ))}
  </div>
);

export const ErrorBox = ({ error }: { error: unknown }) => (
  <div role="alert" className="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm">
    <AlertTriangle className="mt-0.5 size-4 shrink-0 text-destructive" aria-hidden />
    <span className="min-w-0 break-words">{errorText(error)}</span>
  </div>
);

export const Empty = ({ children }: { children: ReactNode }) => <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">{children}</div>;

/** A button that asks first; for anything that deletes, kicks, bans, or stops. */
export const Confirm = ({
  label,
  title,
  text,
  action,
  onConfirm,
  destructive,
  disabled,
  children,
  size = "sm",
  variant,
}: {
  label: ReactNode;
  title: string;
  text: ReactNode;
  action: string;
  onConfirm: () => Promise<unknown>;
  destructive?: boolean;
  disabled?: boolean;
  /** Extra fields inside the dialog, such as a reason. */
  children?: ReactNode;
  size?: "sm" | "default" | "icon";
  variant?: "outline" | "destructive" | "secondary" | "ghost";
}) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger asChild>
        <Button size={size} variant={variant ?? (destructive ? "destructive" : "outline")} disabled={disabled}>
          {label}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{text}</AlertDialogDescription>
        </AlertDialogHeader>
        {children}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>{t("common.cancel")}</AlertDialogCancel>
          <AlertDialogAction
            disabled={busy}
            className={cn(destructive && "bg-destructive text-white hover:bg-destructive/90")}
            onClick={async (event) => {
              event.preventDefault();
              setBusy(true);
              try {
                await onConfirm();
                setOpen(false);
              } catch {
                // The caller showed the error; the dialog stays open to try again.
              } finally {
                setBusy(false);
              }
            }}
          >
            {action}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};

/** Tables scroll sideways inside their frame on a phone, never the page. */
export const TableFrame = ({ children }: { children: ReactNode }) => <div className="overflow-hidden rounded-lg border bg-card">{children}</div>;

export const Definition = ({ items }: { items: Array<[string, ReactNode]> }) => (
  <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
    {items.map(([label, value]) => (
      <div key={label} className="flex min-w-0 flex-col gap-0.5">
        <dt className="text-xs text-muted-foreground">{label}</dt>
        <dd className="tabular truncate font-medium">{value}</dd>
      </div>
    ))}
  </dl>
);

const LIVE: Record<LinkState, string> = { open: "bg-success", connecting: "bg-warning", closed: "bg-muted-foreground" };

/** Whether the WebSocket is up: a dot and a word, never color alone. */
export const LiveBadge = ({ paused }: { paused?: boolean }) => {
  const { t } = useTranslation();
  const { state } = useLink();
  return (
    <Badge variant="outline" className="gap-1.5 font-normal">
      <span className={cn("size-2 rounded-full", paused ? "bg-warning" : LIVE[state], state === "open" && !paused && "animate-pulse")} aria-hidden />
      {paused ? t("live.paused") : t(`live.${state}`)}
    </Badge>
  );
};

/** A secret shown once: monospace, selectable, with a copy button. */
export const Secret = ({ value }: { value: string }) => {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex items-center gap-2 rounded-md border bg-muted/50 p-2">
      <code className="min-w-0 flex-1 font-mono text-sm break-all select-all">{value}</code>
      <Button
        size="icon"
        variant="ghost"
        aria-label={t("common.copy")}
        onClick={async () => {
          await copy(value);
          setCopied(true);
        }}
      >
        {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
      </Button>
    </div>
  );
};

/** Permissions as small monospace chips. */
export const Chips = ({ items, className }: { items: string[]; className?: string }) => (
  <div className={cn("flex flex-wrap gap-1", className)}>
    {items.map((item) => (
      <Badge key={item} variant="secondary" className="font-mono text-[11px] font-normal">
        {item}
      </Badge>
    ))}
  </div>
);
