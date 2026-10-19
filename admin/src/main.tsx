import "./lib/zod";
import "./index.css";
import "./i18n";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { ApiError } from "@/api/client";
import { resetPerformance } from "@/lib/performance";
import { meQuery } from "@/lib/session";
import { ThemeProvider } from "@/lib/theme";
import { link } from "@/lib/ws";
import { router } from "./router";

/** A session that ran out (7 days without a visit, a new password elsewhere) sends the page back to sign-in. */
const expired = (error: unknown) => {
  if (!(error instanceof ApiError) || error.status !== 401) return;
  link.close();
  resetPerformance();
  queryClient.setQueryData(meQuery.queryKey, null);
  if (!router.state.location.pathname.endsWith("/login")) void router.navigate({ to: "/login", search: { next: router.state.location.href } });
};

const queryClient: QueryClient = new QueryClient({
  queryCache: new QueryCache({ onError: expired }),
  mutationCache: new MutationCache({ onError: expired }),
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      refetchOnWindowFocus: true,
      // A signed-out or forbidden answer won't change by asking again.
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <TooltipProvider delayDuration={200}>
          <RouterProvider router={router} context={{ queryClient }} />
          <Toaster richColors position="top-right" />
        </TooltipProvider>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
