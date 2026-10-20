import { bindXHRActivity } from "@/utils/sessionActivity";
import { useAuthStore } from "@/stores/auth";
import { useFavoritesStore } from "@/stores/favorites";
import { useLayoutStore } from "@/stores/layout";
import { useTagsStore } from "@/stores/tags";
import { useRecentStore } from "@/stores/recent";
import { baseURL } from "@/utils/constants";
import {
  upload as postTus,
  uploadBatchHeaders,
  uploadTransferId,
  useTus,
} from "./tus";
import { fetchURL, removePrefix, StatusError } from "./utils";
import type { ApiMethod, ApiOpts, ApiContent, ChecksumAlg } from "@/types/api";
import type { TrashItem } from "./trash";
import type { TaskItem } from "./tasks";
import { isEncodableResponse, makeRawResource } from "@/utils/encodings";
import type {
  Resource,
  ResourceItem,
  RecursiveEntry,
  BatchResourceResult,
  DownloadFormat,
} from "@/types/file";
import urlUtils from "@/utils/url";
import * as transfersApi from "./transfers";
import { batchRenameWireKey } from "@/utils/batchRename";
import { mediaResourceURL } from "@/utils/mediaResource";
import { favoriteIdentity } from "@/utils/favoritePersistence";
import { tagAssociationBody } from "@/utils/tagPersistence";
import { fileSelectionScope } from "@/utils/fileListing";
import {
  operationWireTarget,
  operationSource,
  type OperationResource,
} from "@/utils/resourceOperationWire";

function captureOperationSource() {
  return operationSource(() =>
    fileSelectionScope(useAuthStore()?.user ?? null)
  );
}
async function confirmWireSupport(
  opaque: boolean,
  source: ReturnType<typeof captureOperationSource>
) {
  source.ensure();
  if (!opaque) return;
  let capabilities;
  try {
    const response = await fetchURL("/api/client-capabilities", {});
    capabilities = await response.json();
  } catch (error) {
    source.ensure();
    if (
      error instanceof SyntaxError ||
      (error instanceof StatusError &&
        (error.status === 404 || error.status === 405))
    )
      throw new Error("服务器不支持原始路径写入，请升级服务器后重试");
    throw error;
  }
  source.ensure();
  if (capabilities?.resourceWireOperations !== true)
    throw new Error("服务器不支持原始路径写入，请升级服务器后重试");
}
function refreshOperationCollections(
  source: ReturnType<typeof captureOperationSource>
) {
  if (!source.current()) return;
  // Reuse the stores authority refresh and their owner/revision guards. A
  // refresh failure cannot turn an acknowledged filesystem write into failure.
  void useFavoritesStore()
    .refreshAfterMutation(true)
    .catch(() => {});
  void useTagsStore()
    .refreshAfterMutation(true)
    .catch(() => {});
  void useRecentStore()
    .load()
    .catch(() => {});
}

export interface BatchRenameItem {
  from: string;
  to: string;
  fromWirePath?: string;
  toWirePath?: string;
}

export interface BatchRenameResultItem extends BatchRenameItem {
  status: "ready" | "completed" | "error";
  error?: string;
}

export interface BatchRenameResult {
  valid: boolean;
  executed: boolean;
  items: BatchRenameResultItem[];
  error?: string;
}

export async function fetch(url: string, signal?: AbortSignal) {
  const encoding = isEncodableResponse(url);
  url = removePrefix(url);
  const res = await fetchURL(`/api/resources${url}`, {
    signal,
    headers: {
      "X-Encoding": encoding ? "true" : "false",
    },
  });

  let data: Resource;
  try {
    if (res.headers.get("Content-Type") == "application/octet-stream") {
      data = await makeRawResource(res, url);
    } else {
      data = (await res.json()) as Resource;
    }
  } catch (e) {
    // Check if the error is an intentional cancellation
    if (e instanceof Error && e.name === "AbortError") {
      throw new StatusError("000 No connection", 0, true);
    }
    throw e;
  }
  data.url = `/files${data.wirePath ?? url}`;

  if (data.isDir) {
    if (!data.url.endsWith("/")) data.url += "/";
    // Perhaps change the any
    data.items = data.items.map((item: ResourceItem, index: number) => {
      item.index = index;
      item.url = item.wirePath
        ? `/files${item.wirePath}`
        : `${data.url}${encodeURIComponent(item.name)}`;

      if (item.isDir) {
        item.url += "/";
      }

      return item;
    });
  }

  return data;
}

/** Fetch only the target's metadata without listing a directory or reading
 * file content. The created field is optional and is omitted by filesystems
 * that do not expose a trustworthy birth time. */
export async function fetchMetadata(
  url: string,
  signal?: AbortSignal
): Promise<Resource> {
  const normalized = removePrefix(url);
  const res = await fetchURL(`/api/resources${normalized}?metadata=1`, {
    signal,
  });
  const data = (await res.json()) as Resource;
  data.url = `/files${data.wirePath ?? normalized}`;
  if (data.isDir && !data.url.endsWith("/")) data.url += "/";
  return data;
}

export async function fetchAll(url: string): Promise<RecursiveEntry[]> {
  url = removePrefix(url);
  const res = await fetchURL(`/api/resources/recursive${url}`, {});
  return (await res.json()) as RecursiveEntry[];
}

export async function fetchBatch(
  paths: string[],
  signal?: AbortSignal,
  wirePaths?: string[]
): Promise<BatchResourceResult[]> {
  if (
    !paths.length ||
    paths.length > 500 ||
    (wirePaths && wirePaths.length !== paths.length)
  )
    throw new Error("批量资源最多500项，路径数量必须一致");
  const refs = wirePaths?.map((wirePath, index) => ({
    path: paths[index],
    wirePath,
  }));
  const targets = refs?.map(tagAssociationBody);
  const body = targets
    ? {
        wirePaths: targets.map((target) => target.wirePath),
        ...(targets.every((target) => target.path !== undefined)
          ? { paths }
          : {}),
      }
    : { paths };
  if (new TextEncoder().encode(JSON.stringify(body)).length > 768 * 1024) {
    if (paths.length < 2) throw new Error("资源路径过长，无法读取元数据");
    const middle = Math.floor(paths.length / 2);
    const left = await fetchBatch(
      paths.slice(0, middle),
      signal,
      wirePaths?.slice(0, middle)
    );
    const right = await fetchBatch(
      paths.slice(middle),
      signal,
      wirePaths?.slice(middle)
    );
    return [...left, ...right].map((row, index) => {
      if (row.item) row.item.index = index;
      return row;
    });
  }
  const res = await fetchURL("/api/resources/batch", {
    method: "POST",
    signal,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const results = (await res.json()) as BatchResourceResult[];
  if (!Array.isArray(results) || results.length !== paths.length)
    throw new Error("批量资源响应数量不匹配");
  return results.map((result, index) => {
    if (
      refs &&
      (favoriteIdentity(result) === null ||
        favoriteIdentity(result) !== favoriteIdentity(refs[index]))
    )
      throw new Error("批量资源返回的原始路径顺序不匹配");
    if (!result.item) return result;
    const item = result.item;
    if (refs && favoriteIdentity(item) !== favoriteIdentity(refs[index]))
      throw new Error("标签资源原始路径不匹配");
    item.index = index;
    item.url = `/files${item.wirePath ?? urlUtils.encodePath(item.path)}${item.isDir ? "/" : ""}`;
    return { ...result, item };
  });
}

async function resourceAction(
  url: string,
  method: ApiMethod,
  content?: ApiContent
) {
  url = removePrefix(url);

  const opts: ApiOpts = {
    method,
  };

  if (content) {
    opts.body = content;
  }

  const res = await fetchURL(`/api/resources${url}`, opts);

  return res;
}

export async function remove(
  resource: OperationResource,
  mode: "trash" | "permanent" = "permanent"
): Promise<TrashItem | null> {
  const source = captureOperationSource();
  const target = operationWireTarget(resource, true);
  await confirmWireSupport(target.plainPath === undefined, source);
  source.ensure();
  const response = await fetchURL(
    `/api/resources${target.wirePath}?mode=${encodeURIComponent(mode)}`,
    { method: "DELETE" }
  );
  refreshOperationCollections(source);
  return mode === "trash" ? ((await response.json()) as TrashItem) : null;
}

export async function schedulePermanentDeletion(
  resources: OperationResource[]
): Promise<TaskItem> {
  const source = captureOperationSource();
  const targets = resources.map((resource) => operationWireTarget(resource));
  if (targets.length === 0) throw new Error("请选择需要删除的文件");
  const compatible = targets.every((target) => target.plainPath !== undefined);
  await confirmWireSupport(!compatible, source);
  source.ensure();
  const response = await fetchURL("/api/deletions/pending", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      kind: "resources",
      wirePaths: targets.map((target) => target.wirePath),
      ...(compatible
        ? { paths: targets.map((target) => target.plainPath!) }
        : {}),
    }),
  });
  return (await response.json()) as TaskItem;
}

export async function put(url: string, content: ApiContent = "") {
  return resourceAction(url, "PUT", content);
}

/**
 * Creates a new resource without ever overwriting an existing path.
 * The backend performs the final existence check atomically; callers may
 * retry a 409 response with a different filename.
 */
export async function postExclusive(path: string, content: Blob) {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  return fetchURL(
    `/api/resources${urlUtils.encodePath(normalized)}?override=false`,
    {
      method: "POST",
      headers: {
        "Content-Type": content.type || "application/octet-stream",
      },
      body: content,
    }
  );
}

export function download(format: DownloadFormat, ...files: string[]) {
  let url = `${baseURL}/api/raw`;
  const transferId = downloadTransferId();

  if (files.length === 1) {
    url += removePrefix(files[0]) + "?";
  } else {
    let arg = "";

    for (const file of files) {
      arg += removePrefix(file) + ",";
    }

    arg = arg.substring(0, arg.length - 1);
    arg = encodeURIComponent(arg);
    url += `/?files=${arg}&`;
  }

  if (format) {
    url += `algo=${format}&`;
  }
  url += `transfer=${encodeURIComponent(transferId)}&`;

  const target = files.length === 1 ? removePrefix(files[0]) : files.join(",");
  const name =
    files.length === 1 ? files[0].split("/").pop() || target : "下载文件";
  // Public-share callers use a different API module, but keep this guard for
  // legacy integrations that route through files.download without a session.
  // A failed telemetry request must never trigger the auth store's 401 logout
  // side effect or prevent the native download from starting.
  if (useAuthStore().isLoggedIn) {
    void transfersApi
      .createDownload({
        id: transferId,
        name,
        target,
        url,
      })
      .catch(() => {
        // The raw route remains backwards compatible; telemetry is best effort.
      });
  }

  // Use a temporary <a> element to trigger download without popup blocker issues
  const a = document.createElement("a");
  a.href = url;
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function downloadTransferId() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `download-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export async function post(
  url: string,
  content: ApiContent = "",
  overwrite = false,
  onupload: (progress: { loaded: number }) => void = () => {},
  metadata?: Pick<
    Upload,
    | "batchId"
    | "batchName"
    | "batchItems"
    | "batchBytes"
    | "relativePath"
    | "isFolderUpload"
  >
) {
  // Use the pre-existing API if:
  const useResourcesApi =
    // a folder is being created
    url.endsWith("/") ||
    // We're not using http(s)
    (content instanceof Blob &&
      !["http:", "https:"].includes(window.location.protocol)) ||
    // Tus is disabled / not applicable
    !(await useTus(content));
  return useResourcesApi
    ? postResources(url, content, overwrite, onupload, metadata)
    : postTus(url, content, overwrite, onupload, metadata);
}

async function postResources(
  url: string,
  content: ApiContent = "",
  overwrite = false,
  onupload: (progress: { loaded: number }) => void = () => {},
  metadata?: Pick<
    Upload,
    | "batchId"
    | "batchName"
    | "batchItems"
    | "batchBytes"
    | "relativePath"
    | "isFolderUpload"
  >
) {
  url = removePrefix(url);

  let bufferContent: ArrayBuffer;
  if (
    content instanceof Blob &&
    !["http:", "https:"].includes(window.location.protocol)
  ) {
    bufferContent = await new Response(content).arrayBuffer();
  }

  const authStore = useAuthStore();
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open(
      "POST",
      `${baseURL}/api/resources${url}?override=${overwrite}`,
      true
    );
    request.setRequestHeader("X-Auth", authStore.jwt);
    bindXHRActivity(request);
    if (content instanceof Blob) {
      request.setRequestHeader("X-Transfer-ID", uploadTransferId(url, content));
      for (const [name, value] of Object.entries(
        uploadBatchHeaders(metadata)
      )) {
        request.setRequestHeader(name, value);
      }
    }

    if (typeof onupload === "function") {
      request.upload.onprogress = onupload;
    }

    request.onload = () => {
      if (request.status === 200) {
        resolve(request.responseText);
      } else if (request.status === 409) {
        reject(new Error(request.status.toString()));
      } else {
        reject(new Error(request.responseText));
      }
    };

    request.onerror = () => {
      reject(new Error("001 Connection aborted"));
    };

    request.send(bufferContent || content);
  });
}

type ResourceTransferItem = {
  from: OperationResource;
  to?: OperationResource;
  overwrite?: boolean;
  rename?: boolean;
};

async function moveCopy(
  items: ResourceTransferItem[],
  copy = false,
  overwrite = false,
  rename = false
) {
  const source = captureOperationSource();
  if (!items.length || items.length > 1000)
    throw new Error("文件操作必须包含1到1000项");
  const targets = items.map((item) => {
    if (!item.to) throw new Error("目标路径无法确认");
    return {
      item,
      from: operationWireTarget(item.from, true),
      to: operationWireTarget(item.to, true),
    };
  });
  const opaque = targets.some(
    ({ from, to }) => from.plainPath === undefined || to.plainPath === undefined
  );
  await confirmWireSupport(opaque, source);
  source.ensure();
  const layoutStore = useLayoutStore();
  const sameDirectoryMove =
    !copy &&
    targets.every(
      ({ from, to }) =>
        (from.identity.slice(0, from.identity.lastIndexOf("/")) || "/") ===
        (to.identity.slice(0, to.identity.lastIndexOf("/")) || "/")
    );

  if (!sameDirectoryMove) {
    const response = await fetchURL("/api/resources/transfer", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: copy ? "copy" : "move",
        items: targets.map(({ item, from, to }) => ({
          ...item,
          from: undefined,
          to: undefined,
          fromWirePath: from.wirePath,
          toWirePath: to.wirePath,
          ...(from.plainPath !== undefined && to.plainPath !== undefined
            ? { from: from.legacyRoute, to: to.legacyRoute }
            : {}),
        })),
      }),
    });
    if (source.current()) layoutStore.closeHovers();
    return [response];
  }

  const promises = targets.map(async ({ item, from, to }) => {
    source.ensure();
    const params = new URLSearchParams({
      action: "rename",
      destinationWirePath: to.wirePath,
      override: String(item.overwrite ?? overwrite),
      rename: String(item.rename ?? rename),
    });
    if (to.plainPath !== undefined) params.set("destination", to.plainPath);
    return fetchURL(`/api/resources${from.wirePath}?${params}`, {
      method: "PATCH",
    });
  });
  if (source.current()) layoutStore.closeHovers();
  const outcomes = await Promise.allSettled(promises);
  if (outcomes.some((outcome) => outcome.status === "fulfilled"))
    refreshOperationCollections(source);
  const failure = outcomes.find(
    (outcome): outcome is PromiseRejectedResult => outcome.status === "rejected"
  );
  if (failure) throw failure.reason;
  return outcomes.map(
    (outcome) => (outcome as PromiseFulfilledResult<Response>).value
  );
}

export function move(
  items: ResourceTransferItem[],
  overwrite = false,
  rename = false
) {
  return moveCopy(items, false, overwrite, rename);
}

function applyCompletedBatchRename(items: BatchRenameResultItem[]) {
  const completed = items.filter((item) => item.status === "completed");
  if (completed.length === 0) return;

  const token = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const staged = completed.map((item, index) => ({
    ...item,
    temp: `/.nas-file-browser-ui-rename-${token}-${index}`,
    tempWire: urlUtils.encodePath(
      `/.nas-file-browser-ui-rename-${token}-${index}`
    ),
    unicodeMetadata:
      (!item.fromWirePath ||
        batchRenameWireKey(item.fromWirePath) ===
          batchRenameWireKey(urlUtils.encodePath(item.from))) &&
      (!item.toWirePath ||
        batchRenameWireKey(item.toWirePath) ===
          batchRenameWireKey(urlUtils.encodePath(item.to))),
  }));
  const favoritesStore = useFavoritesStore();
  const tagsStore = useTagsStore();
  const recentStore = useRecentStore();
  for (const item of staged) {
    // These older caches have no wire identity. Avoid changing a same-display
    // sibling for opaque paths; server metadata has already been rewritten.
    if (item.unicodeMetadata) {
      favoritesStore.applyPathRewrite(item.from, item.temp);
      tagsStore.applyPathRewrite(item.from, item.temp);
    }
    recentStore.applyPathRewrite(
      item.from,
      item.temp,
      item.fromWirePath,
      item.tempWire
    );
  }
  for (const item of staged) {
    if (item.unicodeMetadata) {
      favoritesStore.applyPathRewrite(item.temp, item.to);
      tagsStore.applyPathRewrite(item.temp, item.to);
    }
    recentStore.applyPathRewrite(
      item.temp,
      item.to,
      item.tempWire,
      item.toWirePath
    );
  }
}

export async function batchRename(
  items: BatchRenameItem[],
  dryRun: boolean
): Promise<BatchRenameResult> {
  const source = captureOperationSource();
  const response = await fetchURL("/api/resources/batch-rename", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ items, dryRun }),
  });
  const result = (await response.json()) as BatchRenameResult;
  if (result.valid) {
    if (result.items.length !== items.length)
      throw new Error("服务器返回的批量重命名项目数不一致，请刷新文件状态");
    result.items.forEach((item, index) => {
      const requested = items[index];
      if (
        batchRenameWireKey(
          item.fromWirePath || urlUtils.encodePath(item.from)
        ) !==
          batchRenameWireKey(
            requested.fromWirePath || urlUtils.encodePath(requested.from)
          ) ||
        batchRenameWireKey(item.toWirePath || urlUtils.encodePath(item.to)) !==
          batchRenameWireKey(
            requested.toWirePath || urlUtils.encodePath(requested.to)
          )
      )
        throw new Error("服务器返回的批量重命名原始路径不一致，请刷新文件状态");
      if (item.status !== (result.executed ? "completed" : "ready"))
        throw new Error("服务器返回的批量重命名项目状态无效，请刷新文件状态");
    });
  }
  if (result.executed) {
    if (
      !result.valid ||
      dryRun ||
      result.items.some((item) => item.status !== "completed")
    )
      throw new Error("服务器返回的批量重命名执行状态无效，请刷新文件状态");
    if (source.current()) applyCompletedBatchRename(result.items);
  }
  return result;
}

export function copy(
  items: ResourceTransferItem[],
  overwrite = false,
  rename = false
) {
  return moveCopy(items, true, overwrite, rename);
}

export async function checksum(url: string, algo: ChecksumAlg) {
  const data = await resourceAction(`${url}?checksum=${algo}`, "GET");
  return (await data.json()).checksums[algo];
}

export function getDownloadURL(
  file: Pick<ResourceItem, "path" | "wirePath">,
  inline: boolean
) {
  const params = {
    ...(inline && { inline: "true" }),
  };

  return mediaResourceURL("api/raw", file, params);
}

export function getPreviewURL(
  file: ResourceItem,
  size: string,
  options: { warm?: "big"; fit?: "contain" } = {}
) {
  const params = {
    inline: "true",
    generator: "v3",
    key: `${Date.parse(file.modified)}-${file.size}`,
    ...(options.warm ? { warm: options.warm } : {}),
    ...(options.fit ? { fit: options.fit } : {}),
  };

  return mediaResourceURL("api/preview/" + size, file, params);
}

export function getSubtitlesURL(file: ResourceItem) {
  const params = {
    inline: "true",
  };

  return file.subtitles?.flatMap((path) => {
    try {
      return [mediaResourceURL("api/subtitle", { path }, params)];
    } catch {
      return [];
    } // Legacy subtitle names with lost bytes cannot be guessed.
  });
}

export async function usage(url: string, signal: AbortSignal) {
  url = removePrefix(url);

  const res = await fetchURL(`/api/usage${url}`, { signal });

  try {
    return await res.json();
  } catch (e) {
    // Check if the error is an intentional cancellation
    if (e instanceof Error && e.name == "AbortError") {
      throw new StatusError("000 No connection", 0, true);
    }
    throw e;
  }
}
