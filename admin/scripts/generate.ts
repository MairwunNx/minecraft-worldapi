// Types for every endpoint from docs/openapi.json, so a changed endpoint fails the panel's typecheck.
// openapi-typescript needs TypeScript 5's compiler API, gone in TypeScript 7, so it lives in its own
// folder with its own lockfile and TypeScript.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const here = fileURLToPath(new URL(".", import.meta.url));
const run = (args: string[], cwd: string) => {
  const result = spawnSync(process.execPath, args, { cwd, stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
};

run(["install", "--frozen-lockfile", "--silent"], `${here}openapi`);
run([`${here}openapi/node_modules/openapi-typescript/bin/cli.js`, "../docs/openapi.json", "-o", "src/api/generated/schema.d.ts", "--silent"], `${here}..`);
