import { onScopeDispose, watch, type Ref } from "vue";
import { captureSessionActivity } from "./auth";
import { baseURL } from "./constants";

/** Native elements hide response headers. Only actual resource load/progress
 * events count; playback time, focus, clicks and timers never extend a session. */
function isAuthenticatedResource(source: string) {
  try {
    const url = new URL(source, window.location.href);
    return (
      url.origin === window.location.origin &&
      url.pathname.startsWith(`${baseURL}/api/`) &&
      !url.pathname.startsWith(`${baseURL}/api/public/`)
    );
  } catch {
    return false;
  }
}

export function bindResourceActivity(element: HTMLElement) {
  const activity = captureSessionActivity();
  let buffered = "";
  const onActivity = (event: Event) => {
    const resource = element as HTMLMediaElement | HTMLImageElement;
    const source = resource.currentSrc || resource.getAttribute("src") || "";
    if (!isAuthenticatedResource(source)) return;
    if (event.type === "progress" && "buffered" in resource) {
      const ranges = Array.from(
        { length: resource.buffered.length },
        (_, i) => [resource.buffered.start(i), resource.buffered.end(i)]
      );
      const next = JSON.stringify(ranges);
      if (next === buffered) return;
      buffered = next;
    }
    void activity.activity();
  };
  const events = ["load", "loadedmetadata", "loadeddata", "progress"];
  events.forEach((type) => element.addEventListener(type, onActivity));
  return () =>
    events.forEach((type) => element.removeEventListener(type, onActivity));
}

export function useResourceActivity(
  element: Ref<HTMLElement | null>,
  source: () => unknown
) {
  const stop = watch(
    () => [element.value, source()] as const,
    ([target], _, cleanup) => {
      if (target) cleanup(bindResourceActivity(target));
    },
    { immediate: true, flush: "post" }
  );
  onScopeDispose(stop);
}

/** Called before send; readyState 2 is the headers boundary, before a large
 * response body or TUS completion callback. It cannot replay the upload. */
export function bindXHRActivity(
  xhr: XMLHttpRequest,
  activity = captureSessionActivity(),
  token = activity.token
) {
  const headers = () => {
    if (xhr.readyState !== 2) return;
    xhr.removeEventListener("readystatechange", headers);
    if (!isAuthenticatedResource(xhr.responseURL)) return;
    const hint = xhr.getResponseHeader("X-Renew-Token");
    if (
      xhr.status !== 401 &&
      ((xhr.status >= 200 && xhr.status < 300) || hint === "true")
    )
      void activity.response(hint, token);
  };
  xhr.addEventListener("readystatechange", headers);
}
