<template>
  <div id="archive-page" class="archive-page">
    <header-bar
      show-menu
      show-logo
      title="压缩包浏览"
      title-icon="file-archive"
    >
      <template #actions>
        <router-link
          v-if="
            archivePath &&
            (archiveWire || !archivePath.includes('\uFFFD')) &&
            extractReport?.pathsVerified !== false
          "
          class="archive-header-action"
          aria-label="打开所在目录"
          title="打开所在目录"
          :to="parentRoute"
        >
          <AppIcon name="arrow-left" :size="18" />
          打开所在目录
        </router-link>
      </template>
    </header-bar>

    <main class="archive-workspace">
      <section v-if="loading" class="archive-state" aria-live="polite">
        <AppIcon name="loader" class="archive-spin" :size="24" />
        <div>
          <strong>正在读取压缩包目录</strong>
          <p>大型 TAR 或压缩 TAR 需要顺序扫描，请稍候。</p>
        </div>
      </section>

      <section
        v-else-if="loadError"
        class="archive-state archive-state--error"
        role="alert"
      >
        <AppIcon name="circle-alert" :size="24" />
        <div>
          <strong>无法打开压缩包</strong>
          <p>{{ loadError }}</p>
          <small
            >支持 ZIP、7Z、RAR、TAR、tar.gz、tar.bz2、tar.xz 和 tar.zst；暂不支持密码输入和分卷归档。</small
          >
        </div>
        <button
          v-if="archivePath"
          type="button"
          @click="loadListing(archivePath)"
        >
          重试
        </button>
      </section>

      <section
        v-else-if="!archivePath && !currentTask && !listing"
        class="archive-state archive-state--empty"
      >
        <AppIcon name="file-archive" :size="24" />
        <div>
          <strong>选择压缩包开始浏览</strong>
          <p>从文件列表打开 ZIP 或 TAR 压缩包，即可查看其中的文件。</p>
        </div>
        <router-link class="archive-state-link" to="/files/">
          去文件列表
        </router-link>
      </section>

      <template v-if="listing && !loading">
        <section class="archive-overview" aria-label="压缩包摘要">
          <article>
            <small>格式</small>
            <strong>{{ listing.format.toUpperCase() }}</strong>
            <span :title="archivePath">{{ archiveName }}</span>
            <span>{{ formatBytes(listing.sourceSize) }} 源文件</span>
          </article>
          <article>
            <small>安全条目</small>
            <strong>{{ listing.entries.length.toLocaleString() }}</strong>
            <span>{{ formatBytes(listing.listedBytes) }} 声明内容</span>
          </article>
          <article :class="{ 'is-warning': listing.blockedCount > 0 }">
            <small>已阻止条目</small>
            <strong>{{ listing.blockedCount.toLocaleString() }}</strong>
            <span>链接、特殊文件或不安全路径</span>
          </article>
        </section>

        <section v-if="listing.truncated" class="archive-warning" role="alert">
          <AppIcon name="risk-high" :size="22" />
          <div>
            <strong>压缩包超过安全限制，已停止继续读取</strong>
            <p>{{ listing.limitReason }}。在确认完整内容前不会允许解压。</p>
          </div>
        </section>

        <details v-if="listing.blockedCount" class="archive-blocked">
          <summary>{{ listing.blockedCount }} 个条目已被安全阻止</summary>
          <p>阻止的内容不会出现在选择列表，也不会写入服务器。</p>
          <ul>
            <li
              v-for="entry in listing.blocked"
              :key="`${entry.path}:${entry.reason}`"
            >
              <span>{{ entry.path }}</span>
              <small>{{ entry.reason }}</small>
            </li>
          </ul>
        </details>

        <section class="archive-browser" aria-labelledby="entries-title">
          <div class="archive-browser-toolbar">
            <div>
              <div>
                <h2 id="entries-title">选择条目</h2>
                <p>勾选目录会包含其全部后代；也可展开后只选单个文件。</p>
              </div>
            </div>
            <div class="archive-browser-actions">
              <label>
                <AppIcon name="search" :size="18" />
                <input
                  v-model.trim="filter"
                  type="search"
                  placeholder="筛选归档路径"
                />
              </label>
              <button type="button" @click="toggleSelectAll">
                {{ selected.has(".") ? "清空选择" : "选择全部" }}
              </button>
            </div>
          </div>

          <div class="archive-table-heading" aria-hidden="true">
            <span>名称</span>
            <span>大小</span>
            <span>修改时间</span>
          </div>
          <div
            v-if="visibleRows.length"
            class="archive-entry-list"
            role="tree"
            aria-multiselectable="true"
          >
            <div
              v-for="row in visibleRows"
              :key="row.path"
              class="archive-entry-row"
              :class="{
                'is-selected': rowSelected(row.path),
                'is-inherited': rowInherited(row.path),
              }"
              role="treeitem"
              :aria-level="row.depth + 1"
              :aria-expanded="row.isDir ? expanded.has(row.path) : undefined"
              :aria-selected="rowSelected(row.path)"
            >
              <div
                class="archive-entry-name"
                :style="{ paddingLeft: `${12 + row.depth * 20}px` }"
              >
                <button
                  v-if="row.isDir"
                  type="button"
                  class="archive-expand"
                  :aria-label="
                    expanded.has(row.path)
                      ? `收起 ${row.name}`
                      : `展开 ${row.name}`
                  "
                  @click="toggleExpanded(row.path)"
                >
                  <AppIcon
                    :name="
                      expanded.has(row.path) ? 'chevron-down' : 'chevron-right'
                    "
                    :size="19"
                  />
                </button>
                <span v-else class="archive-expand-spacer"></span>
                <label>
                  <input
                    type="checkbox"
                    :checked="rowSelected(row.path)"
                    :disabled="rowInherited(row.path)"
                    :aria-label="`选择 ${rowDisplay(row.path)}`"
                    @change="toggleSelected(row)"
                  />
                  <AppIcon :name="entryIcon(row)" :size="20" />
                  <span :title="rowDisplay(row.path)">
                    <strong>{{ row.name }}</strong>
                    <small>{{ rowDisplay(row.path) }}</small>
                  </span>
                </label>
              </div>
              <span>{{ row.isDir ? "—" : formatBytes(row.size) }}</span>
              <time
                :datetime="
                  row.modified
                    ? new Date(row.modified).toISOString()
                    : undefined
                "
              >
                {{ row.modified ? formatModified(row.modified) : "—" }}
              </time>
            </div>
          </div>
          <div v-else class="archive-empty-filter">
            <AppIcon name="filter-clear" :size="22" />
            没有匹配“{{ filter }}”的条目
          </div>
          <button
            v-if="visibleRows.length < filteredRows.length"
            type="button"
            class="archive-load-more"
            @click="visibleLimit += 200"
          >
            再显示
            {{ Math.min(200, filteredRows.length - visibleRows.length) }} 项
          </button>
        </section>

        <section class="archive-extract-card" aria-labelledby="extract-title">
          <div class="archive-section-title">
            <div>
              <h2 id="extract-title">确认解压</h2>
              <p>
                当前选择 {{ selectedStats.files }} 个文件，共
                {{ formatBytes(selectedStats.bytes) }}。
              </p>
            </div>
          </div>
          <label class="archive-destination">
            <span>目标目录</span>
            <div>
              <AppIcon name="move" :size="19" />
              <input
                v-model="destination"
                type="text"
                autocomplete="off"
                placeholder="例如 /照片/已解压"
              />
              <button
                type="button"
                class="archive-destination-picker"
                @click="showDestinationPicker = true"
              >
                <AppIcon name="folder" :size="18" />
                浏览
              </button>
            </div>
            <small>目录必须已存在；同名文件会跳过并写入结果报告。</small>
          </label>
          <PathPicker
            v-if="showDestinationPicker"
            title="选择解压目标目录"
            :model-value="destination"
            :model-wire-path="destinationWire || undefined"
            wire-paths
            @select-resource="selectDestination"
            @close="showDestinationPicker = false"
          />
          <div class="archive-extract-footer">
            <button
              type="button"
              class="archive-primary"
              :disabled="!canExtract"
              @click="startExtraction"
            >
              <AppIcon name="archive-restore" :size="18" />
              {{ starting ? "正在提交…" : "解压所选项目" }}
            </button>
          </div>
        </section>
      </template>

      <section v-if="currentTask" class="archive-task" aria-live="polite">
        <div class="archive-task-icon" :class="`is-${currentTask.status}`">
          <AppIcon :name="taskIcon" :size="22" />
        </div>
        <div>
          <strong>{{ currentTask.title }}</strong>
          <span>{{ taskStatusLabel }}</span>
          <div v-if="currentTask.totalItems > 0" class="archive-progress">
            <div :style="{ width: `${taskProgress}%` }"></div>
          </div>
          <small v-if="currentTask.totalItems > 0">
            {{ currentTask.processedItems }} / {{ currentTask.totalItems }} 项
            <template v-if="currentTask.totalBytes > 0">
              · {{ formatBytes(currentTask.processedBytes) }} /
              {{ formatBytes(currentTask.totalBytes) }}</template
            >
          </small>
          <small v-else-if="taskActive">等待唯一解压工作槽</small>
          <small v-if="currentTask.error" class="archive-task-error">{{
            currentTask.error
          }}</small>
        </div>
        <button
          v-if="taskActive"
          type="button"
          :disabled="canceling"
          @click="cancelExtraction"
        >
          {{ canceling ? "提交中…" : "取消解压" }}
        </button>
      </section>

      <section
        v-if="extractReport"
        class="archive-result"
        aria-labelledby="result-title"
      >
        <div class="archive-result-mark" aria-hidden="true">
          <AppIcon name="circle-check" :size="24" />
        </div>
        <div>
          <h2 id="result-title">解压任务已完成</h2>
          <p>
            写入 {{ extractReport.extractedFiles }} 个文件、{{
              extractReport.extractedDirs
            }}
            个目录， 共 {{ formatBytes(extractReport.extractedBytes) }}。
          </p>
          <p v-if="extractReport.skippedCount" class="archive-result-warning">
            {{ extractReport.skippedCount }}
            项因目标已存在而跳过，没有覆盖原文件。
          </p>
          <ul v-if="extractReport.skipped?.length">
            <li v-for="entry in extractReport.skipped" :key="entry.path">
              <span>{{ entry.path }}</span
              ><small>{{ entry.reason }}</small>
            </li>
          </ul>
        </div>
        <p v-if="extractReport.pathsVerified === false">
          历史结果的原始路径无法确认，请从文件列表重新选择目录。
        </p>
        <router-link v-else :to="destinationRoute">
          打开目标目录
          <AppIcon name="arrow-right" :size="17" />
        </router-link>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import HeaderBar from "@/components/header/HeaderBar.vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import PathPicker from "@/components/prompts/PathPicker.vue";
import * as archiveApi from "@/api/archive";
import * as taskApi from "@/api/tasks";
import type { ArchiveExtractReport, ArchiveListing } from "@/api/archive";
import type { TaskItem, TaskStatus } from "@/api/tasks";
import { useAuthStore } from "@/stores/auth";
import { useTasksStore } from "@/stores/tasks";
import { useRecentStore } from "@/stores/recent";
import type { AppIconName } from "@/components/ui/iconRegistry";
import {
  buildArchiveTree,
  flattenArchiveTree,
  hasSelectedAncestor,
  pathCoveredBySelection,
  selectedArchiveStats,
  type ArchiveTreeNode,
  type ArchiveTreeRow,
} from "@/utils/archiveTree";
import { encodePath } from "@/utils/url";
import {
  archiveWirePath,
  archiveWireEntries,
  archiveDisplayPaths,
} from "@/utils/archiveWire";
import { filesize } from "@/utils";
import dayjs from "@/utils/date";
import { getResourceIconName } from "@/utils/fileIcons";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const tasksStore = useTasksStore();
const recentStore = useRecentStore();
const $showError = inject<IToastError>("$showError")!;
const $showSuccess = inject<IToastSuccess>("$showSuccess")!;

const archivePath = ref(archivePathFromRoute());
const archiveWire = ref(
  typeof route.query.wirePath === "string" ? route.query.wirePath : ""
);
const destination = ref(parentPath(archivePath.value));
const destinationWire = ref(
  archiveWire.value ? parentPath(archiveWire.value) : ""
);
let destinationProofPath = destination.value;
const showDestinationPicker = ref(false);

function selectDestination(
  value:
    | { path: string; wirePath: string }
    | Array<{ path: string; wirePath: string }>
) {
  const resource = Array.isArray(value) ? value[0] : value;
  if (!resource) return;
  bindDestination(resource.path, resource.wirePath);
  showDestinationPicker.value = false;
}

const listing = ref<ArchiveListing | null>(null);
const loading = ref(false);
const loadError = ref("");
const filter = ref("");
const expanded = ref<Set<string>>(new Set());
const selected = ref<Set<string>>(new Set());
const visibleLimit = ref(200);
const starting = ref(false);
const canceling = ref(false);
const currentTask = ref<TaskItem | null>(null);
const extractReport = ref<ArchiveExtractReport | null>(null);
let pollTimer: number | undefined;

let disposed = false;
let routeLoadSequence = 0;
let listingLoadSequence = 0;

const archiveName = computed(() => archivePath.value.split("/").at(-1) || "");
const wireEntries = computed(() =>
  archiveWireEntries(listing.value?.entries ?? [])
);
const displayPaths = computed(() =>
  archiveDisplayPaths(listing.value?.entries ?? [])
);
function rowDisplay(wire: string) {
  return displayPaths.value.get(wire) || wire;
}
function displayTree(nodes: ArchiveTreeNode[]): ArchiveTreeNode[] {
  return nodes.map((node) => ({
    ...node,
    name: node.implicit
      ? rowDisplay(node.path).split("/").at(-1) || node.name
      : node.name,
    children: displayTree(node.children),
  }));
}
const tree = computed(() => displayTree(buildArchiveTree(wireEntries.value)));
const allExpanded = computed(() => {
  const paths = new Set<string>();
  collectDirectoryPaths(tree.value, paths);
  return paths;
});
const flattenedRows = computed(() =>
  flattenArchiveTree(
    tree.value,
    filter.value ? allExpanded.value : expanded.value
  )
);
const filteredRows = computed(() => {
  const query = filter.value.toLocaleLowerCase();
  if (!query) return flattenedRows.value;
  return flattenedRows.value.filter((row) =>
    rowDisplay(row.path).toLocaleLowerCase().includes(query)
  );
});
const visibleRows = computed(() =>
  filteredRows.value.slice(0, visibleLimit.value)
);
const selectedStats = computed(() =>
  selectedArchiveStats(wireEntries.value, selected.value)
);
const taskActive = computed(
  () =>
    currentTask.value?.status === "queued" ||
    currentTask.value?.status === "running"
);
const canExtract = computed(
  () =>
    Boolean(authStore.user?.perm.create && authStore.user?.perm.download) &&
    selectedStats.value.items > 0 &&
    destination.value.startsWith("/") &&
    !listing.value?.truncated &&
    !starting.value &&
    !taskActive.value
);
const taskProgress = computed(() => {
  const task = currentTask.value;
  if (!task || task.totalItems <= 0) return 0;
  return Math.min(
    100,
    Math.round((task.processedItems / task.totalItems) * 100)
  );
});
const taskStatusLabel = computed(() =>
  currentTask.value ? taskStatus(currentTask.value.status) : ""
);
const taskIcon = computed<AppIconName>(() => {
  const icons: Record<TaskStatus, AppIconName> = {
    queued: "hourglass",
    running: "loader",
    completed: "circle-check",
    failed: "circle-alert",
    canceled: "circle-stop",
    interrupted: "retry",
  };
  return currentTask.value ? icons[currentTask.value.status] : "hourglass";
});
const parentRoute = computed(() =>
  filesRoute(
    parentPath(archivePath.value),
    archiveWire.value ? parentPath(archiveWire.value) : undefined
  )
);
const destinationRoute = computed(() =>
  filesRoute(
    extractReport.value?.destination || destination.value,
    extractReport.value?.destinationWirePath ||
      destinationWire.value ||
      undefined
  )
);
function bindDestination(path: string, wire: string) {
  destinationProofPath = path;
  destination.value = path;
  destinationWire.value = wire;
}
watch(destination, (path) => {
  if (path !== destinationProofPath) destinationWire.value = "";
});

watch(filter, () => {
  visibleLimit.value = 200;
});

watch(
  () => [route.query.path, route.query.wirePath, route.query.task],
  () => {
    void loadFromRoute();
  }
);

async function loadListing(path = archivePath.value, wire = archiveWire.value) {
  if (!path) return;
  const sequence = ++listingLoadSequence;
  archivePath.value = path;
  loading.value = true;
  loadError.value = "";
  try {
    const next = await archiveApi.entries(path, wire || undefined);
    if (
      disposed ||
      sequence !== listingLoadSequence ||
      archivePath.value !== path
    )
      return;
    archiveWireEntries(next.entries);
    archiveWire.value = archiveWirePath(
      next.archivePath,
      next.archiveWirePath,
      next.pathVerified
    );
    archivePath.value = next.archivePath;
    listing.value = next;
    void recentStore
      .record(next.archivePath, archiveWire.value)
      .catch(() => {});
    if (!destination.value)
      bindDestination(
        parentPath(next.archivePath),
        parentPath(archiveWire.value)
      );
    selected.value = new Set();
    expanded.value = new Set(
      treeRootDirectories(archiveWireEntries(next.entries))
    );
    visibleLimit.value = 200;
  } catch (error) {
    if (disposed || sequence !== listingLoadSequence) return;
    listing.value = null;
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    if (!disposed && sequence === listingLoadSequence) loading.value = false;
  }
}

// The report can be replaced by an awaited task read during route loading.
function reportPathsAreVerified() {
  return extractReport.value?.pathsVerified !== false;
}

async function loadFromRoute() {
  const sequence = ++routeLoadSequence;
  stopPolling();
  loadError.value = "";
  extractReport.value = null;
  const routePath = archivePathFromRoute();
  archiveWire.value =
    typeof route.query.wirePath === "string" ? route.query.wirePath : "";
  archivePath.value = routePath;
  bindDestination(
    parentPath(routePath),
    archiveWire.value ? parentPath(archiveWire.value) : ""
  );
  const taskId = typeof route.query.task === "string" ? route.query.task : "";
  if (!routePath && !taskId) listing.value = null;
  try {
    if (taskId) {
      const task = await taskApi.get(taskId);
      if (sequence !== routeLoadSequence || disposed) return;
      if (task.type !== "archive.extract") {
        loadError.value = "该任务不是压缩包解压任务。";
        return;
      }
      currentTask.value = task;
      tasksStore.record(task);
      if (task.status === "completed") {
        await loadExtractReport(task.id);
      } else if (task.status === "queued" || task.status === "running") {
        schedulePoll(0);
      }
    } else {
      currentTask.value = null;
    }
    if (
      reportPathsAreVerified() &&
      archivePath.value &&
      (!listing.value ||
        listing.value.archivePath !== archivePath.value ||
        listing.value.archiveWirePath !== archiveWire.value)
    ) {
      await loadListing(archivePath.value);
    }
  } catch (error) {
    if (sequence !== routeLoadSequence || disposed) return;
    loadError.value = error instanceof Error ? error.message : String(error);
  }
}

async function startExtraction() {
  if (!canExtract.value || !listing.value) return;
  starting.value = true;
  loadError.value = "";
  extractReport.value = null;
  try {
    const task = await archiveApi.extract({
      archivePath: listing.value.archivePath,
      archiveWirePath: archiveWire.value,
      destination: destination.value,
      destinationWirePath: archiveWirePath(
        destination.value,
        destinationWire.value || undefined
      ),
      selected: [...selected.value].map((wire) =>
        wire === "." ? "." : rowDisplay(wire)
      ),
      selectedWirePaths: [...selected.value],
    });
    currentTask.value = task;
    tasksStore.record(task);
    await router.replace({
      path: "/archive",
      query: {
        path: listing.value.archivePath,
        wirePath: archiveWire.value,
        task: task.id,
      },
    });
    $showSuccess("解压任务已提交，可在任务中心取消");
    schedulePoll(0);
  } catch (error) {
    $showError(
      error instanceof Error ? error : new Error(String(error)),
      false
    );
  } finally {
    starting.value = false;
  }
}

async function cancelExtraction() {
  if (!currentTask.value || canceling.value) return;
  canceling.value = true;
  try {
    currentTask.value = await tasksStore.cancel(currentTask.value.id);
    stopPolling();
    $showSuccess("取消请求已提交");
  } catch (error) {
    $showError(
      error instanceof Error ? error : new Error(String(error)),
      false
    );
  } finally {
    canceling.value = false;
  }
}

function schedulePoll(delay = 750) {
  stopPolling();
  if (disposed || !currentTask.value) return;
  pollTimer = window.setTimeout(pollTask, delay);
}

function stopPolling() {
  if (pollTimer !== undefined) window.clearTimeout(pollTimer);
  pollTimer = undefined;
}

async function pollTask() {
  const id = currentTask.value?.id;
  if (!id || disposed) return;
  try {
    const task = await taskApi.get(id);
    currentTask.value = task;
    tasksStore.record(task);
    if (task.status === "queued" || task.status === "running") {
      schedulePoll();
      return;
    }
    if (task.status === "completed") await loadExtractReport(task.id);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
    schedulePoll(1500);
  }
}

async function loadExtractReport(taskId: string) {
  const report = await archiveApi.extractionResult(taskId);
  if (disposed) return;
  if (
    (!report.archiveWirePath && report.archivePath.includes("\uFFFD")) ||
    (!report.destinationWirePath && report.destination.includes("\uFFFD"))
  )
    report.pathsVerified = false;
  extractReport.value = report;
  archivePath.value = report.archivePath;
  if (report.pathsVerified === false) {
    listing.value = null;
    archiveWire.value = "";
    bindDestination(report.destination, "");
    return;
  }
  archiveWire.value = archiveWirePath(
    report.archivePath,
    report.archiveWirePath
  );
  bindDestination(
    report.destination,
    archiveWirePath(report.destination, report.destinationWirePath)
  );
  if (
    !listing.value ||
    listing.value.archivePath !== report.archivePath ||
    listing.value.archiveWirePath !== archiveWire.value
  ) {
    await loadListing(report.archivePath, archiveWire.value);
    bindDestination(
      report.destination,
      archiveWirePath(report.destination, report.destinationWirePath)
    );
  }
}

function toggleExpanded(path: string) {
  const next = new Set(expanded.value);
  if (next.has(path)) next.delete(path);
  else next.add(path);
  expanded.value = next;
}

function toggleSelected(row: ArchiveTreeRow) {
  if (rowInherited(row.path)) return;
  const next = new Set(selected.value);
  if (next.has(row.path)) {
    next.delete(row.path);
  } else {
    if (next.size >= 500) {
      $showError(
        new Error("一次最多选择 500 个独立条目；可勾选上层目录合并范围。"),
        false
      );
      return;
    }
    for (const saved of next) {
      if (saved.startsWith(`${row.path}/`)) next.delete(saved);
    }
    next.add(row.path);
  }
  selected.value = next;
}

function toggleSelectAll() {
  selected.value = selected.value.has(".") ? new Set() : new Set(["."]);
}

function rowSelected(path: string) {
  return pathCoveredBySelection(selected.value, path);
}

function rowInherited(path: string) {
  return hasSelectedAncestor(selected.value, path);
}

function taskStatus(status: TaskStatus) {
  const labels: Record<TaskStatus, string> = {
    queued: "排队中",
    running: "正在解压",
    completed: "已完成",
    failed: "解压失败",
    canceled: "已取消",
    interrupted: "已中断",
  };
  return labels[status];
}

function archivePathFromRoute() {
  return typeof route.query.path === "string" ? route.query.path : "";
}

function parentPath(value: string) {
  const parts = value.split("/").filter(Boolean);
  parts.pop();
  return parts.length ? `/${parts.join("/")}` : "/";
}

function filesRoute(value: string, wire?: string) {
  return value === "/" ? "/files/" : `/files${wire || encodePath(value)}/`;
}

function treeRootDirectories(entries: ArchiveListing["entries"]) {
  const roots = new Set<string>();
  for (const entry of entries) {
    const root = entry.path.split("/")[0];
    if (entry.path.includes("/") || entry.isDir) roots.add(root);
  }
  return roots;
}

function collectDirectoryPaths(nodes: ArchiveTreeNode[], paths: Set<string>) {
  for (const node of nodes) {
    if (!node.isDir) continue;
    paths.add(node.path);
    collectDirectoryPaths(node.children, paths);
  }
}

function formatBytes(value: number) {
  return filesize(value || 0);
}

function formatModified(value: number) {
  return dayjs(value).format("YYYY-MM-DD HH:mm");
}

function entryIcon(row: ArchiveTreeRow): AppIconName {
  return getResourceIconName(row.name, row.isDir ? "dir" : "blob", row.isDir);
}

onMounted(loadFromRoute);
onBeforeUnmount(() => {
  disposed = true;
  stopPolling();
});
</script>

<style scoped>
.archive-page {
  min-height: 100vh;
  color: var(--textSecondary);
  background: var(--background);
}
.archive-header-action {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  gap: 7px;
  padding: 0 11px;
  border-radius: 8px;
  color: var(--textSecondary);
  font-size: 12px;
  text-decoration: none;
}
.archive-header-action:hover,
.archive-header-action:focus-visible {
  outline: none;
  background: var(--hover);
}
.archive-header-action:focus-visible {
  box-shadow: inset 0 0 0 2px var(--focus-ring);
}
.archive-workspace {
  box-sizing: border-box;
  width: calc(100% - 32px);
  margin: 0 auto;
  padding: 18px 0 56px;
}
.archive-state,
.archive-warning,
.archive-task,
.archive-result {
  margin-top: 14px;
  border: 1px solid var(--borderPrimary);
  border-radius: 13px;
  background: var(--surfacePrimary);
}
.archive-state {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 17px;
}
.archive-state--empty {
  flex-wrap: wrap;
  align-items: flex-start;
}
.archive-state--empty > div {
  flex: 1 1 calc(100% - 39px);
}
.archive-state > .app-icon {
  color: var(--blue);
  width: 27px;
  height: 27px;
}
.archive-state strong,
.archive-state p,
.archive-state small {
  display: block;
  margin: 0;
}
.archive-state p {
  margin-top: 3px;
  color: var(--textPrimary);
  font-size: 11px;
}
.archive-state small {
  margin-top: 5px;
  color: var(--textPrimary);
  font-size: 10px;
}
.archive-state button,
.archive-state-link {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  justify-content: center;
  padding: 0 13px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  color: var(--textSecondary);
  background: var(--surfaceSecondary);
  cursor: pointer;
}
.archive-state button {
  margin-left: auto;
}
.archive-state-link {
  margin-left: 39px;
}
.archive-state--error > .app-icon {
  color: var(--red);
}
.archive-spin {
  animation: archive-spin 1.4s linear infinite;
}
.archive-overview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-top: 14px;
}
.archive-overview article {
  display: grid;
  gap: 4px;
  padding: 15px 16px;
  border: 1px solid var(--borderPrimary);
  border-radius: 12px;
  background: var(--surfacePrimary);
}
.archive-overview small,
.archive-overview span {
  color: var(--textPrimary);
  font-size: 10px;
}
.archive-overview strong {
  font-size: 21px;
}
.archive-overview article.is-warning strong {
  color: var(--icon-orange);
}
.archive-warning {
  display: flex;
  gap: 11px;
  padding: 13px 15px;
  color: var(--icon-orange);
}
.archive-warning .app-icon {
  width: 23px;
  height: 23px;
}
.archive-warning strong,
.archive-warning p {
  margin: 0;
}
.archive-warning p {
  margin-top: 3px;
  color: var(--textPrimary);
  font-size: 11px;
}
.archive-blocked {
  margin-top: 10px;
  padding: 11px 13px;
  border: 1px solid var(--borderPrimary);
  border-radius: 10px;
  background: var(--surfacePrimary);
  font-size: 11px;
}
.archive-blocked summary {
  cursor: pointer;
  font-weight: 700;
}
.archive-blocked p {
  color: var(--textPrimary);
}
.archive-blocked ul,
.archive-result ul {
  display: grid;
  gap: 5px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}
.archive-blocked li,
.archive-result li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-top: 5px;
  border-top: 1px solid var(--borderPrimary);
}
.archive-blocked li small,
.archive-result li small {
  color: var(--textPrimary);
}
.archive-browser,
.archive-extract-card {
  margin-top: 14px;
  border: 1px solid var(--borderPrimary);
  border-radius: 14px;
  background: var(--surfacePrimary);
  overflow: hidden;
}
.archive-browser-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 16px;
  border-bottom: 1px solid var(--borderPrimary);
}
.archive-browser-toolbar > div:first-child {
  min-width: 0;
}
.archive-section-title {
  display: flex;
  align-items: center;
  gap: 10px;
}
.archive-browser h2,
.archive-section-title h2 {
  margin: 0;
  font-size: 15px;
}
.archive-browser p,
.archive-section-title p {
  margin: 3px 0 0;
  color: var(--textPrimary);
  font-size: 11px;
}
.archive-browser-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.archive-browser-actions label {
  display: flex;
  min-height: 36px;
  align-items: center;
  gap: 6px;
  padding: 0 9px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  background: var(--surfaceSecondary);
}
.archive-browser-actions label .app-icon {
  color: var(--textPrimary);
  width: 17px;
  height: 17px;
}
.archive-browser-actions input {
  width: 170px;
  border: 0;
  outline: 0;
  color: var(--textSecondary);
  background: transparent;
}
.archive-browser-actions button,
.archive-load-more {
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--borderPrimary);
  border-radius: 8px;
  color: var(--blue);
  background: var(--surfacePrimary);
  cursor: pointer;
}
.archive-table-heading,
.archive-entry-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px 150px;
  align-items: center;
}
.archive-table-heading {
  min-height: 34px;
  padding: 0 12px;
  color: var(--textPrimary);
  background: var(--surfaceSecondary);
  font-size: 10px;
  font-weight: 700;
}
.archive-table-heading span:not(:first-child) {
  text-align: right;
}
.archive-entry-row {
  min-height: 52px;
  border-top: 1px solid var(--borderPrimary);
  transition: background-color 120ms ease;
}
.archive-entry-row:hover {
  background: var(--hover);
}
.archive-entry-row.is-selected {
  background: color-mix(in srgb, var(--blue) 5%, var(--surfacePrimary));
}
.archive-entry-row > span,
.archive-entry-row > time {
  padding-right: 12px;
  color: var(--textPrimary);
  font-size: 10px;
  text-align: right;
}
.archive-entry-name {
  display: flex;
  min-width: 0;
  align-items: center;
}
.archive-expand,
.archive-expand-spacer {
  width: 32px;
  flex-shrink: 0;
}
.archive-expand {
  display: grid;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 6px;
  color: var(--textPrimary);
  background: transparent;
  cursor: pointer;
}
.archive-expand:hover {
  background: var(--hover);
}
.archive-expand .app-icon {
  width: 19px;
  height: 19px;
}
.archive-entry-name > label {
  display: grid;
  min-width: 0;
  min-height: 32px;
  flex: 1;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  gap: 9px;
  cursor: pointer;
}
.archive-entry-name input {
  width: 16px;
  height: 16px;
}
.archive-entry-name label > .app-icon {
  color: var(--textPrimary);
  width: 20px;
  height: 20px;
}
.archive-entry-name label > span {
  display: grid;
  min-width: 0;
  gap: 2px;
}
.archive-entry-name strong,
.archive-entry-name small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.archive-entry-name strong {
  font-size: 11px;
}
.archive-entry-name small {
  color: var(--textPrimary);
  font-size: 9px;
}
.archive-empty-filter {
  display: flex;
  min-height: 100px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--textPrimary);
  font-size: 11px;
}
.archive-load-more {
  display: block;
  margin: 12px auto;
}
.archive-extract-card {
  padding: 17px;
  overflow: visible;
}
.archive-destination {
  display: grid;
  gap: 6px;
  margin-top: 14px;
}
.archive-destination > span {
  font-size: 11px;
  font-weight: 700;
}
.archive-destination > div {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 8px;
  padding: 0 11px;
  border: 1px solid var(--borderPrimary);
  border-radius: 9px;
  background: var(--surfaceSecondary);
}
.archive-destination .app-icon {
  color: var(--textPrimary);
  width: 19px;
  height: 19px;
}
.archive-destination input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  color: var(--textSecondary);
  background: transparent;
}
.archive-destination-picker {
  display: inline-flex;
  min-height: 34px;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border: 1px solid var(--borderPrimary);
  border-radius: 7px;
  color: var(--blue);
  background: var(--surfacePrimary);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  white-space: nowrap;
}
.archive-destination-picker:hover,
.archive-destination-picker:focus-visible {
  border-color: var(--blue);
}
.archive-destination small {
  color: var(--textPrimary);
  font-size: 10px;
}
.archive-extract-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  margin-top: 14px;
}
.archive-primary {
  display: inline-flex;
  min-height: 42px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 16px;
  border: 0;
  border-radius: 8px;
  color: white;
  background: var(--blue);
  box-shadow: 0 5px 14px color-mix(in srgb, var(--blue) 24%, transparent);
  cursor: pointer;
  font-weight: 700;
}
.archive-primary:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.archive-task {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 13px;
  padding: 14px 16px;
}
.archive-task-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 11px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 10%, transparent);
}
.archive-task-icon.is-running .app-icon {
  animation: archive-spin 1.4s linear infinite;
}
.archive-task-icon.is-completed {
  color: #16845d;
  background: color-mix(in srgb, #1ea672 10%, transparent);
}
.archive-task-icon.is-failed,
.archive-task-icon.is-canceled,
.archive-task-icon.is-interrupted {
  color: var(--red);
  background: color-mix(in srgb, var(--red) 8%, transparent);
}
.archive-task > div:nth-child(2) {
  display: grid;
  min-width: 0;
  gap: 3px;
}
.archive-task strong {
  font-size: 13px;
}
.archive-task span,
.archive-task small {
  color: var(--textPrimary);
  font-size: 10px;
}
.archive-task-error {
  color: var(--red) !important;
}
.archive-task > button {
  min-height: 36px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  color: var(--textSecondary);
  background: var(--surfaceSecondary);
  cursor: pointer;
}
.archive-progress {
  height: 5px;
  margin: 5px 0 2px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--surfaceSecondary);
}
.archive-progress > div {
  height: 100%;
  border-radius: inherit;
  background: var(--blue);
  transition: width 180ms ease;
}
.archive-result {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: 14px;
  padding: 17px;
}
.archive-result-mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 12px;
  color: #16845d;
  background: color-mix(in srgb, #1ea672 10%, transparent);
}
.archive-result > div:nth-child(2) > small {
  color: #16845d;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.12em;
}
.archive-result h2 {
  margin: 4px 0 0;
  font-size: 15px;
}
.archive-result p {
  margin: 5px 0 0;
  color: var(--textPrimary);
  font-size: 11px;
}
.archive-result-warning {
  color: var(--icon-orange) !important;
}
.archive-result > a {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border-radius: 8px;
  color: var(--blue);
  background: color-mix(in srgb, var(--blue) 8%, var(--surfacePrimary));
  font-size: 11px;
  font-weight: 700;
  text-decoration: none;
}
.archive-result > a .app-icon {
  width: 17px;
  height: 17px;
}
@keyframes archive-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .archive-workspace {
    width: min(100% - 20px, 1120px);
    padding-top: 12px;
  }
  .archive-overview {
    grid-template-columns: 1fr;
  }
  .archive-browser-toolbar,
  .archive-extract-footer {
    align-items: stretch;
    flex-direction: column;
  }
  .archive-browser-actions {
    width: 100%;
  }
  .archive-browser-actions label {
    flex: 1;
  }
  .archive-browser-actions input {
    width: 100%;
  }
  .archive-table-heading,
  .archive-entry-row {
    grid-template-columns: minmax(0, 1fr) 80px;
  }
  .archive-table-heading span:last-child,
  .archive-entry-row > time {
    display: none;
  }
  .archive-primary {
    width: 100%;
  }
  .archive-result {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .archive-result > a {
    grid-column: 2;
    justify-self: start;
  }
}
@media (max-width: 520px) {
  .archive-header-action {
    width: 42px;
    justify-content: center;
    padding: 0;
    font-size: 0;
  }
  .archive-header-action:first-child {
    display: none;
  }
  .archive-browser-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .archive-browser-toolbar {
    padding: 13px;
  }
  .archive-entry-row > span {
    display: none;
  }
  .archive-table-heading,
  .archive-entry-row {
    grid-template-columns: minmax(0, 1fr);
  }
  .archive-table-heading span:not(:first-child) {
    display: none;
  }
  .archive-entry-name {
    padding-right: 8px;
  }
  .archive-task {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .archive-task > button {
    grid-column: 2;
    justify-self: start;
  }
}
@media (prefers-reduced-motion: reduce) {
  .archive-spin,
  .archive-task-icon.is-running .app-icon {
    animation: none;
  }
  .archive-entry-row,
  .archive-progress > div {
    transition: none;
  }
}
</style>
