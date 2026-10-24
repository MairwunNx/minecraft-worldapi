import { type QueryClient, queryOptions, useQuery } from "@tanstack/react-query";
import { auth, type Me, type Status } from "@/api/client";

export const statusQuery = queryOptions({ queryKey: ["auth", "status"], queryFn: () => auth<Status>("/status"), staleTime: 60_000 });

/** Who's signed in; WorldAPI answers `anonymous` without a session. */
export const meQuery = queryOptions({ queryKey: ["auth", "me"], queryFn: () => auth<Me>("/me").catch((): Me | null => null), staleTime: 60_000 });

export const useMe = () => useQuery(meQuery).data ?? null;

/** Permission ids, as WorldAPI names them: `server:read`, `files:write`, … */
export type Permission =
  | "server:read"
  | "players:read"
  | "players:private"
  | "events:read"
  | "chat:read"
  | "logs:read"
  | "system:read"
  | "world:write"
  | "players:write"
  | "players:moderate"
  | "server:console"
  | "commands:read"
  | "server:control"
  | "files:read"
  | "files:write"
  | "tokens:manage"
  | "code";

/** Whether the signed-in account has every one of these; WorldAPI checks again on each call. */
export const useCan = () => {
  const me = useMe();
  return (...needed: Permission[]) => !!me && needed.every((permission) => me.scopes.includes(permission));
};

/** After signing in or out: everything cached belonged to the previous session. */
export const resetSession = async (client: QueryClient) => {
  client.removeQueries({ predicate: (query) => query.queryKey[0] !== "auth" });
  await client.invalidateQueries({ queryKey: ["auth"] });
};

/** A code from a `#code=XXXX-XXXX` link; the hash never reaches the server or its logs. */
export const codeInHash = () => new URLSearchParams(window.location.hash.replace(/^#/, "")).get("code")?.trim() || undefined;
