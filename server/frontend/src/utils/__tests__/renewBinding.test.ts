import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const owned = vi.hoisted(() => ({
  auth: {
    jwt: "",
    user: null as { id: number; scope: string } | null,
    logoutTimer: null as number | null,
    sessionHealth: null as string | null,
    setUser: vi.fn(),
    setLogoutTimer: vi.fn(),
    clearUser: vi.fn(),
  },
  push: vi.fn(),
  timers: [] as (() => void)[],
}));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => owned.auth }));
vi.mock("@/router", () => ({ default: { push: owned.push } }));
vi.mock("../constants", () => ({
  baseURL: "",
  authMethod: "json",
  noAuth: false,
  logoutPage: "/login",
}));

import {
  captureSessionActivity,
  shouldRenewToken,
  parseToken,
  renew,
  validateLogin,
} from "../auth";

function token(id: number, nonce: string) {
  const payload = {
    user: { id, scope: "/owned" },
    iat: Math.floor(Date.now() / 1000) - 16 * 86400,
    exp: Math.floor(Date.now() / 1000) + 3600,
    nonce,
  };
  return (
    "owned." +
    Buffer.from(JSON.stringify(payload)).toString("base64url") +
    ".owned"
  );
}

describe("renewal response ownership", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    owned.timers.length = 0;
    owned.auth.jwt = token(1, "first");
    owned.auth.user = { id: 1, scope: "/owned" };
    owned.auth.logoutTimer = null;
    owned.auth.sessionHealth = null;
    owned.auth.setUser.mockImplementation((user) => {
      owned.auth.user = user;
    });
    owned.auth.setLogoutTimer.mockImplementation((timer) => {
      owned.auth.logoutTimer = timer;
    });
    owned.auth.clearUser.mockImplementation(() => {
      owned.auth.jwt = "";
      owned.auth.user = null;
    });
    const storage = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
    });
    vi.stubGlobal("document", { cookie: "" });
    vi.stubGlobal("window", {
      setTimeout: (callback: () => void) => {
        owned.timers.push(callback);
        return owned.timers.length;
      },
      clearTimeout: vi.fn(),
    });
  });
  afterEach(() => vi.unstubAllGlobals());

  it("discards a successful but late renewal after another account signs in", async () => {
    let finish!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve;
          })
      )
    );
    const first = owned.auth.jwt;
    const request = renew(first);
    owned.auth.jwt = token(2, "next-account");
    owned.auth.user = { id: 2, scope: "/other" };
    finish(new Response(token(1, "late")));
    await expect(request).rejects.toMatchObject({ is_canceled: true });
    expect(owned.auth.user.id).toBe(2);
    expect(owned.auth.setUser).not.toHaveBeenCalled();
    expect(document.cookie).toBe("");
  });

  it("refuses a renewal token for a different user even while the original request is current", async () => {
    const first = owned.auth.jwt;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(token(2, "wrong-user")))
    );
    await expect(renew(first)).rejects.toMatchObject({ status: 502 });
    expect(owned.auth.jwt).toBe(first);
    expect(owned.auth.setUser).not.toHaveBeenCalled();
  });

  it("still restores the saved account when the in-memory store is initially empty", async () => {
    const saved = owned.auth.jwt;
    const fresh = token(1, "restored");
    localStorage.setItem("jwt", saved);
    owned.auth.jwt = "";
    owned.auth.user = null;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(fresh)));
    await validateLogin();
    expect(owned.auth.jwt).toBe(fresh);
    expect(owned.auth.user?.id).toBe(1);
  });

  it("does not let an already queued old expiry callback log out a newer token", () => {
    parseToken(owned.auth.jwt);
    expect(owned.timers).toHaveLength(1);
    const newer = token(1, "newer");
    owned.auth.jwt = newer;
    owned.timers[0]();
    expect(owned.auth.clearUser).not.toHaveBeenCalled();
    expect(owned.push).not.toHaveBeenCalled();
    expect(owned.auth.jwt).toBe(newer);
  });
  it.each([200, 503])(
    "singleflights concurrent renewal including failure HTTP %s",
    async (status) => {
      let finish!: (response: Response) => void;
      vi.stubGlobal(
        "fetch",
        vi.fn(
          () =>
            new Promise<Response>((resolve) => {
              finish = resolve;
            })
        )
      );
      const first = renew(owned.auth.jwt);
      const second = renew(owned.auth.jwt);
      expect(second).toBe(first);
      finish(
        new Response(status === 200 ? token(1, "next") : "unavailable", {
          status,
        })
      );
      const results = await Promise.allSettled([first, second]);
      expect(results.map((result) => result.status)).toEqual(
        status === 200 ? ["fulfilled", "fulfilled"] : ["rejected", "rejected"]
      );
      expect(fetch).toHaveBeenCalledTimes(1);
    }
  );

  it("refuses a different explicit token before sending renewal", async () => {
    vi.stubGlobal("fetch", vi.fn());
    await expect(renew(token(2, "other"))).rejects.toMatchObject({
      is_canceled: true,
    });
    expect(fetch).not.toHaveBeenCalled();
  });

  it("invalidates resource activity on a same-account explicit sign in", async () => {
    const activity = captureSessionActivity();
    const unchanged = owned.auth.jwt;
    parseToken(unchanged);
    vi.stubGlobal("fetch", vi.fn());
    await activity.activity();
    expect(activity.current()).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not let an old same-token expiry callback log out a new generation", () => {
    const unchanged = owned.auth.jwt;
    parseToken(unchanged);
    const old = owned.timers[0];
    parseToken(unchanged);
    old();
    expect(owned.auth.clearUser).not.toHaveBeenCalled();
  });

  it("retains new in-memory token and expiry timer when storage rejects renewal", async () => {
    const fresh = token(1, "fresh");
    vi.stubGlobal("localStorage", {
      setItem: () => {
        throw new Error("storage unavailable");
      },
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(fresh)));
    await renew(owned.auth.jwt);
    expect(owned.auth.jwt).toBe(fresh);
    expect(owned.auth.sessionHealth).toBe("storage_failed");
    expect(owned.auth.setLogoutTimer).toHaveBeenCalledTimes(1);
  });

  it("slides on day 16 and respects explicit false and expired JWTs", () => {
    const jwt = token(1, "day-16");
    expect(shouldRenewToken(jwt, null)).toBe(true);
    expect(shouldRenewToken(jwt, "false")).toBe(false);
    const expired =
      "owned." +
      Buffer.from(JSON.stringify({ user: { id: 1 }, iat: 1, exp: 2 })).toString(
        "base64url"
      ) +
      ".owned";
    expect(shouldRenewToken(expired, null)).toBe(false);
  });
});
