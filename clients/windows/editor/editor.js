import { CrepeBuilder } from "@milkdown/crepe/builder";
import { codeMirror } from "@milkdown/crepe/feature/code-mirror";
import { cursor } from "@milkdown/crepe/feature/cursor";
import { listItem } from "@milkdown/crepe/feature/list-item";
import { table } from "@milkdown/crepe/feature/table";
import { toolbar } from "@milkdown/crepe/feature/toolbar";
import { editorViewCtx } from "@milkdown/kit/core";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";
import "@milkdown/crepe/theme/common/prosemirror.css";
import "@milkdown/crepe/theme/common/reset.css";
import "@milkdown/crepe/theme/common/code-mirror.css";
import "@milkdown/crepe/theme/common/cursor.css";
import "@milkdown/crepe/theme/common/list-item.css";
import "@milkdown/crepe/theme/common/toolbar.css";
import "@milkdown/crepe/theme/common/table.css";
import "@milkdown/crepe/theme/frame.css";
import "./editor.css";

const channel = window.chrome.webview;
let current = null;
let queue = Promise.resolve();
const markdownSyntax = unified().use(remarkParse).use(remarkGfm);

function fingerprint(tree) {
  return JSON.stringify(tree, (key, value) => {
    if (key === "position") return undefined;
    if (value?.type === "text") {
      return { ...value, value: value.value.replace(/[ \t]*\r?\n[ \t]*/g, " ") };
    }
    return value;
  });
}

function hasRawHtml(node) {
  return node.type === "html" || node.children?.some(hasRawHtml) === true;
}

function uniqueBlocks(tree) {
  const blocks = new Map();
  for (const node of tree.children) {
    const key = fingerprint(node);
    blocks.set(key, blocks.has(key) ? null : node.position);
  }
  return blocks;
}

function snapshot(state) {
  const markdown = state.editor.getMarkdown();
  if (markdown === state.canonical) return state.original;
  const tree = markdownSyntax.parse(markdown);
  const editedBlocks = uniqueBlocks(tree);
  const parts = [];
  let offset = 0;
  for (const [key, range] of editedBlocks) {
    const original = state.blocks.get(key);
    if (!range || !original) continue;
    parts.push(markdown.slice(offset, range.start.offset));
    parts.push(state.original.slice(original.start.offset, original.end.offset));
    offset = range.end.offset;
  }
  if (parts.length === 0) return markdown;
  parts.push(markdown.slice(offset));
  const preserved = parts.join("");
  return fingerprint(tree) === fingerprint(markdownSyntax.parse(preserved)) ? preserved : markdown;
}

async function openDocument(message) {
  if (current) await current.editor.destroy();
  current = null;
  const sourceTree = markdownSyntax.parse(message.text);
  const frontMatter = /^---\s*\r?\n[\w-]+\s*:/.test(message.text);
  if (frontMatter || hasRawHtml(sourceTree)) {
    channel.postMessage({ kind: "unsupported", id: message.id });
    return;
  }
  const editor = new CrepeBuilder({
    root: "#document",
    defaultValue: message.text,
  }).addFeature(cursor).addFeature(listItem).addFeature(table).addFeature(codeMirror).addFeature(toolbar);
  const documentState = { editor, id: message.id, original: message.text, canonical: null, blocks: uniqueBlocks(sourceTree) };
  editor.on((listener) => {
    listener.markdownUpdated((_ctx, markdown) => {
      if (current === documentState && documentState.canonical !== null && !editor.readonly) {
        channel.postMessage({ kind: "dirty", id: documentState.id,
          text: markdown === documentState.canonical ? documentState.original : markdown });
      }
    });
  });
  editor.setReadonly(true);
  await editor.create();
  documentState.canonical = editor.getMarkdown();
  if (fingerprint(sourceTree) !== fingerprint(markdownSyntax.parse(documentState.canonical))) {
    await editor.destroy();
    channel.postMessage({ kind: "unsupported", id: message.id });
    return;
  }
  current = documentState;
  editor.editor.action((ctx) => {
    const element = ctx.get(editorViewCtx).dom;
    element.setAttribute("role", "textbox");
    element.setAttribute("aria-label", "Markdown 正文");
    element.setAttribute("aria-multiline", "true");
    element.setAttribute("aria-readonly", "true");
  });
  channel.postMessage({ kind: "loaded", id: message.id });
}

async function receive(message) {
  if (message.kind === "theme") {
    document.documentElement.dataset.dark = String(message.dark);
    return;
  }
  if (message.kind === "load") {
    await openDocument(message);
    return;
  }
  if (!current || message.id !== current.id) return;
  if (message.kind === "editable") {
    current.editor.setReadonly(!message.value);
    current.editor.editor.action((ctx) => ctx.get(editorViewCtx).dom.setAttribute("aria-readonly", String(!message.value)));
  }
  if (message.kind === "focus") current.editor.editor.action((ctx) => ctx.get(editorViewCtx).focus());
  if (message.kind === "snapshot") {
    channel.postMessage({
      kind: "snapshot", id: current.id, request: message.request,
      text: snapshot(current),
    });
  }
}

channel.addEventListener("message", ({ data }) => {
  queue = queue.then(() => receive(data)).catch(() => channel.postMessage({ kind: "failed", id: data.id }));
});

document.addEventListener("click", (event) => {
  const link = event.target instanceof Element ? event.target.closest("a") : null;
  if (!link) return;
  event.preventDefault();
  const url = link.getAttribute("href");
  if (current && (current.editor.readonly || event.ctrlKey) && /^https?:\/\//i.test(url ?? "")) {
    channel.postMessage({ kind: "link", id: current.id, url });
  }
}, true);

document.addEventListener("keydown", (event) => {
  if (event.isComposing) return;
  const key = event.key.toLowerCase();
  const kind = key === "escape" ? "close" : event.ctrlKey && key === "f" ? "find" : event.ctrlKey && key === "s" ? "save" : null;
  if (!kind) return;
  event.preventDefault();
  if (kind === "find") event.stopPropagation();
  channel.postMessage({ kind });
}, true);

channel.postMessage({ kind: "ready" });
