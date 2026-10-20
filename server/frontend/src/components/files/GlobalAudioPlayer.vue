<template>
  <section
    v-if="mediaStore.currentAudio"
    class="global-audio-player"
    aria-label="全局音频播放器"
  >
    <audio
      ref="audio"
      :src="mediaStore.currentAudio.source"
      preload="metadata"
      @loadedmetadata="onLoadedMetadata"
      @timeupdate="onTimeUpdate"
      @play="mediaStore.setAudioPlaybackState(true)"
      @pause="mediaStore.setAudioPlaybackState(false)"
      @ended="mediaStore.nextAudio()"
      @error="mediaStore.setAudioError('音频无法播放')"
    ></audio>

    <button
      class="audio-track"
      type="button"
      title="打开当前音频"
      @click="openCurrentAudio"
    >
      <span class="audio-art" aria-hidden="true">
        <AppIcon :name="mediaIcon('graphic_eq')" :size="20" />
      </span>
      <span class="audio-copy">
        <strong>{{ mediaStore.currentAudio.name }}</strong>
        <span>{{ queueLabel }}</span>
      </span>
    </button>

    <div class="audio-transport">
      <button
        type="button"
        aria-label="上一首"
        title="上一首"
        :disabled="!mediaStore.hasPreviousAudio"
        @click="mediaStore.previousAudio()"
      >
        <AppIcon :name="mediaIcon('skip_previous')" :size="18" />
      </button>
      <button
        class="audio-play"
        type="button"
        :aria-label="mediaStore.audioPlaying ? '暂停' : '播放'"
        :title="mediaStore.audioPlaying ? '暂停' : '播放'"
        @click="mediaStore.toggleAudio()"
      >
        <AppIcon
          :name="mediaIcon(mediaStore.audioPlaying ? 'pause' : 'play_arrow')"
          :size="18"
        />
      </button>
      <button
        type="button"
        aria-label="下一首"
        title="下一首"
        :disabled="!mediaStore.hasNextAudio"
        @click="mediaStore.nextAudio()"
      >
        <AppIcon :name="mediaIcon('skip_next')" :size="18" />
      </button>
    </div>

    <div class="audio-timeline">
      <span>{{ formatMediaTime(mediaStore.audioCurrentTime) }}</span>
      <input
        type="range"
        min="0"
        :max="Math.max(mediaStore.audioDuration, 0)"
        step="0.1"
        :value="mediaStore.audioCurrentTime"
        aria-label="音频播放进度"
        @input="seekAudio"
      />
      <span>{{ formatMediaTime(mediaStore.audioDuration) }}</span>
    </div>

    <div class="audio-volume">
      <AppIcon :name="mediaIcon('volume_up')" :size="18" />
      <input
        type="range"
        min="0"
        max="1"
        step="0.05"
        :value="mediaStore.audioVolume"
        aria-label="音量"
        @input="changeVolume"
      />
    </div>

    <span v-if="mediaStore.audioError" class="audio-error" role="status">
      {{ mediaStore.audioError }}
    </span>
    <button
      class="audio-close"
      type="button"
      aria-label="关闭音频播放器"
      title="关闭音频播放器"
      @click="mediaStore.closeAudio()"
    >
      <AppIcon :name="mediaIcon('close')" :size="18" />
    </button>
  </section>
</template>

<script setup lang="ts">
import { useResourceActivity } from "@/utils/sessionActivity";
import { useMediaStore } from "@/stores/media";
import { formatMediaTime } from "@/utils/videoGestures";
import { computed, nextTick, ref, watch } from "vue";
import { useRouter } from "vue-router";
import AppIcon from "@/components/ui/AppIcon.vue";
import { mediaIcon } from "@/utils/mediaIconSemantics";
import { favoriteWirePath } from "@/utils/favoritePersistence";

const mediaStore = useMediaStore();
const router = useRouter();
const audio = ref<HTMLAudioElement | null>(null);
useResourceActivity(audio, () => mediaStore.currentAudio?.source);

const queueLabel = computed(() => {
  const item = mediaStore.currentAudio;
  if (!item) return "";
  const origin = item.origin === "favorite-group" ? "收藏分组" : "当前目录";
  return `${origin} · ${mediaStore.audioIndex + 1} / ${mediaStore.audioQueue.length}`;
});

watch(
  () => mediaStore.currentAudio?.source,
  async () => {
    await nextTick();
    if (!audio.value || !mediaStore.currentAudio) return;
    audio.value.load();
  }
);

watch(
  () => mediaStore.audioCommand,
  async () => {
    await nextTick();
    if (!audio.value) return;
    if (mediaStore.desiredAudioPlaying) {
      try {
        await audio.value.play();
      } catch {
        mediaStore.setAudioError("浏览器阻止了自动播放，请再次点击播放");
      }
    } else {
      audio.value.pause();
    }
  }
);

watch(
  () => mediaStore.audioQueue.length,
  (length) => {
    if (length === 0) audio.value?.pause();
  }
);

function onLoadedMetadata() {
  if (!audio.value) return;
  const position = Math.min(
    mediaStore.audioCurrentTime,
    Number.isFinite(audio.value.duration) ? audio.value.duration : 0
  );
  audio.value.currentTime = position;
  audio.value.volume = mediaStore.audioVolume;
  mediaStore.updateAudioPosition(position, audio.value.duration || 0);
  if (mediaStore.desiredAudioPlaying) {
    audio.value.play().catch(() => {
      mediaStore.setAudioError("浏览器阻止了自动播放，请再次点击播放");
    });
  }
}

function onTimeUpdate() {
  if (!audio.value) return;
  mediaStore.updateAudioPosition(
    audio.value.currentTime,
    audio.value.duration || 0
  );
}

function seekAudio(event: Event) {
  if (!audio.value) return;
  const position = Number((event.target as HTMLInputElement).value);
  audio.value.currentTime = position;
  mediaStore.updateAudioPosition(position, audio.value.duration || 0);
}

function changeVolume(event: Event) {
  const volume = Number((event.target as HTMLInputElement).value);
  mediaStore.setAudioVolume(volume);
  if (audio.value) audio.value.volume = volume;
}

function openCurrentAudio() {
  const item = mediaStore.currentAudio;
  if (!item) return;
  const wire = favoriteWirePath(item);
  if (!wire) {
    mediaStore.setAudioError("音频原始路径无法确认，请重新选择");
    return;
  }
  router.push({
    path: `/files${wire}`,
    query: item.groupId ? { mediaQueue: item.groupId } : {},
  });
}
</script>

<style scoped>
.global-audio-player {
  position: fixed;
  z-index: 1100;
  right: 18px;
  bottom: 18px;
  left: calc(var(--sidebar-width, 288px) + 18px);
  display: grid;
  min-height: 68px;
  grid-template-columns: minmax(160px, 1fr) auto minmax(220px, 1.5fr) auto auto;
  align-items: center;
  gap: 14px;
  padding: 10px 12px;
  color: var(--textPrimary);
  background: color-mix(in srgb, var(--surfacePrimary) 88%, transparent);
  border: 1px solid var(--borderPrimary);
  border-radius: 16px;
  box-shadow: 0 18px 56px rgb(0 0 0 / 28%);
  backdrop-filter: blur(18px) saturate(140%);
}

.audio-track {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  padding: 0;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.audio-art {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  place-items: center;
  color: #fff;
  background: linear-gradient(145deg, var(--blue), #5956d6);
  border-radius: 12px;
  box-shadow: inset 0 1px rgb(255 255 255 / 24%);
}

.audio-copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.audio-copy strong,
.audio-copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audio-copy strong {
  font-size: 13px;
}

.audio-copy span,
.audio-timeline span {
  color: var(--textSecondary);
  font-size: 11px;
}

.audio-transport,
.audio-timeline,
.audio-volume {
  display: flex;
  align-items: center;
  gap: 8px;
}

.audio-transport button,
.audio-close {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  color: inherit;
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
}

.audio-transport .audio-play {
  color: #fff;
  background: var(--blue);
}

.audio-transport button:disabled {
  opacity: 0.35;
  cursor: default;
}

.audio-timeline input {
  width: 100%;
  min-width: 120px;
  accent-color: var(--blue);
}

.audio-volume input {
  width: 74px;
  accent-color: var(--blue);
}

.audio-volume .app-icon {
  color: var(--textSecondary);
  font-size: 18px;
}

.audio-error {
  max-width: 170px;
  color: var(--red);
  font-size: 12px;
}

button:focus-visible,
input:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}

@media (max-width: 980px) {
  .global-audio-player {
    left: 18px;
    grid-template-columns: minmax(140px, 1fr) auto minmax(160px, 1fr) auto;
  }

  .audio-volume {
    display: none;
  }
}

@media (max-width: 736px) {
  .global-audio-player {
    right: 10px;
    bottom: 10px;
    left: 10px;
    min-height: 64px;
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 8px;
    border-radius: 14px;
  }

  .audio-timeline,
  .audio-error {
    display: none;
  }

  .audio-art {
    width: 38px;
    height: 38px;
    flex-basis: 38px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .global-audio-player,
  .global-audio-player * {
    transition: none !important;
  }
}
</style>
