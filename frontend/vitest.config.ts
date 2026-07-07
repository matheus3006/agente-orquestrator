import { defineConfig } from "vitest/config";
import path from "node:path";

// Piso da F.1+ (handoff frontend, Q10): Vitest cobre SÓ os helpers puros de
// src/lib (fetcher, queryKeys, merges, formatadores). Telas = verificação viva.
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
