import { fileURLToPath } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Relative asset paths: WorldAPI serves the panel under /admin behind any prefix and adds a <base>.
// Gradle builds into build/admin-dist and puts it in the jar under admin/.
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  // No data: URIs for fonts: the CSP allows fonts from the panel itself only.
  build: {
    outDir: process.env.WORLDAPI_PANEL_OUT ?? "../build/admin-dist",
    emptyOutDir: true,
    chunkSizeWarningLimit: 1600,
    assetsInlineLimit: 0,
  },
  server: {
    // `bun run dev` here, a server with WorldAPI on its default port 8110: the dev server passes the API and the WebSocket through.
    proxy: { "/api": { target: "http://localhost:8110", ws: true } },
  },
});
