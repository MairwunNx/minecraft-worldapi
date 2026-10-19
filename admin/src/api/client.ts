import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./generated/schema";

/**
 * The panel's address and its API's. WorldAPI puts a <base> at <prefix>/admin/ and names the API in a
 * meta tag, so the page never hard-codes a path and works behind any proxy prefix. The Vite dev server
 * has neither: the panel sits at the root and the API at /api/v1.
 */
const basePath = new URL(document.baseURI).pathname.replace(/\/+$/, "");

export const ROUTER_BASE = basePath || "/";
export const API_BASE = document.querySelector<HTMLMetaElement>('meta[name="worldapi-api"]')?.content.replace(/\/+$/, "") || "/api/v1";

/** The WebSocket's address on this page's host, as ws: or wss:. */
export const wsUrl = () => {
  const url = new URL(`${API_BASE}/ws`, location.href);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
};

export type Schema<K extends keyof components["schemas"]> = components["schemas"][K];

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** WorldAPI's errors look the same everywhere: {"error":{"code","message"}}. */
const toError = async (response: Response) => {
  const data = (await response.json().catch(() => null)) as { error?: { code: string; message: string } } | null;
  return new ApiError(response.status, data?.error?.code ?? "http_error", data?.error?.message ?? response.statusText);
};

/** The session cookie rides along by itself; writes also prove they came from the panel, or WorldAPI answers 403 csrf. */
const panel: Middleware = {
  onRequest({ request }) {
    if (request.method !== "GET" && request.method !== "HEAD") request.headers.set("X-WorldAPI-Panel", "1");
    return request;
  },
  async onResponse({ response }) {
    if (!response.ok) throw await toError(response);
    return response;
  },
};

/** Typed from docs/openapi.json: a changed endpoint fails the typecheck. */
export const client = createClient<paths>({ baseUrl: API_BASE, credentials: "same-origin" });
client.use(panel);

/** Unwraps openapi-fetch's answer; errors already threw in the middleware. */
export const data = async <T>(promise: Promise<{ data?: T }>): Promise<T> => (await promise).data as T;

/** The sign-in endpoints, which live outside the OpenAPI document. */
export const auth = async <T>(path: string, body?: unknown): Promise<T> => {
  const response = await fetch(`${API_BASE}/auth${path}`, {
    method: body === undefined ? "GET" : "POST",
    credentials: "same-origin",
    headers: body === undefined ? {} : { "Content-Type": "application/json", "X-WorldAPI-Panel": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw await toError(response);
  return (await response.json()) as T;
};

/** An item's 16×16 picture, from the game's own textures. */
export const iconUrl = (key: string, retry = 0) => `${API_BASE}/icons/${encodeURIComponent(key.includes(":") ? key : `minecraft:${key}`).replace("%3A", ":")}${retry ? `?r=${retry}` : ""}`;

export interface Me {
  name: string;
  kind: "account" | "token" | "anonymous";
  scopes: string[];
  groups: string[];
  player?: string | null;
}

export interface Status {
  accounts: boolean;
  version: string;
  /** False when WorldAPI saw this request come from the internet without HTTPS. */
  secure?: boolean;
  insecureReason?: string | null;
}
