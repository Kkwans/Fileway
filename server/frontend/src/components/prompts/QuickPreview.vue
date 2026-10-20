<template>
  <div class="card floating quick-preview-card">
    <div class="quick-preview-header">
      <div class="quick-preview-info">
        <AppIcon
          :name="fileIcon"
          class="file-type-icon"
          :class="fileTypeClass"
          :size="24"
        />
        <span class="quick-preview-name">{{ item.name }}</span>
        <span class="quick-preview-meta"
          >{{ humanSize }} · {{ humanTime }}</span
        >
      </div>
      <div class="quick-preview-actions">
        <button
          class="quick-preview-btn"
          type="button"
          :disabled="!canNavigate"
          @click="navigateFile(-1)"
          title="上一个"
          aria-label="上一个"
        >
          <AppIcon name="chevron-left" :size="20" />
        </button>
        <button
          class="quick-preview-btn"
          type="button"
          :disabled="!canNavigate"
          @click="navigateFile(1)"
          title="下一个"
          aria-label="下一个"
        >
          <AppIcon name="chevron-right" :size="20" />
        </button>
        <button
          v-if="authStore.user?.perm.download"
          class="quick-preview-btn"
          type="button"
          @click="downloadFile"
          title="下载"
          aria-label="下载"
        >
          <AppIcon name="download" :size="19" />
        </button>
        <button
          class="quick-preview-btn"
          type="button"
          @click="openFull"
          title="打开文件"
          aria-label="打开文件"
        >
          <AppIcon name="external-link" :size="19" />
        </button>
        <button
          class="quick-preview-btn close-btn"
          type="button"
          @click="close"
          title="关闭"
          aria-label="关闭"
        >
          <AppIcon name="x" :size="20" />
        </button>
      </div>
    </div>
    <div class="quick-preview-body">
      <!-- Image -->
      <img
        v-if="item.type === 'image'"
        ref="nativeImage"
        :key="item.path"
        :src="previewUrl"
        :alt="item.name"
        class="quick-preview-image"
        @load="finishLoading(true)"
        @error="finishLoading(false)"
      />
      <!-- Video -->
      <video
        v-else-if="item.type === 'video'"
        ref="nativeVideo"
        :key="item.path"
        :src="directUrl"
        controls
        autoplay
        class="quick-preview-video"
        :aria-label="`${item.name} 视频预览`"
        @loadeddata="finishLoading(true)"
        @error="finishLoading(false)"
      />
      <!-- Audio -->
      <div v-else-if="item.type === 'audio'" class="quick-preview-audio-wrap">
        <AppIcon name="audio-lines" class="audio-icon" :size="64" />
        <audio
          ref="nativeAudio"
          :key="item.path"
          :src="directUrl"
          controls
          autoplay
          class="quick-preview-audio"
          :aria-label="`${item.name} 音频预览`"
          @loadeddata="finishLoading(true)"
          @error="finishLoading(false)"
        />
      </div>
      <!-- PDF -->
      <iframe
        v-else-if="isPdf"
        :key="item.path"
        :src="directUrl"
        class="quick-preview-pdf"
        :title="`${item.name} PDF 预览`"
        @load="finishLoading(true)"
      />
      <!-- Markdown (rendered) -->
      <div
        v-else-if="isMarkdown"
        class="quick-preview-markdown md_preview"
        ref="markdownBody"
      ></div>
      <!-- Text / Code -->
      <pre
        v-else-if="isText"
        class="quick-preview-text"
      ><code>{{ textContent }}</code></pre>
      <!-- Blob (no preview) -->
      <div v-else class="quick-preview-no-preview">
        <AppIcon name="circle-alert" :size="48" />
        <span>此文件无法预览。</span>
      </div>
    </div>
    <div v-if="loading" class="quick-preview-loading">
      <div class="spinner">
        <div class="bounce1"></div>
        <div class="bounce2"></div>
        <div class="bounce3"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { captureSessionActivity } from "@/utils/auth";
import { useResourceActivity } from "@/utils/sessionActivity";
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { storeToRefs } from "pinia";
import { useLayoutStore } from "@/stores/layout";
import { useFileStore } from "@/stores/file";
import { useRecentStore } from "@/stores/recent";
import { useAuthStore } from "@/stores/auth";
import { files as api } from "@/api";
import type { ResourceItem } from "@/types/file";
import { filesize } from "@/utils";
import AppIcon from "@/components/ui/AppIcon.vue";
import type { AppIconName } from "@/components/ui/iconRegistry";
import { getResourceIconName, isTextFile } from "@/utils/fileIcons";
import {
  findAdjacentQuickPreviewItem,
  getQuickPreviewItems,
} from "@/utils/quickPreview";
import {
  loadMarkdownResources,
  highlightAndAnnotateCodeBlocks,
  getVditorAssetRoot,
} from "@/utils/externalResources";
import dayjs from "@/utils/date";
const router = useRouter();

const fileStore = useFileStore();
const layoutStore = useLayoutStore();
const recentStore = useRecentStore();
const authStore = useAuthStore();
const { closeHovers } = layoutStore;
const { currentPrompt } = storeToRefs(layoutStore);

const loading = ref(true);
const textContent = ref("");
const markdownBody = ref<HTMLElement | null>(null);
const nativeImage = ref<HTMLImageElement | null>(null);
const nativeVideo = ref<HTMLVideoElement | null>(null);
const nativeAudio = ref<HTMLAudioElement | null>(null);
const recordedPath = ref("");
let contentController: AbortController | null = null;

const item = computed(() => currentPrompt.value?.props?.item || {});
useResourceActivity(nativeImage, () => item.value.path);
useResourceActivity(nativeVideo, () => item.value.path);
useResourceActivity(nativeAudio, () => item.value.path);
const listingItems = computed<ResourceItem[]>(
  () => currentPrompt.value?.props?.items || fileStore.req?.items || []
);
const canNavigate = computed(
  () => getQuickPreviewItems(listingItems.value).length > 1
);

const humanSize = computed(() => filesize(item.value.size || 0));
const humanTime = computed(() => dayjs(item.value.modified).fromNow());

const isPdf = computed(() => item.value.extension?.toLowerCase() === ".pdf");
const isMarkdown = computed(() => {
  const ext = item.value.extension?.toLowerCase() || "";
  return ext === ".md" || ext === ".markdown";
});
const isText = computed(() =>
  isTextFile(item.value.type || "", item.value.extension)
);

const fileIcon = computed<AppIconName>(() =>
  getResourceIconName(item.value.name || "", item.value.type || "blob")
);

const fileTypeClass = computed(() => {
  const classes: Record<string, string> = {
    image: "type-image",
    video: "type-video",
    audio: "type-audio",
    pdf: "type-pdf",
    text: "type-text",
    textImmutable: "type-text",
  };
  return classes[item.value.type] || "type-blob";
});

const previewUrl = computed(() => {
  if (item.value.type === "image") {
    return api.getPreviewURL(item.value, "big");
  }
  return api.getDownloadURL(item.value, true);
});

const directUrl = computed(() => api.getDownloadURL(item.value, true));
const recordSuccessfulPreview = async (
  path = item.value.path || "",
  wirePath = item.value.wirePath
) => {
  const identity = wirePath || path;
  if (!path || recordedPath.value === identity) return;
  try {
    await recentStore.record(path, wirePath);
    recordedPath.value = identity;
  } catch (error) {
    console.warn("无法记录快捷预览", error);
  }
};

const finishLoading = (successful: boolean) => {
  loading.value = false;
  if (successful) void recordSuccessfulPreview();
};

onMounted(() => {
  window.addEventListener("keydown", handleKeydown);
  activateCurrentItem();
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", handleKeydown);
  contentController?.abort();
});

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    event.preventDefault();
    closeHovers();
    return;
  }
  if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
    event.preventDefault();
    navigateFile(event.key === "ArrowRight" ? 1 : -1);
  }
};

const navigateFile = (direction: -1 | 1) => {
  const target = findAdjacentQuickPreviewItem(
    listingItems.value,
    item.value.path || "",
    direction
  );
  if (!target || !currentPrompt.value?.props) return;
  currentPrompt.value.props.item = target;
  fileStore.selectOnly(target.path);
  activateCurrentItem();
};

const close = () => closeHovers();

const downloadFile = () => {
  const a = document.createElement("a");
  a.href = directUrl.value;
  a.download = item.value.name || "";
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
};

const openFull = async () => {
  const target = item.value.url;
  if (!target) return;
  closeHovers();
  await router.push({ path: target });
};

const activateCurrentItem = () => {
  contentController?.abort();
  contentController = null;
  loading.value = true;
  textContent.value = "";
  if (markdownBody.value) markdownBody.value.replaceChildren();
  if (isMarkdown.value) {
    void loadMarkdownContent();
  } else if (isText.value) {
    void loadTextContent();
  } else if (item.value.type === "blob" || item.value.type === "invalid_link") {
    loading.value = false;
  }
};

const loadTextContent = async () => {
  const controller = new AbortController();
  contentController = controller;
  const source = directUrl.value;
  const activity = captureSessionActivity();
  const path = item.value.path || "";
  const wirePath = item.value.wirePath;
  try {
    const resp = await fetch(source, {
      credentials: "include",
      signal: controller.signal,
    });
    if (resp.ok) await activity.response(resp.headers.get("X-Renew-Token"));
    if (!resp.ok) throw new Error(`无法加载预览（HTTP ${resp.status}）`);
    const text = await resp.text();
    if (controller.signal.aborted) return;
    textContent.value =
      text.length > 51200
        ? text.substring(0, 51200) + "\n\n... " + "文件过大"
        : text;
    await recordSuccessfulPreview(path, wirePath);
  } catch (error) {
    if (controller.signal.aborted) return;
    textContent.value =
      error instanceof Error ? error.message : "无法加载预览内容";
  } finally {
    if (contentController === controller) loading.value = false;
  }
};

const loadMarkdownContent = async () => {
  const controller = new AbortController();
  contentController = controller;
  const source = directUrl.value;
  const activity = captureSessionActivity();
  const path = item.value.path || "";
  const wirePath = item.value.wirePath;
  try {
    const resp = await fetch(source, {
      credentials: "include",
      signal: controller.signal,
    });
    if (resp.ok) await activity.response(resp.headers.get("X-Renew-Token"));
    if (!resp.ok) throw new Error(`无法加载预览（HTTP ${resp.status}）`);
    const text = await resp.text();
    if (controller.signal.aborted) return;
    const truncated =
      text.length > 51200
        ? text.substring(0, 51200) + "\n\n... " + "文件过大"
        : text;
    await renderMarkdown(truncated, controller.signal);
    if (controller.signal.aborted) return;
    await recordSuccessfulPreview(path, wirePath);
  } catch (error) {
    if (controller.signal.aborted) return;
    textContent.value =
      error instanceof Error ? error.message : "无法加载预览内容";
    nextTick(() => {
      if (markdownBody.value) {
        markdownBody.value.textContent = textContent.value;
      }
    });
  } finally {
    if (contentController === controller) loading.value = false;
  }
};

const renderMarkdown = async (content: string, signal: AbortSignal) => {
  await loadMarkdownResources();
  if (signal.aborted) return;

  const VditorClass = (window as any).Vditor;
  const isDark = document.documentElement.className === "dark";
  const htmlResult = VditorClass.md2html(content, {
    cdn: getVditorAssetRoot(),
    mode: isDark ? "dark" : "light",
  });
  const html = await Promise.resolve(htmlResult);
  if (signal.aborted) return;

  if (typeof html === "string" && markdownBody.value) {
    markdownBody.value.innerHTML = html;
    highlightAndAnnotateCodeBlocks(markdownBody.value);
  }
};
</script>
