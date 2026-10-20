import { captureSessionActivity } from "@/utils/auth";
import { baseURL } from "@/utils/constants";
import { removePrefix } from "./utils";

const ssl = window.location.protocol === "https:";
const protocol = ssl ? "wss:" : "ws:";

export default function command(
  url: string,
  command: string,
  onmessage: WebSocket["onmessage"],
  onclose: WebSocket["onclose"]
) {
  url = removePrefix(url);
  url = `${protocol}//${window.location.host}${baseURL}/api/command${url}`;

  const activity = captureSessionActivity();
  const conn = new window.WebSocket(url);
  conn.onopen = () => {
    if (!activity.current()) {
      conn.close();
      return;
    }
    void activity.activity();
    conn.send(command);
  };
  conn.onmessage = (event) => {
    if (activity.current()) {
      void activity.activity();
      onmessage?.call(conn, event);
    }
  };
  conn.onclose = (event) => {
    if (activity.current()) onclose?.call(conn, event);
  };
}
