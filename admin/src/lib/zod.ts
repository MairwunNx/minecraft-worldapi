import { z } from "zod";

// Zod probes `new Function` to speed up parsing; the panel's CSP forbids it and reports even the caught probe.
z.config({ jitless: true });
