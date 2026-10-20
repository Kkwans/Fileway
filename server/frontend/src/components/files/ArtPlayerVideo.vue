<template>
  <div
    class="art-player-stage"
    :class="{
      'art-player-stage--busy': busy,
      'art-player-stage--asking': askVisible,
      'art-player-stage--portrait': isPortrait,
      'art-player-stage--mobile': isMobile,
      'art-player-stage--rate-open': rateDialogVisible,
      'art-player-stage--timeline':
        actualMode === 'compat' && sourceDuration > 0,
    }"
  >
    <div ref="container" class="art-player-box"></div>
    <Teleport
      v-if="playerRoot && actualMode === 'compat' && sourceDuration > 0"
      :to="playerRoot"
    >
      <CompatibilityTimeline
        :duration="sourceDuration"
        :position="timelinePosition"
        :available-start="compatOffset"
        :available-end="timelineAvailableEnd"
        :busy="busy"
        :sprite="timelineSprite"
        :transcoded-end="backgroundPlayableEnd"
        @seek="seekTo"
      />
    </Teleport>

    <div v-if="loadingVisible" class="art-loading" role="status">
      <div class="art-ring" aria-hidden="true"></div>
      <div class="art-loading-text">
        <strong>{{ loadingTitle }}</strong>
        <span v-if="progressLabel">{{ progressLabel }}</span>
      </div>
    </div>

    <div v-if="playbackError" class="art-ask-overlay" role="alert">
      <div class="art-ask-card">
        <div class="art-ask-title">视频暂时无法播放</div>
        <p class="art-ask-sub">{{ playbackError }}</p>
        <div class="art-ask-actions">
          <button
            type="button"
            class="art-ask-btn art-ask-btn--primary"
            @click="
              switchEngine(actualMode, undefined, false, timelinePosition)
            "
          >
            重试播放
          </button>
          <a class="art-ask-btn" :href="downloadUrl" download>下载原文件</a>
        </div>
      </div>
    </div>

    <div v-if="askVisible" class="art-ask-overlay">
      <div class="art-ask-card">
        <div class="art-ask-title">选择播放方式</div>
        <div class="art-ask-sub">默认策略为「每次询问」；选择后立即生效</div>
        <div class="art-ask-actions">
          <button
            type="button"
            class="art-ask-btn"
            @click="chooseMode('native')"
          >
            原生播放
          </button>
          <button
            type="button"
            class="art-ask-btn art-ask-btn--primary"
            :disabled="busy"
            @click="chooseMode('compat')"
          >
            兼容转码
          </button>
          <a class="art-ask-btn" :href="downloadUrl" download>下载</a>
        </div>
      </div>
    </div>

    <PathPicker
      v-if="subtitlePickerOpen"
      title="选择外挂字幕文件"
      mode="file"
      :model-value="videoDirPath"
      :model-wire-path="videoDirWire"
      wire-paths
      :file-extensions="SUBTITLE_EXTS"
      @select="onPickSubtitleFile"
      @close="subtitlePickerOpen = false"
    />

    <!-- Custom rate dialog: teleported into ArtPlayer so it works in fullscreen -->
    <Teleport v-if="rateDialogVisible && playerRoot" :to="playerRoot">
      <div class="art-modal-mask" @click.self="rateDialogVisible = false">
        <div class="art-modal" role="dialog" aria-label="自定义倍速">
          <div class="art-modal-title">自定义倍速</div>
          <div class="art-modal-sub">范围 0.10x – 5.00x，支持两位小数</div>
          <div class="art-modal-field">
            <input
              ref="rateInput"
              v-model.number="rateDraft"
              type="number"
              min="0.1"
              max="5"
              step="0.01"
              inputmode="decimal"
            />
            <span class="art-modal-unit">x</span>
          </div>
          <input
            v-model.number="rateDraft"
            class="art-modal-range"
            type="range"
            min="0.1"
            max="5"
            step="0.01"
          />
          <div class="art-modal-actions">
            <button
              type="button"
              class="art-modal-btn"
              @click="rateDialogVisible = false"
            >
              取消
            </button>
            <button
              type="button"
              class="art-modal-btn art-modal-btn--ok"
              @click="confirmRate"
            >
              应用
            </button>
          </div>
        </div>
      </div>
    </Teleport>
    <div
      v-else-if="rateDialogVisible"
      class="art-modal-mask"
      @click.self="rateDialogVisible = false"
    >
      <div class="art-modal" role="dialog" aria-label="自定义倍速">
        <div class="art-modal-title">自定义倍速</div>
        <div class="art-modal-sub">范围 0.10x – 5.00x，支持两位小数</div>
        <div class="art-modal-field">
          <input
            ref="rateInput"
            v-model.number="rateDraft"
            type="number"
            min="0.1"
            max="5"
            step="0.01"
            inputmode="decimal"
          />
          <span class="art-modal-unit">x</span>
        </div>
        <input
          v-model.number="rateDraft"
          class="art-modal-range"
          type="range"
          min="0.1"
          max="5"
          step="0.01"
        />
        <div class="art-modal-actions">
          <button
            type="button"
            class="art-modal-btn"
            @click="rateDialogVisible = false"
          >
            取消
          </button>
          <button
            type="button"
            class="art-modal-btn art-modal-btn--ok"
            @click="confirmRate"
          >
            应用
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { captureSessionActivity } from "@/utils/auth";
import { bindResourceActivity, bindXHRActivity } from "@/utils/sessionActivity";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from "vue";
import Artplayer from "artplayer";
import Hls from "hls.js";
import { files as api, media as mediaApi, users as usersApi } from "@/api";
import { decodePath, resolveBackendMediaURL } from "@/utils/url";
import { mediaResourceURL } from "@/utils/mediaResource";
import {
  favoriteIdentity,
  favoriteWirePath,
} from "@/utils/favoritePersistence";
import { baseURL } from "@/utils/constants";
import { useAuthStore } from "@/stores/auth";
import { useAccountPreferencesStore } from "@/stores/accountPreferences";
import { resolveControlsTimeoutMs } from "@/utils/playerControls";
import {
  getNativeContainerPlayback,
  supportsH264CompatibilityPlayback,
} from "@/utils/videoPlayback";
import type { MediaTrack } from "@/api/media";
import {
  videoClassHeight,
  videoResolutionLabel,
} from "@/utils/videoResolution";
import PathPicker from "@/components/prompts/PathPicker.vue";
import CompatibilityTimeline from "./CompatibilityTimeline.vue";

type Policy = "native" | "compat" | "ask";
type ActualMode = "native" | "compat";
type Quality =
  | "source"
  | "2160p"
  | "1440p"
  | "1080p"
  | "720p"
  | "480p"
  | "native";

const PRESET_RATES = [0.25, 0.5, 1, 1.25, 1.5, 2];

const props = defineProps<{
  path: string;
  wirePath?: string;
  source: string;
  poster?: string;
  downloadSource?: string;
  subtitles?: { url: string; lang?: string; name?: string }[];
  transcodeTaskId?: string;
}>();

const mediaPath = computed(() => ({
  path: props.path,
  wirePath: props.wirePath,
}));
let unsupportedSaveNoticeShown = false;

const authStore = useAuthStore();
const accountPreferences = useAccountPreferencesStore();
const container = ref<HTMLElement | null>(null);
const art = shallowRef<Artplayer | null>(null);
const busy = ref(false);
const playbackError = ref("");
const askVisible = ref(false);
const actualMode = ref<ActualMode>("native");
const currentRate = ref(1);
const rateDraft = ref(1);
const rateDialogVisible = ref(false);
const rateInput = ref<HTMLInputElement | null>(null);
const sourceWidth = ref(0);
const sourceHeight = ref(0);
const sourceDuration = ref(0);
const compatOffset = ref(0);
const timelinePosition = ref(0);
const timelineAvailableEnd = ref(0);
const timelineSprite = ref<mediaApi.VideoSprite>();
const backgroundPlayableEnd = ref(0);
let backgroundPollTimer: number | undefined;
let backgroundDetached = false;
let backgroundQualityApplied = false;
const playbackSession = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
let activeCompatCacheID = "";
let compatWindowSeconds = 0;
let resumePromptShown = false;
type PrefetchedWindow = {
  start: number;
  signature: string;
  adopted: boolean;
  status?: mediaApi.HLSPlaybackStatus;
  request: Promise<mediaApi.HLSPlaybackStatus | undefined>;
};
let prefetchedWindow: PrefetchedWindow | null = null;
let attemptedPrefetchStart = -1;
let pendingSeek: number | null = null;
const sourceVideoCodec = ref("");
const sourceVideoBitDepth = ref(0);
const sourceAudioCodec = ref("");
const embeddedSubtitleTracks = ref<MediaTrack[]>([]);
const embeddedSubtitleIndex = ref<number | null>(null);
const embeddedAudioTracks = ref<MediaTrack[]>([]);
const embeddedAudioIndex = ref<number | null>(null);
const playerRoot = ref<HTMLElement | null>(null);
const subtitlePickerOpen = ref(false);
const extraSubtitles = ref<{ url: string; name: string; path?: string }[]>([]);

const SUBTITLE_EXTS = ["srt", "ass", "ssa", "vtt"];
const SUBTITLE_EXT = /\.(srt|ass|ssa|vtt)$/i;
const EMBEDDED_TEXT_SUBTITLE_CODECS = new Set([
  "subrip",
  "ass",
  "ssa",
  "webvtt",
  "mov_text",
  "text",
]);
/** History must be at least this long before resume UI is offered (account-configurable). */
function accountResumeMinSec(): number {
  const raw = Number(authStore.user?.playerPreferences?.resumeMinSec);
  if (!Number.isFinite(raw) || raw <= 0) return 10;
  return Math.min(600, Math.max(5, Math.round(raw)));
}

const videoDirPath = computed(() => {
  const p = props.path || "";
  const i = p.lastIndexOf("/");
  return i <= 0 ? "/" : `${p.slice(0, i)}/`;
});
const videoDirWire = computed(() => {
  const wire = favoriteWirePath(mediaPath.value);
  if (!wire) return undefined;
  const separator = wire.lastIndexOf("/");
  return separator <= 0 ? "/" : wire.slice(0, separator) + "/";
});

const allSubtitleItems = computed(() => {
  const embedded = embeddedSubtitleTracks.value
    .filter(
      (track) =>
        track.codec === "hdmv_pgs_subtitle" ||
        EMBEDDED_TEXT_SUBTITLE_CODECS.has(track.codec)
    )
    .map((track) => ({
      url:
        track.codec === "hdmv_pgs_subtitle"
          ? embeddedSubtitleValue(track.index)
          : mediaResourceURL("api/subtitle", mediaPath.value, {
              inline: "true",
              streamIndex: String(track.index),
            }),
      name: `内挂 · ${track.title || track.language || `字幕 ${track.index}`} (${track.codec === "hdmv_pgs_subtitle" ? "PGS" : track.codec === "subrip" ? "SRT" : track.codec.toUpperCase()})`,
      path: undefined as string | undefined,
    }));
  const base = (props.subtitles || []).map((s) => ({
    url: s.url,
    name: s.name || s.lang || "字幕",
    path: undefined as string | undefined,
  }));
  return [...embedded, ...base, ...extraSubtitles.value];
});

function embeddedSubtitleValue(index: number) {
  return `embedded:${encodeURIComponent(favoriteIdentity(mediaPath.value) || "")}:${index}`;
}

async function scanSiblingSubtitles() {
  try {
    if (!videoDirWire.value) return;
    const res = await api.fetch("/files" + videoDirWire.value);
    const items = (res?.items || []) as Array<{
      name: string;
      path?: string;
      wirePath?: string;
      url?: string;
      isDir?: boolean;
    }>;
    const found = items
      .filter((it) => !it.isDir && SUBTITLE_EXT.test(it.name || ""))
      .map((it) => {
        const path = it.path || it.url || `${videoDirPath.value}${it.name}`;
        return {
          path,
          name: it.name,
          url: mediaResourceURL(
            "api/subtitle",
            { path, wirePath: it.wirePath },
            { inline: "true" }
          ),
        };
      });
    const known = new Set(allSubtitleItems.value.map((s) => s.url));
    extraSubtitles.value = [
      ...extraSubtitles.value.filter((s) => found.some((f) => f.url === s.url)),
      ...found.filter((f) => !known.has(f.url)),
    ];
    refreshSubtitlePicker();
  } catch {
    /* optional */
  }
}

function onPickSubtitleFile(path: string | string[]) {
  const raw = Array.isArray(path) ? path[0] : path;
  subtitlePickerOpen.value = false;
  if (!raw) return;
  if (!SUBTITLE_EXT.test(raw)) {
    notice("请选择 srt / ass / vtt 等字幕文件");
    return;
  }
  const name = raw.split("/").pop() || "外挂字幕";
  const url = mediaResourceURL(
    "api/subtitle",
    { path: decodePath(raw), wirePath: raw },
    { inline: "true" }
  );
  if (!extraSubtitles.value.some((s) => s.url === url)) {
    extraSubtitles.value = [...extraSubtitles.value, { url, name, path: raw }];
  }
  refreshSubtitlePicker();
  switchSubtitle({ html: name, value: url, name });
  notice(`已加载字幕 ${name}`);
}
const transcodeQuality = ref<Quality>("source");
const loadProgress = ref<number | null>(null);
const loadStatusText = ref("");
const loadWaitSec = ref(0);
let loadWaitTimer: number | null = null;

function startLoadWaitTimer() {
  if (loadWaitTimer) return;
  loadWaitTimer = window.setInterval(() => {
    if (!loadingVisible.value) {
      loadWaitSec.value = 0;
      return;
    }
    loadWaitSec.value += 1;
  }, 1000);
}

function stopLoadWaitTimer() {
  if (loadWaitTimer) {
    window.clearInterval(loadWaitTimer);
    loadWaitTimer = null;
  }
  loadWaitSec.value = 0;
}
const videoPlaying = ref(false);
const nativeLoaderForced = ref(false);
const isPortrait = ref(false);
const isMobile = ref(false);
let hlsInstance: Hls | null = null;
let progressTimer: number | null = null;
let loaderForceTimer: number | null = null;
let switchToken = 0;
let switchingEngine = false;
let lastSavedPosition = 0;
let lastSaveAt = 0;
let nativeLoadHandlers: Array<() => void> = [];
let sizeHandler: (() => void) | null = null;

type SettingRow = {
  tooltip?: string;
  $item?: HTMLElement;
  selector?: Array<{ name?: string; html?: string; value?: string }>;
};

type SettingApi = {
  show?: boolean;
  find?: (name: string) => SettingRow | null | undefined;
  check?: (item: unknown) => void;
  resize?: () => void;
  add?: (o: Record<string, unknown>) => void;
};

const policy = computed<Policy>(() => {
  const raw = (
    authStore.user?.playerPreferences?.playbackMode || "native"
  ).toLowerCase();
  if (raw === "compat") return "compat";
  if (raw === "ask") return "ask";
  return "native";
});

const downloadUrl = computed(
  () => props.downloadSource || api.getDownloadURL(mediaPath.value, false)
);

const resolutionLabel = computed(() => {
  return videoResolutionLabel(sourceWidth.value, sourceHeight.value);
});

/**
 * Native mode cannot re-encode: only show the source resolution.
 * Compat mode (ffmpeg HLS) can go down from source.
 */
const qualityOptions = computed(() => {
  if (actualMode.value !== "compat") {
    return [{ html: resolutionLabel.value, value: "native" }];
  }
  const h = videoClassHeight(sourceWidth.value, sourceHeight.value);
  const opts: { html: string; value: Quality }[] = [
    {
      html: h ? `原画 ${resolutionLabel.value}` : "原画",
      value: "source",
    },
  ];
  const caps: Array<{ value: Quality; minH: number; html: string }> = [
    { value: "2160p", minH: 2000, html: "4K" },
    { value: "1440p", minH: 1300, html: "2K" },
    { value: "1080p", minH: 900, html: "1080p" },
    { value: "720p", minH: 600, html: "720p" },
    { value: "480p", minH: 400, html: "480p" },
  ];
  for (const cap of caps) {
    if (!h || h >= cap.minH) opts.push({ html: cap.html, value: cap.value });
  }
  if (!opts.some((o) => o.value === "480p")) {
    opts.push({ html: "480p", value: "480p" });
  }
  return opts;
});

function preferredCompatQuality(): Exclude<Quality, "native"> {
  if (
    transcodeQuality.value !== "source" &&
    transcodeQuality.value !== "native"
  ) {
    return transcodeQuality.value;
  }
  if (sourceVideoCodec.value === "h264" && supportsH264CompatibilityPlayback())
    return "source";
  const h = sourceHeight.value || 0;
  // Auto-compat defaults cap at 1080p to avoid surprise 4K ffmpeg cost.
  if (h >= 1300) return "1080p";
  if (h >= 900) return "1080p";
  if (h >= 600) return "720p";
  return "480p";
}

const actualModeLabel = computed(() =>
  actualMode.value === "compat" ? "兼容" : "原生"
);

const loadingTitle = computed(() =>
  actualMode.value === "compat" ? "兼容播放加载中" : "原生播放加载中"
);

const progressLabel = computed(() => {
  const wait = loadWaitSec.value > 0 ? ` · ${loadWaitSec.value}s` : "";
  if (loadStatusText.value) return `${loadStatusText.value}${wait}`;
  if (loadProgress.value == null) {
    return loadWaitSec.value > 0 ? `加载中${wait}` : "";
  }
  return `${Math.min(100, Math.max(0, Math.round(loadProgress.value)))}%${wait}`;
});

/** Reactive media readiness — HTMLMediaElement fields are not Vue-tracked. */
const mediaUiReady = ref(false);

function nativeBufferPercent(video?: HTMLVideoElement | null): number | null {
  if (!video || !video.duration || !Number.isFinite(video.duration))
    return null;
  try {
    if (!video.buffered || video.buffered.length === 0) return null;
    const end = video.buffered.end(video.buffered.length - 1);
    return Math.min(99, (end / video.duration) * 100);
  } catch {
    return null;
  }
}

/** Pull loader/UI state from the real <video> element (events + poll). */
function refreshMediaUiState(reason = "") {
  const video = art.value?.video as HTMLVideoElement | undefined;
  if (!video) return;
  const playing = !video.paused && !video.ended && video.currentTime > 0.02;
  const hasFrame = videoHasFrame(video);
  const ready = (video.readyState >= 2 && hasFrame) || playing;
  if (ready && !video.error) {
    mediaUiReady.value = true;
    videoPlaying.value = playing || videoPlaying.value;
    clearLoadingState();
    return;
  }
  if (video.error) {
    mediaUiReady.value = false;
    clearLoadingState({ force: true });
    return;
  }
  // Still loading native — show honest buffer progress when available.
  if (actualMode.value === "native" && !videoPlaying.value) {
    const pct = nativeBufferPercent(video);
    if (pct != null && pct > 0) {
      loadStatusText.value = "";
      loadProgress.value = pct;
    } else if (
      video.readyState <= 1 ||
      (video.readyState >= 2 && !videoHasFrame(video))
    ) {
      loadStatusText.value = "原生加载中…（缓冲/探测）";
      loadProgress.value = null;
    }
  }
  void reason;
}

const loadingVisible = computed(() => {
  if (playbackError.value) return false;
  if (askVisible.value) return false;
  if (mediaUiReady.value) return false;
  const video = art.value?.video as HTMLVideoElement | undefined;
  if (video && video.readyState >= 2 && videoHasFrame(video) && !video.error) {
    return false;
  }
  if (busy.value || nativeLoaderForced.value) return true;
  return loadProgress.value != null && loadProgress.value < 100;
});

function rawUrl() {
  return props.source || api.getDownloadURL(mediaPath.value, true);
}

function clampRate(n: number) {
  if (!Number.isFinite(n)) return 1;
  return Math.min(5, Math.max(0.1, Math.round(n * 100) / 100));
}

function accountRate() {
  return clampRate(authStore.user?.playerPreferences?.playbackRate ?? 1);
}

function persistRate(rate: number) {
  if (!authStore.user?.id) return;
  const prev = authStore.user?.playerPreferences || {};
  const next = {
    ...prev,
    controlsTimeoutSec: prev.controlsTimeoutSec ?? 4,
    playbackMode: prev.playbackMode || "native",
    playbackRate: rate,
    resumeMode: prev.resumeMode || "resume",
    resumeMinSec: prev.resumeMinSec ?? 10,
  };
  void accountPreferences.save({ playerPreferences: next }).catch(() => {
    notice("倍速偏好保存失败");
  });
}

/** Persist session engine choice as account default (spread — never drop fields). */
function persistPlaybackMode(mode: ActualMode | "ask") {
  if (!authStore.user?.id) return;
  const prev = authStore.user?.playerPreferences || {};
  const next = {
    ...prev,
    controlsTimeoutSec: prev.controlsTimeoutSec ?? 4,
    playbackMode: mode,
    playbackRate: prev.playbackRate ?? 1,
    resumeMode: prev.resumeMode || "resume",
    resumeMinSec: prev.resumeMinSec ?? 10,
  };
  void accountPreferences.save({ playerPreferences: next }).catch(() => {
    notice("播放方式偏好保存失败");
  });
}

function accountResumeMode(): "resume" | "from-start" | "ask" {
  const raw = (
    authStore.user?.playerPreferences?.resumeMode || "resume"
  ).toLowerCase();
  if (raw === "from-start" || raw === "start" || raw === "restart")
    return "from-start";
  if (raw === "ask" || raw === "prompt") return "ask";
  return "resume";
}

function formatClock(sec: number) {
  if (!Number.isFinite(sec) || sec < 0) return "0:00";
  const s = Math.floor(sec % 60);
  const m = Math.floor((sec / 60) % 60);
  const h = Math.floor(sec / 3600);
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Container support — Matroska/legacy containers are not browser-native. */
function browserSupportsContainer(path: string): boolean | null {
  if (
    getNativeContainerPlayback(
      path,
      sourceVideoCodec.value,
      sourceAudioCodec.value,
      sourceVideoBitDepth.value
    ) === "supported"
  )
    return true;
  const ext = pathExt(path);
  if (!ext) return null;
  // Unsupported or unconfirmed track/container combinations use compatibility.
  if (
    ["mkv", "mk3d", "mka", "flv", "f4v", "rm", "rmvb", "wmv", "avi"].includes(
      ext
    )
  ) {
    return false;
  }
  const probe = document.createElement("video");
  if (ext === "mp4" || ext === "m4v" || ext === "mov") {
    return probe.canPlayType("video/mp4") !== "";
  }
  if (ext === "webm") return probe.canPlayType("video/webm") !== "";
  return null;
}

function pathExt(path: string) {
  return (path.split(".").pop() || "").toLowerCase();
}

function videoHasFrame(video?: HTMLVideoElement | null) {
  return !!video && ((video.videoWidth || 0) > 0 || video.currentTime > 0.05);
}

function playerFeedbackRail(player: HTMLElement) {
  let rail = player.querySelector<HTMLDivElement>(".nfb-player-feedback");
  if (!rail) {
    rail = document.createElement("div");
    rail.className = "nfb-player-feedback";
    player.appendChild(rail);
  }
  const operationNotice = player.querySelector<HTMLElement>(".art-notice");
  if (operationNotice && operationNotice.parentElement !== rail) {
    operationNotice.setAttribute("role", "status");
    operationNotice.setAttribute("aria-live", "polite");
    rail.appendChild(operationNotice);
  }
  return rail;
}

/** Official ArtPlayer auto-playback chrome — reuse DOM/classes/icons from artplayer.org. */
function injectResumeToast(
  position: number,
  mode: "resume" | "from-start" | "ask"
) {
  const tryMount = (attempt: number) => {
    const t = art.value?.template as unknown as {
      $player?: HTMLElement;
    } | null;
    const stage = document.querySelector(
      ".art-player-stage"
    ) as HTMLElement | null;
    const player = (t?.$player as HTMLElement | null) || stage;
    if (!player) {
      if (attempt < 10) window.setTimeout(() => tryMount(attempt + 1), 150);
      return;
    }
    player.querySelector(".art-layer-auto-playback")?.remove();
    player.querySelector(".nfb-resume-toast")?.remove();

    const label = formatClock(position);
    const isResume = mode === "resume";
    const el = document.createElement("div");
    // Official ArtPlayer layer class + our handle for QA
    el.className = "art-layer-auto-playback nfb-resume-toast";
    el.style.display = "flex";
    el.innerHTML = `
      <div class="art-auto-playback-close" data-act="close" role="button" aria-label="关闭"></div>
      <div class="art-auto-playback-last"></div>
      <div class="art-auto-playback-jump" data-act="${isResume ? "restart" : "resume"}" role="button"></div>
    `;
    const closeEl = el.querySelector(".art-auto-playback-close") as HTMLElement;
    const lastEl = el.querySelector(".art-auto-playback-last") as HTMLElement;
    const jumpEl = el.querySelector(".art-auto-playback-jump") as HTMLElement;
    const closeIcon = iconClone("close");
    if (closeIcon) closeEl.appendChild(closeIcon);
    else closeEl.textContent = "×";
    // Short copy: 将从 mm:ss 继续播放 + one action
    lastEl.textContent = `将从 ${label} 继续播放`;
    jumpEl.textContent = isResume ? "从头播放" : "跳转播放";

    let hideTimer = 0;
    const dismiss = () => {
      window.clearTimeout(hideTimer);
      el.remove();
    };
    const armAutoHide = () => {
      window.clearTimeout(hideTimer);
      hideTimer = window.setTimeout(dismiss, 6000);
    };
    armAutoHide();

    el.addEventListener("click", (event) => {
      const target = event.target as HTMLElement | null;
      const host = target?.closest("[data-act]") as HTMLElement | null;
      const act = host?.dataset?.act;
      const video = art.value?.video as HTMLVideoElement | undefined;
      if (act === "resume") {
        if (video) {
          seekTo(position);
          void video.play?.().catch(() => {});
        }
        dismiss();
      } else if (act === "restart") {
        if (video) {
          seekTo(0);
          void video.play?.().catch(() => {});
        }
        void mediaApi.clearPlayback(mediaPath.value).catch(() => {});
        dismiss();
      } else if (act === "close") {
        dismiss();
      }
    });
    playerFeedbackRail(player).prepend(el);
  };
  tryMount(0);
}

/** Account policy drives seek; every mode must show official-style feedback when history ≥ 30s. */
function showResumeUi(position: number) {
  if (!Number.isFinite(position) || position < accountResumeMinSec()) return;
  seedArtPlayerResume(position);
  lastSavedPosition = position;
  const mode = accountResumeMode();
  const label = formatClock(position);
  if (mode === "resume") {
    applyResume({ position, playing: true, rate: currentRate.value });
    notice(`将从 ${label} 继续播放`);
  } else if (mode !== "ask") {
    notice(`将从 ${label} 继续播放`);
  }
  window.setTimeout(() => injectResumeToast(position, mode), 80);
}

function notice(msg: string) {
  art.value && (art.value.notice.show = msg);
}

function artSetting(): SettingApi | null {
  return (
    ((art.value as unknown as { setting?: SettingApi })
      ?.setting as SettingApi) ?? null
  );
}

/** Official ArtPlayer SVG icons only — never Material ligature text in the player. */
function iconClone(key: string): HTMLElement | undefined {
  const icons = (art.value as unknown as { icons?: Record<string, unknown> })
    ?.icons;
  const el = icons?.[key];
  if (el instanceof HTMLElement) return el.cloneNode(true) as HTMLElement;
  return undefined;
}

function settingWidth() {
  const ctor = (
    art.value as unknown as {
      constructor?: { SETTING_ITEM_WIDTH?: number };
    }
  )?.constructor;
  return ctor?.SETTING_ITEM_WIDTH ?? 250;
}

function forceHideArtLoading() {
  try {
    const loading = (art.value as unknown as { loading?: { show: boolean } })
      ?.loading;
    if (loading) loading.show = false;
  } catch {
    /* ignore */
  }
}

function clearLoadingState(options?: { force?: boolean }) {
  const video = art.value?.video as HTMLVideoElement | undefined;
  const resetLoader = () => {
    videoPlaying.value = false;
    mediaUiReady.value = false;
    loadProgress.value = null;
    loadStatusText.value = "";
    nativeLoaderForced.value = false;
    busy.value = false;
    forceHideArtLoading();
    if (progressTimer) {
      window.clearInterval(progressTimer);
      progressTimer = null;
    }
    if (loaderForceTimer) {
      window.clearTimeout(loaderForceTimer);
      loaderForceTimer = null;
    }
  };
  if (options?.force || video?.error) {
    if (!options?.force && video?.error) {
      videoPlaying.value = false;
    }
    if (options?.force) resetLoader();
    else {
      loadProgress.value = null;
      loadStatusText.value = "";
      nativeLoaderForced.value = false;
      busy.value = false;
      forceHideArtLoading();
      if (loaderForceTimer) {
        window.clearTimeout(loaderForceTimer);
        loaderForceTimer = null;
      }
    }
    return;
  }
  const mediaReady =
    !!video &&
    ((video.readyState >= 2 && videoHasFrame(video)) ||
      (!video.paused && video.currentTime > 0.05 && !video.ended));
  if (!mediaReady) return;
  mediaUiReady.value = true;
  videoPlaying.value = !video.paused && !video.ended;
  loadProgress.value = null;
  loadStatusText.value = "";
  nativeLoaderForced.value = false;
  busy.value = false;
  stopLoadWaitTimer();
  forceHideArtLoading();
  if (progressTimer) {
    window.clearInterval(progressTimer);
    progressTimer = null;
  }
  if (loaderForceTimer) {
    window.clearTimeout(loaderForceTimer);
    loaderForceTimer = null;
  }
}

function playerStorageId() {
  return `${authStore.user?.id ?? "guest"}:${favoriteIdentity(mediaPath.value)}`;
}

/** Official ArtPlayer auto-playback stores times under artplayer_settings. */
function seedArtPlayerResume(position: number) {
  if (!Number.isFinite(position) || position < accountResumeMinSec()) return;
  try {
    const key = "artplayer_settings";
    const raw = localStorage.getItem(key);
    const data = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
    const times = (data.times as Record<string, number>) || {};
    times[playerStorageId()] = position;
    data.times = times;
    localStorage.setItem(key, JSON.stringify(data));
  } catch {
    /* ignore */
  }
}

type SubtitleSize = "sm" | "md" | "lg";
type SubtitlePrefs = {
  size: SubtitleSize;
  /** px from bottom — ArtPlayer --art-subtitle-bottom */
  bottom: number;
  enabled: boolean;
  url: string;
  offset: number;
};

const SUBTITLE_SIZE_PX: Record<SubtitleSize, string> = {
  sm: "14px",
  md: "20px",
  lg: "28px",
};

function defaultSubtitlePrefs(): SubtitlePrefs {
  return { size: "md", bottom: 15, enabled: true, url: "", offset: 0 };
}

function loadSubtitlePrefs(): SubtitlePrefs {
  try {
    const raw = localStorage.getItem(subtitleStorageKey());
    if (!raw) return defaultSubtitlePrefs();
    const data = JSON.parse(raw) as Partial<SubtitlePrefs>;
    const base = defaultSubtitlePrefs();
    const savedSize: string | undefined = data.size;
    return {
      size:
        savedSize === "sm" || savedSize === "small"
          ? "sm"
          : savedSize === "lg" || savedSize === "large"
            ? "lg"
            : "md",
      bottom:
        typeof data.bottom === "number" &&
        data.bottom >= 0 &&
        data.bottom <= 120
          ? data.bottom
          : base.bottom,
      enabled: typeof data.enabled === "boolean" ? data.enabled : !!data.url,
      url: typeof data.url === "string" ? data.url : "",
      offset:
        typeof data.offset === "number" && Math.abs(data.offset) <= 30
          ? data.offset
          : 0,
    };
  } catch {
    return defaultSubtitlePrefs();
  }
}

const subtitlePrefs = ref<SubtitlePrefs>(loadSubtitlePrefs());

function subtitleStorageKey() {
  return `nas-file-browser-subtitle-v1:${authStore.user?.id ?? "guest"}`;
}

function persistSubtitlePrefs() {
  try {
    localStorage.setItem(
      subtitleStorageKey(),
      JSON.stringify(subtitlePrefs.value)
    );
  } catch {
    /* ignore */
  }
}

function subtitleTypeFromUrl(url: string): "vtt" | "srt" | "ass" {
  if (new URL(url, location.origin).pathname.includes("/api/subtitle/"))
    return "vtt";
  const m = /\.([a-z0-9]+)(?:\?|#|$)/i.exec(url || "");
  const ext = (m?.[1] || "").toLowerCase();
  if (ext === "srt") return "srt";
  if (ext === "ass" || ext === "ssa") return "ass";
  return "vtt";
}

function subtitleStyleFromPrefs(): Partial<CSSStyleDeclaration> {
  return {
    fontSize: SUBTITLE_SIZE_PX[subtitlePrefs.value.size] || "20px",
  } as Partial<CSSStyleDeclaration>;
}

/** Official CSS vars + art.subtitle.style — position / size / offset. */
function applySubtitleChrome() {
  const player = art.value as unknown as {
    template?: { $player?: HTMLElement };
    subtitleOffset?: number;
  } | null;
  const root = player?.template?.$player as HTMLElement | undefined;
  if (root) {
    root.style.setProperty(
      "--art-subtitle-font-size",
      SUBTITLE_SIZE_PX[subtitlePrefs.value.size]
    );
    root.style.setProperty(
      "--art-subtitle-bottom",
      `${subtitlePrefs.value.bottom}px`
    );
  }
  try {
    const sub = (
      art.value as unknown as {
        subtitle?: { style?: (k: string, v: string) => void };
      }
    )?.subtitle;
    sub?.style?.("fontSize", SUBTITLE_SIZE_PX[subtitlePrefs.value.size]);
  } catch {
    /* ignore */
  }
  try {
    if (player) player.subtitleOffset = subtitlePrefs.value.offset;
  } catch {
    /* ignore */
  }
  shiftSubtitleTimeline();
}

function shiftSubtitleTimeline() {
  const subtitle = art.value?.subtitle as unknown as
    | {
        cues?: Array<
          VTTCue & { originalStartTime?: number; originalEndTime?: number }
        >;
        update?: () => void;
      }
    | undefined;
  if (!subtitle?.cues) return;
  const offset = actualMode.value === "compat" ? compatOffset.value : 0;
  for (const cue of subtitle.cues) {
    cue.originalStartTime ??= cue.startTime;
    cue.originalEndTime ??= cue.endTime;
    const start = cue.originalStartTime - offset + subtitlePrefs.value.offset;
    const end = cue.originalEndTime - offset + subtitlePrefs.value.offset;
    // Past cues must not become zero-length cues visible at the new origin.
    cue.startTime =
      end <= 0 ? (sourceDuration.value || 86400) + 1 : Math.max(0, start);
    cue.endTime =
      end <= 0 ? cue.startTime + 0.001 : Math.max(cue.startTime + 0.001, end);
  }
  subtitle.update?.();
}

function switchSubtitle(item: { html: string; value: string; name?: string }) {
  const p = art.value as unknown as {
    subtitle: {
      url: string;
      switch: (
        url: string,
        opt?: Record<string, unknown>
      ) => Promise<string | null>;
    };
  } | null;
  if (!p?.subtitle) return item.html || "关";
  if (switchingEngine) {
    notice("请等待当前播放方式切换完成");
    return currentSubtitleLabel();
  }
  const embedded = embeddedSubtitleTracks.value.find(
    (track) => embeddedSubtitleValue(track.index) === item.value
  );
  if (embedded) {
    embeddedSubtitleIndex.value = embedded.index;
    p.subtitle.url = "";
    subtitlePrefs.value.enabled = true;
    subtitlePrefs.value.url = item.value;
    persistSubtitlePrefs();
    syncPlayerLabels();
    syncSubtitleCheck();
    void nextTick(refreshSubtitlePicker);
    notice("内挂 PGS 字幕需要兼容转码，正在切换…");
    void switchEngine(
      "compat",
      actualMode.value === "compat"
        ? transcodeQuality.value
        : preferredCompatQuality(),
      true
    );
    return item.html || "内挂字幕";
  }
  const hadEmbedded = embeddedSubtitleIndex.value !== null;
  embeddedSubtitleIndex.value = null;
  if (!item.value) {
    p.subtitle.url = "";
    subtitlePrefs.value.enabled = false;
    subtitlePrefs.value.url = "";
    persistSubtitlePrefs();
    syncPlayerLabels();
    syncSubtitleCheck();
    void nextTick(refreshSubtitlePicker);
    if (hadEmbedded) void switchEngine("compat", transcodeQuality.value, true);
    return "关";
  }
  const type = subtitleTypeFromUrl(item.value);
  void p.subtitle
    .switch(item.value, {
      type,
      escape: true,
      name: item.name || item.html || "字幕",
      style: subtitleStyleFromPrefs(),
    })
    .catch(() => {
      notice("字幕加载失败，请重试或选择其他字幕");
    });
  subtitlePrefs.value.enabled = true;
  subtitlePrefs.value.url = item.value;
  persistSubtitlePrefs();
  applySubtitleChrome();
  syncPlayerLabels();
  syncSubtitleCheck();
  void nextTick(refreshSubtitlePicker);
  if (hadEmbedded) void switchEngine("compat", transcodeQuality.value, true);
  return item.html || "字幕";
}

function currentAudioLabel() {
  if (embeddedAudioIndex.value === null) return "默认音轨";
  const track = embeddedAudioTracks.value.find(
    (item) => item.index === embeddedAudioIndex.value
  );
  return track?.title || track?.language || "内挂音轨";
}

function switchAudioTrack(item: { value: string; html: string }) {
  if (switchingEngine) {
    notice("请等待当前播放方式切换完成");
    return currentAudioLabel();
  }
  const index = item.value === "__default__" ? null : Number(item.value);
  if (
    index !== null &&
    !embeddedAudioTracks.value.some((track) => track.index === index)
  ) {
    notice("所选音轨已不可用");
    return currentAudioLabel();
  }
  if (embeddedAudioIndex.value === index) return currentAudioLabel();
  embeddedAudioIndex.value = index;
  notice("正在切换内挂音轨，使用兼容转码…");
  void switchEngine(
    "compat",
    actualMode.value === "compat"
      ? transcodeQuality.value
      : preferredCompatQuality(),
    true
  );
  syncPlayerLabels();
  return currentAudioLabel();
}

function persistPlaybackPosition(force = false) {
  if (switchingEngine && !force) return;
  const video = art.value?.video as HTMLVideoElement | undefined;
  if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return;
  const pos =
    video.currentTime +
    (actualMode.value === "compat" ? compatOffset.value : 0);
  if (!Number.isFinite(pos) || pos < 1) return;
  const now = Date.now();
  if (
    !force &&
    Math.abs(pos - lastSavedPosition) < 5 &&
    now - lastSaveAt < 8000
  ) {
    return;
  }
  lastSavedPosition = pos;
  lastSaveAt = now;
  void mediaApi
    .savePlayback(
      mediaPath.value,
      pos,
      actualMode.value === "compat"
        ? sourceDuration.value || video.duration
        : video.duration
    )
    .catch((error: unknown) => {
      if (
        !unsupportedSaveNoticeShown &&
        error instanceof Error &&
        error.message.startsWith("服务器尚不支持")
      ) {
        unsupportedSaveNoticeShown = true;
        notice(error.message);
      }
    });
}

function captureResume() {
  const video = art.value?.video as HTMLVideoElement | undefined;
  return {
    position:
      (video?.currentTime || 0) +
      (actualMode.value === "compat" ? compatOffset.value : 0),
    playing: !!video && !video.paused && !video.ended,
    rate: currentRate.value,
  };
}

function updateTimeline() {
  const video = art.value?.video;
  if (!video || switchingEngine) return;
  const offset = actualMode.value === "compat" ? compatOffset.value : 0;
  timelinePosition.value = Math.min(
    sourceDuration.value || Infinity,
    offset + video.currentTime
  );
  const end = video.seekable.length
    ? video.seekable.end(video.seekable.length - 1)
    : 0;
  timelineAvailableEnd.value = Math.min(
    sourceDuration.value || Infinity,
    offset + end
  );
  maybePrefetchWindow(video);
}

function compatibilityRequestOptions(quality: Quality) {
  const q = quality === "native" ? preferredCompatQuality() : quality;
  const format = supportsH264CompatibilityPlayback()
    ? ("hls" as const)
    : ("webm" as const);
  const backendQuality =
    q === "2160p" ? ("4k" as const) : q === "1440p" ? ("2k" as const) : q;
  return {
    format,
    quality: backendQuality,
    signature: JSON.stringify([
      format,
      backendQuality,
      embeddedSubtitleIndex.value,
      embeddedAudioIndex.value,
    ]),
  };
}

function discardPrefetchedWindow(window: PrefetchedWindow | null) {
  if (window?.status?.id)
    void mediaApi.cancelHLSPlayback(window.status.id).catch(() => {});
  // A request still in flight checks ownership on resolution and cancels itself.
}

function maybePrefetchWindow(video: HTMLVideoElement) {
  if (
    backgroundDetached ||
    switchingEngine ||
    actualMode.value !== "compat" ||
    !activeCompatCacheID ||
    video.paused ||
    prefetchedWindow ||
    compatWindowSeconds <= 0
  )
    return;
  const nextStart = compatOffset.value + compatWindowSeconds;
  const options = compatibilityRequestOptions(transcodeQuality.value);
  const ahead = options.format === "webm" ? 8 : 30;
  if (
    nextStart >= sourceDuration.value - 0.1 ||
    attemptedPrefetchStart === nextStart ||
    video.currentTime < Math.max(0, compatWindowSeconds - ahead)
  )
    return;
  attemptedPrefetchStart = nextStart;
  const candidate: PrefetchedWindow = {
    start: nextStart,
    signature: options.signature,
    adopted: false,
    request: Promise.resolve(undefined),
  };
  prefetchedWindow = candidate;
  candidate.request = mediaApi
    .startHLSPlayback(
      mediaPath.value,
      options.format,
      options.quality,
      embeddedSubtitleIndex.value ?? undefined,
      embeddedAudioIndex.value ?? undefined,
      {
        startSeconds: nextStart,
        windowSeconds: options.format === "webm" ? 16 : 120,
        sessionId: `${playbackSession}-prefetch-${nextStart.toFixed(3)}`,
      }
    )
    .then((status) => {
      candidate.status = status;
      if (
        backgroundDetached ||
        (!candidate.adopted && prefetchedWindow !== candidate)
      )
        discardPrefetchedWindow(candidate);
      return status;
    })
    .catch(() => {
      if (prefetchedWindow === candidate) prefetchedWindow = null;
      return undefined;
    });
}

function seekTo(seconds: number) {
  const video = art.value?.video;
  if (!video || !Number.isFinite(seconds)) return;
  const target = Math.max(
    0,
    Math.min(seconds, (sourceDuration.value || video.duration) - 0.1)
  );
  if (switchingEngine) {
    pendingSeek = target;
    if (activeCompatCacheID)
      void mediaApi.cancelHLSPlayback(activeCompatCacheID).catch(() => {});
    return;
  }
  const offset = actualMode.value === "compat" ? compatOffset.value : 0;
  const local = target - offset;
  const available = Array.from({ length: video.seekable.length }, (_, i) => [
    video.seekable.start(i),
    video.seekable.end(i),
  ]).some(([start, end]) => local >= start && local < end - 0.1);
  timelinePosition.value = target;
  if (actualMode.value !== "compat" || available) {
    video.currentTime = Math.max(0, local);
    notice(
      `${formatClock(target)} / ${formatClock(sourceDuration.value || video.duration)}`
    );
  } else {
    notice(`正在跳转到 ${formatClock(target)}…`);
    void switchEngine("compat", transcodeQuality.value, true, target);
  }
}

function handleTimelineKey(event: KeyboardEvent) {
  if (
    actualMode.value !== "compat" ||
    !sourceDuration.value ||
    event.altKey ||
    event.ctrlKey ||
    event.metaKey
  )
    return;
  const target = event.target as HTMLElement | null;
  if (
    target?.closest(
      "input, textarea, select, [contenteditable=true], [role=dialog]"
    )
  )
    return;
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault();
  event.stopImmediatePropagation();
  seekTo(timelinePosition.value + (event.key === "ArrowRight" ? 5 : -5));
}

function applyResume(resume: {
  position: number;
  playing: boolean;
  rate: number;
}) {
  const player = art.value as unknown as {
    video?: HTMLVideoElement;
    playbackRate?: number;
  } | null;
  if (!player) return;
  currentRate.value = clampRate(resume.rate);
  const run = () => {
    try {
      const video = player.video;
      if (video && resume.position > 0.5 && Number.isFinite(video.duration)) {
        const localPosition =
          resume.position -
          (actualMode.value === "compat" ? compatOffset.value : 0);
        let max = Math.max(0, video.duration - 0.5);
        try {
          if (video.seekable && video.seekable.length > 0) {
            max = Math.max(
              0,
              video.seekable.end(video.seekable.length - 1) - 0.5
            );
          }
        } catch {
          /* ignore */
        }
        if (
          actualMode.value === "compat" &&
          sourceDuration.value > 0 &&
          (localPosition < 0 || localPosition > max + 0.5)
        ) {
          seekTo(resume.position);
          return;
        }
        if (localPosition > max + 0.5 && max > 0.5) {
          notice("进度超出可播范围，已从较近位置继续");
        }
        video.currentTime = Math.min(Math.max(0, localPosition), max);
      }
      try {
        player.playbackRate = currentRate.value;
      } catch {
        /* ignore */
      }
      if (resume.playing) {
        void player.video?.play?.().catch(() => {});
      }
    } catch {
      /* ignore */
    }
    clearLoadingState();
    syncPlayerLabels();
  };
  const video = player.video;
  if (video && video.readyState >= 1) {
    run();
    return;
  }
  const onMeta = () => {
    video?.removeEventListener("loadedmetadata", onMeta);
    run();
  };
  video?.addEventListener("loadedmetadata", onMeta);
  window.setTimeout(() => {
    video?.removeEventListener("loadedmetadata", onMeta);
    run();
  }, 2800);
}

function markBarSelectorCurrent(name: string, labels: string[]) {
  const t = art.value?.template as unknown as { $controls?: Element } | null;
  const bar = t?.$controls as HTMLElement | null;
  if (!bar) return;
  const set = new Set(labels);
  bar
    .querySelectorAll(`.art-control-${name} .art-selector-item`)
    .forEach((el) => {
      const html = (el.textContent || "").trim();
      const value = el.getAttribute("data-value") || "";
      const on = set.has(html) || set.has(value);
      el.classList.toggle("art-current", on);
    });
}

function refreshQualityPickers() {
  const setting = artSetting() as
    | (SettingApi & {
        update?: (s: Record<string, unknown>) => unknown;
      })
    | null;
  const qualityItem = buildSettings().find(
    (s) => (s as { name?: string }).name === "playback-quality"
  ) as Record<string, unknown> | undefined;
  if (qualityItem && setting?.update) {
    try {
      const row = { ...qualityItem };
      delete row.icon;
      const name = row.name as string;
      row.mounted = function mounted(
        _panel: HTMLElement,
        it: Record<string, unknown>
      ) {
        try {
          it.icon = iconClone(settingIconKey(name));
        } catch {
          /* ignore */
        }
      };
      setting.update(row);
    } catch {
      /* ignore */
    }
  }
  const controlsApi = art.value as unknown as {
    controls?: { update?: (o: Record<string, unknown>) => unknown };
  };
  const barQuality = buildBarControls().find(
    (c) => (c as { name?: string }).name === "playback-quality"
  );
  if (barQuality && controlsApi.controls?.update) {
    try {
      controlsApi.controls.update(barQuality);
    } catch {
      /* ignore */
    }
  }
  hardenSelectorLists();
}

function settingIconKey(name: string): string {
  switch (name) {
    case "playback-mode":
      return "config";
    case "playback-rate":
      return "playbackRate";
    case "playback-quality":
      return "aspectRatio";
    case "playback-audio":
      return "volume";
    case "playback-subtitle":
    case "subtitle-size":
    case "subtitle-position":
      return "subtitle";
    default:
      return "config";
  }
}

/** Settings passed into the constructor (no post-ready add → no name clashes). */
function buildSettingsOption() {
  return buildSettings().map((item) => {
    const row = { ...item } as Record<string, unknown>;
    delete row.icon;
    const name = row.name as string;
    row.mounted = function mounted(
      _panel: HTMLElement,
      it: Record<string, unknown>
    ) {
      try {
        const ic = iconClone(settingIconKey(name)) || iconClone("config");
        if (ic) it.icon = ic;
      } catch {
        /* ignore */
      }
    };
    return row;
  });
}

function refreshSubtitlePicker() {
  const row = buildSettingsOption().find(
    (item) => item.name === "playback-subtitle"
  );
  if (row && art.value) art.value.setting.update(row as never);
}

function syncSubtitleCheck() {
  const setting = artSetting();
  const index = allSubtitleItems.value.findIndex(
    (item) => item.url === subtitlePrefs.value.url
  );
  const selected = setting?.find?.(
    subtitlePrefs.value.enabled && index >= 0 ? `sub-track-${index}` : "sub-off"
  );
  if (selected) setting?.check?.(selected);
}

function installPlayerChrome() {
  const setting = artSetting();
  // Constructor already registered settings/controls; only sync labels/checks.
  syncPlayerLabels();
  if (setting?.check && setting.find) {
    const rateItem = setting.find(`rate-${currentRate.value}`);
    if (rateItem) setting.check(rateItem);
  }
  bindControlBarScroll();
  bindBarSelectorPopups();
  hardenSelectorLists();
  if (isMobile.value) hideMobileExtraControls();
}

/**
 * Switch native/compat (and compat quality) as a source reload.
 * Chips + loading title update immediately; playback position is restored.
 */
async function switchEngine(
  mode: ActualMode,
  quality?: Quality,
  fromAuto = false,
  targetPosition?: number
) {
  if (switchingEngine) return;
  if (mode === "native" && embeddedSubtitleIndex.value !== null) {
    embeddedSubtitleIndex.value = null;
    subtitlePrefs.value.enabled = false;
    subtitlePrefs.value.url = "";
    persistSubtitlePrefs();
    refreshSubtitlePicker();
    notice("原生播放不支持内挂 PGS 字幕，已关闭字幕");
  }
  if (mode === "native" && embeddedAudioIndex.value !== null) {
    embeddedAudioIndex.value = null;
    notice("原生播放已恢复默认音轨");
  }
  switchingEngine = true;
  playbackError.value = "";
  const token = ++switchToken;
  const resume = captureResume();
  if (targetPosition !== undefined) {
    resume.position = targetPosition;
    resume.playing = true;
  }
  persistPlaybackPosition(true);

  const targetQuality =
    mode === "compat"
      ? (quality ?? preferredCompatQuality())
      : transcodeQuality.value;
  const candidate = prefetchedWindow;
  prefetchedWindow = null;
  attemptedPrefetchStart = -1;
  const reusePrefetch =
    mode === "compat" &&
    candidate &&
    Math.abs(candidate.start - resume.position) < 0.5 &&
    candidate.signature ===
      compatibilityRequestOptions(targetQuality).signature;
  if (reusePrefetch) candidate.adopted = true;
  else discardPrefetchedWindow(candidate);

  // Optimistic UI — user sees the switch immediately
  actualMode.value = mode;
  if (!fromAuto) persistPlaybackMode(mode);
  if (mode === "compat" && targetQuality !== "native") {
    transcodeQuality.value = targetQuality;
  }
  closeBarSelectors();
  syncPlayerLabels();
  refreshQualityPickers();
  markBarSelectorCurrent(
    "playback-mode",
    mode === "compat" ? ["兼容", "compat"] : ["原生", "native"]
  );
  markBarSelectorCurrent(
    "playback-quality",
    mode === "compat"
      ? [qualityLabel(transcodeQuality.value), transcodeQuality.value]
      : [resolutionLabel.value, "native"]
  );

  if (progressTimer) {
    window.clearInterval(progressTimer);
    progressTimer = null;
  }
  if (loaderForceTimer) {
    window.clearTimeout(loaderForceTimer);
    loaderForceTimer = null;
  }

  videoPlaying.value = false;
  busy.value = true;
  nativeLoaderForced.value = true;
  startLoadWaitTimer();
  loadStatusText.value =
    mode === "compat" ? "正在启动兼容转码…" : "正在切换原生…";
  loadProgress.value = null;
  forceHideArtLoading();

  try {
    if (mode === "compat") {
      const q: Exclude<Quality, "native"> =
        targetQuality === "native" ? preferredCompatQuality() : targetQuality;
      transcodeQuality.value = q;
      loadStatusText.value = "正在请求转码任务…";
      const format = supportsH264CompatibilityPlayback() ? "hls" : "webm";
      const backendQuality = q === "2160p" ? "4k" : q === "1440p" ? "2k" : q;
      art.value?.video.pause();
      detachHls();
      if (activeCompatCacheID) {
        await mediaApi.cancelHLSPlayback(activeCompatCacheID).catch(() => {});
        activeCompatCacheID = "";
      }
      const playbackWindow =
        sourceDuration.value > 0
          ? {
              startSeconds: Math.max(
                0,
                Math.min(resume.position, sourceDuration.value - 0.1)
              ),
              windowSeconds: format === "webm" ? 16 : 120,
              sessionId: playbackSession,
            }
          : undefined;
      let status =
        (reusePrefetch ? await candidate.request : undefined) ||
        (await mediaApi.startHLSPlayback(
          mediaPath.value,
          format,
          backendQuality,
          embeddedSubtitleIndex.value ?? undefined,
          embeddedAudioIndex.value ?? undefined,
          playbackWindow
        ));
      if (token !== switchToken) {
        if (playbackWindow)
          void mediaApi.cancelHLSPlayback(status.id).catch(() => {});
        return;
      }
      activeCompatCacheID = playbackWindow ? status.id : "";
      while (
        token === switchToken &&
        status.id &&
        !status.playlistUrl &&
        !status.sourceUrl &&
        !["completed", "failed", "canceled"].includes(status.state)
      ) {
        loadStatusText.value =
          status.state === "queued" ? "转码排队中…" : "兼容转码中…";
        if (status.durationSeconds && status.processedSeconds) {
          loadProgress.value = Math.min(
            99,
            (status.processedSeconds / status.durationSeconds) * 100
          );
        }
        await new Promise((resolve) => window.setTimeout(resolve, 700));
        if (token !== switchToken) return;
        status = await mediaApi.getHLSPlayback(status.id);
      }
      if (token !== switchToken) return;
      if (status.state === "failed" || status.state === "canceled")
        throw new Error(status.error || "兼容转码失败");
      const url = status.playlistUrl || status.sourceUrl;
      if (!url) {
        loadStatusText.value = status.id ? "转码排队中…" : "暂无法播放地址";
        loadProgress.value = null;
        notice(
          status.id ? "兼容任务已提交，转码完成后可播放" : "兼容播放地址不可用"
        );
        if (!status.id) {
          window.setTimeout(() => {
            if (token !== switchToken) return;
            clearLoadingState({ force: true });
          }, 4000);
        }
        return;
      }
      loadStatusText.value = "";
      const hlsUrl = resolveBackendMediaURL(
        url,
        window.location.origin,
        baseURL
      );
      compatOffset.value = status.startSeconds || 0;
      compatWindowSeconds = status.durationSeconds || status.windowSeconds || 0;
      timelinePosition.value = resume.position;
      timelineAvailableEnd.value = compatOffset.value;
      if (status.sourceDurationSeconds)
        sourceDuration.value = status.sourceDurationSeconds;
      if (status.playlistUrl) await attachHls(hlsUrl);
      else if (art.value) art.value.url = hlsUrl;
      if (token !== switchToken) return;
      if (status.id && !mediaUiReady.value)
        startCompatProgressPolling(status.id);
      if (!reusePrefetch)
        notice(
          fromAuto
            ? targetPosition !== undefined
              ? `已跳转到 ${formatClock(targetPosition)}`
              : embeddedSubtitleIndex.value !== null
                ? "已切换兼容转码以显示内挂字幕"
                : "原生无法播放，已切换兼容转码"
            : `已切换兼容 · ${qualityLabel(transcodeQuality.value)}`
        );
      applyResume({ ...resume, rate: currentRate.value });
      const hv = art.value?.video as HTMLVideoElement | undefined;
      try {
        void hv?.play?.().catch(() => {});
      } catch {
        /* ignore */
      }
      refreshMediaUiState("hls-attached");
    } else {
      if (activeCompatCacheID) {
        void mediaApi.cancelHLSPlayback(activeCompatCacheID).catch(() => {});
        activeCompatCacheID = "";
      }
      compatOffset.value = 0;
      compatWindowSeconds = 0;
      detachHls();
      clearNativeProgressHooks();
      if (!art.value) return;
      art.value.url = rawUrl();
      notice("已切换原生播放");
      applyResume({ ...resume, rate: currentRate.value });
    }
  } catch (e) {
    if (token !== switchToken) return;
    clearLoadingState({ force: true });
    stopLoadWaitTimer();
    playbackError.value =
      mode === "compat"
        ? "兼容视频处理失败。可以重试，或下载原文件使用本地播放器。"
        : "视频加载失败。请检查连接后重试。";
    void e;
  } finally {
    if (token === switchToken) {
      switchingEngine = false;
      busy.value = false;
      if (pendingSeek !== null) {
        const next = pendingSeek;
        pendingSeek = null;
        window.setTimeout(() => seekTo(next), 0);
      }
      syncPlayerLabels();
      if (!playbackError.value)
        loaderForceTimer = window.setTimeout(() => {
          if (token !== switchToken) return;
          const video = art.value?.video as HTMLVideoElement | undefined;
          if (
            mediaUiReady.value ||
            (video && video.readyState >= 2 && videoHasFrame(video))
          ) {
            clearLoadingState();
            return;
          }
          const st = loadStatusText.value || "";
          if (
            mode === "compat" &&
            (/转码|排队|兼容/.test(st) ||
              (loadProgress.value != null && loadProgress.value >= 90))
          ) {
            // Still attaching HLS / waiting first frame — keep loader.
            return;
          }
          if (video && (video.readyState >= 2 || video.currentTime > 0)) {
            clearLoadingState();
          } else {
            clearLoadingState({ force: true });
            notice("加载较慢，可再点一次播放或切换播放方式");
          }
        }, 8000);
    }
  }
}

function startCompatProgressPolling(id: string) {
  if (progressTimer) window.clearInterval(progressTimer);
  if (videoPlaying.value) return;
  const startedAt = Date.now();
  loadStatusText.value = "转码准备中…";
  loadProgress.value = null;
  progressTimer = window.setInterval(async () => {
    try {
      const status = await mediaApi.getHLSPlayback(id);
      if (videoPlaying.value || mediaUiReady.value) {
        clearLoadingState();
        return;
      }
      const elapsed = Math.round((Date.now() - startedAt) / 1000);
      if (status.processedSeconds && status.durationSeconds) {
        const pct = Math.min(
          99,
          (status.processedSeconds / status.durationSeconds) * 100
        );
        loadStatusText.value = "";
        loadProgress.value = pct;
      } else if (status.state === "queued") {
        loadStatusText.value = `转码排队中… ${elapsed}s`;
        loadProgress.value = null;
      } else if (
        status.state === "streamable" ||
        status.state === "completed"
      ) {
        loadStatusText.value = "";
        loadProgress.value = 99;
        const video = art.value?.video as HTMLVideoElement | undefined;
        if (video && video.readyState >= 2 && videoHasFrame(video)) {
          clearLoadingState();
          return;
        }
        video?.addEventListener("canplay", () => clearLoadingState(), {
          once: true,
        });
      } else if (status.state === "failed") {
        loadStatusText.value = status.error || "转码失败";
        notice(status.error || "兼容转码失败");
        clearLoadingState({ force: true });
      } else {
        loadStatusText.value = `兼容转码中… ${elapsed}s`;
      }
    } catch {
      const elapsed = Math.round((Date.now() - startedAt) / 1000);
      loadStatusText.value = `兼容转码中… ${elapsed}s`;
    }
  }, 700);
}

async function loadMediaInfo() {
  try {
    const info = await mediaApi.getMediaInformation(mediaPath.value, false);
    sourceVideoCodec.value = info.videoCodec || "";
    sourceVideoBitDepth.value = info.videoBitDepth || 0;
    sourceAudioCodec.value = info.audioCodec || "";
    sourceDuration.value = info.duration || 0;
    if (info.resolution) {
      sourceWidth.value = info.resolution.width || 0;
      sourceHeight.value = info.resolution.height || 0;
    }
    embeddedSubtitleTracks.value = info.subtitleTracks || [];
    embeddedAudioTracks.value = info.audioTracks || [];
    const savedTrack = embeddedSubtitleTracks.value.find(
      (track) =>
        track.codec === "hdmv_pgs_subtitle" &&
        embeddedSubtitleValue(track.index) === subtitlePrefs.value.url
    );
    embeddedSubtitleIndex.value =
      subtitlePrefs.value.enabled && savedTrack ? savedTrack.index : null;
  } catch {
    /* optional */
  }
}

async function loadVideoSprite() {
  try {
    let sprite = await mediaApi.getVideoSprite(mediaPath.value);
    const startedAt = Date.now();
    while (
      sprite.state === "preparing" &&
      art.value &&
      Date.now() - startedAt < 180000
    ) {
      await new Promise((resolve) => window.setTimeout(resolve, 2000));
      if (!art.value) return;
      sprite = await mediaApi.getVideoSprite(mediaPath.value);
    }
    if (
      !art.value ||
      sprite.state === "failed" ||
      !sprite.url ||
      !sprite.number
    )
      return;
    timelineSprite.value = sprite;
    art.value.thumbnails = {
      url: sprite.url,
      number: sprite.number,
      column: sprite.column,
      width: sprite.width,
      height: sprite.height,
    };
  } catch {
    // Progress thumbnails are optional; playback remains fully functional.
  }
}

async function attachBackgroundTranscode() {
  if (!props.transcodeTaskId || backgroundDetached || !art.value) return;
  try {
    const status = await mediaApi.getTranscodePlayback(props.transcodeTaskId);
    if (backgroundDetached || !art.value) return;
    if (!backgroundQualityApplied && status.quality) {
      const q =
        status.quality === "4k"
          ? "2160p"
          : status.quality === "2k"
            ? "1440p"
            : status.quality;
      if (["source", "2160p", "1440p", "1080p", "720p", "480p"].includes(q))
        transcodeQuality.value = q as Quality;
      backgroundQualityApplied = true;
      syncPlayerLabels();
      refreshQualityPickers();
    }
    backgroundPlayableEnd.value = status.progress?.playableSeconds || 0;
    if (status.sourceDurationSeconds)
      sourceDuration.value = status.sourceDurationSeconds;
    if (
      !activeCompatCacheID &&
      !hlsInstance &&
      supportsH264CompatibilityPlayback() &&
      status.playlistUrl
    ) {
      actualMode.value = "compat";
      compatOffset.value = 0;
      await attachHls(
        resolveBackendMediaURL(
          status.playlistUrl,
          window.location.origin,
          baseURL
        )
      );
      applyResume({
        position: lastSavedPosition,
        playing: true,
        rate: currentRate.value,
      });
    }
    if (status.state === "failed" || status.state === "canceled") {
      detachHls();
      art.value.video.pause();
      notice(status.error || "后台转码已停止，可在任务中心重试");
    }
    if (
      status.state === "completed" &&
      status.sourceUrl &&
      !activeCompatCacheID &&
      supportsH264CompatibilityPlayback()
    ) {
      const resume = captureResume();
      detachHls();
      art.value.url = resolveBackendMediaURL(
        status.sourceUrl,
        window.location.origin,
        baseURL
      );
      applyResume(resume);
      backgroundPlayableEnd.value = sourceDuration.value;
      return;
    }
    if (!["failed", "canceled", "completed"].includes(status.state))
      backgroundPollTimer = window.setTimeout(
        () => void attachBackgroundTranscode(),
        1500
      );
  } catch {
    if (!backgroundDetached)
      backgroundPollTimer = window.setTimeout(
        () => void attachBackgroundTranscode(),
        3000
      );
  }
}

function chooseMode(mode: ActualMode) {
  askVisible.value = false;
  void switchEngine(mode);
}

function detachHls() {
  if (hlsInstance) {
    hlsInstance.destroy();
    hlsInstance = null;
  }
}

function clearNativeProgressHooks() {
  const video = art.value?.video as HTMLVideoElement | undefined;
  if (!video) {
    nativeLoadHandlers = [];
    return;
  }
  nativeLoadHandlers.forEach((fn) => fn());
  nativeLoadHandlers = [];
}

function bindNativeProgress() {
  clearNativeProgressHooks();
  const video = art.value?.video as HTMLVideoElement | undefined;
  if (!video) return;
  if (
    videoPlaying.value ||
    video.readyState >= 2 ||
    (video.currentTime > 0 && !video.paused)
  ) {
    clearLoadingState();
    return;
  }
  loadProgress.value = 0;
  const onProgress = () => {
    if (videoPlaying.value) return;
    if (!video.duration || !Number.isFinite(video.duration)) {
      loadProgress.value = Math.max(loadProgress.value ?? 0, 5);
      return;
    }
    let end = 0;
    if (video.buffered.length > 0) {
      end = video.buffered.end(video.buffered.length - 1);
    }
    loadProgress.value = Math.min(95, (end / video.duration) * 100);
  };
  const onReady = () => {
    const v = art.value?.video as HTMLVideoElement | undefined;
    if (v && (v.readyState >= 2 || v.currentTime > 0.05) && !v.error) {
      clearLoadingState();
    }
  };
  const onWaiting = () => {
    if (videoPlaying.value) return;
    loadProgress.value = Math.min(95, loadProgress.value ?? 10);
  };
  const onTime = () => {
    if (video.currentTime > 0.05) clearLoadingState();
  };
  video.addEventListener("progress", onProgress);
  video.addEventListener("canplay", onReady);
  video.addEventListener("playing", onReady);
  video.addEventListener("waiting", onWaiting);
  video.addEventListener("timeupdate", onTime);
  nativeLoadHandlers = [
    () => video.removeEventListener("progress", onProgress),
    () => video.removeEventListener("canplay", onReady),
    () => video.removeEventListener("playing", onReady),
    () => video.removeEventListener("waiting", onWaiting),
    () => video.removeEventListener("timeupdate", onTime),
  ];
}

async function attachHls(url: string) {
  detachHls();
  const video = art.value?.video as HTMLVideoElement | undefined;
  if (!art.value || !video) return;
  if (Hls.isSupported()) {
    const activity = captureSessionActivity();
    hlsInstance = new Hls({
      xhrSetup: (xhr: XMLHttpRequest, reqUrl: string) => {
        if (!activity.current()) {
          xhr.abort();
          throw new Error("播放来源已切换");
        }
        bindXHRActivity(xhr, activity, authStore.jwt);
        try {
          const jwt = authStore.jwt;
          if (jwt && !/[?&]auth=/.test(reqUrl)) {
            xhr.setRequestHeader("X-Auth", jwt);
          }
        } catch {
          /* ignore */
        }
      },
    });
    hlsInstance.on(Hls.Events.ERROR, (_evt, data) => {
      if (data?.fatal) {
        notice("兼容流播放出错，可重试或下载");
        loadStatusText.value = "兼容流出错";
        clearLoadingState({ force: true });
      }
    });
    hlsInstance.loadSource(url);
    hlsInstance.attachMedia(video);
  } else {
    art.value.url = url;
  }
  bindNativeProgress();
}

function openRateDialog() {
  rateDraft.value = currentRate.value;
  rateDialogVisible.value = true;
  try {
    (art.value as unknown as { pause?: () => void })?.pause?.();
  } catch {
    /* ignore */
  }
  void nextTick(() => rateInput.value?.focus());
}

function confirmRate() {
  applyRate(clampRate(Number(rateDraft.value)));
  rateDialogVisible.value = false;
  notice(`倍速 ${currentRate.value.toFixed(2)}x`);
}

function orientationPref(): "auto-fullscreen" | "auto-rotate" | "manual" {
  try {
    const v = localStorage.getItem(
      `nas-file-browser-player-orientation:${authStore.user?.id ?? "guest"}`
    );
    if (v === "auto-rotate" || v === "manual" || v === "auto-fullscreen")
      return v;
  } catch {
    /* ignore */
  }
  return "auto-fullscreen";
}

function updateOrientationState() {
  const box = container.value;
  if (!box) return;
  const w = box.clientWidth || window.innerWidth;
  const h = box.clientHeight || window.innerHeight;
  isPortrait.value = h > w;
  isMobile.value =
    /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) || w < 768;
}

function rateDisplay() {
  return `${currentRate.value.toFixed(2)}x`;
}

function qualityLabel(q: Quality) {
  switch (q) {
    case "2160p":
      return "4K";
    case "1440p":
      return "2K";
    case "1080p":
      return "1080p";
    case "720p":
      return "720p";
    case "480p":
      return "480p";
    default:
      return "原画";
  }
}

function qualityDisplay() {
  return actualMode.value === "compat"
    ? qualityLabel(transcodeQuality.value)
    : resolutionLabel.value;
}

function modeDisplay() {
  return actualModeLabel.value;
}

function setBarLabel(name: string, text: string) {
  const t = art.value?.template as unknown as { $controls?: Element } | null;
  const bar = t?.$controls as HTMLElement | null;
  if (!bar) return;
  const root = bar.querySelector<HTMLElement>(`.art-control-${name}`);
  if (!root) return;
  const value = root.querySelector<HTMLElement>(
    ".art-selector-value, .art-bar-label"
  );
  if (value) value.textContent = text;
}

function closeBarSelectors() {
  const t = art.value?.template as unknown as {
    $controls?: Element;
    $player?: Element;
  } | null;
  const bar = t?.$controls as HTMLElement | null;
  if (bar) {
    bar
      .querySelectorAll(".art-control-selector.art-selector-open")
      .forEach((el) => el.classList.remove("art-selector-open"));
  }
  const player = t?.$player as HTMLElement | null;
  player?.classList.remove("art-bar-selector-open");
}

function openBarSelector(ctrl: HTMLElement) {
  const setting = artSetting();
  try {
    if (setting) setting.show = false;
  } catch {
    /* ignore */
  }
  closeBarSelectors();
  ctrl.classList.add("art-selector-open");
  const t = art.value?.template as unknown as { $player?: Element } | null;
  (t?.$player as HTMLElement | null)?.classList.add("art-bar-selector-open");
}

let barSelectorDocHandler: ((e: MouseEvent) => void) | null = null;

function syncSettingEcho(name: string, value: string, checkName?: string) {
  const setting = artSetting();
  if (!setting?.find) return;
  const item = setting.find(name);
  if (!item) return;
  item.tooltip = value;
  if (checkName && setting.check) {
    const target = setting.find(checkName);
    if (target) setting.check(target);
  }
}

function syncPlayerLabels() {
  setBarLabel("playback-mode", modeDisplay());
  setBarLabel("playback-rate", rateDisplay());
  setBarLabel("playback-quality", qualityDisplay());
  syncSettingEcho(
    "playback-mode",
    modeDisplay(),
    actualMode.value === "compat" ? "mode-compat" : "mode-native"
  );
  syncSettingEcho("playback-rate", rateDisplay(), `rate-${currentRate.value}`);
  syncSettingEcho(
    "playback-quality",
    qualityDisplay(),
    `quality-${transcodeQuality.value}`
  );
  const subName = currentSubtitleLabel();
  syncSettingEcho("playback-subtitle", subName);
  syncSettingEcho(
    "playback-audio",
    currentAudioLabel(),
    embeddedAudioIndex.value === null
      ? "audio-default"
      : `audio-track-${embeddedAudioIndex.value}`
  );
}

function applyRate(rate: number, persist = true) {
  currentRate.value = clampRate(rate);
  if (art.value) {
    try {
      art.value.playbackRate = currentRate.value;
    } catch {
      /* ignore */
    }
  }
  if (persist) persistRate(currentRate.value);
  syncPlayerLabels();
}

async function applyTranscodeQuality(q: Quality): Promise<string> {
  if (q === "native") {
    return resolutionLabel.value;
  }
  if (actualMode.value !== "compat") {
    // Native cannot re-encode — keep source res, remember preferred compat quality.
    transcodeQuality.value = q;
    notice(
      `原生模式不支持转码分辨率；已记录 ${qualityLabel(q)}，切到兼容后生效`
    );
    syncPlayerLabels();
    return resolutionLabel.value;
  }
  notice(`正在切换兼容画质 ${qualityLabel(q)}…`);
  await switchEngine("compat", q);
  return qualityLabel(transcodeQuality.value);
}

/**
 * Settings panel — official ArtPlayer pattern:
 * html = name only, icon = SVG clone, tooltip = gray right echo.
 */
function buildSettings() {
  const width = settingWidth();
  return [
    {
      width,
      name: "playback-mode",
      html: "播放方式",
      icon: iconClone("config"),
      tooltip: modeDisplay(),
      selector: [
        {
          name: "mode-native",
          html: "原生播放",
          value: "native",
          default: actualMode.value === "native",
        },
        {
          name: "mode-compat",
          html: "兼容转码",
          value: "compat",
          default: actualMode.value === "compat",
        },
      ],
      onSelect(item: { html: string; value: string }) {
        if (!item || item.value == null) return modeDisplay();
        void switchEngine(item.value === "compat" ? "compat" : "native");
        return item.value === "compat" ? "兼容" : "原生";
      },
    },
    {
      width,
      name: "playback-rate",
      html: "播放速度",
      icon: iconClone("playbackRate"),
      tooltip: rateDisplay(),
      selector: [
        ...PRESET_RATES.map((r) => ({
          name: `rate-${r}`,
          html: `${r.toFixed(2)}x`,
          value: String(r),
          default: Math.abs(r - currentRate.value) < 0.001,
        })),
        { name: "rate-custom", html: "自定义倍速…", value: "custom" },
      ],
      onSelect(item: { html: string; value: string }) {
        if (item.value === "custom") {
          openRateDialog();
          return rateDisplay();
        }
        applyRate(clampRate(Number(item.value)));
        return rateDisplay();
      },
    },
    {
      width,
      name: "playback-quality",
      html: "转码画质",
      icon: iconClone("aspectRatio"),
      tooltip: qualityDisplay(),
      selector: qualityOptions.value.map((o) => ({
        name: `quality-${o.value}`,
        html: o.html,
        value: o.value,
        default:
          actualMode.value === "compat"
            ? o.value === transcodeQuality.value
            : o.value === "native",
      })),
      onSelect(item: { html: string; value: string }) {
        if (!item || item.value == null) return qualityDisplay();
        void applyTranscodeQuality(item.value as Quality);
        return qualityDisplay();
      },
    },
    ...(embeddedAudioTracks.value.length > 1
      ? [
          {
            width,
            name: "playback-audio",
            html: "音轨",
            icon: iconClone("volume") || iconClone("config"),
            tooltip: currentAudioLabel(),
            selector: [
              {
                name: "audio-default",
                html: "默认（源文件首轨）",
                value: "__default__",
                default: embeddedAudioIndex.value === null,
              },
              ...embeddedAudioTracks.value.map((track) => ({
                name: `audio-track-${track.index}`,
                html: `${track.title || track.language || `音轨 ${track.index}`} · ${track.codec.toUpperCase()}`,
                value: String(track.index),
                default: embeddedAudioIndex.value === track.index,
              })),
            ],
            onSelect: switchAudioTrack,
          },
        ]
      : []),
    {
      width,
      name: "playback-subtitle",
      html: "字幕",
      icon: iconClone("subtitle") || iconClone("config"),
      tooltip: currentSubtitleLabel(),
      selector: [
        {
          name: "sub-off",
          html: "关闭",
          value: "__off__",
          default:
            !subtitlePrefs.value.enabled ||
            !allSubtitleItems.value.some(
              (subtitle) => subtitle.url === subtitlePrefs.value.url
            ),
        },
        ...allSubtitleItems.value.map((s, i) => ({
          name: `sub-track-${i}`,
          html: s.name || `字幕 ${i + 1}`,
          value: s.url,
          default:
            subtitlePrefs.value.enabled && subtitlePrefs.value.url === s.url,
        })),
        {
          name: "sub-pick",
          html: "从其他目录添加字幕…",
          value: "__pick__",
        },
        ...(embeddedSubtitleIndex.value === null
          ? [
              {
                name: "sub-size",
                html: "字幕大小",
                selector: [
                  {
                    name: "sub-size-sm",
                    html: "小",
                    value: "__size_sm__",
                    default: subtitlePrefs.value.size === "sm",
                  },
                  {
                    name: "sub-size-md",
                    html: "中",
                    value: "__size_md__",
                    default: subtitlePrefs.value.size === "md",
                  },
                  {
                    name: "sub-size-lg",
                    html: "大",
                    value: "__size_lg__",
                    default: subtitlePrefs.value.size === "lg",
                  },
                ],
                onSelect(item: { html: string; value: string }) {
                  const size = item.value.slice(7, -2);
                  if (size === "sm" || size === "md" || size === "lg") {
                    subtitlePrefs.value.size = size;
                    persistSubtitlePrefs();
                    applySubtitleChrome();
                  }
                  return item.html;
                },
              },
              {
                name: "sub-pos",
                html: "字幕位置",
                selector: [
                  {
                    name: "sub-pos-15",
                    html: "低（贴底）",
                    value: "__pos_15__",
                    default: subtitlePrefs.value.bottom <= 20,
                  },
                  {
                    name: "sub-pos-40",
                    html: "中",
                    value: "__pos_40__",
                    default:
                      subtitlePrefs.value.bottom > 20 &&
                      subtitlePrefs.value.bottom <= 50,
                  },
                  {
                    name: "sub-pos-80",
                    html: "高",
                    value: "__pos_80__",
                    default: subtitlePrefs.value.bottom > 50,
                  },
                ],
                onSelect(item: { html: string; value: string }) {
                  const bottom = Number(item.value.slice(6, -2));
                  if (bottom === 15 || bottom === 40 || bottom === 80) {
                    subtitlePrefs.value.bottom = bottom;
                    persistSubtitlePrefs();
                    applySubtitleChrome();
                  }
                  return item.html;
                },
              },
              {
                name: "sub-offset",
                html: "时间偏移",
                range: [subtitlePrefs.value.offset, -10, 10, 0.1],
                onRange(this: unknown, item: { range?: number[] }) {
                  const n = Number(item?.range?.[0] ?? 0);
                  subtitlePrefs.value.offset = Math.round(n * 10) / 10;
                  persistSubtitlePrefs();
                  applySubtitleChrome();
                  syncSettingEcho("playback-subtitle", currentSubtitleLabel());
                  return `${subtitlePrefs.value.offset > 0 ? "+" : ""}${subtitlePrefs.value.offset}s`;
                },
              },
            ]
          : [
              {
                name: "sub-pgs-info",
                html: "PGS 字幕已合成到画面，样式不可调整",
                value: "__info__",
              },
            ]),
      ],
      onSelect(item: { html: string; value: string; name?: string }) {
        const v = item?.value;
        if (!v) return currentSubtitleLabel();
        if (v === "__off__") {
          return switchSubtitle({ html: "关闭", value: "", name: "" });
        }
        if (v === "__pick__") {
          subtitlePickerOpen.value = true;
          syncSubtitleCheck();
          return currentSubtitleLabel();
        }
        if (v === "__info__") return currentSubtitleLabel();
        return switchSubtitle(item);
      },
    },
  ];
}

function currentSubtitleLabel() {
  if (!subtitlePrefs.value.enabled || !subtitlePrefs.value.url) return "关";
  const hit = allSubtitleItems.value.find(
    (s) => s.url === subtitlePrefs.value.url
  );
  return hit?.name || "关";
}

/**
 * Bottom-bar chips: each opens its OWN ArtPlayer selector list anchored
 * above that chip — never the settings panel (which sits at the gear).
 */
function modeSelector() {
  return [
    {
      html: "原生",
      value: "native",
      default: actualMode.value === "native",
    },
    {
      html: "兼容",
      value: "compat",
      default: actualMode.value === "compat",
    },
  ];
}

function rateSelector() {
  return [
    ...PRESET_RATES.map((r) => ({
      html: `${r.toFixed(2)}x`,
      value: String(r),
      default: Math.abs(r - currentRate.value) < 0.001,
    })),
    { html: "自定义…", value: "custom" },
  ];
}

function qualitySelector() {
  return qualityOptions.value.map((o) => ({
    html: o.html,
    value: o.value,
    default:
      actualMode.value === "compat"
        ? o.value === transcodeQuality.value
        : o.value === "native",
  }));
}

function buildBarControls() {
  const compact = isMobile.value && isPortrait.value;
  const controls: Record<string, unknown>[] = [];
  if (!compact) {
    controls.push({
      position: "right",
      index: 10,
      name: "playback-mode",
      html: `<span class="art-bar-label">${modeDisplay()}</span>`,
      selector: modeSelector(),
      onSelect(item: { html: string; value: string }) {
        if (!item || item.value == null) return modeDisplay();
        void switchEngine(item.value === "compat" ? "compat" : "native");
        return item.value === "compat" ? "兼容" : "原生";
      },
    });
  }
  controls.push({
    position: "right",
    index: 11,
    name: "playback-rate",
    html: `<span class="art-bar-label">${rateDisplay()}</span>`,
    selector: rateSelector(),
    onSelect(item: { html: string; value: string }) {
      if (!item || item.value == null) return rateDisplay();
      if (item.value === "custom") {
        closeBarSelectors();
        openRateDialog();
        return rateDisplay();
      }
      applyRate(clampRate(Number(item.value)));
      closeBarSelectors();
      return rateDisplay();
    },
  });
  if (!compact) {
    controls.push({
      position: "right",
      index: 12,
      name: "playback-quality",
      html: `<span class="art-bar-label">${qualityDisplay()}</span>`,
      selector: qualitySelector(),
      onSelect(item: { html: string; value: string }) {
        if (!item || item.value == null) return qualityDisplay();
        void applyTranscodeQuality(item.value as Quality);
        closeBarSelectors();
        return qualityDisplay();
      },
    });
  }
  return controls;
}

/** Click-only independent popups — never hover, never settings panel. */
function bindBarSelectorPopups() {
  const t = art.value?.template as unknown as { $controls?: Element } | null;
  const bar = t?.$controls as HTMLElement | null;
  if (!bar || bar.dataset.nfbBarBound === "1") return;
  bar.dataset.nfbBarBound = "1";

  const onBarClick = (e: MouseEvent) => {
    const target = e.target as Element | null;
    if (!target || !bar.contains(target)) return;
    const inItem = target.closest?.(".art-selector-item");
    if (inItem) {
      // Let ArtPlayer onSelect run; just ensure we don't toggle.
      return;
    }
    const ctrl = target.closest?.(
      ".art-control-playback-mode, .art-control-playback-rate, .art-control-playback-quality"
    ) as HTMLElement | null;
    if (!ctrl || !bar.contains(ctrl)) return;
    e.preventDefault();
    e.stopPropagation();
    const open = ctrl.classList.contains("art-selector-open");
    if (open) closeBarSelectors();
    else openBarSelector(ctrl);
  };
  bar.addEventListener("click", onBarClick, true);

  if (!barSelectorDocHandler) {
    barSelectorDocHandler = (e: MouseEvent) => {
      const target = e.target as Element | null;
      if (target && bar.contains(target)) {
        if (target.closest?.(".art-control-selector")) return;
      }
      closeBarSelectors();
    };
    document.addEventListener("click", barSelectorDocHandler);
  }
}

function hardenSelectorLists() {
  const t = art.value?.template as unknown as { $controls?: Element } | null;
  const bar = t?.$controls as HTMLElement | null;
  if (!bar) return;
  bar.querySelectorAll(".art-selector-list").forEach((node) => {
    const list = node as HTMLElement;
    if (list.dataset.nfbHard === "1") return;
    list.dataset.nfbHard = "1";
    list.addEventListener(
      "touchstart",
      (e) => {
        e.stopPropagation();
      },
      { passive: true }
    );
    list.addEventListener(
      "touchmove",
      (e) => {
        e.stopPropagation();
      },
      { passive: false }
    );
  });
}

function hideMobileExtraControls() {
  const t = art.value?.template as unknown as { $controls?: Element } | null;
  const bar = t?.$controls as HTMLElement | undefined;
  if (!bar) return;
  bar
    .querySelectorAll(
      ".art-pip, .art-fullscreen-web, .art-quality-label, .art-mode-label"
    )
    .forEach((el) => {
      (el as HTMLElement).style.display = "none";
    });
}

function bindControlBarScroll() {
  const t = art.value?.template as unknown as {
    $controls?: Element;
    $controlsRight?: Element;
  } | null;
  // overflow-x:auto forces overflow-y to auto and clips selector popups.
  const bar = t?.$controls as HTMLElement | null;
  if (bar) {
    bar.style.overflow = "visible";
  }
  const right = t?.$controlsRight as HTMLElement | null;
  if (right) {
    right.style.overflow = "visible";
  }
}

onMounted(async () => {
  if (!container.value) return;
  // Re-read account prefs from server so resumeMode/rate/policy are not stale cache.
  try {
    const uid = authStore.user?.id;
    if (uid) {
      const me = await usersApi.get(uid);
      if (me && authStore.user) {
        authStore.updateUser({
          ...authStore.user,
          ...me,
          playerPreferences:
            me.playerPreferences || authStore.user.playerPreferences,
        });
      } else if (me) {
        authStore.setUser(me);
      }
    }
  } catch {
    /* keep local */
  }
  currentRate.value = accountRate();
  const policyNow = (
    authStore.user?.playerPreferences?.playbackMode || "native"
  ).toLowerCase() as Policy;
  actualMode.value = policyNow === "compat" ? "compat" : "native";
  askVisible.value = policy.value === "ask";
  await loadMediaInfo();
  updateOrientationState();
  sizeHandler = () => updateOrientationState();
  window.addEventListener("resize", sizeHandler);
  window.addEventListener("orientationchange", sizeHandler);

  const orientation = orientationPref();

  // Always seed official toast storage when we have a saved position.
  if (!askVisible.value) {
    try {
      const saved = await mediaApi.getPlayback(mediaPath.value);
      if (saved.exists && saved.position > accountResumeMinSec()) {
        lastSavedPosition = saved.position;
        seedArtPlayerResume(saved.position);
      }
    } catch {
      /* ignore */
    }
  }

  // Native-first is only for browser-friendly containers.
  // Huge MKV/legacy files: browser progressive playback gives no usable progress
  // (metadata/buffered often empty until nearly the whole file is read).
  const containerOk = browserSupportsContainer(props.path);
  const ext = pathExt(props.path);
  const forceCompatContainer =
    containerOk === false || Boolean(props.transcodeTaskId);
  if (!askVisible.value && forceCompatContainer) {
    actualMode.value = "compat";
  }
  if (!askVisible.value && forceCompatContainer) {
    nativeLoaderForced.value = true;
    busy.value = true;
    startLoadWaitTimer();
    loadProgress.value = null;
    loadStatusText.value = `.${ext} 启动兼容转码…`;
  }

  // Text subtitles load independently of the video, including saved embedded tracks.
  let subInit: Record<string, unknown> = {};
  const fallbackSubtitle = props.subtitles?.[0];
  const pick =
    (subtitlePrefs.value.url &&
      allSubtitleItems.value.find((s) => s.url === subtitlePrefs.value.url)) ||
    (fallbackSubtitle && {
      url: fallbackSubtitle.url,
      name: fallbackSubtitle.name || fallbackSubtitle.lang || "字幕",
    });
  if (pick && embeddedSubtitleIndex.value === null) {
    if (!subtitlePrefs.value.url && subtitlePrefs.value.enabled) {
      subtitlePrefs.value.url = pick.url;
      subtitlePrefs.value.enabled = true;
    }
    if (subtitlePrefs.value.enabled !== false) {
      subInit = {
        url: pick.url,
        type: subtitleTypeFromUrl(pick.url),
        escape: true,
        name: pick.name || "字幕",
        style: subtitleStyleFromPrefs(),
      };
    }
  }
  const startCompatUrl =
    !askVisible.value &&
    (policy.value === "compat" ||
      forceCompatContainer ||
      embeddedSubtitleIndex.value !== null);
  if (startCompatUrl) {
    actualMode.value = "compat";
  }
  const controlsTimeout = resolveControlsTimeoutMs(
    authStore.user?.playerPreferences?.controlsTimeoutSec
  );
  Artplayer.CONTROL_HIDE_TIME =
    controlsTimeout > 0 ? controlsTimeout : 2_147_483_647;
  const barControls = buildBarControls();
  art.value = new Artplayer({
    container: container.value as HTMLDivElement,
    url: askVisible.value ? "" : startCompatUrl ? "" : rawUrl(),
    id: playerStorageId(),
    poster: props.poster || "",
    volume: 0.7,
    autoplay: !askVisible.value,
    pip: !isMobile.value,
    setting: true,
    playbackRate: false,
    flip: true,
    aspectRatio: true,
    fullscreen: true,
    fullscreenWeb: !isMobile.value,
    miniProgressBar: true,
    mutex: true,
    backdrop: false,
    playsInline: true,
    autoOrientation: orientation !== "manual",
    hotkey: true,
    lang: "zh-cn",
    theme: "#2979ff",
    autoPlayback: false,
    subtitleOffset: false,
    subtitle: subInit as never,
    moreVideoAttr: { playsInline: true, preload: "metadata" } as never,
    settings: buildSettingsOption() as never,
    controls: barControls as never,
  });
  // ArtPlayer renders controls before media is ready; make them usable during
  // a cold compatibility transcode instead of waiting for the ready event.
  playerFeedbackRail(art.value.template.$player);
  // ArtPlayer's toggle handlers do not await play(). A user pause or source
  // switch can abort that promise normally; retain actual decoder failures.
  const videoElement = art.value.video;
  releaseResourceActivity?.();
  releaseResourceActivity = bindResourceActivity(videoElement);
  const nativePlay = videoElement.play.bind(videoElement);
  videoElement.play = () =>
    nativePlay().catch((error: unknown) => {
      if (error instanceof DOMException && error.name === "AbortError") return;
      throw error;
    });
  playerRoot.value = art.value.template.$player;
  document.addEventListener("keydown", handleTimelineKey, true);
  bindBarSelectorPopups();
  hardenSelectorLists();
  void loadVideoSprite();

  if (startCompatUrl && !askVisible.value) {
    notice(
      forceCompatContainer
        ? `.${ext} 使用兼容转码以显示加载进度`
        : "按账号策略使用兼容转码…"
    );
    if (props.transcodeTaskId && supportsH264CompatibilityPlayback()) {
      void attachBackgroundTranscode();
    } else {
      void switchEngine("compat", preferredCompatQuality(), true);
      if (props.transcodeTaskId) void attachBackgroundTranscode();
    }
  }

  art.value.on("ready", () => {
    applyRate(currentRate.value, false);
    applySubtitleChrome();
    void scanSiblingSubtitles().then(() => {
      try {
        installPlayerChrome();
      } catch {
        /* ignore */
      }
      syncPlayerLabels();
    });

    // Keep our loader until media actually plays (MKV/HEVC included).
    const video = art.value?.video as HTMLVideoElement | undefined;
    if (!askVisible.value) {
      if (!videoPlaying.value && !mediaUiReady.value) {
        nativeLoaderForced.value = true;
        if (loadProgress.value == null && !loadStatusText.value) {
          loadStatusText.value =
            actualMode.value === "compat"
              ? "兼容加载中…"
              : "原生加载中…（缓冲/探测）";
        }
      }
      forceHideArtLoading();
      refreshMediaUiState("ready");
      try {
        void video?.play?.().catch(() => {});
      } catch {
        /* ignore */
      }
      if (
        !startCompatUrl &&
        policy.value !== "ask" &&
        actualMode.value === "native"
      ) {
        // Native-first: poll media state; compat only on error / true stall.
        const startedAt = Date.now();
        const poll = window.setInterval(() => {
          const v = art.value?.video as HTMLVideoElement | undefined;
          if (actualMode.value !== "native" || askVisible.value) {
            window.clearInterval(poll);
            return;
          }
          refreshMediaUiState("native-poll");
          if (
            mediaUiReady.value ||
            (v && !v.error && videoHasFrame(v) && v.readyState >= 2)
          ) {
            window.clearInterval(poll);
            clearLoadingState();
            return;
          }
          if (v?.error) {
            window.clearInterval(poll);
            notice("原生解码失败，切换兼容转码…");
            void switchEngine("compat", preferredCompatQuality(), true);
            return;
          }
          const elapsed = Date.now() - startedAt;
          // Still buffering native file — keep loader + progress, do not flip engine yet.
          if (v && v.readyState < 2 && elapsed < 8000) {
            nativeLoaderForced.value = true;
            busy.value = true;
            const pct = nativeBufferPercent(v);
            if (pct != null) {
              loadStatusText.value = "";
              loadProgress.value = pct;
            } else {
              loadStatusText.value = `原生加载中… ${Math.round(elapsed / 1000)}s`;
            }
            return;
          }
          // Stall: no frame after grace period (or known-bad container with no progress).
          if (
            elapsed < (forceCompatContainer ? 8000 : 6000) &&
            !(v && v.readyState >= 2 && !videoHasFrame(v))
          ) {
            return;
          }
          if (switchingEngine) {
            window.clearInterval(poll);
            return;
          }
          window.clearInterval(poll);
          nativeLoaderForced.value = true;
          loadProgress.value = null;
          loadStatusText.value = "原生无法稳定播放，切换兼容…";
          busy.value = true;
          notice(
            forceCompatContainer
              ? `.${ext} 原生加载无画面，切换兼容转码…`
              : "原生加载无响应，切换兼容转码…"
          );
          void switchEngine("compat", preferredCompatQuality(), true);
        }, 400);
        nativeLoadHandlers.push(() => window.clearInterval(poll));
      }
    }

    // Resume toast for every account mode (seek only when mode = resume).
    if (
      !askVisible.value &&
      !resumePromptShown &&
      lastSavedPosition > accountResumeMinSec()
    ) {
      resumePromptShown = true;
      showResumeUi(lastSavedPosition);
    } else if (!askVisible.value && !resumePromptShown) {
      resumePromptShown = true;
      void mediaApi
        .getPlayback(mediaPath.value)
        .then((saved) => {
          if (saved?.exists && saved.position > accountResumeMinSec()) {
            lastSavedPosition = saved.position;
            showResumeUi(saved.position);
          }
        })
        .catch(() => {});
    }

    const t = art.value?.template as unknown as {
      $player?: HTMLElement;
    } | null;
    playerRoot.value = t?.$player || null;
    try {
      installPlayerChrome();
    } catch (e) {
      console.error("[NFB] player chrome install failed", e);
    }

    if (isMobile.value && orientation === "auto-fullscreen") {
      window.setTimeout(() => {
        try {
          const p = art.value as unknown as {
            fullscreen?: { enter?: () => void };
          };
          p?.fullscreen?.enter?.();
        } catch {
          /* ignore */
        }
      }, 400);
    }
  });

  art.value.on("video:timeupdate", () => {
    updateTimeline();
    persistPlaybackPosition(false);
  });
  art.value.on("video:progress", updateTimeline);
  art.value.on("video:ended", () => {
    if (
      actualMode.value === "compat" &&
      activeCompatCacheID &&
      sourceDuration.value > 0 &&
      timelinePosition.value < sourceDuration.value - 0.5
    ) {
      seekTo(timelinePosition.value);
    }
  });
  art.value.on("pause", () => {
    persistPlaybackPosition(true);
  });
  art.value.on("subtitleOffset", (value: unknown) => {
    const n = Number(value);
    if (Number.isFinite(n)) {
      subtitlePrefs.value.offset = Math.round(n * 10) / 10;
      persistSubtitlePrefs();
      shiftSubtitleTimeline();
      syncSettingEcho(
        "subtitle-offset",
        `${subtitlePrefs.value.offset > 0 ? "+" : ""}${subtitlePrefs.value.offset}s`
      );
    }
  });
  art.value.on("subtitleLoad", () => {
    applySubtitleChrome();
    syncPlayerLabels();
  });

  (
    [
      "playing",
      "canplay",
      "canplaythrough",
      "loadeddata",
      "loadedmetadata",
    ] as const
  ).forEach((ev) =>
    art.value?.on(ev, () => {
      refreshMediaUiState(ev);
    })
  );
  art.value?.on("video:timeupdate", () => {
    if (!mediaUiReady.value) refreshMediaUiState("timeupdate");
  });

  art.value.on("error", () => {
    if (askVisible.value) {
      clearLoadingState();
      notice("请选择播放方式");
      return;
    }
    if (actualMode.value === "compat") {
      clearLoadingState();
      notice("播放失败，可下载后用本地播放器打开");
      return;
    }
    // Native failed for real (decode/network) — then compat is the right path.
    videoPlaying.value = false;
    loadProgress.value = Math.max(loadProgress.value ?? 0, 12);
    busy.value = true;
    notice("原生播放失败，切换兼容转码…");
    void switchEngine("compat", preferredCompatQuality(), true);
  });
});

watch(isPortrait, () => {
  /* control bar layout is native; nothing to rebind */
});

let releaseResourceActivity: (() => void) | undefined;
onBeforeUnmount(() => {
  releaseResourceActivity?.();
  backgroundDetached = true;
  discardPrefetchedWindow(prefetchedWindow);
  prefetchedWindow = null;
  if (backgroundPollTimer) window.clearTimeout(backgroundPollTimer);
  if (activeCompatCacheID)
    void mediaApi.cancelHLSPlayback(activeCompatCacheID).catch(() => {});
  ++switchToken;
  persistPlaybackPosition(true);
  stopLoadWaitTimer();
  if (progressTimer) window.clearInterval(progressTimer);
  if (loaderForceTimer) window.clearTimeout(loaderForceTimer);
  if (sizeHandler) {
    window.removeEventListener("resize", sizeHandler);
    window.removeEventListener("orientationchange", sizeHandler);
  }
  if (barSelectorDocHandler) {
    document.removeEventListener("click", barSelectorDocHandler);
    barSelectorDocHandler = null;
  }
  clearNativeProgressHooks();
  detachHls();
  art.value?.destroy(false);
  document.removeEventListener("keydown", handleTimelineKey, true);
  art.value = null;
});
</script>

<style scoped>
.art-player-stage {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 320px;
  background: #000;
}
.art-player-box {
  width: 100%;
  height: 100%;
  min-height: 320px;
}
.art-player-stage--timeline :deep(.art-control-progress),
.art-player-stage--timeline :deep(.art-control-time) {
  display: none !important;
}
.art-player-stage--timeline :deep(.nfb-player-feedback) {
  margin-bottom: 44px;
}
.art-player-stage--timeline :deep(.compat-timeline) {
  opacity: 0;
  pointer-events: none;
  transition: opacity 150ms ease;
}
.art-player-stage--timeline :deep(.art-control-show .compat-timeline),
.art-player-stage--timeline :deep(.art-hover .compat-timeline),
.art-player-stage--timeline.art-player-stage--busy :deep(.compat-timeline),
.art-player-stage--timeline :deep(.compat-timeline:focus-within) {
  opacity: 1;
  pointer-events: auto;
}
.art-player-box :deep(.art-loading),
.art-player-box :deep(.art-video-loading) {
  display: none !important;
}
.art-loading {
  position: absolute;
  top: 50%;
  left: 50%;
  z-index: 80;
  display: grid;
  justify-items: center;
  gap: 12px;
  color: #fff;
  transform: translate(-50%, -50%);
  pointer-events: none;
}
.art-ring {
  width: 48px;
  height: 48px;
  border: 3px solid rgb(255 255 255 / 20%);
  border-top-color: #6db3ff;
  border-radius: 50%;
  animation: art-spin 0.9s linear infinite;
}
@keyframes art-spin {
  to {
    transform: rotate(360deg);
  }
}
.art-loading-text {
  display: grid;
  gap: 4px;
  justify-items: center;
  font-size: 13px;
}
.art-loading-text span {
  color: rgb(255 255 255 / 80%);
  font-variant-numeric: tabular-nums;
  font-size: 12px;
}
.art-ask-overlay {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(0 0 0 / 48%);
}
.art-ask-card {
  width: min(400px, 100%);
  padding: 20px;
  text-align: center;
  color: #eef3ff;
  background: rgb(14 18 28 / 96%);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 14px;
}
.art-ask-title {
  font-size: 16px;
  font-weight: 650;
}
.art-ask-sub {
  margin-top: 6px;
  color: rgb(235 242 255 / 65%);
  font-size: 12px;
}
.art-ask-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 16px;
}
.art-ask-btn {
  min-width: 96px;
  min-height: 40px;
  padding: 8px 14px;
  color: #e9f2ff;
  font-size: 13px;
  font-weight: 600;
  text-align: center;
  text-decoration: none;
  background: rgb(255 255 255 / 8%);
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 10px;
  cursor: pointer;
}
.art-ask-btn--primary {
  color: #071321;
  background: #8bc0ff;
  border-color: transparent;
}
.art-player-stage :deep(.art-setting-item-left-icon) {
  flex: 0 0 auto;
}
.art-player-stage :deep(.art-setting-item-left-icon .art-icon) {
  width: 18px;
  height: 18px;
}
.art-player-stage :deep(.art-setting-item-left-text) {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.art-player-stage :deep(.art-setting-item-right-tooltip) {
  flex: 0 0 auto;
  margin-left: 12px;
  color: rgba(255, 255, 255, 0.5);
  font-weight: 400;
  font-size: 12px;
  white-space: nowrap;
}
.art-player-stage--rate-open :deep(.art-control-bar),
.art-player-stage--rate-open :deep(.art-setting),
.art-player-stage--rate-open :deep(.art-settings),
.art-player-stage--rate-open :deep(.art-subtitle),
.art-player-stage--rate-open :deep(.art-state),
.art-player-stage--rate-open :deep(.art-big-play-button),
.art-player-stage--rate-open :deep(.art-contextmenus) {
  opacity: 0 !important;
  pointer-events: none !important;
  visibility: hidden !important;
}
.art-player-stage :deep(.art-video-player.art-control-show .art-subtitle),
.art-player-stage :deep(.art-video-player.art-hover .art-subtitle) {
  bottom: calc(
    var(--art-control-height) + var(--art-subtitle-bottom)
  ) !important;
}
.art-player-stage--mobile :deep(.art-pip),
.art-player-stage--mobile :deep(.art-fullscreen-web),
.art-player-stage--mobile :deep([class*="art-pip"]),
.art-player-stage--mobile :deep([class*="fullscreen-web"]) {
  display: none !important;
}
.art-player-stage--portrait :deep(.art-quality-label),
.art-player-stage--portrait :deep(.art-mode-label) {
  display: none !important;
}
.art-bar-label {
  padding: 0 8px;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  white-space: nowrap;
}
/* Independent bottom-bar popups (not settings panel) */
.art-player-stage :deep(.art-bottom),
.art-player-stage :deep(.art-controls),
.art-player-stage :deep(.art-controls-left),
.art-player-stage :deep(.art-controls-center),
.art-player-stage :deep(.art-controls-right),
.art-player-stage :deep(.art-control) {
  overflow: visible !important;
}
.art-player-stage :deep(.art-control-selector) {
  position: relative;
  overflow: visible !important;
}
/* Raise bottom above settings so chip popups are not covered */
.art-player-stage :deep(.art-video-player.art-bar-selector-open .art-bottom),
.art-player-stage :deep(.art-bar-selector-open .art-bottom) {
  z-index: 120 !important;
}
.art-player-stage :deep(.art-control-selector .art-selector-list) {
  position: absolute;
  left: 50%;
  bottom: calc(var(--art-control-height) + 6px);
  z-index: 160;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  min-width: 104px;
  max-height: min(42vh, 280px);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 6px;
  margin: 0;
  transform: translate(-50%, 10px);
  opacity: 0;
  pointer-events: none;
  color: #f3f6fb;
  background: rgba(28, 30, 36, 0.82);
  backdrop-filter: blur(18px) saturate(1.25);
  -webkit-backdrop-filter: blur(18px) saturate(1.25);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 12px;
  box-shadow:
    0 16px 40px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  transition:
    opacity 0.15s ease,
    transform 0.15s ease;
}
.art-player-stage
  :deep(.art-control-selector.art-selector-open .art-selector-list) {
  opacity: 1;
  transform: translate(-50%, 0);
  pointer-events: auto;
}
/* Mobile: list must fit without fighting volume gestures */
.art-player-stage :deep(.art-control-selector .art-selector-list) {
  max-height: min(48vh, 340px) !important;
  overscroll-behavior: contain;
  touch-action: pan-y;
  -webkit-overflow-scrolling: touch;
}
.art-player-stage :deep(.art-bar-selector-open .art-video) {
  pointer-events: none !important;
}
.art-player-stage :deep(.nfb-player-feedback) {
  position: absolute;
  left: var(--art-padding, 10px);
  right: var(--art-padding, 10px);
  bottom: calc(
    var(--art-control-height, 46px) + var(--art-bottom-gap, 5px) + 10px
  );
  z-index: 180;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  pointer-events: none;
}
.art-player-stage :deep(.nfb-player-feedback .art-notice) {
  position: static;
  inset: auto;
  width: auto;
  height: auto;
  max-width: 48%;
  margin-left: auto;
  padding: 0;
  text-align: right;
}
.art-player-stage :deep(.nfb-player-feedback .art-notice-inner) {
  padding: 10px 12px;
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
  text-align: left;
}
.art-player-stage :deep(.art-layer-auto-playback) {
  z-index: 180;
  display: flex;
  gap: 10px;
  align-items: center;
  position: static;
  min-width: 0;
  max-width: 52%;
  flex-wrap: wrap;
  pointer-events: auto;
  padding: 10px;
  line-height: 1;
  color: var(--art-font-color, #fff);
  border-radius: var(--art-border-radius, 3px);
  background-color: var(--art-widget-background, rgba(0, 0, 0, 0.85));
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  backdrop-filter: saturate(180%) blur(20px);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
}
.art-player-stage :deep(.art-layer-auto-playback .art-auto-playback-close) {
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
}
.art-player-stage :deep(.art-layer-auto-playback .art-auto-playback-close svg) {
  width: 15px;
  height: 15px;
  fill: var(--art-theme, #2979ff);
}
.art-player-stage :deep(.art-layer-auto-playback .art-auto-playback-close) {
  color: var(--art-theme, #2979ff);
  font-size: 16px;
  line-height: 1;
}
.art-player-stage :deep(.art-layer-auto-playback .art-auto-playback-last) {
  color: var(--art-font-color, #fff);
  font-size: 13px;
  white-space: normal;
  line-height: 1.5;
}
.art-player-stage :deep(.art-layer-auto-playback .art-auto-playback-jump) {
  color: var(--art-theme, #2979ff);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}
.art-player-stage
  :deep(.art-layer-auto-playback .art-auto-playback-jump:hover) {
  filter: brightness(1.12);
}
/* Player-themed PathPicker — full dark glass, no light-theme leakage */
.art-player-stage :deep(.path-picker-backdrop) {
  z-index: 100002;
  background: rgba(0, 0, 0, 0.55);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}
.art-player-stage :deep(.path-picker) {
  display: grid;
  height: auto;
  max-height: min(64vh, 520px);
  color: #e8f0ff;
  background: rgba(18, 20, 28, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 16px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.55);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  backdrop-filter: blur(20px) saturate(160%);
  --borderPrimary: rgba(255, 255, 255, 0.12);
  --surfacePrimary: rgba(255, 255, 255, 0.04);
  --surfaceSecondary: rgba(255, 255, 255, 0.06);
  --textPrimary: #f3f6fb;
  --textSecondary: rgba(232, 240, 255, 0.72);
  --blue: #6da8ff;
  --hover: rgba(255, 255, 255, 0.08);
}
.art-player-stage :deep(.path-picker__header) {
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  text-align: left;
}
.art-player-stage :deep(.path-picker__header h2),
.art-player-stage :deep(.path-picker__header p),
.art-player-stage :deep(.path-picker__header button) {
  margin: 0;
  color: #fff;
  text-align: left;
  background: transparent;
}
.art-player-stage :deep(.path-picker__header p) {
  color: rgba(109, 168, 255, 0.9);
  font-size: 11px;
}
.art-player-stage :deep(.path-picker__location) {
  min-height: 40px;
  padding: 8px 14px;
  color: rgba(255, 255, 255, 0.88);
  background: rgba(255, 255, 255, 0.06);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.art-player-stage :deep(.path-picker__location button),
.art-player-stage :deep(.path-picker__location button:hover),
.art-player-stage :deep(.path-picker__entry-main),
.art-player-stage :deep(.path-picker__entry-enter) {
  color: rgba(232, 240, 255, 0.92);
  background: transparent;
}
.art-player-stage :deep(.path-picker__location button:hover) {
  color: #9ec9ff;
  background: rgba(255, 255, 255, 0.08);
}
.art-player-stage :deep(.path-picker__list) {
  min-height: 0;
  max-height: none;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: transparent;
}
.art-player-stage :deep(.path-picker__entry) {
  color: rgba(255, 255, 255, 0.88);
  background: transparent;
  border: 1px solid transparent;
  border-bottom-color: rgba(255, 255, 255, 0.06);
}
.art-player-stage :deep(.path-picker__entry:hover),
.art-player-stage :deep(.path-picker__entry:focus-within),
.art-player-stage :deep(.path-picker__entry.selected) {
  color: #9ec9ff;
  background: rgba(109, 168, 255, 0.12);
  border-color: rgba(109, 168, 255, 0.28);
}
.art-player-stage :deep(.path-picker__empty),
.art-player-stage :deep(.path-picker__loading),
.art-player-stage :deep(.path-picker__error) {
  color: rgba(255, 255, 255, 0.7);
  min-height: 72px;
}
.art-player-stage :deep(.path-picker__footer) {
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.2);
}
.art-player-stage :deep(.path-picker__footer p) {
  color: rgba(255, 255, 255, 0.55);
}
.art-player-stage :deep(.path-picker__footer button) {
  color: #e8f0ff;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.14);
}
.art-player-stage :deep(.path-picker__footer button.primary) {
  color: #0b1220;
  background: linear-gradient(180deg, #9ec9ff, #6da8ff);
  border: 0;
}
.art-player-stage :deep(.path-picker__entry-action input[type="checkbox"]) {
  accent-color: #6da8ff;
}
/* Force click-only: neutralize ArtPlayer hover-open */
.art-player-stage :deep(.art-control-selector:hover .art-selector-list) {
  opacity: 0 !important;
  transform: translate(-50%, 10px) !important;
  pointer-events: none !important;
}
.art-player-stage
  :deep(.art-control-selector.art-selector-open:hover .art-selector-list) {
  opacity: 1 !important;
  transform: translate(-50%, 0) !important;
  pointer-events: auto !important;
}
/* Kill ArtPlayer hint tooltips on bar chips — they cover the list */
.art-player-stage :deep(.art-control-playback-mode),
.art-player-stage :deep(.art-control-playback-rate),
.art-player-stage :deep(.art-control-playback-quality) {
  pointer-events: auto;
}
.art-player-stage :deep(.art-control-playback-mode[class*="hint"]),
.art-player-stage :deep(.art-control-playback-rate[class*="hint"]),
.art-player-stage :deep(.art-control-playback-quality[class*="hint"]) {
  /* hint tooltips are pseudo-elements; ensure they never show */
}
.art-player-stage :deep(.art-control-playback-mode.hint--top:after),
.art-player-stage :deep(.art-control-playback-mode.hint--top:before),
.art-player-stage :deep(.art-control-playback-rate.hint--top:after),
.art-player-stage :deep(.art-control-playback-rate.hint--top:before),
.art-player-stage :deep(.art-control-playback-quality.hint--top:after),
.art-player-stage :deep(.art-control-playback-quality.hint--top:before) {
  display: none !important;
  opacity: 0 !important;
  visibility: hidden !important;
}
.art-player-stage :deep(.art-selector-item) {
  min-width: 88px;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 600;
  text-align: center;
  white-space: nowrap;
  border-radius: 8px;
  cursor: pointer;
}
.art-player-stage :deep(.art-selector-item:hover),
.art-player-stage :deep(.art-selector-item.art-current) {
  color: #9ec9ff;
  background: rgba(255, 255, 255, 0.08);
}
.art-player-stage :deep(.art-settings),
.art-player-stage :deep(.art-setting-panel) {
  max-height: min(70vh, 480px);
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  touch-action: pan-y;
  -webkit-overflow-scrolling: touch;
}
.art-player-stage :deep(.art-settings) {
  /* The progress hit area sits above the controls and must not cover rows. */
  bottom: calc(var(--art-control-height, 46px) + 21px);
}
.art-modal-mask {
  position: absolute;
  inset: 0;
  z-index: 100000;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.55);
}
.art-modal {
  width: min(320px, calc(100% - 28px));
  padding: 20px 20px 16px;
  color: #f5f7fb;
  background: rgba(32, 34, 40, 0.72);
  backdrop-filter: blur(18px) saturate(1.25);
  -webkit-backdrop-filter: blur(18px) saturate(1.25);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 14px;
  box-shadow:
    0 16px 48px rgba(0, 0, 0, 0.4),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}
.art-modal-title {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.01em;
  text-align: center;
}
.art-modal-sub {
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 11px;
  text-align: center;
}
.art-modal-field {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  margin: 16px 0 10px;
}
.art-modal-field input {
  width: 108px;
  height: 40px;
  color: #fff;
  font-size: 17px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  text-align: center;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 10px;
  outline: none;
  appearance: textfield;
}
.art-modal-field input::-webkit-outer-spin-button,
.art-modal-field input::-webkit-inner-spin-button {
  appearance: none;
  margin: 0;
}
.art-modal-field input:focus {
  border-color: #7cb5ff;
  box-shadow: 0 0 0 3px rgba(124, 181, 255, 0.22);
}
.art-modal-unit {
  color: rgba(255, 255, 255, 0.6);
  font-size: 13px;
  font-weight: 600;
}
.art-modal-range {
  width: 100%;
  margin: 0 0 16px;
  accent-color: #7cb5ff;
}
.art-modal-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
}
.art-modal-btn {
  min-width: 96px;
  min-height: 38px;
  padding: 0 14px;
  color: #f0f4ff;
  font-size: 13px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 10px;
  cursor: pointer;
}
.art-modal-btn--ok {
  color: #0b1220;
  background: linear-gradient(180deg, #9ec9ff, #6da8ff);
  border-color: transparent;
}
@media (max-width: 720px) {
  .art-modal {
    width: min(300px, calc(100% - 24px));
    padding: 16px;
  }
}
</style>
