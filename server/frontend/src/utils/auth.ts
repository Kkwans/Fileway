import { useAuthStore } from "@/stores/auth";
import router from "@/router";
import type { JwtPayload } from "jwt-decode";
import { jwtDecode } from "jwt-decode";
import { authMethod, baseURL, noAuth, logoutPage } from "./constants";
import { StatusError } from "@/api/utils";
import { setSafeTimeout } from "@/api/utils";
import type { IUser } from "@/types/user";

const AUTH_REQUEST_TIMEOUT_MS = 15_000;
let generation = 0;
const renewals = new Map<string, Promise<void>>();

/** Captured before starting a request/resource. Token changes from renewal keep
 * the generation; an explicit login or logout invalidates every older callback. */
export function captureSessionActivity() {
  const auth = useAuthStore();
  const boundGeneration = generation;
  const owner = () =>
    JSON.stringify(auth.user ? [auth.user.id, auth.user.scope] : null);
  const boundOwner = owner();
  const token = auth.jwt;
  const current = () =>
    generation === boundGeneration && owner() === boundOwner;
  return {
    token,
    current,
    async response(hint: string | null, requestToken = token) {
      if (!current() || !requestToken || auth.jwt !== requestToken) return;
      if (!shouldRenewToken(requestToken, hint)) return;
      try {
        await renew(requestToken, current);
      } catch {
        if (current() && auth.jwt === requestToken)
          auth.sessionHealth = "renewal_failed";
        // Business success and already loaded media remain valid results.
      }
    },
    async activity() {
      if (current()) await this.response(null, auth.jwt);
    },
  };
}

export function shouldRenewToken(token: string, hint: string | null) {
  if (hint === "false") return false;
  if (hint === "true") return true;
  try {
    const claims = jwtDecode<JwtPayload>(token);
    const now = Math.floor(Date.now() / 1000);
    return (claims.iat ?? 0) < now && (claims.exp ?? 0) > now;
  } catch {
    return false;
  }
}

async function fetchAuth(url: string, body: unknown): Promise<Response> {
  const controller = new AbortController();
  const timer = window.setTimeout(
    () => controller.abort(),
    AUTH_REQUEST_TIMEOUT_MS
  );
  try {
    return await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
  } finally {
    window.clearTimeout(timer);
  }
}

export function parseToken(token: string, renewal = false) {
  // falsy or malformed jwt will throw InvalidTokenError
  const data = jwtDecode<
    JwtPayload & { user: IUser; instance?: { hostname?: string } }
  >(token);

  const authStore = useAuthStore();
  if (!renewal) generation++;
  authStore.jwt = token;
  authStore.setUser(data.user);
  authStore.instanceHostname = data.instance?.hostname?.trim() ?? "";
  authStore.sessionHealth = null;
  // Keep the renewed in-memory session and expiry timer even when browser
  // storage is unavailable. This must not erase an acknowledged operation.
  try {
    document.cookie = `auth=${token}; Path=/; SameSite=Strict;`;
    localStorage.setItem("jwt", token);
  } catch {
    authStore.sessionHealth = "storage_failed";
  }

  // proxy auth with custom logout subject to unknown external timeout
  if (logoutPage !== "/login" && authMethod === "proxy") {
    // Silent fail - auth detection should not produce side effects
    return;
  }

  if (authStore.logoutTimer) {
    clearTimeout(authStore.logoutTimer);
  }

  const tokenGeneration = generation;
  const expiresAt = new Date(data.exp! * 1000);
  const timeout = expiresAt.getTime() - Date.now();
  authStore.setLogoutTimer(
    setSafeTimeout(() => {
      if (generation === tokenGeneration && authStore.jwt === token)
        logout("inactivity");
    }, timeout)
  );
}

export async function validateLogin() {
  try {
    if (localStorage.getItem("jwt")) {
      await renew(<string>localStorage.getItem("jwt"));
    }
  } catch (error) {
    // Silent fail - invalid JWT is expected during normal flow
    throw error;
  }
}

export async function login(
  username: string,
  password: string,
  recaptcha: string
) {
  const data = { username, password, recaptcha };

  const res = await fetchAuth(`${baseURL}/api/login`, data);

  const body = await res.text();

  if (res.status === 200) {
    parseToken(body);
  } else {
    throw new StatusError(
      body || `${res.status} ${res.statusText}`,
      res.status
    );
  }
}

export function renew(jwt: string, accepts?: () => boolean): Promise<void> {
  const authStore = useAuthStore();
  const beforeToken = authStore.jwt;
  if (beforeToken && beforeToken !== jwt)
    return Promise.reject(
      new StatusError("登录来源已变化，未执行续期", 0, true)
    );
  const beforeGeneration = generation;
  const owner = () =>
    JSON.stringify(
      authStore.user ? [authStore.user.id, authStore.user.scope] : null
    );
  const beforeOwner = owner();
  const current = () =>
    generation === beforeGeneration &&
    authStore.jwt === beforeToken &&
    owner() === beforeOwner &&
    (!accepts || accepts());
  const key = JSON.stringify([beforeGeneration, beforeOwner, jwt]);
  const inflight = renewals.get(key);
  if (inflight) return inflight;
  const request = (async () => {
    const controller = new AbortController();
    const timer = window.setTimeout(
      () => controller.abort(),
      AUTH_REQUEST_TIMEOUT_MS
    );
    try {
      const res = await fetch(`${baseURL}/api/renew`, {
        method: "POST",
        headers: { "X-Auth": jwt },
        signal: controller.signal,
      });
      const body = await res.text();
      if (!current()) throw new StatusError("已忽略过期的登录响应", 0, true);
      if (res.status !== 200)
        throw new StatusError(
          body || `${res.status} ${res.statusText}`,
          res.status
        );
      const previous = jwtDecode<JwtPayload & { user: IUser }>(jwt);
      const next = jwtDecode<JwtPayload & { user: IUser }>(body);
      if (!previous.user || !next.user || previous.user.id !== next.user.id)
        throw new StatusError("续期响应的账号不匹配，已保留原登录状态", 502);
      parseToken(body, true);
    } finally {
      window.clearTimeout(timer);
    }
  })();
  renewals.set(key, request);
  void request
    .finally(() => {
      if (renewals.get(key) === request) renewals.delete(key);
    })
    .catch(() => {});
  return request;
}

export async function signup(username: string, password: string) {
  const data = { username, password };

  const res = await fetchAuth(`${baseURL}/api/signup`, data);

  if (res.status !== 200) {
    const body = await res.text();
    throw new StatusError(
      body || `${res.status} ${res.statusText}`,
      res.status
    );
  }
}

export function logout(reason?: string) {
  generation++;
  document.cookie = "auth=; Max-Age=0; Path=/; SameSite=Strict;";

  const authStore = useAuthStore();
  authStore.clearUser();

  localStorage.setItem("jwt", "");
  if (noAuth) {
    window.location.reload();
  } else if (logoutPage !== "/login") {
    document.location.href = `${logoutPage}`;
  } else {
    if (typeof reason === "string" && reason.trim() !== "") {
      router.push({
        path: "/login",
        query: { "logout-reason": reason },
      });
    } else {
      router.push({
        path: "/login",
      });
    }
  }
}
