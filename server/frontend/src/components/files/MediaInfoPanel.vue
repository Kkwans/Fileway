<template>
  <aside v-if="open" class="media-info-panel" aria-label="媒体信息">
    <header>
      <div>
        <span>媒体信息</span>
        <strong>{{ resource.name }}</strong>
      </div>
      <button type="button" aria-label="关闭媒体信息" @click="$emit('close')">
        <AppIcon :name="mediaIcon('close')" :size="20" />
      </button>
    </header>

    <div class="media-info-body">
      <div v-if="loading" class="media-info-loading" role="status">
        <span></span>
        正在读取媒体信息…
      </div>
      <div v-else class="media-info-grid">
        <div>
          <span>类型</span>
          <strong>{{ typeLabel }}</strong>
        </div>
        <div>
          <span>大小</span>
          <strong>{{ filesize(resource.size) }}</strong>
        </div>
        <div>
          <span>修改时间</span>
          <strong>{{ modifiedLabel }}</strong>
        </div>
        <div v-if="info?.resolution">
          <span>分辨率</span>
          <strong
            >{{ info.resolution.width }} × {{ info.resolution.height }}</strong
          >
        </div>
        <div v-if="info?.duration">
          <span>时长</span>
          <strong>{{ formatMediaTime(info.duration) }}</strong>
        </div>
        <div v-if="info?.format">
          <span>封装</span>
          <strong>{{ info.format }}</strong>
        </div>
        <div v-if="info?.videoCodec">
          <span>视频编码</span>
          <strong>{{ info.videoCodec.toUpperCase() }}</strong>
        </div>
        <div
          v-if="
            info?.hdr || (info?.dynamicRange && info.dynamicRange !== 'unknown')
          "
        >
          <span>动态范围</span>
          <strong>{{ dynamicRangeLabel }}</strong>
        </div>
        <div v-if="info?.audioCodec">
          <span>音频编码</span>
          <strong>{{ info.audioCodec.toUpperCase() }}</strong>
        </div>
        <div v-if="info?.audioTracks?.length">
          <span>内挂音轨</span>
          <strong>{{ info.audioTracks.length }} 条</strong>
        </div>
        <div v-if="info?.subtitleTracks?.length">
          <span>内挂字幕</span>
          <strong>{{ info.subtitleTracks.length }} 条</strong>
        </div>
        <div v-if="info?.bitRate">
          <span>码率</span>
          <strong>{{ bitRateLabel }}</strong>
        </div>
        <div v-if="info?.sampleRate">
          <span>采样率</span>
          <strong>{{ (info.sampleRate / 1000).toFixed(1) }} kHz</strong>
        </div>
      </div>

      <section v-if="info?.audioTracks?.length" class="media-info-section">
        <h2>内挂音轨</h2>
        <dl>
          <template v-for="track in info.audioTracks" :key="track.index">
            <dt>轨道 {{ track.index }}</dt>
            <dd>{{ mediaTrackLabel(track) }}</dd>
          </template>
        </dl>
      </section>

      <section v-if="info?.subtitleTracks?.length" class="media-info-section">
        <h2>内挂字幕</h2>
        <dl>
          <template v-for="track in info.subtitleTracks" :key="track.index">
            <dt>轨道 {{ track.index }}</dt>
            <dd>{{ mediaTrackLabel(track) }}</dd>
          </template>
        </dl>
        <p
          v-if="
            info.subtitleTracks.some(
              (track) => track.codec === 'hdmv_pgs_subtitle'
            )
          "
          class="media-info-track-note"
        >
          PGS 图形字幕可在播放器字幕菜单中选择，需使用兼容转码。
        </p>
      </section>

      <section v-if="metadata.length" class="media-info-section">
        <h2>作品信息</h2>
        <dl>
          <template v-for="item in metadata" :key="item.label">
            <dt>{{ item.label }}</dt>
            <dd>{{ item.value }}</dd>
          </template>
        </dl>
      </section>

      <section class="media-info-section media-location-section">
        <h2>位置信息</h2>
        <p v-if="!locationRequested">
          为保护隐私，位置信息默认隐藏，仅在你主动请求后读取。
        </p>
        <p v-else-if="locationLoading">正在读取位置元数据…</p>
        <p v-else-if="locationError" class="media-info-error">
          {{ locationError }}
        </p>
        <p v-else-if="info?.location" class="media-location-value">
          <AppIcon :name="mediaIcon('location_on')" :size="18" />
          {{ info.location }}
        </p>
        <p v-else>该文件未提供可识别的位置信息。</p>
        <button
          v-if="!locationRequested"
          type="button"
          class="media-location-button"
          aria-label="显示位置信息"
          @click="requestLocation"
        >
          <AppIcon :name="mediaIcon('my_location')" :size="18" />
          显示位置信息
        </button>
      </section>

      <p v-if="error" class="media-info-error" role="status">{{ error }}</p>
      <p v-else-if="info?.technicalError" class="media-info-note">
        部分技术信息无法读取：{{ info.technicalError }}
      </p>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { media as mediaApi } from "@/api";
import type { MediaInformation, MediaTrack } from "@/api/media";
import type { Resource } from "@/types/file";
import { filesize } from "@/utils";
import { formatMediaTime } from "@/utils/videoGestures";
import { computed, ref, watch } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { mediaIcon } from "@/utils/mediaIconSemantics";

const props = defineProps<{ open: boolean; resource: Resource }>();
defineEmits<{ (event: "close"): void }>();

const info = ref<MediaInformation | null>(null);
const loading = ref(false);
const error = ref("");
const locationRequested = ref(false);
const locationLoading = ref(false);
const locationError = ref("");
let requestId = 0;

const typeLabel = computed(
  () =>
    (
      ({ image: "图片", video: "视频", audio: "音频" }) as Record<
        string,
        string
      >
    )[props.resource.type] ?? "媒体"
);
const modifiedLabel = computed(() =>
  new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(props.resource.modified))
);
const bitRateLabel = computed(() => {
  const bitRate = info.value?.bitRate ?? 0;
  return bitRate >= 1_000_000
    ? `${(bitRate / 1_000_000).toFixed(2)} Mbps`
    : `${Math.round(bitRate / 1000)} kbps`;
});
const dynamicRangeLabel = computed(() => {
  const range = info.value?.dynamicRange;
  const labels: Record<string, string> = {
    sdr: "SDR",
    pq: "HDR10 / PQ",
    hlg: "HLG",
    "dolby-vision": "Dolby Vision",
  };
  return range && labels[range]
    ? labels[range]
    : info.value?.hdr
      ? "HDR"
      : "未知";
});
const metadata = computed(() =>
  [
    { label: "标题", value: info.value?.title },
    { label: "艺术家", value: info.value?.artist },
    { label: "专辑", value: info.value?.album },
    { label: "日期", value: info.value?.date },
  ].filter((item): item is { label: string; value: string } =>
    Boolean(item.value)
  )
);

function mediaTrackLabel(track: MediaTrack) {
  const codec =
    track.codec === "hdmv_pgs_subtitle"
      ? "PGS 图形字幕"
      : track.codec.toUpperCase();
  return [
    track.title || track.language || "未命名",
    codec,
    track.default ? "默认" : "",
  ]
    .filter(Boolean)
    .join(" · ");
}

watch(
  () => [props.open, props.resource.wirePath || props.resource.path] as const,
  ([open], previous) => {
    if (!open) return;
    if (previous?.[1] !== (props.resource.wirePath || props.resource.path)) {
      info.value = null;
      locationRequested.value = false;
      locationError.value = "";
    }
    void loadInformation(false);
  },
  { immediate: true }
);

async function loadInformation(includeLocation: boolean) {
  const currentRequest = ++requestId;
  if (includeLocation) locationLoading.value = true;
  else loading.value = true;
  error.value = "";
  try {
    const loaded = await mediaApi.getMediaInformation(
      props.resource,
      includeLocation
    );
    if (currentRequest !== requestId) return;
    info.value = loaded;
  } catch (requestError) {
    if (currentRequest !== requestId) return;
    const message =
      requestError instanceof Error ? requestError.message : "媒体信息读取失败";
    if (includeLocation) locationError.value = message;
    else error.value = message;
  } finally {
    if (currentRequest === requestId) {
      loading.value = false;
      locationLoading.value = false;
    }
  }
}

function requestLocation() {
  locationRequested.value = true;
  locationError.value = "";
  void loadInformation(true);
}
</script>

<style scoped>
.media-info-panel {
  position: fixed;
  z-index: 20020;
  top: 64px;
  right: 14px;
  bottom: 14px;
  width: min(360px, calc(100vw - 28px));
  overflow: hidden;
  color: #fff;
  background: rgb(11 15 23 / 92%);
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 18px;
  box-shadow: 0 24px 70px rgb(0 0 0 / 45%);
  backdrop-filter: blur(18px) saturate(130%);
}

.media-info-panel > header {
  position: static;
  inset: auto;
  display: flex;
  width: auto;
  min-height: 66px;
  height: auto;
  align-items: center;
  justify-content: space-between;
  padding: 12px 12px 12px 18px;
  background: transparent;
  border-bottom: 1px solid rgb(255 255 255 / 8%);
  box-shadow: none;
  transition: none;
}

.media-info-panel header div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.media-info-panel header span {
  color: rgb(255 255 255 / 48%);
  font-size: 11px;
  letter-spacing: 0.08em;
}

.media-info-panel header strong {
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.media-info-panel header button {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  color: #fff;
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
}

.media-info-body {
  height: calc(100% - 66px);
  padding: 18px;
  overflow: auto;
  overscroll-behavior: contain;
}

.media-info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.media-info-grid > div {
  display: grid;
  min-height: 68px;
  align-content: center;
  gap: 5px;
  padding: 12px;
  text-align: left;
  background: rgb(255 255 255 / 5%);
  border: 1px solid rgb(255 255 255 / 6%);
  border-radius: 12px;
}

.media-info-grid span,
.media-info-section dt {
  color: rgb(255 255 255 / 46%);
  font-size: 11px;
}

.media-info-grid strong {
  overflow-wrap: anywhere;
  font-size: 13px;
}

.media-info-section {
  padding-top: 18px;
  margin-top: 18px;
  text-align: left;
  border-top: 1px solid rgb(255 255 255 / 8%);
}

.media-info-section h2 {
  margin: 0 0 12px;
  font-size: 13px;
}

.media-info-section dl {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 9px 12px;
  margin: 0;
}

.media-info-section dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 13px;
}

.media-info-track-note {
  margin: 12px 0 0;
  color: rgb(255 255 255 / 58%);
  font-size: 12px;
  line-height: 1.6;
}

.media-location-section p {
  margin: 0 0 12px;
  color: rgb(255 255 255 / 58%);
  font-size: 12px;
  line-height: 1.6;
}

.media-location-button {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  gap: 7px;
  padding: 0 13px;
  color: #fff;
  background: rgb(78 134 255 / 18%);
  border: 1px solid rgb(105 155 255 / 30%);
  border-radius: 10px;
  cursor: pointer;
}

.media-location-button i,
.media-location-value i {
  font-size: 18px;
}

.media-info-loading {
  display: flex;
  min-height: 100px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: rgb(255 255 255 / 58%);
  font-size: 12px;
}

.media-info-loading span {
  width: 16px;
  height: 16px;
  border: 2px solid rgb(255 255 255 / 18%);
  border-top-color: #fff;
  border-radius: 50%;
  animation: media-info-spin 0.8s linear infinite;
}

.media-info-error,
.media-info-note {
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.5;
}

.media-info-error {
  color: #ff8d88 !important;
}

.media-info-note {
  color: rgb(255 255 255 / 48%);
}

button:focus-visible {
  outline: 2px solid #78a8ff;
  outline-offset: 2px;
}

@keyframes media-info-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 736px) {
  .media-info-panel {
    top: auto;
    right: 8px;
    bottom: 8px;
    left: 8px;
    width: auto;
    max-height: min(72dvh, 620px);
    border-radius: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .media-info-loading span {
    animation: none;
  }
}
</style>
