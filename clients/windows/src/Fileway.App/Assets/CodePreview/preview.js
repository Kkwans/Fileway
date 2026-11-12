"use strict";

const channel = window.chrome.webview;
ace.config.set("basePath", "./ace");
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

channel.addEventListener("message", ({ data }) => {
  if (data === "focus") {
    editor.focus();
    return;
  }
  document.documentElement.dataset.dark = String(data.dark);
  document.documentElement.style.colorScheme = data.dark ? "dark" : "light";
  editor.setTheme(data.dark ? "ace/theme/tomorrow_night" : "ace/theme/github_light_default");
  editor.session.setMode("ace/mode/" + data.mode);
  editor.setOptions({ showGutter: data.lineNumbers, showLineNumbers: data.lineNumbers, wrap: data.wrap });
  if (typeof data.text === "string") {
    editor.session.setValue(data.text);
    editor.gotoLine(1, 0);
  }
  channel.postMessage("painted");
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  if (editor.searchBox?.element?.style.display === "block") return;
  event.preventDefault();
  channel.postMessage("close");
}, true);

channel.postMessage("ready");
