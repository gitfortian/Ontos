import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * Spike 专用测试配置：与主线 vite.config.ts 分离，不改动任何正式构建配置。
 */
export default defineConfig({
  root: fileURLToPath(new URL(".", import.meta.url)),
  esbuild: { jsx: "automatic" },
  resolve: {
    dedupe: ["react", "react-dom"],
    alias: {
      "@ontos/ui": fileURLToPath(
        new URL("../../../../packages/ontos-ui/src/index.ts", import.meta.url),
      ),
      "@": fileURLToPath(new URL("../../", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    globals: false,
    include: ["**/*.test.tsx"],
    restoreMocks: true,
  },
});
