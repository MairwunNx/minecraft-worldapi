import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, Outlet, useNavigate } from "@tanstack/react-router";
import {
  Activity,
  FileClock,
  FolderTree,
  Globe,
  KeyRound,
  LogOut,
  Menu,
  MessagesSquare,
  Monitor,
  Moon,
  Puzzle,
  Shield,
  SquareTerminal,
  Sun,
  UserCog,
  UserRound,
  Users,
} from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { InsecureBanner } from "./insecure-banner";
import { auth } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Sheet, SheetContent, SheetTitle, SheetTrigger } from "@/components/ui/sheet";
import { resetPerformance } from "@/lib/performance";
import { type Permission, resetSession, statusQuery, useCan, useMe } from "@/lib/session";
import { type Theme, useTheme } from "@/lib/theme";
import { link } from "@/lib/ws";
import { Logo } from "./logo";

interface Item {
  to: string;
  label: string;
  icon: typeof Activity;
  /** Any one of these shows the page. */
  needs: Permission[];
}

export const SECTIONS: Array<{ title: string; items: Item[] }> = [
  {
    title: "nav.server",
    items: [
      { to: "/", label: "nav.overview", icon: Activity, needs: ["server:read"] },
      { to: "/players", label: "nav.players", icon: Users, needs: ["players:read"] },
      { to: "/worlds", label: "nav.worlds", icon: Globe, needs: ["server:read"] },
      { to: "/console", label: "nav.console", icon: SquareTerminal, needs: ["logs:read", "server:console"] },
      { to: "/chat", label: "nav.chat", icon: MessagesSquare, needs: ["chat:read"] },
    ],
  },
  {
    title: "nav.setup",
    items: [
      { to: "/files", label: "nav.files", icon: FolderTree, needs: ["files:read", "logs:read"] },
      { to: "/plugins", label: "nav.plugins", icon: Puzzle, needs: ["server:read"] },
    ],
  },
  {
    title: "nav.access",
    items: [
      { to: "/access/tokens", label: "nav.tokens", icon: KeyRound, needs: ["tokens:manage"] },
      { to: "/access/accounts", label: "nav.accounts", icon: UserCog, needs: ["tokens:manage"] },
      { to: "/access/groups", label: "nav.groups", icon: Shield, needs: ["tokens:manage"] },
      { to: "/access/audit", label: "nav.audit", icon: FileClock, needs: ["tokens:manage"] },
    ],
  },
];

const Nav = ({ onPick }: { onPick?: () => void }) => {
  const { t } = useTranslation();
  const can = useCan();
  return (
    <nav className="flex flex-col gap-5 text-sm">
      {SECTIONS.map((section) => {
        const items = section.items.filter((item) => item.needs.some((permission) => can(permission)));
        if (!items.length) return null;
        return (
          <div key={section.title} className="flex flex-col gap-0.5">
            <div className="px-3 pb-1 text-xs font-medium text-muted-foreground">{t(section.title)}</div>
            {items.map((item) => (
              <Link
                key={item.to}
                to={item.to}
                onClick={onPick}
                activeOptions={{ exact: item.to === "/", includeSearch: false }}
                className="flex items-center gap-2.5 rounded-md px-3 py-1.5 text-foreground/80 transition-colors hover:bg-accent hover:text-accent-foreground data-[status=active]:bg-accent data-[status=active]:font-medium data-[status=active]:text-accent-foreground"
              >
                <item.icon className="size-4 shrink-0" aria-hidden />
                {t(item.label)}
              </Link>
            ))}
          </div>
        );
      })}
    </nav>
  );
};

const THEMES: Array<{ value: Theme; icon: typeof Sun }> = [
  { value: "light", icon: Sun },
  { value: "dark", icon: Moon },
  { value: "system", icon: Monitor },
];

const UserMenu = ({ onPick }: { onPick?: () => void }) => {
  const { t } = useTranslation();
  const me = useMe();
  const { theme, setTheme } = useTheme();
  const client = useQueryClient();
  const navigate = useNavigate();
  const signOut = async () => {
    await auth("/logout", {}).catch(() => undefined);
    link.close();
    resetPerformance();
    await resetSession(client);
    await navigate({ to: "/login" });
  };
  if (!me) return null;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="h-auto w-full justify-start gap-2.5 px-3 py-2">
          <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary uppercase">{me.name.slice(0, 2)}</span>
          <span className="flex min-w-0 flex-col items-start text-left">
            <span className="max-w-full truncate text-sm font-medium">{me.name}</span>
            <span className="max-w-full truncate text-xs text-muted-foreground">{me.groups.join(", ") || t("menu.noGroups")}</span>
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-56">
        <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">{t("menu.theme")}</DropdownMenuLabel>
        <div className="flex gap-1 px-2 pb-2">
          {THEMES.map(({ value, icon: Icon }) => (
            <Button key={value} size="sm" variant={theme === value ? "secondary" : "ghost"} className="flex-1" onClick={() => setTheme(value)} aria-pressed={theme === value} aria-label={t(`menu.themes.${value}`)}>
              <Icon className="size-4" aria-hidden />
            </Button>
          ))}
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild onSelect={onPick}>
          <Link to="/account">
            <UserRound className="size-4" aria-hidden />
            {t("menu.account")}
          </Link>
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={signOut}>
          <LogOut className="size-4" aria-hidden />
          {t("menu.signOut")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

/** The signed-in frame: a sidebar on wide screens, a menu sheet on phones. */
export const Layout = () => {
  const { t } = useTranslation();
  const status = useQuery(statusQuery).data;
  const [open, setOpen] = useState(false);
  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[14rem_minmax(0,1fr)]">
      <aside className="sticky top-0 hidden h-dvh flex-col gap-6 border-r bg-sidebar px-3 py-5 lg:flex">
        <Link to="/" className="px-3">
          <Logo />
        </Link>
        <div className="flex-1 overflow-y-auto">
          <Nav />
        </div>
        <div className="flex flex-col gap-1">
          <UserMenu />
          <div className="px-3 text-xs text-muted-foreground">{t("layout.version", { version: status?.version ?? "" })}</div>
        </div>
      </aside>
      <header className="sticky top-0 z-30 flex h-14 items-center justify-between border-b bg-background/90 px-4 backdrop-blur lg:hidden">
        <Link to="/">
          <Logo />
        </Link>
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger asChild>
            <Button variant="ghost" size="icon" aria-label={t("layout.menu")}>
              <Menu className="size-5" />
            </Button>
          </SheetTrigger>
          <SheetContent side="left" className="flex w-72 flex-col gap-6 bg-sidebar px-3 py-5">
            <SheetTitle className="px-3">
              <Logo />
            </SheetTitle>
            <div className="flex-1 overflow-y-auto">
              <Nav onPick={() => setOpen(false)} />
            </div>
            <UserMenu onPick={() => setOpen(false)} />
          </SheetContent>
        </Sheet>
      </header>
      <main className="mx-auto w-full max-w-7xl min-w-0 px-4 py-5 sm:px-6 lg:py-7">
        <InsecureBanner className="mb-5" />
        <Outlet />
      </main>
    </div>
  );
};
