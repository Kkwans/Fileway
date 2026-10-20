<template>
  <div
    class="image-ex-container"
    ref="container"
    :data-status="imageStatus"
    @touchstart="touchStart"
    @touchmove="touchMove"
    @dblclick="zoomAuto"
    @mousedown="mousedownStart"
    @mousemove="mouseMove"
    @mouseup="mouseUp"
    @wheel="wheelMove"
    @mouseenter="showUI = true"
    @mouseleave="showUI = false"
  >
    <!-- Loading spinner -->
    <div
      v-if="imageStatus === 'loading' && !placeholderUsingFallback"
      class="image-viewer-loading"
    >
      <div class="spinner-ring"></div>
      <div class="spinner-text">加载中...</div>
    </div>

    <div
      v-else-if="imageStatus === 'error'"
      class="image-viewer-error"
      role="alert"
    >
      <div class="image-viewer-error-title">图片加载失败</div>
      <div class="image-viewer-error-actions">
        <button type="button" @click.stop="retryImage">重试</button>
        <a v-if="directSrc" :href="directSrc" target="_blank" rel="noopener">
          直接打开
        </a>
        <a v-if="downloadSrc" :href="downloadSrc" download>下载</a>
      </div>
    </div>

    <img
      v-if="placeholderSrc && imageStatus === 'loading' && !placeholderFailed"
      class="image-ex-img image-ex-img-placeholder"
      ref="placeholderImage"
      :src="placeholderSrc"
      alt=""
      aria-hidden="true"
      decoding="async"
      fetchpriority="high"
      loading="eager"
      @load="onPlaceholderLoad"
      @error="onPlaceholderError"
    />

    <!-- Image info (top-right) -->
    <div class="image-viewer-info" :class="{ visible: showUI && imageLoaded }">
      <div v-if="naturalWidth && naturalHeight" class="info-tag">
        <AppIcon :name="mediaIcon('aspect_ratio')" :size="14" />
        {{ naturalWidth }} × {{ naturalHeight }}
      </div>
      <div v-if="fileSize" class="info-tag">
        <AppIcon :name="mediaIcon('data_usage')" :size="14" />
        {{ fileSize }}
      </div>
    </div>

    <!-- Floating toolbar -->
    <div
      class="image-viewer-toolbar"
      :class="{ visible: showUI && imageLoaded }"
    >
      <button
        type="button"
        @click.stop="zoomIn"
        :disabled="scale >= maxScale"
        aria-label="放大 (+)"
        title="放大 (+)"
      >
        <AppIcon :name="mediaIcon('zoom_in')" :size="20" />
      </button>
      <div class="zoom-display">{{ Math.round(scale * 100) }}%</div>
      <button
        type="button"
        @click.stop="zoomOut"
        :disabled="scale <= minScale"
        aria-label="缩小 (-)"
        title="缩小 (-)"
      >
        <AppIcon :name="mediaIcon('zoom_out')" :size="20" />
      </button>
      <div class="toolbar-divider"></div>
      <button
        type="button"
        @click.stop="zoomFit"
        aria-label="适应屏幕"
        title="适应屏幕"
      >
        <AppIcon :name="mediaIcon('fit_screen')" :size="20" />
      </button>
      <button
        type="button"
        @click.stop="zoomOriginal"
        aria-label="原始大小"
        title="原始大小"
      >
        <AppIcon :name="mediaIcon('aspect_ratio')" :size="20" />
      </button>
      <div class="toolbar-divider"></div>
      <button
        type="button"
        @click.stop="rotateLeft"
        aria-label="左旋转"
        title="左旋转"
      >
        <AppIcon :name="mediaIcon('rotate_left')" :size="20" />
      </button>
      <button
        type="button"
        @click.stop="rotateRight"
        aria-label="右旋转"
        title="右旋转"
      >
        <AppIcon :name="mediaIcon('rotate_right')" :size="20" />
      </button>
      <div class="toolbar-divider"></div>
      <button
        type="button"
        @click.stop="zoomAuto"
        aria-label="切换缩放"
        title="切换缩放"
      >
        <AppIcon :name="mediaIcon('search')" :size="20" />
      </button>
    </div>

    <!-- Keyboard hints (bottom-right) -->
    <div class="image-viewer-hints" :class="{ visible: showUI && imageLoaded }">
      <div class="hint-item"><kbd>+</kbd> / <kbd>-</kbd> 缩放</div>
      <div class="hint-item"><kbd>0</kbd> 适应屏幕</div>
      <div class="hint-item"><kbd>1</kbd> 原始大小</div>
      <div class="hint-item"><kbd>R</kbd> 旋转</div>
      <div class="hint-item"><kbd>Esc</kbd> 关闭</div>
    </div>

    <img
      class="image-ex-img image-ex-img-center"
      ref="imgex"
      @load="onLoad"
      @error="onError"
      decoding="async"
      fetchpriority="high"
      loading="eager"
      :alt="fileName"
    />
  </div>
</template>

<script setup lang="ts">
import { bindXHRActivity, useResourceActivity } from "@/utils/sessionActivity";
import { throttle } from "lodash-es";
import UTIF from "utif";
import { filesize } from "@/utils";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import AppIcon from "@/components/ui/AppIcon.vue";
import { mediaIcon } from "@/utils/mediaIconSemantics";

interface IProps {
  src: string;
  moveDisabledTime?: number;
  classList?: string[];
  zoomStep?: number;
  fileName?: string;
  fileSizeBytes?: number;
  placeholderSrc?: string;
  /** The placeholder URL already contains the full preview bytes. */
  placeholderIsFull?: boolean;
  /** Wait for a real thumbnail before starting the expensive full preview. */
  deferFullUntilPlaceholder?: boolean;
  /** A lightweight thumbnail to keep visible while the original is loading. */
  fallbackPlaceholderSrc?: string;
  directSrc?: string;
  downloadSrc?: string;
}

const props = withDefaults(defineProps<IProps>(), {
  moveDisabledTime: () => 200,
  classList: () => [],
  zoomStep: () => 0.25,
  fileName: () => "",
  fileSizeBytes: () => 0,
  placeholderSrc: () => "",
  placeholderIsFull: false,
  deferFullUntilPlaceholder: false,
  fallbackPlaceholderSrc: () => "",
  directSrc: () => "",
  downloadSrc: () => "",
});
const emit = defineEmits<{
  ready: [];
}>();

const scale = ref<number>(1);
const rotation = ref<number>(0);
const lastX = ref<number | null>(null);
const lastY = ref<number | null>(null);
const inDrag = ref<boolean>(false);
const touches = ref<number>(0);
const lastTouchDistance = ref<number | null>(0);
const moveDisabled = ref<boolean>(false);
const disabledTimer = ref<number | null>(null);
type ImageStatus = "loading" | "ready" | "error";
const imageStatus = ref<ImageStatus>("loading");
const placeholderFailed = ref(false);
const placeholderUsingFallback = ref(false);
const fullLoadStarted = ref(false);
const imageLoaded = computed(() => imageStatus.value === "ready");
const showUI = ref<boolean>(true);
const naturalWidth = ref<number>(0);
const naturalHeight = ref<number>(0);
const position = ref<{
  center: { x: number; y: number };
  relative: { x: number; y: number };
}>({
  center: { x: 0, y: 0 },
  relative: { x: 0, y: 0 },
});
const maxScale = ref<number>(4);
const minScale = ref<number>(0.25);

// Refs
const imgex = ref<HTMLImageElement | null>(null);
useResourceActivity(imgex, () => [props.src, props.directSrc]);
const placeholderImage = ref<HTMLImageElement | null>(null);
const container = ref<HTMLDivElement | null>(null);
let tiffRequest: XMLHttpRequest | null = null;
let loadToken = 0;
let loadTimeout: number | null = null;
let placeholderTimer: number | null = null;
let fullFallbackTimer: number | null = null;
const IMAGE_LOAD_TIMEOUT_MS = 30_000;
// Give the real thumbnail a short head start so the first paint does not wait
// behind the much more expensive 1080px preview on the single-image queue.
const PLACEHOLDER_MAX_WAIT_MS = 650;
// A very large JPEG can spend several seconds in the NAS decoder before the
// scaled preview responds. Let the real thumbnail finish before competing
// with a huge original: downloading and decoding a 10K JPEG at the same time
// can starve the thumbnail worker and make the first useful paint slower.
// Keep a bounded raw fallback for a genuinely stalled preview service.
const RAW_IMAGE_FALLBACK_DELAY_MS = 2000;

const tiffSuffixes = new Set(["tif", "tiff", "dng", "cr2", "nef"]);

// Computed
const fileSize = computed(() => {
  if (!props.fileSizeBytes) return "";
  return filesize(props.fileSizeBytes);
});

onMounted(() => {
  loadImage();

  props.classList.forEach((className) =>
    container.value !== null ? container.value.classList.add(className) : ""
  );

  if (container.value === null) {
    return;
  }

  // set width and height if they are zero
  if (getComputedStyle(container.value).width === "0px") {
    container.value.style.width = "100%";
  }
  if (getComputedStyle(container.value).height === "0px") {
    container.value.style.height = "100%";
  }

  window.addEventListener("resize", onResize);
  window.addEventListener("keydown", onKeyDown);
});

onBeforeUnmount(() => {
  cancelImageLoad();
  window.removeEventListener("resize", onResize);
  window.removeEventListener("keydown", onKeyDown);
  document.removeEventListener("mouseup", onMouseUp);
});

watch(
  () => props.src,
  () => {
    loadImage();
    scale.value = 1;
    rotation.value = 0;
    setZoom();
    setCenter();
  }
);

// Keyboard shortcuts
const onKeyDown = (e: KeyboardEvent) => {
  if (!imageLoaded.value) return;
  switch (e.key) {
    case "+":
    case "=":
      e.preventDefault();
      zoomIn();
      break;
    case "-":
    case "_":
      e.preventDefault();
      zoomOut();
      break;
    case "0":
      e.preventDefault();
      zoomFit();
      break;
    case "1":
      e.preventDefault();
      zoomOriginal();
      break;
    case "r":
    case "R":
      e.preventDefault();
      rotateRight();
      break;
  }
};

const cancelImageLoad = () => {
  loadToken += 1;
  placeholderFailed.value = false;
  placeholderUsingFallback.value = false;
  fullLoadStarted.value = false;
  if (placeholderTimer !== null) {
    window.clearTimeout(placeholderTimer);
    placeholderTimer = null;
  }
  clearFullFallbackTimer();
  if (loadTimeout !== null) {
    window.clearTimeout(loadTimeout);
    loadTimeout = null;
  }
  const request = tiffRequest;
  tiffRequest = null;
  if (request) {
    const index = UTIF._xhrs.indexOf(request);
    if (index >= 0) {
      UTIF._xhrs.splice(index, 1);
      UTIF._imgs.splice(index, 1);
    }
    request.abort();
  }
  imgex.value?.removeAttribute("src");
};

const armLoadTimeout = (token: number) => {
  if (loadTimeout !== null) window.clearTimeout(loadTimeout);
  loadTimeout = window.setTimeout(() => {
    if (token === loadToken) failImageLoad(token);
  }, IMAGE_LOAD_TIMEOUT_MS);
};

const clearPlaceholderTimer = () => {
  if (placeholderTimer !== null) {
    window.clearTimeout(placeholderTimer);
    placeholderTimer = null;
  }
};

const clearFullFallbackTimer = () => {
  if (fullFallbackTimer !== null) {
    window.clearTimeout(fullFallbackTimer);
    fullFallbackTimer = null;
  }
};

const armRawImageFallback = (token: number) => {
  if (props.deferFullUntilPlaceholder) {
    // A deferred large-JPEG preview is deliberately bounded to the server
    // preview. Falling back to the original 8K/10K source while that preview
    // is still decoding can make Chromium allocate hundreds of MB and look
    // like a frozen page. The explicit “直接打开” action remains available
    // if the user really wants the original file.
    clearFullFallbackTimer();
    return;
  }
  if (props.placeholderIsFull || !props.directSrc) {
    return;
  }
  clearFullFallbackTimer();
  fullFallbackTimer = window.setTimeout(() => {
    fullFallbackTimer = null;
    if (
      token === loadToken &&
      imageStatus.value === "loading" &&
      fullLoadStarted.value
    ) {
      startRawImageFallback(token);
    }
  }, RAW_IMAGE_FALLBACK_DELAY_MS);
};

const sourceExtension = () =>
  props.src.split(/[?#]/, 1)[0].split(".").pop()?.toLowerCase() ?? "";

// Modified from UTIF.replaceIMG. Keep the existing decoder, but retain the
// request handle so route changes can abort a large TIFF/RAW download.
const decodeUTIF = (token: number) => {
  if (!tiffSuffixes.has(sourceExtension()) || imgex.value === null) {
    return false;
  }
  const xhr = new XMLHttpRequest();
  tiffRequest = xhr;
  UTIF._xhrs.push(xhr);
  UTIF._imgs.push(imgex.value);
  xhr.open("GET", props.src);
  bindXHRActivity(xhr);
  xhr.responseType = "arraybuffer";
  xhr.onload = (event) => {
    if (token !== loadToken || tiffRequest !== xhr) return;
    tiffRequest = null;
    try {
      UTIF._imgLoaded.call(xhr, event);
    } catch {
      onError();
    }
  };
  xhr.onerror = () => {
    if (token === loadToken) failImageLoad(token);
  };
  xhr.onabort = () => {
    if (token === loadToken) imageStatus.value = "loading";
  };
  xhr.send();
  return true;
};

const startFullImageLoad = (token: number) => {
  if (
    token !== loadToken ||
    fullLoadStarted.value ||
    imageStatus.value !== "loading" ||
    imgex.value === null
  ) {
    return;
  }
  clearPlaceholderTimer();
  fullLoadStarted.value = true;
  armLoadTimeout(token);
  if (!props.placeholderIsFull && !decodeUTIF(token)) {
    imgex.value.src = props.src;
    armRawImageFallback(token);
  } else if (props.placeholderIsFull) {
    // The warm thumbnail response is the same 1080px JPEG used by the
    // viewer. Reuse that URL so promotion is satisfied from the browser
    // cache instead of triggering a second server-side image decode.
    imgex.value.src = props.placeholderSrc;
  }
};

const startRawImageFallback = (token: number) => {
  if (
    token !== loadToken ||
    imageStatus.value !== "loading" ||
    imgex.value === null ||
    !props.directSrc ||
    !props.placeholderIsFull ||
    props.deferFullUntilPlaceholder
  ) {
    return;
  }
  clearFullFallbackTimer();
  // A slow full preview may already have started after the thumbnail loaded.
  // Abort that request before falling back to the original so two large image
  // responses never compete for the same viewer.
  if (fullLoadStarted.value) imgex.value.removeAttribute("src");
  clearPlaceholderTimer();
  fullLoadStarted.value = true;
  armLoadTimeout(token);
  if (props.fallbackPlaceholderSrc) {
    // Keep a regular cached thumbnail visible while the original is fetched.
    // The warm coordinator may continue server-side, but it no longer blocks
    // the first useful paint or the raw fallback request.
    placeholderUsingFallback.value = true;
    placeholderFailed.value = false;
    if (placeholderImage.value) {
      placeholderImage.value.src = props.fallbackPlaceholderSrc;
    }
  } else {
    // Removing the warm request's source lets the browser cancel the server
    // coordinator when a cold decode has not produced a response in time.
    placeholderImage.value?.removeAttribute("src");
    placeholderFailed.value = true;
  }
  imgex.value.src = props.directSrc || props.src;
};

const onPlaceholderLoad = () => {
  if (placeholderUsingFallback.value) return;
  startFullImageLoad(loadToken);
};

const onPlaceholderError = () => {
  if (placeholderUsingFallback.value) {
    placeholderFailed.value = true;
    return;
  }
  if (props.placeholderIsFull || props.deferFullUntilPlaceholder) {
    if (props.deferFullUntilPlaceholder) {
      // Keep the real thumbnail visible while the bounded server preview is
      // retried. Do not replace it with an unbounded original-image decode.
      startFullImageLoad(loadToken);
      return;
    }
    startRawImageFallback(loadToken);
    return;
  }
  placeholderFailed.value = true;
  startFullImageLoad(loadToken);
};

const loadImage = () => {
  cancelImageLoad();
  const token = loadToken;
  imageStatus.value = "loading";
  if (imgex.value === null) return;
  if (props.placeholderSrc) {
    placeholderTimer = window.setTimeout(
      () => {
        placeholderTimer = null;
        if (props.deferFullUntilPlaceholder) {
          if (!fullLoadStarted.value) {
            // 大图预览仍在生成，保持缩略图，不回退到原始大图
            startFullImageLoad(token);
          }
        } else if (props.placeholderIsFull && props.directSrc) {
          if (!fullLoadStarted.value) startRawImageFallback(token);
        } else {
          startFullImageLoad(token);
        }
      },
      props.placeholderIsFull || props.deferFullUntilPlaceholder
        ? RAW_IMAGE_FALLBACK_DELAY_MS
        : PLACEHOLDER_MAX_WAIT_MS
    );
  } else {
    startFullImageLoad(token);
  }
};

const failImageLoad = (token = loadToken) => {
  if (token !== loadToken || imageStatus.value !== "loading") return;
  // Invalidate the callback token before aborting so XHR's onabort handler
  // cannot turn the terminal error state back into loading.
  loadToken += 1;
  if (loadTimeout !== null) {
    window.clearTimeout(loadTimeout);
    loadTimeout = null;
  }
  if (placeholderTimer !== null) {
    window.clearTimeout(placeholderTimer);
    placeholderTimer = null;
  }
  clearFullFallbackTimer();
  placeholderFailed.value = true;
  if (tiffRequest) {
    const request = tiffRequest;
    tiffRequest = null;
    const index = UTIF._xhrs.indexOf(request);
    if (index >= 0) {
      UTIF._xhrs.splice(index, 1);
      UTIF._imgs.splice(index, 1);
    }
    request.abort();
  }
  imgex.value?.removeAttribute("src");
  imageStatus.value = "error";
};

const onError = () => failImageLoad();

const retryImage = () => {
  loadImage();
};

const onLoad = () => {
  if (loadTimeout !== null) {
    window.clearTimeout(loadTimeout);
    loadTimeout = null;
  }
  if (placeholderTimer !== null) {
    window.clearTimeout(placeholderTimer);
    placeholderTimer = null;
  }
  clearFullFallbackTimer();
  placeholderFailed.value = false;
  imageStatus.value = "ready";
  emit("ready");

  if (imgex.value === null) {
    return;
  }

  naturalWidth.value = imgex.value.naturalWidth;
  naturalHeight.value = imgex.value.naturalHeight;

  imgex.value.classList.remove("image-ex-img-center");
  setCenter();
  imgex.value.classList.add("image-ex-img-ready");

  document.addEventListener("mouseup", onMouseUp);

  let realSize = imgex.value.naturalWidth;
  let displaySize = imgex.value.offsetWidth;

  // Image is in portrait orientation
  if (imgex.value.naturalHeight > imgex.value.naturalWidth) {
    realSize = imgex.value.naturalHeight;
    displaySize = imgex.value.offsetHeight;
  }

  // Scale needed to display the image on full size
  const fullScale = realSize / displaySize;

  // Full size plus additional zoom
  maxScale.value = fullScale + 4;
};

const onMouseUp = () => {
  inDrag.value = false;
};

const onResize = throttle(function () {
  if (imageLoaded.value) {
    setCenter();
    doMove(position.value.relative.x, position.value.relative.y);
  }
}, 100);

const setCenter = () => {
  if (container.value === null || imgex.value === null) {
    return;
  }

  position.value.center.x = Math.floor(
    (container.value.clientWidth - imgex.value.clientWidth) / 2
  );
  position.value.center.y = Math.floor(
    (container.value.clientHeight - imgex.value.clientHeight) / 2
  );

  imgex.value.style.left = position.value.center.x + "px";
  imgex.value.style.top = position.value.center.y + "px";
};

// Zoom actions
const zoomIn = () => {
  scale.value += props.zoomStep;
  setZoom();
};

const zoomOut = () => {
  scale.value -= props.zoomStep;
  setZoom();
};

const zoomFit = () => {
  scale.value = 1;
  rotation.value = 0;
  setZoom();
  setCenter();
  if (imgex.value) {
    imgex.value.style.left = position.value.center.x + "px";
    imgex.value.style.top = position.value.center.y + "px";
  }
};

const zoomOriginal = () => {
  if (!imgex.value) return;
  // Calculate scale needed for 1:1 pixel mapping
  const containerW = container.value?.clientWidth || window.innerWidth;
  const containerH = container.value?.clientHeight || window.innerHeight;
  const fitScale = Math.min(
    containerW / imgex.value.naturalWidth,
    containerH / imgex.value.naturalHeight,
    1
  );
  scale.value = 1 / fitScale;
  setZoom();
  setCenter();
};

const rotateLeft = () => {
  rotation.value = (rotation.value - 90) % 360;
  applyTransform();
};

const rotateRight = () => {
  rotation.value = (rotation.value + 90) % 360;
  applyTransform();
};

const applyTransform = () => {
  if (imgex.value !== null) {
    imgex.value.style.transform = `scale(${scale.value}) rotate(${rotation.value}deg)`;
  }
};

const mousedownStart = (event: MouseEvent) => {
  if (event.button !== 0) return;
  lastX.value = null;
  lastY.value = null;
  inDrag.value = true;
  event.preventDefault();
};
const mouseMove = (event: MouseEvent) => {
  if (!inDrag.value) return;
  doMove(event.movementX, event.movementY);
  event.preventDefault();
};
const mouseUp = (event: Event) => {
  if (inDrag.value) {
    event.preventDefault();
  }
  inDrag.value = false;
};
const touchStart = (event: TouchEvent) => {
  lastX.value = null;
  lastY.value = null;
  lastTouchDistance.value = null;
  if (event.targetTouches.length < 2) {
    setTimeout(() => {
      touches.value = 0;
    }, 300);
    touches.value++;
    if (touches.value > 1) {
      zoomAuto(event);
    }
  }
  event.preventDefault();
};

const zoomAuto = (event: Event) => {
  switch (scale.value) {
    case 1:
      scale.value = 2;
      break;
    case 2:
      scale.value = 4;
      break;
    default:
    case 4:
      scale.value = 1;
      setCenter();
      break;
  }
  setZoom();
  event.preventDefault();
};

const touchMove = (event: TouchEvent) => {
  event.preventDefault();
  if (lastX.value === null) {
    lastX.value = event.targetTouches[0].pageX;
    lastY.value = event.targetTouches[0].pageY;
    return;
  }
  if (imgex.value === null) {
    return;
  }
  const step = imgex.value.width / 5;
  if (event.targetTouches.length === 2) {
    moveDisabled.value = true;
    if (disabledTimer.value) clearTimeout(disabledTimer.value);
    disabledTimer.value = window.setTimeout(
      () => (moveDisabled.value = false),
      props.moveDisabledTime
    );

    const p1 = event.targetTouches[0];
    const p2 = event.targetTouches[1];
    const touchDistance = Math.sqrt(
      Math.pow(p2.pageX - p1.pageX, 2) + Math.pow(p2.pageY - p1.pageY, 2)
    );
    if (!lastTouchDistance.value) {
      lastTouchDistance.value = touchDistance;
      return;
    }
    scale.value += (touchDistance - lastTouchDistance.value) / step;
    lastTouchDistance.value = touchDistance;
    setZoom();
  } else if (event.targetTouches.length === 1) {
    if (moveDisabled.value) return;
    const x = event.targetTouches[0].pageX - (lastX.value ?? 0);
    const y = event.targetTouches[0].pageY - (lastY.value ?? 0);
    if (Math.abs(x) >= step && Math.abs(y) >= step) return;
    lastX.value = event.targetTouches[0].pageX;
    lastY.value = event.targetTouches[0].pageY;
    doMove(x, y);
  }
};

const doMove = (x: number, y: number) => {
  if (imgex.value === null) {
    return;
  }
  const style = imgex.value.style;

  const posX = pxStringToNumber(style.left) + x;
  const posY = pxStringToNumber(style.top) + y;

  style.left = posX + "px";
  style.top = posY + "px";

  position.value.relative.x = Math.abs(position.value.center.x - posX);
  position.value.relative.y = Math.abs(position.value.center.y - posY);

  if (posX < position.value.center.x) {
    position.value.relative.x = position.value.relative.x * -1;
  }

  if (posY < position.value.center.y) {
    position.value.relative.y = position.value.relative.y * -1;
  }
};
const wheelMove = (event: WheelEvent) => {
  scale.value += -Math.sign(event.deltaY) * props.zoomStep;
  setZoom();
};
const setZoom = () => {
  scale.value = scale.value < minScale.value ? minScale.value : scale.value;
  scale.value = scale.value > maxScale.value ? maxScale.value : scale.value;
  applyTransform();
};
const pxStringToNumber = (style: string) => {
  return +style.replace("px", "");
};
</script>

<style>
.image-ex-container {
  margin: auto;
  overflow: hidden;
  position: relative;
}

.image-ex-img {
  position: absolute;
}

.image-ex-img-center {
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  position: absolute;
  transition: none;
}

.image-ex-img-placeholder {
  left: 50%;
  top: 50%;
  max-width: 100%;
  max-height: 100%;
  transform: translate(-50%, -50%);
  object-fit: contain;
  filter: blur(1px);
  opacity: 0.82;
  pointer-events: none;
}

.image-ex-img-ready {
  left: 0;
  top: 0;
  transition: transform 0.1s ease;
}

.image-viewer-error {
  position: absolute;
  inset: 50% auto auto 50%;
  z-index: 2;
  display: grid;
  gap: 0.75rem;
  min-width: 14rem;
  padding: 1rem 1.25rem;
  color: #fff;
  text-align: center;
  background: rgb(20 20 24 / 88%);
  border: 1px solid rgb(255 255 255 / 18%);
  border-radius: 0.75rem;
  transform: translate(-50%, -50%);
}

.image-viewer-error-actions {
  display: flex;
  justify-content: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.image-viewer-error-actions button,
.image-viewer-error-actions a {
  color: inherit;
  font: inherit;
  text-decoration: none;
  cursor: pointer;
  background: rgb(255 255 255 / 12%);
  border: 1px solid rgb(255 255 255 / 28%);
  border-radius: 0.4rem;
  padding: 0.35rem 0.65rem;
}
</style>
