import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const owned = vi.hoisted(() => ({
  auth: {
    jwt: "",
    user: { id: 1, scope: "/owned" },
    sessionHealth: null as string | null,
    logoutTimer: null as number | null,
    setUser: vi.fn(),
    setLogoutTimer: vi.fn(),
    clearUser: vi.fn(),
  },
  push: vi.fn(),
}));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => owned.auth }));
vi.mock("@/router", () => ({ default: { push: owned.push } }));
vi.mock("@/utils/constants", () => ({
  baseURL: "",
  authMethod: "json",
  noAuth: false,
  logoutPage: "/login",
}));
import { fetchURL } from "./utils";
import { parseToken } from "@/utils/auth";

function token(id = 1) {
  return (
    "owned." +
    Buffer.from(
      JSON.stringify({
        user: { id, scope: "/owned" },
        iat: 1,
        exp: Math.floor(Date.now() / 1000) + 3600,
      })
    ).toString("base64url") +
    ".owned"
  );
}
function pendingResponse() {
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
  return (response: Response) => finish(response);
}
function changeAccount() {
  owned.auth.jwt = token(2);
  owned.auth.user = { id: 2, scope: "/other" };
}

describe("request authentication and acknowledged writes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    owned.auth.jwt = token();
    owned.auth.user = { id: 1, scope: "/owned" };
    owned.auth.sessionHealth = null;
    owned.auth.setUser.mockImplementation((user) => {
      owned.auth.user = user;
    });
    vi.stubGlobal("window", {
      setTimeout: vi.fn(() => 1),
      clearTimeout: vi.fn(),
    });
    vi.stubGlobal("document", { cookie: "" });
    vi.stubGlobal("localStorage", { getItem: vi.fn(), setItem: vi.fn() });
  });
  afterEach(() => vi.unstubAllGlobals());

  it.each(["GET", "POST", "PUT", "PATCH", "DELETE"] as const)(
    "keeps a successful %s response when renewal fails and never replays it",
    async (method) => {
      const response = new Response('{"id":"owned-confirmed"}', {
        headers: { "X-Renew-Token": "true" },
      });
      const send = vi
        .fn()
        .mockResolvedValueOnce(response)
        .mockRejectedValueOnce(new Error("renewal unavailable"));
      vi.stubGlobal("fetch", send);
      const actual = await fetchURL("/api/owned", { method });
      expect(actual).toBe(response);
      expect(await actual.json()).toEqual({ id: "owned-confirmed" });
      expect(send).toHaveBeenCalledTimes(2);
      expect(send.mock.calls[1][0]).toBe("/api/renew");
      expect(owned.auth.sessionHealth).toBe("renewal_failed");
    }
  );

  it.each([403, 409, 503])(
    "preserves original HTTP %s and body when hinted renewal fails",
    async (status) => {
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValueOnce(
            new Response("original business error", {
              status,
              headers: { "X-Renew-Token": "true" },
            })
          )
          .mockResolvedValueOnce(
            new Response("renewal denied", { status: 401 })
          )
      );
      await expect(
        fetchURL("/api/owned", { method: "POST" })
      ).rejects.toMatchObject({ status, message: "original business error" });
      expect(owned.auth.sessionHealth).toBe("renewal_failed");
      expect(owned.auth.clearUser).not.toHaveBeenCalled();
    }
  );

  it("returns streaming headers before EOF while running renewal", async () => {
    const response = new Response(new ReadableStream({ start() {} }), {
      headers: { "X-Renew-Token": "true" },
    });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(response)
        .mockResolvedValueOnce(new Response(token()))
    );
    expect(await fetchURL("/api/search", {})).toBe(response);
    await response.body?.cancel();
  });

  it("does not log out a new account because an old request returned 401", async () => {
    const finish = pendingResponse();
    const request = fetchURL("/api/old", {});
    changeAccount();
    finish(new Response("denied", { status: 401 }));
    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(owned.auth.clearUser).not.toHaveBeenCalled();
  });

  it("does not act on an old response after the same account signs in again with the same JWT", async () => {
    const finish = pendingResponse();
    const request = fetchURL("/api/old", {});
    parseToken(owned.auth.jwt);
    finish(new Response("denied", { status: 401 }));
    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(owned.auth.clearUser).not.toHaveBeenCalled();
  });

  it("does not renew a new account from an old successful response", async () => {
    const finish = pendingResponse();
    const request = fetchURL("/api/old", { method: "POST" });
    changeAccount();
    finish(new Response("confirmed", { headers: { "X-Renew-Token": "true" } }));
    expect(await (await request).text()).toBe("confirmed");
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("ignores authentication side effects for an explicitly different request token", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("denied", {
          status: 401,
          headers: { "X-Renew-Token": "true" },
        })
      )
    );
    await expect(
      fetchURL("/api/other", { headers: { "x-auth": token(2) } })
    ).rejects.toMatchObject({ status: 401 });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(owned.auth.clearUser).not.toHaveBeenCalled();
  });

  it("logs out only on the bound business request's actual 401 and never renews it", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("denied", {
          status: 401,
          headers: { "X-Renew-Token": "true" },
        })
      )
    );
    await expect(fetchURL("/api/current", {})).rejects.toMatchObject({
      status: 401,
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(owned.auth.clearUser).toHaveBeenCalledTimes(1);
  });
});
