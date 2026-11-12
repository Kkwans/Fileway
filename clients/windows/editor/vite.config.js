import { defineConfig } from "vite";
import { fileURLToPath } from "node:url";

const editorEntries = [
  "@milkdown/crepe/builder",
  "@milkdown/crepe/feature/code-mirror",
  "@milkdown/crepe/feature/cursor",
  "@milkdown/crepe/feature/list-item",
  "@milkdown/crepe/feature/table",
  "@milkdown/crepe/feature/toolbar",
  ...["prosemirror", "reset", "code-mirror", "cursor", "list-item", "toolbar", "table"]
    .map((name) => "@milkdown/crepe/theme/common/" + name + ".css"),
  "@milkdown/crepe/theme/frame.css",
  "@milkdown/kit/core",
  "remark-gfm",
  "remark-parse",
  "unified",
];

export default defineConfig({
  base: "./",
  // Resolve the editor entry points through Node before bundling on Windows pnpm junctions.
  resolve: {
    alias: editorEntries.map((name) => ({ find: name, replacement: fileURLToPath(import.meta.resolve(name)) })),
  },
  build: {
    license: { fileName: "MarkdownEditor-LICENSE.md" },
    // The editor is loaded on demand from local assets as a single bundle.
    chunkSizeWarningLimit: 1200,
  },
});
