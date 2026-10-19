import type { QueryClient } from "@tanstack/react-query";
import { Outlet, createRootRouteWithContext, createRoute, createRouter, redirect } from "@tanstack/react-router";
import { z } from "zod";
import { ROUTER_BASE } from "@/api/client";
import { Layout } from "@/components/layout";
import { NotFound } from "@/components/not-found";
import { codeInHash, meQuery } from "@/lib/session";
import { AccountPage } from "@/pages/account";
import { AccountsPage } from "@/pages/accounts";
import { AuditPage } from "@/pages/audit";
import { ChatPage } from "@/pages/chat";
import { ConsolePage } from "@/pages/console";
import { FilesPage } from "@/pages/files";
import { GroupsPage } from "@/pages/groups";
import { LoginPage } from "@/pages/login";
import { OverviewPage } from "@/pages/overview";
import { PlayerPage } from "@/pages/player";
import { PlayersPage } from "@/pages/players";
import { PluginsPage } from "@/pages/plugins";
import { WorldPage } from "@/pages/world";
import { WorldsPage } from "@/pages/worlds";
import { TokensPage } from "@/pages/tokens";

interface Context {
  queryClient: QueryClient;
}

const root = createRootRouteWithContext<Context>()({ component: Outlet, notFoundComponent: NotFound });

const login = createRoute({
  getParentRoute: () => root,
  path: "/login",
  validateSearch: z.object({ next: z.string().optional().catch(undefined), code: z.string().optional().catch(undefined) }),
  beforeLoad: async ({ context, search }) => {
    const me = await context.queryClient.ensureQueryData(meQuery);
    // A code link still opens the form when someone is signed in: it may reset a password or make another account.
    if (me?.kind === "account" && !search.code && !codeInHash()) throw redirect({ to: "/" });
  },
  component: LoginPage,
});

/** Every page behind sign-in: the sign-in form when nobody's signed in. */
const app = createRoute({
  getParentRoute: () => root,
  id: "app",
  beforeLoad: async ({ context, location }) => {
    // The link /worldapi admin sends in game: /admin/?code=XXXX-XXXX, or #code=XXXX-XXXX.
    const code = (location.search as { code?: unknown }).code;
    if (typeof code === "string" && code) throw redirect({ to: "/login", search: { code } });
    if (codeInHash()) throw redirect({ to: "/login", hash: location.hash });
    const me = await context.queryClient.ensureQueryData(meQuery);
    if (me?.kind !== "account") throw redirect({ to: "/login", search: { next: location.href } });
  },
  component: Layout,
});

const page = <P extends string>(path: P, component: () => React.ReactNode) => createRoute({ getParentRoute: () => app, path, component });

const overview = page("/", OverviewPage);
const players = createRoute({
  getParentRoute: () => app,
  path: "/players",
  validateSearch: z.object({
    sort: z.enum(["name", "last_seen", "playtime"]).optional().catch(undefined),
    online: z.enum(["online", "offline"]).optional().catch(undefined),
  }),
  component: PlayersPage,
});
const player = createRoute({
  getParentRoute: () => app,
  path: "/players/$player",
  validateSearch: z.object({ tab: z.string().optional().catch(undefined) }),
  component: PlayerPage,
});
const worlds = page("/worlds", WorldsPage);
const world = page("/worlds/$world", WorldPage);
const consolePage = page("/console", ConsolePage);
const chat = page("/chat", ChatPage);
const files = createRoute({
  getParentRoute: () => app,
  path: "/files",
  validateSearch: z.object({ file: z.string().optional().catch(undefined) }),
  component: FilesPage,
});
const plugins = page("/plugins", PluginsPage);
const tokens = page("/access/tokens", TokensPage);
const accounts = page("/access/accounts", AccountsPage);
const groups = page("/access/groups", GroupsPage);
const audit = createRoute({
  getParentRoute: () => app,
  path: "/access/audit",
  validateSearch: z.object({ actor: z.string().optional().catch(undefined) }),
  component: AuditPage,
});
const account = page("/account", AccountPage);

const tree = root.addChildren([login, app.addChildren([overview, players, player, worlds, world, consolePage, chat, files, plugins, tokens, accounts, groups, audit, account])]);

export const router = createRouter({
  routeTree: tree,
  basepath: ROUTER_BASE,
  context: { queryClient: undefined! },
  defaultPreload: "intent",
  scrollRestoration: true,
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
