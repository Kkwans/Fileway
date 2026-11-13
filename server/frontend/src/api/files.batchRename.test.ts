import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  fetchURL: vi.fn(),
  favoritesRewrite: vi.fn(),
  tagsRewrite: vi.fn(),
}));
vi.mock("./utils", () => ({
  fetchURL: mocks.fetchURL,
  fetchJSON: vi.fn(),
  createURL: vi.fn(),
  removePrefix: vi.fn(),
  StatusError: class StatusError extends Error {},
}));
vi.mock("@/router", () => ({ default: { push: vi.fn() } }));
vi.mock("@/stores/auth", () => ({
  useAuthStore: () => ({ jwt: "owned-fixture" }),
}));
vi.mock("@/stores/layout", () => ({
  useLayoutStore: () => ({ closeHovers: vi.fn() }),
}));
vi.mock("@/stores/favorites", () => ({
  useFavoritesStore: () => ({ applyPathRewrite: mocks.favoritesRewrite }),
}));
vi.mock("@/stores/tags", () => ({
  useTagsStore: () => ({ applyPathRewrite: mocks.tagsRewrite }),
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

import { batchRename, type BatchRenameResultItem } from "./files";
import { useRecentStore } from "@/stores/recent";

function result(items: BatchRenameResultItem[], executed = true, valid = true) {
  mocks.fetchURL.mockResolvedValue(
    new Response(JSON.stringify({ valid, executed, items }))
  );
}

describe("batch rename API byte identity and metadata", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("keeps legacy UTF-8 input compatible and stages a name swap in two phases", async () => {
    const recent = useRecentStore();
    recent.items = [
      { id: "a", path: "/a.txt", name: "a.txt", isDir: false, accessedAt: 2 },
      { id: "b", path: "/b.txt", name: "b.txt", isDir: false, accessedAt: 1 },
    ];
    const changes = [
      { from: "/a.txt", to: "/b.txt" },
      { from: "/b.txt", to: "/a.txt" },
    ];
    result(changes.map((item) => ({ ...item, status: "completed" })));
    await batchRename(changes, false);
    expect(JSON.parse(mocks.fetchURL.mock.calls[0][1].body)).toEqual({
      items: changes,
      dryRun: false,
    });
    expect(recent.items.map((item) => item.path)).toEqual(["/b.txt", "/a.txt"]);
    expect(mocks.favoritesRewrite).toHaveBeenCalledTimes(4);
    const firstTemp = mocks.favoritesRewrite.mock.calls[0][1];
    const secondTemp = mocks.favoritesRewrite.mock.calls[1][1];
    expect(mocks.favoritesRewrite.mock.calls[2]).toEqual([firstTemp, "/b.txt"]);
    expect(mocks.favoritesRewrite.mock.calls[3]).toEqual([
      secondTemp,
      "/a.txt",
    ]);
  });

  it("uses response wire identities in both metadata phases without touching a same-display sibling", async () => {
    const recent = useRecentStore();
    recent.items = [
      {
        id: "opaque",
        path: "/中文.txt",
        wirePath: "/%D6%D0%CE%C4.txt",
        name: "中文.txt",
        isDir: false,
        accessedAt: 2,
      },
      {
        id: "utf8",
        path: "/中文.txt",
        wirePath: "/%E4%B8%AD%E6%96%87.txt",
        name: "中文.txt",
        isDir: false,
        accessedAt: 1,
      },
    ];
    const rewrite = vi.spyOn(recent, "applyPathRewrite");
    const change = {
      from: "/中文.txt",
      to: "/new +%?#.txt",
      fromWirePath: "/%D6%D0%CE%C4.txt",
      toWirePath: "/new%20%2B%25%3F%23.txt",
    };
    result([{ ...change, status: "completed" }]);
    await batchRename([change], false);
    expect(JSON.parse(mocks.fetchURL.mock.calls[0][1].body)).toEqual({
      items: [change],
      dryRun: false,
    });
    expect(recent.items[0].path).toBe(change.to);
    expect(recent.items[0].wirePath).toBe(change.toWirePath);
    expect(recent.items[1].wirePath).toBe("/%E4%B8%AD%E6%96%87.txt");
    expect(rewrite).toHaveBeenCalledTimes(2);
    const temp = rewrite.mock.calls[0][1];
    const tempWire = rewrite.mock.calls[0][3];
    expect(rewrite.mock.calls[0]).toEqual([
      change.from,
      temp,
      change.fromWirePath,
      tempWire,
    ]);
    expect(rewrite.mock.calls[1]).toEqual([
      temp,
      change.to,
      tempWire,
      change.toWirePath,
    ]);
    expect(mocks.favoritesRewrite).not.toHaveBeenCalled();
    expect(mocks.tagsRewrite).not.toHaveBeenCalled();
  });

  it("does not rewrite metadata on dry-run or invalid results", async () => {
    const recent = useRecentStore();
    const rewrite = vi.spyOn(recent, "applyPathRewrite");
    const changes = [{ from: "/a", to: "/b" }];
    result([{ ...changes[0], status: "ready" }], false);
    await batchRename(changes, true);
    result(
      [{ ...changes[0], status: "error", error: "conflict" }],
      false,
      false
    );
    await batchRename(changes, true);
    expect(rewrite).not.toHaveBeenCalled();
    expect(mocks.favoritesRewrite).not.toHaveBeenCalled();
  });

  it("rejects a response that names another raw resource or falsely claims dry-run execution", async () => {
    const changes = [
      {
        from: "/中文",
        to: "/new",
        fromWirePath: "/%D6%D0%CE%C4",
        toWirePath: "/new",
      },
    ];
    result([
      {
        ...changes[0],
        fromWirePath: "/%E4%B8%AD%E6%96%87",
        status: "completed",
      },
    ]);
    await expect(batchRename(changes, false)).rejects.toThrow("原始路径不一致");
    result([{ ...changes[0], status: "completed" }]);
    await expect(batchRename(changes, true)).rejects.toThrow("执行状态无效");
    expect(mocks.favoritesRewrite).not.toHaveBeenCalled();
  });
});
