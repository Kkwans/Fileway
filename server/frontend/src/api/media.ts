import { fetchJSON, fetchURL } from "./utils";
import {
  mediaJSONPath,
  mediaPathQuery,
  mediaReference,
  type MediaSource,
} from "@/utils/mediaResource";
import { favoriteIdentity } from "@/utils/favoritePersistence";

export interface PlaybackPosition {
  path: string;
  identity: string;
  position: number;
  duration: number;
  updatedAt: number;
  exists: boolean;
}

type PlaybackWaiter = {
  resolve: (value: PlaybackPosition | void) => void;
  reject: (reason?: unknown) => void;
};

type PlaybackMutation =
  | {
      kind: "put";
      path: string;
      position: number;
      duration: number;
      waiters: PlaybackWaiter[];
    }
  | {
      kind: "delete";
      path: string;
      wirePath?: string;
      waiters: PlaybackWaiter[];
    };

type PlaybackQueue = {
  running: boolean;
  operations: PlaybackMutation[];
};

// Playback updates are frequent while a video is playing.  Keep the queue
// per path so another video remains responsive, and coalesce only contiguous
// PUTs.  A DELETE stays in the operation list as a strict ordering barrier:
// PUTs submitted after it can never overtake it.
const playbackQueues = new Map<string, PlaybackQueue>();

export interface MediaInformation {
  path: string;
  name: string;
  type: "image" | "video" | "audio";
  extension: string;
  size: number;
  modified: string;
  resolution?: { width: number; height: number };
  format?: string;
  duration?: number;
  bitRate?: number;
  videoCodec?: string;
  videoBitDepth?: number;
  hdr?: boolean;
  dynamicRange?: "sdr" | "pq" | "hlg" | "dolby-vision" | "unknown";
  colorTransfer?: string;
  audioCodec?: string;
  audioTracks?: MediaTrack[];
  subtitleTracks?: MediaTrack[];
  channels?: number;
  sampleRate?: number;
  title?: string;
  artist?: string;
  album?: string;
  date?: string;
  location?: string;
  technicalError?: string;
}

export interface MediaTrack {
  index: number;
  codec: string;
  language?: string;
  title?: string;
  default?: boolean;
  forced?: boolean;
}

export type HLSPlaybackState =
  | "queued"
  | "preparing"
  | "streamable"
  | "completed"
  | "failed"
  | "canceled";

export interface HLSPlaybackStatus {
  quality?: string;
  progress?: import("./tasks").MediaProgress;
  sourceDurationSeconds?: number;
  startSeconds?: number;
  windowSeconds?: number;
  id: string;
  taskId?: string;
  path: string;
  identity: string;
  profile: string;
  subtitleStreamIndex?: number;
  audioStreamIndex?: number;
  hdrToneMapped?: boolean;
  state: HLSPlaybackState;
  error?: string;
  updatedAt: number;
  lastAccessAt?: number;
  sizeBytes?: number;
  processedSeconds?: number;
  durationSeconds?: number;
  format?: "hls" | "copy" | "mp4-copy" | "webm" | "webm-copy";
  playlistUrl?: string;
  sourceUrl?: string;
}

export interface VideoSprite {
  state?: "preparing" | "ready" | "failed";
  error?: string;
  path: string;
  number: number;
  column: number;
  width: number;
  height: number;
  interval: number;
  url: string;
}

export function getPlayback(path: MediaSource): Promise<PlaybackPosition> {
  return fetchJSON<PlaybackPosition>(
    `/api/media/playback?${mediaPathQuery(path)}`
  );
}

export async function savePlayback(
  source: MediaSource,
  position: number,
  duration: number
): Promise<PlaybackPosition> {
  const path = mediaJSONPath(source);
  return enqueuePlaybackMutation<PlaybackPosition>(
    favoriteIdentity(mediaReference(source))!,
    {
      kind: "put",
      path,
      position,
      duration,
      waiters: [],
    }
  );
}

export function clearPlayback(source: MediaSource): Promise<void> {
  mediaPathQuery(source);
  const resource = mediaReference(source);
  return enqueuePlaybackMutation<void>(favoriteIdentity(resource)!, {
    kind: "delete",
    path: resource.path,
    wirePath: resource.wirePath,
    waiters: [],
  });
}

function enqueuePlaybackMutation<T extends PlaybackPosition | void>(
  path: string,
  mutation: PlaybackMutation
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const queue = playbackQueues.get(path) ?? {
      running: false,
      operations: [],
    };
    playbackQueues.set(path, queue);

    const tail = queue.operations[queue.operations.length - 1];
    if (mutation.kind === "put" && tail?.kind === "put") {
      // Every caller waiting on the coalesced operation receives the response
      // for the newest position rather than an obsolete intermediate value.
      tail.position = mutation.position;
      tail.duration = mutation.duration;
      tail.waiters.push({
        resolve: (value) => resolve(value as T),
        reject,
      });
    } else {
      mutation.waiters.push({
        resolve: (value) => resolve(value as T),
        reject,
      });
      queue.operations.push(mutation);
    }
    runPlaybackQueue(path, queue);
  });
}

function runPlaybackQueue(path: string, queue: PlaybackQueue) {
  if (queue.running) return;
  const mutation = queue.operations.shift();
  if (!mutation) {
    playbackQueues.delete(path);
    return;
  }

  queue.running = true;
  const request =
    mutation.kind === "put"
      ? fetchJSON<PlaybackPosition>("/api/media/playback", {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            path: mutation.path,
            position: mutation.position,
            duration: mutation.duration,
          }),
        })
      : fetchURL(
          `/api/media/playback?${mediaPathQuery({ path: mutation.path, wirePath: mutation.wirePath })}`,
          { method: "DELETE" }
        ).then(() => undefined);

  void request
    .then((value) => {
      mutation.waiters.forEach((waiter) => {
        if (mutation.kind === "put") {
          waiter.resolve(value as PlaybackPosition);
        } else {
          waiter.resolve(undefined);
        }
      });
    })
    .catch((error: unknown) => {
      mutation.waiters.forEach((waiter) => waiter.reject(error));
    })
    .finally(() => {
      queue.running = false;
      runPlaybackQueue(path, queue);
    });
}

export function getMediaInformation(
  path: MediaSource,
  includeLocation = false,
  signal?: AbortSignal
): Promise<MediaInformation> {
  return fetchJSON<MediaInformation>(
    `/api/media/info?${mediaPathQuery(path)}${includeLocation ? "&includeLocation=true" : ""}`,
    {
      signal,
    }
  );
}

export function getVideoSprite(path: MediaSource): Promise<VideoSprite> {
  return fetchJSON<VideoSprite>(`/api/media/sprite?${mediaPathQuery(path)}`);
}

export async function startTranscodes(
  paths: string[],
  quality: string,
  destination?: string
) {
  const response = await fetchURL("/api/media/transcodes", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ paths, quality, destination }),
  });
  return response.json() as Promise<{
    items: import("./tasks").TaskItem[];
    failures: Array<{ path: string; error: string }>;
  }>;
}

export function getTranscodePlayback(id: string): Promise<HLSPlaybackStatus> {
  return fetchJSON<HLSPlaybackStatus>(
    `/api/media/transcodes/${encodeURIComponent(id)}/playback`
  );
}

export async function startHLSPlayback(
  source: MediaSource,
  format: "hls" | "mp4" | "webm" = "hls",
  quality: "source" | "4k" | "2k" | "1080p" | "720p" | "480p" = "source",
  subtitleStreamIndex?: number,
  audioStreamIndex?: number,
  window?: { startSeconds: number; windowSeconds: number; sessionId: string }
): Promise<HLSPlaybackStatus> {
  const path = mediaJSONPath(source);
  const response = await fetchURL("/api/media/hls", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      path,
      ...(format === "hls" ? {} : { format }),
      quality,
      ...(subtitleStreamIndex === undefined ? {} : { subtitleStreamIndex }),
      ...(audioStreamIndex === undefined ? {} : { audioStreamIndex }),
      ...window,
    }),
  });
  return response.json() as Promise<HLSPlaybackStatus>;
}

export function getHLSPlayback(id: string): Promise<HLSPlaybackStatus> {
  return fetchJSON<HLSPlaybackStatus>(
    `/api/media/hls/${encodeURIComponent(id)}`
  );
}

export async function cancelHLSPlayback(
  id: string
): Promise<HLSPlaybackStatus> {
  const response = await fetchURL(
    `/api/media/hls/${encodeURIComponent(id)}/cancel`,
    { method: "POST" }
  );
  return response.json() as Promise<HLSPlaybackStatus>;
}
