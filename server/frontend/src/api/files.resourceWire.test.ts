import { beforeEach, describe, expect, it, vi } from "vitest";
const mocks = vi.hoisted(() => ({
  auth: { user: { id: 1, scope: "/one" }, jwt: "owned-fixture-token" },
  favoritesRemoval: vi.fn(),
  tagsRemoval: vi.fn(),
  recentRemoval: vi.fn(),
  fetchURL: vi.fn(),
  refreshFavorites: vi.fn(),
  refreshTags: vi.fn(),
  recentLoad: vi.fn(),
}));
vi.mock("./utils", () => ({
  fetchURL: mocks.fetchURL,
  createURL: vi.fn(),
  removePrefix: (url: string) => url.replace(/^\/files(?=\/|$)/, "") || "/",
  StatusError: class extends Error {},
}));
vi.mock("@/router", () => ({ default: { push: vi.fn() } }));
vi.mock("@/stores/auth", () => ({ useAuthStore: () => mocks.auth }));
vi.mock("@/stores/favorites", () => ({
  useFavoritesStore: () => ({
    refreshAfterMutation: mocks.refreshFavorites,
    applyPathRemoval: mocks.favoritesRemoval,
    applyPathRewrite: vi.fn(),
  }),
}));
vi.mock("@/stores/tags", () => ({
  useTagsStore: () => ({
    refreshAfterMutation: mocks.refreshTags,
    applyPathRemoval: mocks.tagsRemoval,
    applyPathRewrite: vi.fn(),
  }),
}));
vi.mock("@/stores/recent", () => ({
  useRecentStore: () => ({
    load: mocks.recentLoad,
    applyPathRemoval: mocks.recentRemoval,
    applyPathRewrite: vi.fn(),
  }),
}));
vi.mock("@/stores/layout", () => ({
  useLayoutStore: () => ({ closeHovers: vi.fn() }),
}));
vi.mock("@/utils/constants", () => ({ baseURL: "" }));
vi.mock("@/utils/encodings", () => ({
  isEncodableResponse: vi.fn(),
  makeRawResource: vi.fn(),
}));
vi.mock("./tus", () => ({
  upload: vi.fn(),
  uploadBatchHeaders: vi.fn(),
  uploadTransferId: vi.fn(),
  useTus: vi.fn(),
}));
vi.mock("./transfers", () => ({}));
import { copy, move, remove, schedulePermanentDeletion } from "./files";
import { StatusError } from "./utils";

const opaque = "/%D6%D0%CE%C4.txt",
  unicode = "/%E4%B8%AD%E6%96%87.txt";
const writes = () =>
  mocks.fetchURL.mock.calls.filter(
    (call) => call[1]?.method && call[1].method !== "GET"
  );
const response = (body: unknown) => new Response(JSON.stringify(body));
describe("resource operation wire transport", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.auth.user = { id: 1, scope: "/one" };
    mocks.refreshFavorites.mockResolvedValue(undefined);
    mocks.refreshTags.mockResolvedValue(undefined);
    mocks.recentLoad.mockResolvedValue(undefined);
    mocks.fetchURL.mockImplementation(async (endpoint: string) =>
      endpoint === "/api/client-capabilities"
        ? response({ resourceWireOperations: true })
        : response({ id: "owned-task" })
    );
  });
  it("transfers GBK, UTF8 and literal-percent siblings with independent exact wire fields", async () => {
    const from = [opaque, unicode, "/%25D6%25D0%25CE%25C4.txt"];
    await copy(
      from.map((wire, index) => ({
        from: `/files${wire}`,
        to: `/files/out/${index}.txt`,
      }))
    );
    expect(writes()).toHaveLength(1);
    const body = JSON.parse(writes()[0][1].body);
    expect(
      body.items.map((item: { fromWirePath: string }) => item.fromWirePath)
    ).toEqual(from);
    expect(body.items[0]).not.toHaveProperty("from");
    expect(body.items[0].toWirePath).toBe("/out/0.txt");
  });
  it("keeps source and destination in the same opaque parent for PATCH", async () => {
    await move([
      { from: "/files/%D6%D0/before.txt", to: "/files/%D6%D0/after.txt" },
    ]);
    expect(writes()).toHaveLength(1);
    const [endpoint, options] = writes()[0];
    expect(endpoint.split("?")[0]).toBe("/api/resources/%D6%D0/before.txt");
    const query = new URLSearchParams(endpoint.split("?")[1]);
    expect(query.get("destinationWirePath")).toBe("/%D6%D0/after.txt");
    expect(query.has("destination")).toBe(false);
    expect(options.method).toBe("PATCH");
  });
  it("uses one compatible UTF8 request, retaining literal%2F, real/files and genuine replacement characters", async () => {
    await copy([
      {
        from: "/files/files/literal%252F.txt",
        to: "/files/out/literal%252F.txt",
      },
      { from: "/files/lost%EF%BF%BD", to: "/files/out/lost%EF%BF%BD" },
    ]);
    expect(
      mocks.fetchURL.mock.calls.some(
        (call) => call[0] === "/api/client-capabilities"
      )
    ).toBe(false);
    const body = JSON.parse(writes()[0][1].body);
    expect(body.items[0].fromWirePath).toBe("/files/literal%252F.txt");
    expect(body.items[0].from).toBe("/files/files/literal%252F.txt");
    expect(body.items[1].fromWirePath).toBe("/lost%EF%BF%BD");
  });
  it("missing capability produces zero opaque writes and no display retry", async () => {
    mocks.fetchURL.mockResolvedValue(response({}));
    await expect(
      copy([{ from: `/files${opaque}`, to: "/files/out/new.txt" }])
    ).rejects.toThrow("升级");
    expect(writes()).toHaveLength(0);
  });
  it("an absent legacy capability endpoint gives an upgrade error without writing", async () => {
    mocks.fetchURL.mockRejectedValue(
      Object.assign(new StatusError("owned-missing-capability"), {
        status: 404,
      })
    );
    await expect(
      copy([{ from: `/files${opaque}`, to: "/files/out/new.txt" }])
    ).rejects.toThrow("升级");
    expect(writes()).toHaveLength(0);
  });
  it("account changes during capability discovery produce zero writes", async () => {
    let finish!: (response: Response) => void;
    mocks.fetchURL.mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      })
    );
    const pending = copy([
      { from: `/files${opaque}`, to: "/files/out/new.txt" },
    ]);
    mocks.auth.user = { id: 1, scope: "/two" };
    finish(response({ resourceWireOperations: true }));
    await expect(pending).rejects.toThrow("来源");
    expect(writes()).toHaveLength(0);
  });
  it("pending deletion carries ResourceRef wire paths in source order", async () => {
    await schedulePermanentDeletion([
      { path: "/中文.txt", wirePath: opaque },
      { path: "/中文.txt", wirePath: unicode },
    ]);
    const body = JSON.parse(writes()[0][1].body);
    expect(body.wirePaths).toEqual([opaque, unicode]);
    expect(body).not.toHaveProperty("paths");
    expect(writes()).toHaveLength(1);
  });
  it("returns successful delete ACK without touching new-account metadata", async () => {
    mocks.fetchURL.mockImplementation(async () => {
      mocks.auth.user = { id: 2, scope: "/two" };
      return response({ id: "owned-trash" });
    });
    await expect(remove("/files/owned.txt", "trash")).resolves.toEqual({
      id: "owned-trash",
    });
    expect(mocks.refreshFavorites).not.toHaveBeenCalled();
    expect(mocks.refreshTags).not.toHaveBeenCalled();
    expect(mocks.recentLoad).not.toHaveBeenCalled();
    expect(mocks.favoritesRemoval).not.toHaveBeenCalled();
    expect(mocks.tagsRemoval).not.toHaveBeenCalled();
    expect(mocks.recentRemoval).not.toHaveBeenCalled();
    expect(writes()).toHaveLength(1);
  });
  it("keeps legacy deletion strings literal and accepts genuine UTF8 replacement characters only with wire", async () => {
    await schedulePermanentDeletion([
      "/files/literal%2F.txt",
      { path: "/lost�", wirePath: "/lost%EF%BF%BD" },
    ]);
    const body = JSON.parse(writes()[0][1].body);
    expect(body.paths).toEqual(["/files/literal%2F.txt", "/lost�"]);
    expect(body.wirePaths).toEqual([
      "/files/literal%252F.txt",
      "/lost%EF%BF%BD",
    ]);
    expect(mocks.fetchURL).toHaveBeenCalledTimes(1);
    await expect(schedulePermanentDeletion(["/lost�"])).rejects.toThrow(
      "无法确认"
    );
    expect(writes()).toHaveLength(1);
  });
  it("same-account token renewal during capability discovery still permits one wire write", async () => {
    mocks.fetchURL.mockImplementation(async (endpoint: string) => {
      if (endpoint === "/api/client-capabilities") {
        mocks.auth.jwt = "owned-renewed-token";
        return response({ resourceWireOperations: true });
      }
      return response({ id: "owned-task" });
    });
    await copy([{ from: `/files${opaque}`, to: "/files/out/new.txt" }]);
    expect(writes()).toHaveLength(1);
  });
  it("successful PATCH ACK survives an account change and does not refresh the new account", async () => {
    mocks.fetchURL.mockImplementation(async () => {
      mocks.auth.user = { id: 2, scope: "/two" };
      return new Response(null, { status: 204 });
    });
    const result = await move([
      { from: "/files/old.txt", to: "/files/new.txt" },
    ]);
    expect(result[0].status).toBe(204);
    expect(writes()).toHaveLength(1);
    expect(mocks.refreshFavorites).not.toHaveBeenCalled();
    expect(mocks.recentLoad).not.toHaveBeenCalled();
  });
  it("missing opaque destination ACK refreshes authority and never retries or invents a keep-both target", async () => {
    mocks.fetchURL.mockImplementation(async (endpoint: string) =>
      endpoint === "/api/client-capabilities"
        ? response({ resourceWireOperations: true })
        : new Response(null, { status: 204 })
    );
    const result = await move([
      {
        from: "/files/%D6%D0/old.txt",
        to: "/files/%D6%D0/new.txt",
        rename: true,
      },
    ]);
    expect(result[0].status).toBe(204);
    expect(writes()).toHaveLength(1);
    expect(mocks.refreshFavorites).toHaveBeenCalledWith(true);
    expect(mocks.refreshTags).toHaveBeenCalledWith(true);
    expect(mocks.recentLoad).toHaveBeenCalledTimes(1);
  });
});
