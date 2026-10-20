import { captureSessionActivity } from "./auth";
import { baseURL, origin } from "@/utils/constants";

export type TaskCenterEventType =
  | "task.changed"
  | "transfer.changed"
  | "history.created"
  | "resync.required";

export interface TaskCenterEvent {
  id: number;
  type: TaskCenterEventType;
  data?: unknown;
}

export type TaskCenterEventHandler = (event: TaskCenterEvent) => void;

const sharedConsumers = new Map<
  TaskCenterEventHandler,
  { onResync: () => void; onStatus?: (connected: boolean) => void }
>();
let sharedStop: (() => void) | undefined;
let sharedFallbackTimer: number | undefined;

/** Keep one authenticated SSE connection for the application shell. */
export function subscribeSharedTaskCenterEvents(
  onChange: TaskCenterEventHandler,
  onResync: () => void,
  onStatus?: (connected: boolean) => void
) {
  sharedConsumers.set(onChange, { onResync, onStatus });
  if (!sharedStop) {
    sharedStop = connectTaskCenterEvents(
      (event) => {
        for (const consumer of sharedConsumers.keys()) consumer(event);
      },
      () => {
        for (const consumer of sharedConsumers.values()) consumer.onResync();
      },
      (connected) => {
        for (const consumer of sharedConsumers.values()) {
          consumer.onStatus?.(connected);
        }
        if (connected) {
          if (sharedFallbackTimer !== undefined) {
            window.clearInterval(sharedFallbackTimer);
            sharedFallbackTimer = undefined;
          }
        } else if (sharedFallbackTimer === undefined) {
          sharedFallbackTimer = window.setInterval(() => {
            for (const consumer of sharedConsumers.values())
              consumer.onResync();
          }, 15000);
        }
      }
    );
  }
  return () => {
    sharedConsumers.delete(onChange);
    if (sharedConsumers.size === 0) {
      sharedStop?.();
      sharedStop = undefined;
      if (sharedFallbackTimer !== undefined) {
        window.clearInterval(sharedFallbackTimer);
        sharedFallbackTimer = undefined;
      }
    }
  };
}

/**
 * Connect to the authenticated task-center event stream. REST remains the
 * snapshot source: normal notifications are merged locally and an explicit
 * resync marker causes the caller to refetch all three stores.
 */
export function connectTaskCenterEvents(
  onChange: TaskCenterEventHandler,
  onResync: () => void,
  onStatus?: (connected: boolean) => void
) {
  if (typeof window === "undefined" || typeof EventSource === "undefined") {
    return () => {};
  }

  const activity = captureSessionActivity();
  const endpoint = `${origin}${baseURL}/api/task-center/events`;
  let source: EventSource | null = null;
  let retryTimer: number | undefined;
  let retryDelay = 1000;
  let closed = false;
  let lastEventId = 0;

  const parse = (event: MessageEvent<string>, type: TaskCenterEventType) => {
    if (!activity.current()) return;
    void activity.activity();
    let data: unknown;
    try {
      data = event.data ? JSON.parse(event.data) : undefined;
    } catch {
      data = event.data;
    }
    const id = Number(event.lastEventId || 0);
    if (Number.isFinite(id) && id > lastEventId) lastEventId = id;
    onChange({ id, type, data });
  };

  const connect = () => {
    if (closed || !activity.current()) return;
    source?.close();
    const url = new URL(endpoint, window.location.href);
    if (lastEventId > 0)
      url.searchParams.set("lastEventId", String(lastEventId));
    source = new EventSource(url.toString(), { withCredentials: true });
    source.onopen = () => {
      if (!activity.current()) {
        source?.close();
        return;
      }
      void activity.activity();
      retryDelay = 1000;
      onStatus?.(true);
    };
    source.onerror = () => {
      if (!activity.current()) {
        source?.close();
        return;
      }
      onStatus?.(false);
      source?.close();
      source = null;
      if (closed || retryTimer !== undefined) return;
      retryTimer = window.setTimeout(() => {
        retryTimer = undefined;
        connect();
      }, retryDelay);
      retryDelay = Math.min(retryDelay * 2, 30000);
    };
    source.addEventListener("task.changed", (event) =>
      parse(event as MessageEvent<string>, "task.changed")
    );
    source.addEventListener("transfer.changed", (event) =>
      parse(event as MessageEvent<string>, "transfer.changed")
    );
    source.addEventListener("history.created", (event) =>
      parse(event as MessageEvent<string>, "history.created")
    );
    source.addEventListener("resync.required", (event) => {
      if (!activity.current()) return;
      void activity.activity();
      const message = event as MessageEvent<string>;
      const id = Number(message.lastEventId || 0);
      if (Number.isFinite(id) && id > lastEventId) lastEventId = id;
      onResync();
    });
  };

  connect();
  return () => {
    closed = true;
    if (retryTimer !== undefined) window.clearTimeout(retryTimer);
    retryTimer = undefined;
    source?.close();
    source = null;
  };
}
