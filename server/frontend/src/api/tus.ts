import { captureSessionActivity } from "@/utils/auth";
import { bindXHRActivity } from "@/utils/sessionActivity";
import * as tus from "tus-js-client";
import { baseURL, tusEndpoint, tusSettings, origin } from "@/utils/constants";
import { useAuthStore } from "@/stores/auth";
import { removePrefix } from "@/api/utils";
import type { ApiContent, TusSettings } from "@/types/api";

const RETRY_BASE_DELAY = 1000;
const RETRY_MAX_DELAY = 20000;
const CURRENT_UPLOAD_LIST: { [key: string]: tus.Upload } = {};

export async function upload(
  filePath: string,
  content: ApiContent = "",
  overwrite = false,
  onupload: (progress: { loaded: number }) => void,
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
  if (!tusSettings) {
    // Shouldn't happen as we check for tus support before calling this function
    throw new Error("Tus.io settings are not defined");
  }

  filePath = removePrefix(filePath);
  const resourcePath = `${tusEndpoint}${filePath}?override=${overwrite}`;

  const authStore = useAuthStore();
  const activity = captureSessionActivity();

  // Exit early because of typescript, tus content can't be a string
  if (content === "") {
    return false;
  }
  return new Promise<void | string>((resolve, reject) => {
    let transferId = newUploadTransferId(filePath, content);
    const contentSize =
      typeof Blob !== "undefined" && content instanceof Blob
        ? content.size
        : undefined;
    const upload = new tus.Upload(content, {
      endpoint: `${origin}${baseURL}${resourcePath}`,
      chunkSize: tusSettings.chunkSize,
      retryDelays: computeRetryDelays(tusSettings),
      parallelUploads: 1,
      headers: {
        "X-Auth": authStore.jwt,
        "X-Transfer-ID": transferId,
        ...uploadBatchHeaders(metadata),
      },
      // Keep tus' fingerprint in localStorage so a reload can resume the
      // server-side offset instead of silently starting a second upload.
      storeFingerprintForResuming: true,
      removeFingerprintOnSuccess: true,
      onBeforeRequest: (request) => {
        if (!activity.current()) throw new Error("上传来源已切换，未继续发送");
        request.setHeader("X-Auth", authStore.jwt);
        const xhr = request.getUnderlyingObject();
        if (
          typeof XMLHttpRequest !== "undefined" &&
          xhr instanceof XMLHttpRequest
        )
          bindXHRActivity(xhr, activity, authStore.jwt);
      },
      onAfterResponse: (request, response) => {
        if (
          response.getStatus() !== 401 &&
          ((response.getStatus() >= 200 && response.getStatus() < 300) ||
            response.getHeader("X-Renew-Token") === "true")
        )
          void activity.response(
            response.getHeader("X-Renew-Token") ?? null,
            request.getHeader("X-Auth")
          );
      },
      onShouldRetry: function (err) {
        if (!activity.current()) return false;
        const status = err.originalResponse
          ? err.originalResponse.getStatus()
          : 0;

        // Do not retry for file conflict.
        if (status === 409) {
          return false;
        }

        return true;
      },
      onError: function (error: Error | tus.DetailedError) {
        delete CURRENT_UPLOAD_LIST[filePath];

        if (error.message === "Upload aborted") {
          return reject(error);
        }

        const message =
          error instanceof tus.DetailedError
            ? error.originalResponse === null
              ? "000 No connection"
              : error.originalResponse.getBody()
            : "Upload failed";

        console.error(error);

        reject(new Error(message));
      },
      onProgress: function (bytesUploaded) {
        if (typeof onupload === "function") {
          onupload({ loaded: bytesUploaded });
        }
      },
      onSuccess: function () {
        delete CURRENT_UPLOAD_LIST[filePath];
        resolve();
      },
    });
    CURRENT_UPLOAD_LIST[filePath] = upload;
    void (async () => {
      let previous: tus.PreviousUpload | undefined;
      try {
        previous = (await upload.findPreviousUploads()).find(
          (candidate) =>
            candidate.size === null ||
            (contentSize !== undefined && candidate.size === contentSize)
        );
      } catch {
        // Browser storage can be unavailable; the normal TUS create flow is
        // still valid and remains the fallback.
      }
      if (previous) {
        const resume =
          typeof window === "undefined" ||
          window.confirm(
            "发现匹配的未完成上传。点击“确定”继续上传，点击“取消”重新上传。"
          );
        if (resume) {
          const savedId = resumedTransferId(previous.uploadUrl, resourcePath);
          if (savedId) {
            transferId = savedId;
            upload.options.headers = {
              ...(upload.options.headers || {}),
              "X-Transfer-ID": transferId,
            };
          }
          upload.resumeFromPreviousUpload(previous);
        } else {
          await removePreviousFingerprint(upload, previous);
          transferId = newUploadTransferId(filePath, content);
          upload.options.headers = {
            ...(upload.options.headers || {}),
            "X-Transfer-ID": transferId,
          };
        }
      }
      upload.start();
    })().catch((error) => {
      delete CURRENT_UPLOAD_LIST[filePath];
      reject(error instanceof Error ? error : new Error(String(error)));
    });
  });
}

export function uploadBatchHeaders(
  metadata?: Pick<
    Upload,
    | "batchId"
    | "batchName"
    | "batchItems"
    | "batchBytes"
    | "relativePath"
    | "isFolderUpload"
  >
): Record<string, string> {
  if (!metadata?.batchId) return {};
  return {
    "X-Upload-Batch-ID": metadata.batchId,
    "X-Upload-Batch-Name": encodeURIComponent(metadata.batchName || "文件夹"),
    "X-Upload-Batch-Items": String(metadata.batchItems || 0),
    "X-Upload-Batch-Bytes": String(metadata.batchBytes || 0),
    "X-Upload-Folder": metadata.isFolderUpload ? "true" : "false",
  };
}

async function removePreviousFingerprint(
  upload: tus.Upload,
  previous: tus.PreviousUpload
) {
  const storage = upload.options.urlStorage;
  if (!storage || !previous.urlStorageKey) return;
  try {
    await storage.removeUpload(previous.urlStorageKey);
  } catch {
    // A stale browser storage entry must not prevent a deliberate restart.
  }
}

function randomAttemptSuffix() {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return Math.random().toString(16).slice(2, 10);
}

function newUploadTransferId(
  filePath: string,
  content: Exclude<ApiContent, "">
) {
  // A new explicit attempt has its own durable session. The deterministic
  // fingerprint still finds previous uploads; it is not a mutation nonce.
  const suffix = randomAttemptSuffix();
  return `${uploadTransferId(filePath, content).slice(0, 219 - suffix.length)}-${suffix}`;
}

function resumedTransferId(
  value: string | null,
  resourcePath: string
): string | null {
  if (!value) return null;
  const saved = new URL(value, origin);
  const expected = new URL(`${origin}${baseURL}${resourcePath}`);
  if (
    saved.origin !== expected.origin ||
    saved.pathname !== expected.pathname
  ) {
    throw new Error("原上传位置与当前服务器或文件不一致，未发送请求");
  }
  return saved.searchParams.get("transfer");
}

export function uploadTransferId(
  filePath: string,
  content: Exclude<ApiContent, "">
) {
  const file =
    typeof File !== "undefined" && content instanceof File
      ? content
      : undefined;
  const size =
    "size" in content && typeof content.size === "number" ? content.size : 0;
  const type =
    "type" in content && typeof content.type === "string" ? content.type : "";
  const signature = [filePath, size, type, file?.lastModified ?? 0].join(":");
  // Headers are used instead of the URL so the tus fingerprint remains
  // stable across retries and browser reloads. Keep the id URL/header safe.
  return `upload-${encodeURIComponent(signature).replace(/%/g, "_")}`.slice(
    0,
    220
  );
}

function computeRetryDelays(tusSettings: TusSettings): number[] | undefined {
  if (!tusSettings.retryCount || tusSettings.retryCount < 1) {
    // Disable retries altogether
    return undefined;
  }
  // The tus client expects our retries as an array with computed backoffs
  // E.g.: [0, 3000, 5000, 10000, 20000]
  const retryDelays = [];
  let delay = 0;

  for (let i = 0; i < tusSettings.retryCount; i++) {
    retryDelays.push(Math.min(delay, RETRY_MAX_DELAY));
    delay =
      delay === 0 ? RETRY_BASE_DELAY : Math.min(delay * 2, RETRY_MAX_DELAY);
  }

  return retryDelays;
}

export async function useTus(content: ApiContent) {
  return isTusSupported() && content instanceof Blob;
}

function isTusSupported() {
  return tus.isSupported === true;
}

export function abortAllUploads() {
  for (const filePath in CURRENT_UPLOAD_LIST) {
    if (CURRENT_UPLOAD_LIST[filePath]) {
      CURRENT_UPLOAD_LIST[filePath].abort(true);
      CURRENT_UPLOAD_LIST[filePath].options!.onError!(
        new Error("Upload aborted")
      );
    }
    delete CURRENT_UPLOAD_LIST[filePath];
  }
}
