<template>
  <div
    ref="stage"
    class="media-video-stage"
    :class="{
      'media-video-stage--awaiting-source': !sourceAttached,
      'media-video-stage--compatibility-open': compatibilityPanelOpen,
    }"
    :style="{ '--video-brightness': brightness }"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerCancel"
  >
    <video
      ref="videoPlayer"
      class="video-max video-js vjs-big-play-centered"
      controls
      playsinline
      preload="metadata"
      :poster="posterSource || undefined"
    >
      <track
        v-for="(sub, index) in subtitles"
        :key="index"
        kind="subtitles"
        :src="sub"
        :label="subLabel(sub)"
        :default="index === 0"
      />
      <p class="vjs-no-js">
        您的浏览器不支持嵌入式视频播放。
        <a :href="source">下载视频</a>
      </p>
    </video>

    <div v-if="gestureHud" class="media-gesture-hud" role="status">
      <AppIcon :name="mediaIcon(gestureHud.icon)" :size="28" />
      <strong>{{ gestureHud.value }}</strong>
      <span>{{ gestureHud.label }}</span>
    </div>

    <div
      v-if="showVideoLoadOverlay"
      class="media-video-status"
      :class="`media-video-status--${videoLoadState}`"
      role="status"
      aria-live="polite"
    >
      <span class="media-video-status__indicator" aria-hidden="true"></span>
      <strong>
        {{ videoLoadState === "stalled" ? "缓冲中" : "正在加载视频" }}
      </strong>
      <small>
        {{
          videoLoadState === "stalled"
            ? "正在读取视频数据，可能因文件较大或磁盘繁忙而较慢"
            : "正在连接视频源，请稍候"
        }}
      </small>
      <button
        v-if="videoLoadState === 'stalled'"
        type="button"
        @click.stop="retryVideoSource"
      >
        重新加载
      </button>
    </div>

    <div
      v-if="shouldShowResumePosition(restoredPosition)"
      class="media-resume-chip"
      role="status"
      @pointerdown.stop
    >
      <AppIcon :name="mediaIcon('history')" :size="18" />
      <span
        >{{ resumeApplied ? "已从" : "将从" }}
        {{ formatMediaTime(restoredPosition) }} 继续</span
      >
      <button type="button" @click.stop="restartFromBeginning">从头播放</button>
    </div>

    <div v-if="progressMessage" class="media-progress-message" role="status">
      {{ progressMessage }}
    </div>

    <button
      v-if="showCompatibilityBadge"
      type="button"
      class="media-compatibility-badge"
      @pointerdown.stop
      @click.stop="compatibilityPanelOpen = true"
    >
      <AppIcon :name="mediaIcon('movie_filter')" :size="18" />
      <span>{{ compatibilityBadgeLabel }}</span>
      <AppIcon
        class="media-compatibility-badge__arrow"
        :name="mediaIcon('expand_more')"
        :size="17"
      />
    </button>

    <section
      v-if="compatibilityPanelOpen"
      class="media-compatibility-card"
      :class="`media-compatibility-card--${compatibilityState}`"
      aria-live="polite"
      @pointerdown.stop
      @pointermove.stop
      @pointerup.stop
    >
      <div class="media-compatibility-card__body">
        <div class="media-compatibility-card__heading">
          <div class="media-compatibility-card__identity">
            <div class="media-compatibility-card__icon" aria-hidden="true">
              <AppIcon :name="mediaIcon(compatibilityCopy.icon)" :size="22" />
            </div>
            <div class="media-compatibility-card__title">
              <span>兼容播放</span>
              <strong>{{ compatibilityCopy.title }}</strong>
            </div>
          </div>
          <button
            type="button"
            class="media-compatibility-card__close"
            aria-label="收起兼容播放状态"
            @click.stop="compatibilityPanelOpen = false"
          >
            <AppIcon :name="mediaIcon('close')" :size="20" />
          </button>
        </div>
        <p>{{ compatibilityCopy.description }}</p>
        <p
          v-if="compatibilityProgressSummary && !compatibilityProgressVisible"
          class="media-compatibility-card__progress"
        >
          {{ compatibilityProgressSummary }}；
          <template v-if="compatibilityStatus?.state === 'completed'">
            兼容文件已生成，可拖动进度。
          </template>
          <template v-else>完整兼容文件生成后即可拖动进度。</template>
        </p>
        <div
          v-if="compatibilityProgressVisible"
          class="media-compatibility-progress"
          role="progressbar"
          aria-label="兼容视频生成进度"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="compatibilityProgressPercent"
          :aria-valuetext="compatibilityProgressText"
        >
          <span class="media-compatibility-progress__track">
            <span
              class="media-compatibility-progress__value"
              :style="{ width: `${compatibilityProgressPercent}%` }"
            ></span>
          </span>
          <span class="media-compatibility-progress__text">
            {{ compatibilityProgressText }}
          </span>
        </div>
        <small v-if="compatibilityNetworkError" role="alert">
          <AppIcon :name="mediaIcon('cloud_off')" :size="16" />
          {{ compatibilityNetworkError }}
        </small>
        <div class="media-compatibility-card__actions">
          <button
            v-if="canStartCompatibility"
            type="button"
            class="media-compatibility-action media-compatibility-action--primary"
            :disabled="compatibilityBusy"
            @click.stop="startCompatibilityPlayback"
          >
            <AppIcon :name="mediaIcon('movie_filter')" :size="18" />
            {{ compatibilityBusy ? "正在提交…" : compatibilityStartLabel }}
          </button>
          <button
            v-if="canCancelCompatibility"
            type="button"
            class="media-compatibility-action"
            :disabled="compatibilityBusy"
            @click.stop="cancelCompatibilityPlayback"
          >
            <AppIcon :name="mediaIcon('stop_circle')" :size="18" />
            {{ compatibilityBusy ? "正在取消…" : "取消任务" }}
          </button>
          <button
            v-if="canActivateCompatibility"
            type="button"
            class="media-compatibility-action media-compatibility-action--primary"
            @click.stop="useCompatibilityPlayback"
          >
            <AppIcon :name="mediaIcon('play_circle')" :size="18" />
            使用兼容版本
          </button>
          <button
            v-if="canTryDirectPlayback"
            type="button"
            class="media-compatibility-action"
            @click.stop="tryDirectPlayback"
          >
            <AppIcon :name="mediaIcon('play_arrow')" :size="18" />
            {{ directPlaybackFailed ? "再次尝试原视频" : "尝试原视频" }}
          </button>
          <a
            v-if="showCompatibilityFallbacks"
            class="media-compatibility-action"
            :href="downloadFallbackSource"
            download
          >
            <AppIcon :name="mediaIcon('download')" :size="18" />
            下载原文件
          </a>
          <a
            v-if="showCompatibilityFallbacks"
            class="media-compatibility-action"
            :href="directFallbackSource"
            target="_blank"
            rel="noopener"
          >
            <AppIcon :name="mediaIcon('open_in_new')" :size="18" />
            直接打开
          </a>
          <button
            v-if="hlsActive"
            type="button"
            class="media-compatibility-action"
            @click.stop="tryDirectPlayback"
          >
            <AppIcon :name="mediaIcon('undo')" :size="18" />
            返回原视频
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { useResourceActivity } from "@/utils/sessionActivity";
import { media as mediaApi } from "@/api";
import {
  clampMediaValue,
  detectVideoGestureAxis,
  formatMediaTime,
  seekFromDoubleTap,
  seekFromSwipe,
  shouldShowResumePosition,
  type VideoGestureAxis,
} from "@/utils/videoGestures";
import {
  getDirectVideoFailure,
  getDirectVideoFailureCopy,
  getVideoSourceType,
  getNativeContainerPlayback,
  isHevcCodec,
  isPlaybackPositionSeekable,
  supportsH264CompatibilityPlayback,
  type DirectVideoFailure,
} from "@/utils/videoPlayback";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { mediaIcon } from "@/utils/mediaIconSemantics";
import videojs from "video.js";
import type Player from "video.js/dist/types/player";
import type { HLSPlaybackState, HLSPlaybackStatus } from "@/api/media";
import { resolveControlsTimeoutMs } from "@/utils/playerControls";
import { useAuthStore } from "@/stores/auth";
import { useAccountPreferencesStore } from "@/stores/accountPreferences";
import "videojs-hotkeys";
import "video.js/dist/video-js.min.css";

const props = withDefaults(
  defineProps<{
    path: string;
    wirePath?: string;
    source: string;
    poster?: string;
    downloadSource?: string;
    directSource?: string;
    subtitles?: string[];
    options?: Record<string, unknown>;
  }>(),
  {
    subtitles: () => [],
    options: () => ({}),
    poster: "",
    downloadSource: "",
    directSource: "",
  }
);

const mediaPath = computed(() => ({
  path: props.path,
  wirePath: props.wirePath,
}));
let unsupportedSaveNoticeShown = false;

const videoPlayer = ref<HTMLVideoElement | null>(null);
useResourceActivity(videoPlayer, () => props.source);
const stage = ref<HTMLDivElement | null>(null);
const player = ref<Player | null>(null);
const posterSource = ref("");
const brightness = ref(1);
const restoredPosition = ref(0);
const resumeApplied = ref(false);
const progressMessage = ref("");
const compatibilityPanelOpen = ref(false);
const compatibilityBusy = ref(false);
const compatibilityNetworkError = ref("");
const compatibilityStatus = ref<HLSPlaybackStatus | null>(null);
const directPlaybackFailure = ref<DirectVideoFailure | null>(null);
const directPlaybackFailed = computed(
  () => directPlaybackFailure.value !== null
);
const mediaCodec = ref("");
const authStore = useAuthStore();
const accountPreferences = useAccountPreferencesStore();
const controlsTimeoutMs = ref(
  resolveControlsTimeoutMs(
    authStore.user?.playerPreferences?.controlsTimeoutSec
  )
);

watch(
  () => authStore.user?.playerPreferences?.controlsTimeoutSec,
  (sec) => {
    controlsTimeoutMs.value = resolveControlsTimeoutMs(sec);
  }
);

watch(
  () => props.path,
  () => {
    controlsTimeoutMs.value = resolveControlsTimeoutMs(
      authStore.user?.playerPreferences?.controlsTimeoutSec
    );
  }
);
const hlsActive = ref(false);
const nativeProbeBusy = ref(false);
// Do not attach containers that the active browser has already declared
// unsupported.  Attaching them makes Chromium issue a full metadata/range
// request before it can show the compatibility action, which is especially
// painful on NAS-hosted MKV/MOV files.  The user can still explicitly retry
// the original source from the compatibility panel.
const sourceAttached = ref(true);
type VideoLoadState = "idle" | "loading" | "stalled" | "ready" | "error";
const videoLoadState = ref<VideoLoadState>(
  sourceAttached.value ? "loading" : "idle"
);
const loadingOverlayVisible = ref(false);
const gestureHud = ref<{
  icon: string;
  value: string;
  label: string;
} | null>(null);

let disposed = false;
let progressRequest = 0;
let lastSavedAt = 0;
let lastSavedPosition = -1;
let suppressPersistenceUntil = 0;
let messageTimer: number | null = null;
let hudTimer: number | null = null;
let tapTimer: number | null = null;
let lastTap = 0;
let compatibilityPollTimer: number | null = null;
let compatibilityRequest = 0;
let nativeProbeRequest = 0;
let nativeProbeController: AbortController | null = null;
let loadStateTimer: number | null = null;
let activeHLSURL = "";
let hlsResumeCleanup: (() => void) | null = null;
let pendingResume: {
  path: string;
  position: number;
  request: number;
  waiting: boolean;
} | null = null;
const PLAYBACK_SAVE_INTERVAL_MS = 8_000;
let gesture:
  | {
      pointerId: number;
      startX: number;
      startY: number;
      startPosition: number;
      startVolume: number;
      startBrightness: number;
      axis: VideoGestureAxis | null;
    }
  | undefined;

nextTick(initVideoPlayer);

watch(
  () => [props.path, props.source, props.wirePath] as const,
  ([nextPath, nextSource], previous) => {
    if (!player.value || !previous) return;
    void persistPlayback(true, previous[0], previous[2]);
    pendingResume = null;
    restoredPosition.value = 0;
    resumeApplied.value = false;
    posterSource.value = "";
    brightness.value = 1;
    suppressPersistenceUntil = 0;
    resetCompatibility();
    player.value.pause();
    player.value.reset();
    void prepareDirectPlayback(nextPath, nextSource);
    if (!shouldAttachDirectSource()) void probeNativeContainer(nextPath);
    void restorePlayback(nextPath);
  }
);

watch(
  () => props.poster,
  () => {
    posterSource.value = "";
    if (videoLoadState.value === "ready") loadPosterAfterVideoReady();
  }
);

onBeforeUnmount(() => {
  disposed = true;
  void persistPlayback(true);
  if (messageTimer) window.clearTimeout(messageTimer);
  if (hudTimer) window.clearTimeout(hudTimer);
  if (tapTimer) window.clearTimeout(tapTimer);
  clearLoadStateTimer();
  stopCompatibilityPolling();
  clearHLSResumeWait();
  compatibilityRequest++;
  nativeProbeRequest++;
  nativeProbeController?.abort();
  nativeProbeController = null;
  player.value?.dispose();
  player.value = null;
});

watch(
  () => props.path,
  () => {
    controlsTimeoutMs.value = resolveControlsTimeoutMs(
      authStore.user?.playerPreferences?.controlsTimeoutSec
    );
  }
);

const DEFAULT_RATES = [
  0.25, 0.5, 0.75, 1, 1.15, 1.25, 1.5, 1.75, 2, 2.5, 3, 4, 5,
];

const accountPlaybackMode = computed(() =>
  normalizePlaybackMode(
    authStore.user?.playerPreferences?.playbackMode || "native"
  )
);
const accountPlaybackRate = computed(() => {
  const raw = authStore.user?.playerPreferences?.playbackRate;
  if (raw == null || !Number.isFinite(raw)) return 1;
  return Math.min(5, Math.max(0.1, Math.round(raw * 100) / 100));
});
const sessionPlaybackMode = ref<string>("native");
const playbackRates = computed(() => {
  const rates = [...DEFAULT_RATES];
  const r = accountPlaybackRate.value;
  if (!rates.includes(r)) rates.push(r);
  return rates.sort((a, b) => a - b);
});

function normalizePlaybackMode(raw: string) {
  const v = (raw || "").toLowerCase();
  if (v === "compat" || v === "ask") return v;
  return "native";
}

function applyPlaybackRate(rate: number) {
  const p = player.value;
  if (!p) return;
  const v = Math.min(5, Math.max(0.1, Math.round(rate * 100) / 100));
  p.playbackRate(v);
  void persistPlayerPrefs({ playbackRate: v });
}

function persistPlayerPrefs(patch: {
  playbackMode?: string;
  playbackRate?: number;
}) {
  const userId = authStore.user?.id;
  if (!userId) return;
  const prev = authStore.user?.playerPreferences || {};
  const next = {
    ...prev,
    controlsTimeoutSec: prev.controlsTimeoutSec ?? 4,
    playbackMode:
      patch.playbackMode ??
      normalizePlaybackMode(prev.playbackMode || "native"),
    playbackRate: patch.playbackRate ?? prev.playbackRate ?? 1,
    resumeMode: prev.resumeMode || "resume",
    resumeMinSec: prev.resumeMinSec ?? 10,
  };
  return accountPreferences.save({ playerPreferences: next }).catch(() => {
    showProgressMessage("播放器偏好保存失败");
  });
}

function switchPlaybackMode(mode: "native" | "compat" | "ask") {
  sessionPlaybackMode.value = mode;
  void persistPlayerPrefs({ playbackMode: mode });
  if (mode === "compat") {
    void startCompatibilityPlayback();
    return;
  }
  if (mode === "native") {
    compatibilityPanelOpen.value = false;
    void tryDirectPlayback();
    return;
  }
  compatibilityPanelOpen.value = true;
}

function playerControlBarEl(): HTMLElement | null {
  const p = player.value as unknown as {
    controlBar?: { el?: () => Element | null };
  } | null;
  return (p?.controlBar?.el?.() as HTMLElement | null) ?? null;
}

function bindPlaybackModeButtons() {
  const bar = playerControlBarEl();
  if (!bar || bar.querySelector(".vjs-playback-mode-button")) return;
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "vjs-playback-mode-button vjs-control vjs-button";
  btn.title = "播放方式";
  btn.textContent =
    sessionPlaybackMode.value === "compat"
      ? "转码"
      : sessionPlaybackMode.value === "ask"
        ? "选择"
        : "播放";
  btn.addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    const next =
      sessionPlaybackMode.value === "native"
        ? "compat"
        : sessionPlaybackMode.value === "compat"
          ? "ask"
          : "native";
    switchPlaybackMode(next as "native" | "compat" | "ask");
    btn.textContent =
      next === "compat" ? "转码" : next === "ask" ? "选择" : "播放";
  });
  bar.insertBefore(btn, bar.querySelector(".vjs-fullscreen-control"));
}

function bindCustomRateUI() {
  const bar = playerControlBarEl();
  if (!bar || bar.querySelector(".vjs-custom-rate")) return;
  const wrap = document.createElement("div");
  wrap.className = "vjs-custom-rate vjs-control";
  wrap.innerHTML = `<input type="number" min="0.1" max="5" step="0.01" value="${accountPlaybackRate.value}" aria-label="倍速" style="width:52px;background:transparent;border:1px solid rgba(255,255,255,.35);color:#fff;border-radius:4px;height:28px;padding:0 4px;" />`;
  const input = wrap.querySelector("input");
  input?.addEventListener("change", () => {
    applyPlaybackRate(Number((input as HTMLInputElement).value));
  });
  input?.addEventListener("click", (e) => e.stopPropagation());
  const rateBtn = bar.querySelector(".vjs-playback-rate");
  if (rateBtn?.parentNode) {
    rateBtn.parentNode.insertBefore(wrap, rateBtn);
  } else {
    bar.insertBefore(wrap, bar.querySelector(".vjs-fullscreen-control"));
  }
}

async function initVideoPlayer() {
  try {
    const initialPath = props.path;
    const lang = document.documentElement.lang;
    const code = languageImports[lang] ? lang : "en";
    // Do not make the first useful player frame wait for a locale chunk. The
    // controls and compatibility state are more important than translated
    // labels during a slow NAS cold start; the local language pack is applied
    // to the already-visible shell as soon as it is available.
    const languagePackPromise = languageImports[lang]
      ? languageImports[lang]?.()
      : Promise.resolve(null);
    if (disposed || !videoPlayer.value || props.path !== initialPath) return;
    const initialSource = sourceAttached.value
      ? {
          sources: buildDirectSource(props.path, props.source),
        }
      : { sources: [] };
    player.value = videojs(
      videoPlayer.value,
      getOptions(props.options, { language: code }, initialSource, {
        playbackRates: playbackRates.value,
      })
    );
    bindControlKeepAlive(player.value);
    sessionPlaybackMode.value = accountPlaybackMode.value;
    player.value.ready(() => {
      bindPlaybackModeButtons();
      bindCustomRateUI();
      const p = player.value;
      if (p) p.playbackRate(accountPlaybackRate.value);
      if (sessionPlaybackMode.value === "compat") {
        void startCompatibilityPlayback();
      } else if (sessionPlaybackMode.value === "ask") {
        compatibilityPanelOpen.value = true;
      }
    });
    player.value.on("timeupdate", onTimeUpdate);
    player.value.on("pause", () => void persistPlayback(true));
    player.value.on("seeked", () => void persistPlayback(true));
    player.value.on("ended", () => void persistPlayback(true));
    player.value.on("error", onPlayerError);
    player.value.on("play", applyPendingResume);
    player.value.on("loadstart", beginVideoLoading);
    player.value.on("canplay", onVideoReady);
    player.value.on("waiting", onVideoWaiting);
    player.value.on("stalled", onVideoWaiting);
    player.value.on("playing", onPlayerPlaying);
    void languagePackPromise
      ?.then((languagePack) => {
        if (
          !languagePack ||
          disposed ||
          !player.value ||
          props.path !== initialPath
        ) {
          return;
        }
        videojs.addLanguage(code, languagePack.default);
        player.value.language(code);
      })
      .catch(() => {
        // The player remains usable with Video.js' built-in labels when a
        // locale chunk cannot be loaded from the local bundle.
      });
    if (sourceAttached.value) beginVideoLoading();
    else {
      // Compatibility panel is opt-in after a real native failure, not on mount.
      compatibilityPanelOpen.value = false;
    }
    const playbackPromise = restorePlayback(props.path);
    await playbackPromise;
  } catch (error) {
    console.error("Error initializing video player:", error);
    showProgressMessage("视频播放器初始化失败");
  }
}

function getOptions(...sources: Record<string, unknown>[]) {
  const timeoutSec = resolveControlsTimeoutMs(
    authStore.user?.playerPreferences?.controlsTimeoutSec
  );
  const options = {
    // 0 = never hide; 1–20s from account preference.
    inactivityTimeout: timeoutSec,
    controlBar: {
      skipButtons: { forward: 10, backward: 10 },
      playbackRates: playbackRates.value,
    },
    html5: {
      nativeTextTracks: false,
      nativeControlsForTouch: false,
    },
    plugins: {
      hotkeys: {
        volumeStep: 0.1,
        seekStep: 10,
        enableModifiersForNumbers: false,
      },
    },
  };
  return videojs.obj.merge(options, ...sources);
}

function keepControlsActive() {
  const p = player.value;
  if (!p || disposed) return;
  try {
    p.userActive(true);
    (
      p as unknown as { reportUserActivity?: () => void }
    ).reportUserActivity?.();
  } catch {}
}

function bindControlKeepAlive(p: {
  el: () => Element | null | undefined;
  on: (type: string, fn: () => void) => void;
}) {
  const root = p.el();
  if (!root) return;
  const events = [
    "touchstart",
    "touchend",
    "touchmove",
    "pointerdown",
    "pointerup",
    "click",
  ] as const;
  const handler = () => keepControlsActive();
  for (const ev of events)
    root.addEventListener(ev, handler, { passive: true });
  p.on("play", keepControlsActive);
  p.on("pause", keepControlsActive);
}

function buildDirectSource(path: string, source: string) {
  const type = getVideoSourceType(source, path);
  // Chromium often rejects video/x-matroska by MIME even when H.264/AAC
  // tracks are decodable. Omit type for Matroska so the element can probe.
  if (!type || type === "video/x-matroska") {
    return { src: source };
  }
  return { src: source, type };
}

async function restorePlayback(path: string) {
  const request = ++progressRequest;
  pendingResume = null;
  try {
    const saved = await mediaApi.getPlayback({
      path,
      wirePath: props.wirePath,
    });
    if (request !== progressRequest || path !== props.path || !saved.exists)
      return;
    restoredPosition.value = saved.position;
    lastSavedPosition = saved.position;
    if (shouldShowResumePosition(saved.position)) {
      pendingResume = {
        path,
        position: saved.position,
        request,
        waiting: false,
      };
    }
  } catch {
    showProgressMessage("续播位置暂时无法读取");
  }
}

function applyPendingResume() {
  const resume = pendingResume;
  const currentPlayer = player.value;
  if (
    !resume ||
    !currentPlayer ||
    resume.request !== progressRequest ||
    resume.path !== props.path
  ) {
    return;
  }
  const apply = () => {
    if (
      !player.value ||
      pendingResume !== resume ||
      resume.request !== progressRequest ||
      resume.path !== props.path
    ) {
      return;
    }
    const current = player.value.currentTime() || 0;
    pendingResume = null;
    if (current <= 0.5) {
      player.value.currentTime(resume.position);
      resumeApplied.value = true;
    } else {
      restoredPosition.value = 0;
    }
  };
  if (currentPlayer.readyState() >= 1) apply();
  else if (!resume.waiting) {
    resume.waiting = true;
    currentPlayer.one("loadedmetadata", apply);
  }
}

const compatibilityState = computed<HLSPlaybackState | "idle" | "error">(() => {
  if (compatibilityStatus.value) return compatibilityStatus.value.state;
  if (compatibilityNetworkError.value) return "error";
  return "idle";
});

const compatibilityCopy = computed(() => {
  const status = compatibilityStatus.value;
  switch (compatibilityState.value) {
    case "queued":
      return {
        icon: "hourglass_top",
        title: "已加入处理队列",
        description:
          "为保证文件浏览流畅，兼容转码排队执行；轮到此视频后会生成可播放文件。",
      };
    case "preparing":
      return {
        icon: "movie_edit",
        title:
          status?.format === "webm" ||
          status?.format === "webm-copy" ||
          status?.format === "mp4-copy"
            ? "正在生成兼容视频"
            : "正在准备首个分段",
        description:
          status?.format === "copy" ||
          status?.format === "mp4-copy" ||
          status?.format === "webm-copy"
            ? status?.format === "webm-copy"
              ? "视频轨道已是浏览器较易支持的格式，服务端只重新封装，不重新编码。"
              : "正在重新封装已有的 H.264/AAC 轨道，不重新编码视频；完成后即可拖动进度。"
            : status?.format === "webm"
              ? "当前浏览器没有可用的 H.264 解码器，服务端正在生成 VP9/WebM 兼容流。"
              : "服务端 FFmpeg 正在转码（例如 H.265/HEVC → 浏览器可播格式）。可离开页面，任务会保留在任务中心。",
      };
    case "streamable":
      return {
        icon: "play_circle",
        title: "首个分段已就绪",
        description: "播放器正在切换到兼容版本，后台会继续完成剩余分段。",
      };
    case "completed":
      return {
        icon: "offline_pin",
        title:
          status?.format === "copy" ||
          status?.format === "mp4-copy" ||
          status?.format === "webm-copy"
            ? "兼容封装已缓存"
            : "兼容版本已缓存",
        description: status?.sizeBytes
          ? `${status.format === "copy" || status.format === "mp4-copy" || status.format === "webm-copy" ? "未重新编码，仅重新封装" : "转换已经完成"}，本次产物占用 ${formatCacheSize(status.sizeBytes)}，再次打开同一文件可直接复用。`
          : `${status?.format === "copy" || status?.format === "mp4-copy" || status?.format === "webm-copy" ? "未重新编码，仅重新封装" : "转换已经完成"}，再次打开同一文件可直接复用缓存。`,
      };
    case "failed":
      return {
        icon: "error_outline",
        title: "兼容播放准备失败",
        description: (() => {
          const raw = status?.error || "";
          if (/ffmpeg|executable file not found|PATH/i.test(raw)) {
            return "未检测到 FFmpeg，无法兼容转码。原视频通常可直接播放；若仍失败，请下载后用本地播放器打开，或将 ffmpeg.exe 放到服务 bin 目录后重试。";
          }
          return raw || "FFmpeg 未能生成可播放分段，可下载后使用本地播放器。";
        })(),
      };
    case "canceled":
      return {
        icon: "cancel",
        title: "兼容播放已取消",
        description: "原视频没有被修改；需要时可以重新创建兼容播放任务。",
      };
    case "error":
      return {
        icon: "cloud_off",
        title: "暂时无法读取任务状态",
        description:
          "网络恢复后可重新尝试；已提交的后台任务不会因此被自动取消。",
      };
    default:
      if (directPlaybackFailure.value) {
        return getDirectVideoFailureCopy(
          directPlaybackFailure.value,
          mediaCodec.value
        );
      }
      return {
        icon: "movie_filter",
        title: nativeProbeBusy.value
          ? "正在检查原生播放"
          : "此格式可能需要兼容播放",
        description: nativeProbeBusy.value
          ? "正在读取视频编码并检查当前浏览器能力，不会自动转码。"
          : "原视频已先尝试浏览器原生 Range 播放；只有浏览器明确不支持时才会处理。兼容文件生成后支持拖动进度，原文件不会修改。",
      };
  }
});

const compatibilityProgressPercent = computed(() => {
  const status = compatibilityStatus.value;
  const duration = status?.durationSeconds ?? 0;
  const processed = status?.processedSeconds ?? 0;
  if (!duration || !Number.isFinite(duration)) return 0;
  return Math.min(100, Math.max(0, (processed / duration) * 100));
});

const compatibilityProgressSummary = computed(() => {
  const status = compatibilityStatus.value;
  if (status?.format !== "webm") return "";
  if (!status.processedSeconds && !status.durationSeconds) return "";
  const processed = formatMediaTime(status.processedSeconds ?? 0);
  const duration = status.durationSeconds
    ? ` / ${formatMediaTime(status.durationSeconds)}`
    : "";
  return `已转换 ${processed}${duration}`;
});

const compatibilityProgressVisible = computed(() => {
  const status = compatibilityStatus.value;
  return (
    status?.format === "webm" &&
    status.durationSeconds !== undefined &&
    status.durationSeconds > 0 &&
    (status.state === "queued" || status.state === "preparing") &&
    !hlsActive.value
  );
});

const compatibilityProgressText = computed(() => {
  const status = compatibilityStatus.value;
  if (!status || !compatibilityProgressVisible.value) return "";
  if (status.state === "queued") return "等待兼容播放队列";
  return `已转换 ${formatMediaTime(status.processedSeconds ?? 0)} / ${formatMediaTime(status.durationSeconds ?? 0)}`;
});

const canStartCompatibility = computed(
  () =>
    !nativeProbeBusy.value &&
    (!compatibilityStatus.value ||
      compatibilityStatus.value.state === "failed" ||
      compatibilityStatus.value.state === "canceled")
);

const canCancelCompatibility = computed(() =>
  ["queued", "preparing", "streamable"].includes(
    compatibilityStatus.value?.state ?? ""
  )
);

const canActivateCompatibility = computed(
  () =>
    !hlsActive.value &&
    Boolean(
      compatibilityStatus.value?.playlistUrl ||
      compatibilityStatus.value?.sourceUrl
    ) &&
    (compatibilityStatus.value?.state === "streamable" ||
      compatibilityStatus.value?.state === "completed")
);

const showCompatibilityFallbacks = computed(
  () =>
    directPlaybackFailed.value || compatibilityStatus.value?.state === "failed"
);

const canTryDirectPlayback = computed(
  () => !hlsActive.value && directPlaybackFailed.value
);

const showCompatibilityBadge = computed(
  () =>
    !compatibilityPanelOpen.value &&
    Boolean(compatibilityStatus.value || hlsActive.value)
);

const compatibilityBadgeLabel = computed(() => {
  if (compatibilityStatus.value?.state === "completed") return "兼容版本已缓存";
  if (compatibilityStatus.value?.state === "failed") return "兼容播放失败";
  if (compatibilityStatus.value?.state === "canceled") return "兼容播放已取消";
  if (hlsActive.value) return "正在使用兼容播放";
  return "兼容播放准备中";
});

const compatibilityStartLabel = computed(() => {
  if (
    compatibilityStatus.value?.state === "failed" ||
    compatibilityStatus.value?.state === "canceled"
  ) {
    return "重新准备";
  }
  if (isHevcCodec(mediaCodec.value)) {
    if (
      /^(hevc|h265|x265|hev1|hvc1)$/i.test(mediaCodec.value.toLowerCase()) ||
      /hevc|h265/i.test(mediaCodec.value)
    ) {
      return "兼容转码";
    }
    return "切换为兼容";
  }
  return "启动兼容播放";
});

const downloadFallbackSource = computed(
  () => props.downloadSource || props.source
);
const directFallbackSource = computed(() => props.directSource || props.source);

function onPlayerError() {
  setVideoLoadState("error");
  if (hlsActive.value) {
    compatibilityNetworkError.value = "兼容视频流暂时中断，可重试或下载原文件";
  } else {
    directPlaybackFailure.value = getDirectVideoFailure(
      player.value?.error()?.code
    );
  }
  compatibilityPanelOpen.value = true;
  void enrichCodecHint();
}

async function enrichCodecHint() {
  const path = props.path;
  if (!path || disposed) return;
  try {
    const info = await mediaApi.getMediaInformation(
      { path, wirePath: props.wirePath },
      false
    );
    if (disposed || path !== props.path) return;
    mediaCodec.value = info.videoCodec || "";
  } catch {
    /* ignore */
  }
}

function onPlayerPlaying() {
  setVideoLoadState("ready");
  if (!hlsActive.value) {
    directPlaybackFailure.value = null;
    return;
  }
  compatibilityNetworkError.value = "";
  compatibilityPanelOpen.value = false;
}

const showVideoLoadOverlay = computed(
  () =>
    loadingOverlayVisible.value &&
    sourceAttached.value &&
    !compatibilityPanelOpen.value &&
    !directPlaybackFailed.value &&
    (videoLoadState.value === "loading" || videoLoadState.value === "stalled")
);

function clearLoadStateTimer() {
  if (loadStateTimer !== null) {
    window.clearTimeout(loadStateTimer);
    loadStateTimer = null;
  }
}

function prepareDirectPlayback(path: string, source: string) {
  if (!shouldAttachDirectSource()) {
    detachDirectSource();
    return;
  }
  attachDirectSource(path, source);
}

async function probeNativeContainer(path: string) {
  if (disposed || path !== props.path || shouldAttachDirectSource()) return;

  nativeProbeController?.abort();
  const controller = new AbortController();
  const request = ++nativeProbeRequest;
  nativeProbeController = controller;
  nativeProbeBusy.value = true;

  try {
    const information = await mediaApi.getMediaInformation(
      { path, wirePath: props.wirePath },
      false,
      controller.signal
    );
    if (
      disposed ||
      controller.signal.aborted ||
      request !== nativeProbeRequest ||
      path !== props.path
    ) {
      return;
    }
    if (
      getNativeContainerPlayback(
        path,
        information.videoCodec,
        information.audioCodec
      ) === "supported"
    ) {
      attachDirectSource(path, props.source);
    }
  } catch {
    // A failed metadata probe must not turn into a fake media error. Keep the
    // explicit compatibility action available and let the user decide.
  } finally {
    if (request === nativeProbeRequest) {
      nativeProbeBusy.value = false;
      nativeProbeController = null;
    }
  }
}

function shouldAttachDirectSource() {
  // Native-first for every container, including MKV.
  return true;
}

function detachDirectSource() {
  sourceAttached.value = false;
  directPlaybackFailure.value = null;
  compatibilityPanelOpen.value = true;
  videoLoadState.value = "idle";
  loadingOverlayVisible.value = false;
  clearLoadStateTimer();
}

function attachDirectSource(path: string, source: string) {
  if (disposed || path !== props.path) return;
  sourceAttached.value = true;
  directPlaybackFailure.value = null;
  compatibilityPanelOpen.value = false;
  const currentPlayer = player.value;
  if (!currentPlayer) return;
  beginVideoLoading();
  currentPlayer.src(buildDirectSource(path, source));
  currentPlayer.load();
}

function beginVideoLoading() {
  if (!sourceAttached.value || disposed) return;
  setVideoLoadState("loading");
}

function onVideoReady() {
  setVideoLoadState("ready");
  loadPosterAfterVideoReady();
}

// Poster generation can invoke FFmpeg and read a large video from the NAS.
// Wait until the browser has its first playable frame so the poster cannot
// compete with the initial metadata/range requests.
function loadPosterAfterVideoReady() {
  if (!props.poster || posterSource.value === props.poster || disposed) return;
  posterSource.value = props.poster;
}

function onVideoWaiting() {
  if (videoLoadState.value !== "error") setVideoLoadState("stalled");
}

function setVideoLoadState(next: VideoLoadState) {
  videoLoadState.value = next;
  clearLoadStateTimer();
  if (next !== "loading" && next !== "stalled") {
    loadingOverlayVisible.value = false;
    return;
  }
  loadingOverlayVisible.value = false;
  loadStateTimer = window.setTimeout(
    () => {
      loadStateTimer = null;
      if (videoLoadState.value === next && !disposed) {
        loadingOverlayVisible.value = true;
      }
    },
    next === "stalled" ? 320 : 240
  );
}

function retryVideoSource() {
  const currentPlayer = player.value;
  if (!currentPlayer || !sourceAttached.value) return;
  const source = hlsActive.value ? activeHLSURL : props.source;
  if (!source) return;
  const compatibilityIsWebM =
    hlsActive.value &&
    (compatibilityStatus.value?.format === "webm" ||
      compatibilityStatus.value?.format === "webm-copy");
  beginVideoLoading();
  currentPlayer.pause();
  currentPlayer.src({
    src: source,
    type: compatibilityIsWebM
      ? "video/webm"
      : hlsActive.value && compatibilityStatus.value?.format === "mp4-copy"
        ? "video/mp4"
        : hlsActive.value
          ? "application/x-mpegURL"
          : getVideoSourceType(props.source, props.path),
  });
  currentPlayer.load();
}

async function startCompatibilityPlayback() {
  if (compatibilityBusy.value) return;
  compatibilityBusy.value = true;
  compatibilityNetworkError.value = "";
  compatibilityPanelOpen.value = true;
  const request = ++compatibilityRequest;
  stopCompatibilityPolling();
  try {
    const status = await mediaApi.startHLSPlayback(
      mediaPath.value,
      supportsH264CompatibilityPlayback() ? "mp4" : "webm"
    );
    if (disposed || request !== compatibilityRequest) return;
    applyCompatibilityStatus(status, request);
  } catch (error) {
    if (request === compatibilityRequest) {
      compatibilityNetworkError.value = errorMessage(error);
    }
  } finally {
    if (request === compatibilityRequest) compatibilityBusy.value = false;
  }
}

async function cancelCompatibilityPlayback() {
  const id = compatibilityStatus.value?.id;
  if (!id || compatibilityBusy.value) return;
  compatibilityBusy.value = true;
  compatibilityNetworkError.value = "";
  const request = compatibilityRequest;
  try {
    const status = await mediaApi.cancelHLSPlayback(id);
    if (disposed || request !== compatibilityRequest) return;
    applyCompatibilityStatus(status, request);
  } catch (error) {
    if (request === compatibilityRequest) {
      compatibilityNetworkError.value = errorMessage(error);
    }
  } finally {
    if (request === compatibilityRequest) compatibilityBusy.value = false;
  }
}

function applyCompatibilityStatus(status: HLSPlaybackStatus, request: number) {
  compatibilityStatus.value = status;
  compatibilityNetworkError.value = "";
  if (
    (status.playlistUrl || status.sourceUrl) &&
    (status.state === "streamable" || status.state === "completed")
  ) {
    activateCompatibilityPlayback(status);
  }
  if (["queued", "preparing", "streamable"].includes(status.state)) {
    scheduleCompatibilityPoll(status.id, request);
  } else {
    stopCompatibilityPolling();
    if (status.state === "failed" || status.state === "canceled") {
      compatibilityPanelOpen.value = true;
    }
  }
}

function scheduleCompatibilityPoll(id: string, request: number) {
  stopCompatibilityPolling();
  compatibilityPollTimer = window.setTimeout(
    () => void pollCompatibilityStatus(id, request),
    750
  );
}

async function pollCompatibilityStatus(id: string, request: number) {
  try {
    const status = await mediaApi.getHLSPlayback(id);
    if (disposed || request !== compatibilityRequest) return;
    applyCompatibilityStatus(status, request);
  } catch (error) {
    if (disposed || request !== compatibilityRequest) return;
    compatibilityNetworkError.value = errorMessage(error);
    scheduleCompatibilityPoll(id, request);
  }
}

function stopCompatibilityPolling() {
  if (compatibilityPollTimer !== null) {
    window.clearTimeout(compatibilityPollTimer);
    compatibilityPollTimer = null;
  }
}

function activateCompatibilityPlayback(status: HLSPlaybackStatus) {
  const currentPlayer = player.value;
  const sourceURL = status.sourceUrl || status.playlistUrl;
  if (!currentPlayer || !sourceURL || activeHLSURL === sourceURL) return;
  const isWebM = status.format === "webm" || status.format === "webm-copy";
  const isMP4 = status.format === "mp4-copy";
  const resumeAt = Math.max(
    currentPlayer.currentTime() || 0,
    restoredPosition.value,
    lastSavedPosition
  );
  const volume = currentPlayer.volume();
  const muted = currentPlayer.muted();
  const playbackRate = currentPlayer.playbackRate();
  activeHLSURL = sourceURL;
  hlsActive.value = true;
  sourceAttached.value = true;
  beginVideoLoading();
  pendingResume = null;
  clearHLSResumeWait();

  // Keep the existing HTML5 tech alive while selecting the generated source.
  // Calling player.reset() here can asynchronously recreate the tech; a
  // subsequent src() then gets queued behind the reset and no media request is
  // issued in Chromium.
  currentPlayer.pause();
  currentPlayer.volume(volume);
  currentPlayer.muted(muted);
  currentPlayer.playbackRate(playbackRate);
  currentPlayer.src({
    src: sourceURL,
    type: isWebM ? "video/webm" : isMP4 ? "video/mp4" : "application/x-mpegURL",
  });
  currentPlayer.one("loadedmetadata", () => {
    props.subtitles.forEach((subtitle, index) => {
      currentPlayer.addRemoteTextTrack(
        {
          kind: "subtitles",
          src: subtitle,
          label: subLabel(subtitle),
          default: index === 0,
        },
        false
      );
    });
  });
  waitForHLSResume(currentPlayer, resumeAt);
  currentPlayer.load();
  playVideo(currentPlayer);
}

function clearHLSResumeWait() {
  hlsResumeCleanup?.();
  hlsResumeCleanup = null;
}

function waitForHLSResume(
  currentPlayer: NonNullable<typeof player.value>,
  position: number
) {
  if (!Number.isFinite(position) || position <= 0) return;

  const events = [
    "loadedmetadata",
    "durationchange",
    "progress",
    "loadeddata",
    "canplay",
    "timeupdate",
  ];
  let timeout: number | null = null;
  const apply = () => {
    if (disposed || player.value !== currentPlayer) {
      cleanup();
      return;
    }

    let seekableStart = Number.NaN;
    let seekableEnd = Number.NaN;
    try {
      const ranges = currentPlayer.seekable();
      if (ranges.length > 0) {
        const last = ranges.length - 1;
        seekableStart = ranges.start(last);
        seekableEnd = ranges.end(last);
      }
    } catch {
      return;
    }
    if (
      !isPlaybackPositionSeekable(
        position,
        seekableStart,
        seekableEnd,
        currentPlayer.duration() ?? Number.NaN
      )
    ) {
      return;
    }
    currentPlayer.currentTime(position);
    resumeApplied.value = true;
    cleanup();
  };
  const cleanup = () => {
    events.forEach((event) => currentPlayer.off(event, apply));
    if (timeout !== null) window.clearTimeout(timeout);
    if (hlsResumeCleanup === cleanup) hlsResumeCleanup = null;
  };

  hlsResumeCleanup = cleanup;
  events.forEach((event) => currentPlayer.on(event, apply));
  timeout = window.setTimeout(cleanup, 30_000);
  apply();
}

function playVideo(currentPlayer: Pick<Player, "play">) {
  const playback = currentPlayer.play();
  if (playback && typeof playback.catch === "function") {
    void playback.catch(() => {
      // Browser autoplay policy may require one more explicit tap.
    });
  }
}

function tryDirectPlayback() {
  const currentPlayer = player.value;
  if (!currentPlayer) return;
  clearHLSResumeWait();
  activeHLSURL = "";
  hlsActive.value = false;
  directPlaybackFailure.value = null;
  compatibilityNetworkError.value = "";
  sourceAttached.value = true;
  beginVideoLoading();
  currentPlayer.src(buildDirectSource(props.path, props.source));
  currentPlayer.load();
  compatibilityPanelOpen.value = false;
}

function useCompatibilityPlayback() {
  const status = compatibilityStatus.value;
  if (!status || (!status.playlistUrl && !status.sourceUrl)) return;
  activateCompatibilityPlayback(status);
}

function resetCompatibility() {
  clearHLSResumeWait();
  compatibilityRequest++;
  nativeProbeRequest++;
  nativeProbeController?.abort();
  nativeProbeController = null;
  nativeProbeBusy.value = false;
  stopCompatibilityPolling();
  compatibilityBusy.value = false;
  compatibilityNetworkError.value = "";
  compatibilityStatus.value = null;
  directPlaybackFailure.value = null;
  hlsActive.value = false;
  activeHLSURL = "";
  sourceAttached.value = shouldAttachDirectSource();
  posterSource.value = "";
  videoLoadState.value = sourceAttached.value ? "loading" : "idle";
  loadingOverlayVisible.value = false;
  clearLoadStateTimer();
  compatibilityPanelOpen.value = !sourceAttached.value;
}

function formatCacheSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  if (bytes < 1024 * 1024 * 1024)
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function onTimeUpdate() {
  if (Date.now() - lastSavedAt >= PLAYBACK_SAVE_INTERVAL_MS)
    void persistPlayback(false);
}

async function persistPlayback(
  force: boolean,
  path = props.path,
  wirePath = props.wirePath
) {
  const currentPlayer = player.value;
  if (!currentPlayer || !path || Date.now() < suppressPersistenceUntil) return;
  if (pendingResume?.path === path) return;
  const position = currentPlayer.currentTime();
  const duration = currentPlayer.duration();
  if (
    typeof position !== "number" ||
    !Number.isFinite(position) ||
    position < 0 ||
    typeof duration !== "number" ||
    !Number.isFinite(duration) ||
    duration < 0
  ) {
    return;
  }
  if (!force && Math.abs(position - lastSavedPosition) < 0.5) return;
  lastSavedAt = Date.now();
  lastSavedPosition = position;
  try {
    await mediaApi.savePlayback({ path, wirePath }, position, duration);
  } catch (error) {
    if (
      !disposed &&
      !unsupportedSaveNoticeShown &&
      error instanceof Error &&
      error.message.startsWith("服务器尚不支持")
    ) {
      unsupportedSaveNoticeShown = true;
      showProgressMessage(error.message);
    } else if (force && !disposed) showProgressMessage("播放位置暂时无法保存");
  }
}

async function restartFromBeginning() {
  pendingResume = null;
  restoredPosition.value = 0;
  resumeApplied.value = false;
  lastSavedPosition = 0;
  suppressPersistenceUntil = Date.now() + 1000;
  player.value?.currentTime(0);
  try {
    await mediaApi.clearPlayback(mediaPath.value);
    showProgressMessage("已清除续播位置");
  } catch {
    showProgressMessage("续播位置清除失败");
  }
}

function showProgressMessage(message: string) {
  progressMessage.value = message;
  if (messageTimer) window.clearTimeout(messageTimer);
  messageTimer = window.setTimeout(() => {
    progressMessage.value = "";
  }, 2400);
}

function showGestureHud(icon: string, value: string, label: string) {
  gestureHud.value = { icon, value, label };
  if (hudTimer) window.clearTimeout(hudTimer);
  hudTimer = window.setTimeout(() => {
    gestureHud.value = null;
  }, 650);
}

function isControlTarget(target: EventTarget | null) {
  return target instanceof Element && Boolean(target.closest(".vjs-control"));
}

function onPointerDown(event: PointerEvent) {
  if (event.pointerType === "mouse" || isControlTarget(event.target)) return;
  const currentPlayer = player.value;
  if (!currentPlayer || !stage.value) return;
  stage.value.setPointerCapture(event.pointerId);
  gesture = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    startPosition: currentPlayer.currentTime() ?? 0,
    startVolume: currentPlayer.volume() ?? 1,
    startBrightness: brightness.value,
    axis: null,
  };
}

function onPointerMove(event: PointerEvent) {
  if (!gesture || gesture.pointerId !== event.pointerId || !stage.value) return;
  const currentPlayer = player.value;
  if (!currentPlayer) return;
  const rect = stage.value.getBoundingClientRect();
  const deltaX = event.clientX - gesture.startX;
  const deltaY = event.clientY - gesture.startY;
  gesture.axis ??= detectVideoGestureAxis(
    deltaX,
    deltaY,
    gesture.startX - rect.left,
    rect.width
  );
  if (!gesture.axis) return;
  if (event.cancelable) event.preventDefault();

  if (gesture.axis === "seek") {
    const duration = currentPlayer.duration() ?? 0;
    const next = seekFromSwipe(
      gesture.startPosition,
      deltaX,
      rect.width,
      duration
    );
    currentPlayer.currentTime(next);
    showGestureHud("swap_horiz", formatMediaTime(next), "播放进度");
    return;
  }
  const delta = -deltaY / Math.max(rect.height, 1);
  if (gesture.axis === "brightness") {
    brightness.value = clampMediaValue(
      gesture.startBrightness + delta,
      0.4,
      1.6
    );
    showGestureHud(
      "brightness_6",
      `${Math.round(brightness.value * 100)}%`,
      "视频画面亮度"
    );
    return;
  }
  const volume = clampMediaValue(gesture.startVolume + delta, 0, 1);
  currentPlayer.volume(volume);
  showGestureHud(
    volume === 0 ? "volume_off" : "volume_up",
    `${Math.round(volume * 100)}%`,
    "音量"
  );
}

function onPointerUp(event: PointerEvent) {
  if (!gesture || gesture.pointerId !== event.pointerId || !stage.value) return;
  const completed = gesture;
  gesture = undefined;
  if (completed.axis) {
    if (completed.axis === "seek") void persistPlayback(true);
    return;
  }
  const now = Date.now();
  const rect = stage.value.getBoundingClientRect();
  if (now - lastTap <= 280) {
    lastTap = 0;
    if (tapTimer) window.clearTimeout(tapTimer);
    tapTimer = null;
    const current = player.value?.currentTime() ?? 0;
    const duration = player.value?.duration() ?? 0;
    const next = seekFromDoubleTap(
      current,
      event.clientX - rect.left,
      rect.width,
      duration
    );
    player.value?.currentTime(next);
    const forward = event.clientX - rect.left >= rect.width / 2;
    showGestureHud(
      forward ? "forward_10" : "replay_10",
      forward ? "+10 秒" : "-10 秒",
      formatMediaTime(next)
    );
    void persistPlayback(true);
    return;
  }
  lastTap = now;
  tapTimer = window.setTimeout(() => {
    const active = player.value?.userActive();
    player.value?.userActive(!active);
    tapTimer = null;
  }, 240);
}

function onPointerCancel() {
  gesture = undefined;
}

function subLabel(subUrl: string) {
  let parsed: URL;
  try {
    parsed = new URL(subUrl);
  } catch {
    parsed = new URL(subUrl, window.location.origin);
  }
  return decodeURIComponent(
    parsed.pathname
      .split("/")
      .pop()!
      .replace(/\.[^/.]+$/, "")
  );
}

interface LanguageImports {
  [key: string]: () => Promise<any>;
}

const languageImports: LanguageImports = {
  en: () => import("video.js/dist/lang/en.json"),
  "zh-cn": () => import("video.js/dist/lang/zh-CN.json"),
};
</script>

<style scoped>
.media-video-stage {
  --video-brightness: 1;
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #05070b;
  touch-action: none;
}

.video-max {
  width: 100%;
  height: 100%;
}

.media-video-stage--awaiting-source :deep(.video-js) {
  pointer-events: none;
}

.media-video-stage--awaiting-source :deep(.vjs-big-play-button),
.media-video-stage--awaiting-source :deep(.vjs-control-bar),
.media-video-stage--awaiting-source :deep(.vjs-loading-spinner) {
  display: none !important;
}

/* Mobile only: slightly larger hit targets without changing desktop chrome. */
@media (max-width: 720px) {
  .media-video-stage :deep(.vjs-control) {
    min-width: 44px;
    min-height: 44px;
  }

  .media-video-stage :deep(.vjs-button > .vjs-icon-placeholder) {
    line-height: 44px;
  }

  .media-video-stage :deep(.vjs-play-control),
  .media-video-stage :deep(.vjs-fullscreen-control) {
    min-width: 48px;
  }
}

.media-video-stage :deep(.vjs-tech) {
  filter: brightness(var(--video-brightness));
  transition: filter 120ms ease-out;
}

.media-video-status {
  position: absolute;
  z-index: 12;
  top: 50%;
  left: 50%;
  display: grid;
  min-width: 238px;
  max-width: calc(100% - 32px);
  justify-items: center;
  gap: 5px;
  padding: 15px 18px 14px;
  color: #f5f8ff;
  text-align: center;
  pointer-events: none;
  background: rgb(7 10 16 / 82%);
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 15px;
  box-shadow: 0 18px 48px rgb(0 0 0 / 30%);
  backdrop-filter: blur(14px);
  transform: translate(-50%, -50%);
}

.media-video-status--stalled {
  border-color: rgb(255 193 102 / 34%);
}

.media-video-status__indicator {
  width: 22px;
  height: 22px;
  margin-bottom: 2px;
  border: 2px solid rgb(255 255 255 / 24%);
  border-top-color: #9bc9ff;
  border-radius: 50%;
  animation: media-video-status-spin 0.9s linear infinite;
}

.media-video-status--stalled .media-video-status__indicator {
  border-top-color: #ffc166;
}

.media-video-status strong {
  font-size: 14px;
  font-weight: 650;
  line-height: 1.35;
}

.media-video-status small {
  color: rgb(235 242 255 / 68%);
  font-size: 12px;
  line-height: 1.45;
}

.media-video-status button {
  min-height: 34px;
  margin-top: 4px;
  padding: 0 12px;
  color: #061321;
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  pointer-events: auto;
  background: #a8d0ff;
  border: 0;
  border-radius: 9px;
  cursor: pointer;
}

.media-video-status button:hover {
  background: #c0dcff;
}

.media-video-status button:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}

@keyframes media-video-status-spin {
  to {
    transform: rotate(360deg);
  }
}

.media-gesture-hud {
  position: absolute;
  z-index: 12;
  top: 50%;
  left: 50%;
  display: grid;
  min-width: 126px;
  padding: 16px 18px;
  color: #fff;
  text-align: center;
  pointer-events: none;
  background: rgb(7 10 16 / 78%);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 16px;
  box-shadow: 0 16px 48px rgb(0 0 0 / 35%);
  backdrop-filter: blur(14px);
  transform: translate(-50%, -50%);
}

.media-gesture-hud i,
.media-gesture-hud > .app-icon {
  margin: 0 auto 4px;
}

.media-gesture-hud strong {
  font-size: 18px;
}

.media-gesture-hud span {
  margin-top: 2px;
  color: rgb(255 255 255 / 66%);
  font-size: 12px;
}

.media-resume-chip,
.media-progress-message {
  position: absolute;
  z-index: 11;
  top: calc(3.5rem + 12px);
  left: 50%;
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 8px;
  padding: 7px 8px 7px 12px;
  color: #fff;
  background: rgb(7 10 16 / 76%);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 999px;
  box-shadow: 0 12px 32px rgb(0 0 0 / 24%);
  backdrop-filter: blur(14px);
  transform: translateX(-50%);
}

.media-compatibility-badge {
  position: absolute;
  z-index: 13;
  top: calc(3.5rem + 12px);
  right: 16px;
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  color: #f3f7ff;
  font: inherit;
  font-size: 12px;
  background: rgb(7 10 16 / 82%);
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 999px;
  box-shadow: 0 12px 32px rgb(0 0 0 / 26%);
  backdrop-filter: blur(14px);
  cursor: pointer;
}

.media-compatibility-badge > i:first-child,
.media-compatibility-badge > .app-icon:first-child {
  color: #7cb5ff;
}

.media-compatibility-badge__arrow {
  color: rgb(255 255 255 / 58%);
}

.media-compatibility-badge:focus-visible,
.media-compatibility-action:focus-visible,
.media-compatibility-card__close:focus-visible {
  outline: 2px solid #7cb5ff;
  outline-offset: 2px;
}

.media-compatibility-card {
  position: absolute;
  z-index: 14;
  bottom: 76px;
  left: 50%;
  display: flex;
  width: min(560px, calc(100% - 24px));
  max-height: min(70%, 420px);
  flex-direction: column;
  gap: 0;
  padding: 16px 16px 14px;
  overflow: auto;
  color: #eef3ff;
  background: rgb(12 16 24 / 92%);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 14px;
  box-shadow: 0 16px 48px rgb(0 0 0 / 45%);
  transform: translateX(-50%);
}

.media-compatibility-card--failed,
.media-compatibility-card--error {
  border-color: rgb(255 140 150 / 35%);
  background: rgb(20 12 14 / 94%);
}

.media-compatibility-card--completed {
  border-color: rgb(120 210 170 / 30%);
  background: rgb(10 16 14 / 94%);
}

.media-compatibility-card__icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  color: #9ec5ff;
  background: rgb(124 181 255 / 12%);
  border-radius: 10px;
}

.media-compatibility-card--failed .media-compatibility-card__icon,
.media-compatibility-card--error .media-compatibility-card__icon {
  color: #ff9da8;
  background: rgb(255 117 132 / 12%);
}

.media-compatibility-card__body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 10px;
}

.media-compatibility-card__heading {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.media-compatibility-card__identity {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: flex-start;
  gap: 12px;
}

.media-compatibility-card__title {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  text-align: left;
}

.media-compatibility-card__heading span {
  color: #8ebfff;
  font-size: 12px;
  font-weight: 600;
}

.media-compatibility-card__heading strong {
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  line-height: 1.4;
  text-overflow: ellipsis;
}

.media-compatibility-card__close {
  display: grid;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  place-items: center;
  padding: 0;
  color: rgb(255 255 255 / 70%);
  background: rgb(255 255 255 / 6%);
  border: 0;
  border-radius: 8px;
  cursor: pointer;
}

.media-compatibility-card__close:hover {
  color: #fff;
  background: rgb(255 255 255 / 12%);
}

.media-compatibility-card__body > p {
  margin: 0;
  color: rgb(235 242 255 / 78%);
  font-size: 13px;
  line-height: 1.55;
}

.media-compatibility-card__body > .media-compatibility-card__progress {
  margin: 0;
  color: rgb(235 242 255 / 70%);
  font-size: 12px;
}

.media-compatibility-card__body > small {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  color: #ffb4bc;
  font-size: 12px;
  line-height: 1.45;
}

.media-compatibility-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.media-compatibility-action {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 12px;
  color: #e9f2ff;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
  background: rgb(255 255 255 / 8%);
  border: 1px solid rgb(255 255 255 / 12%);
  border-radius: 8px;
  cursor: pointer;
}

.media-compatibility-action:hover:not(:disabled) {
  color: #fff;
  background: rgb(255 255 255 / 13%);
}

.media-compatibility-action--primary {
  color: #071321;
  background: #8bc0ff;
  border-color: transparent;
}

.media-compatibility-action--primary:hover:not(:disabled) {
  color: #04101d;
  background: #a8d0ff;
}

.media-compatibility-action:disabled {
  cursor: wait;
  opacity: 0.56;
}

.media-compatibility-progress {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.media-compatibility-progress__track {
  display: block;
  height: 6px;
  overflow: hidden;
  background: rgb(255 255 255 / 10%);
  border-radius: 99px;
}

.media-compatibility-progress__value {
  display: block;
  height: 100%;
  background: #8bc0ff;
  border-radius: inherit;
}

.media-compatibility-progress__text {
  color: rgb(235 242 255 / 70%);
  font-size: 12px;
}

@media (max-width: 720px) {
  .media-compatibility-card {
    bottom: 64px;
    width: calc(100% - 16px);
    padding: 14px 12px 12px;
  }

  .media-compatibility-card__actions {
    gap: 6px;
  }

  .media-compatibility-action {
    flex: 1 1 calc(50% - 6px);
  }
}

.media-resume-chip button {
  min-height: 30px;
  padding: 0 12px;
  color: #fff;
  background: rgb(255 255 255 / 13%);
  border: 0;
  border-radius: 999px;
  cursor: pointer;
}

.media-resume-chip button:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}

.media-progress-message {
  top: auto;
  bottom: 76px;
  padding: 9px 14px;
  font-size: 13px;
}

@media (max-width: 736px) {
  .media-video-status {
    min-width: 210px;
    padding: 13px 15px 12px;
  }

  .media-resume-chip {
    top: calc(3rem + 12px);
    max-width: calc(100% - 24px);
    font-size: 12px;
  }

  .media-compatibility-badge {
    top: calc(3rem + 12px);
    right: 12px;
  }

  .media-compatibility-card {
    bottom: 68px;
    width: calc(100% - 24px);
    max-height: calc(100% - 132px);
    padding: 14px;
    border-radius: 16px;
  }

  .media-compatibility-card__actions {
    align-items: stretch;
  }
}

.media-video-stage--compatibility-open :deep(.vjs-error-display) {
  display: none !important;
}

@media (prefers-reduced-motion: reduce) {
  .media-video-stage :deep(.vjs-tech) {
    transition: none;
  }

  .media-video-status__indicator {
    animation: none;
  }

  .media-compatibility-card,
  .media-compatibility-badge,
  .media-compatibility-action {
    transition: none;
  }
}
</style>
