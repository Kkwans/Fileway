"use strict";

const channel = window.chrome.webview;
ace.config.set("basePath", "./ace");
const Range = ace.require("ace/range").Range;
const editor = ace.edit("source", {
  readOnly: true,
  useWorker: false,
  showPrintMargin: false,
  showFoldWidgets: false,
  highlightActiveLine: false,
  highlightGutterLine: false,
  fixedWidthGutter: true,
  textInputAriaLabel: "代码预览",
});
editor.renderer.setPadding(16);
editor.renderer.setScrollMargin(12, 12);

let findMarker = null;
let findQuery = "";
let findOrdinal = 0;

function clearFindMarker() {
  if (findMarker !== null) editor.session.removeMarker(findMarker);
  findMarker = null;
}

function findInCode({ query, step, request }) {
  clearFindMarker();
  if (!query) {
    findQuery = "";
    findOrdinal = 0;
    return;
  }
  const target = query === findQuery && step ? findOrdinal + step : 1;
  findQuery = query;
  const literal = new RegExp(query.replace(/[.*+?^$(){}|[\]\\]/g, "\\$&"), "giu");
  let first = null;
  let last = null;
  let selected = null;
  let total = 0;
  for (const match of editor.session.getValue().matchAll(literal)) {
    const hit = { offset: match.index, length: match[0].length, ordinal: ++total };
    first ??= hit;
    if (target < 1) last = hit;
    if (total === target) selected = hit;
  }
  const hit = selected ?? last ?? first;
  findOrdinal = hit?.ordinal ?? 0;
  if (hit) {
    const start = editor.session.doc.indexToPosition(hit.offset);
    const end = editor.session.doc.indexToPosition(hit.offset + hit.length);
    findMarker = editor.session.addMarker(new Range(start.row, start.column, end.row, end.column), "fw-find-match", "text", false);
    editor.renderer.scrollCursorIntoView(start, 0.35);
  }
  channel.postMessage({ kind: "findResult", request, ordinal: findOrdinal, total });
}

channel.addEventListener("message", ({ data }) => {
  if (data === "focus") {
    editor.focus();
    return;
  }
  if (data.kind === "find") {
    findInCode(data);
    return;
  }
  document.documentElement.dataset.dark = String(data.dark);
  document.documentElement.style.colorScheme = data.dark ? "dark" : "light";
  editor.setTheme(data.dark ? "ace/theme/tomorrow_night" : "ace/theme/github_light_default");
  editor.session.setMode("ace/mode/" + data.mode);
  editor.setOptions({ showGutter: data.lineNumbers, showLineNumbers: data.lineNumbers, wrap: data.wrap });
  if (typeof data.text === "string") {
    clearFindMarker();
    findQuery = "";
    findOrdinal = 0;
    editor.session.setValue(data.text);
    editor.gotoLine(1, 0);
  }
  channel.postMessage("painted");
});

document.addEventListener("keydown", (event) => {
  if (event.isComposing) return;
  const key = event.key.toLowerCase();
  const command = key === "escape" ? "close" : event.ctrlKey && key === "f" ? "find" : null;
  if (!command) return;
  event.preventDefault();
  event.stopPropagation();
  channel.postMessage(command);
}, true);

channel.postMessage("ready");
