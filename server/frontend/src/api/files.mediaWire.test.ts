import { describe, expect, it, vi } from "vitest";
import { getDownloadURL, getPreviewURL } from "./files";
import type { ResourceItem } from "@/types/file";

vi.mock("./utils", () => ({
  createURL: (prefix: string, params: Record<string, string>) => {
    const url = new URL(
      `https://fixture.invalid/base/${prefix.split("/").map(encodeURIComponent).join("/")}`
    );
    url.search = new URLSearchParams(params).toString();
    return url.toString();
  },
  fetchURL: vi.fn(),
  removePrefix: vi.fn(),
  StatusError: class extends Error {},
}));
vi.mock("@/router", () => ({ default: { push: vi.fn() } }));
vi.mock("@/stores/auth", () => ({ useAuthStore: vi.fn() }));
vi.mock("@/stores/favorites", () => ({ useFavoritesStore: vi.fn() }));
vi.mock("@/stores/layout", () => ({ useLayoutStore: vi.fn() }));
vi.mock("@/stores/tags", () => ({ useTagsStore: vi.fn() }));
vi.mock("@/stores/recent", () => ({ useRecentStore: vi.fn() }));
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

describe("actual media API URL leaves", () => {
  it("keeps original bytes in raw, download, big preview and thumbnail paths", () => {
    const opaque = {
      path: "/中文.jpg",
      wirePath: "/%D6%D0%CE%C4.jpg",
      size: 20,
      modified: "2026-10-09T00:00:00Z",
    } as ResourceItem;
    expect(new URL(getDownloadURL(opaque, true)).pathname).toBe(
      "/base/api/raw/%D6%D0%CE%C4.jpg"
    );
    expect(new URL(getDownloadURL(opaque, false)).pathname).toBe(
      "/base/api/raw/%D6%D0%CE%C4.jpg"
    );
    expect(new URL(getPreviewURL(opaque, "big")).pathname).toBe(
      "/base/api/preview/big/%D6%D0%CE%C4.jpg"
    );
    const thumb = new URL(
      getPreviewURL(opaque, "thumb", { warm: "big", fit: "contain" })
    );
    expect(thumb.pathname).toBe("/base/api/preview/thumb/%D6%D0%CE%C4.jpg");
    expect(thumb.searchParams.get("warm")).toBe("big");
    expect(thumb.searchParams.get("fit")).toBe("contain");
    const utf8 = { ...opaque, wirePath: "/%E4%B8%AD%E6%96%87.jpg" };
    expect(getDownloadURL(opaque, true)).not.toBe(getDownloadURL(utf8, true));
  });
  it("preserves literal percent and UTF8-only legacy input, rejecting lost or contradictory paths", () => {
    expect(
      new URL(getDownloadURL({ path: "/a%2Fb +?#中文.mp4" }, true)).pathname
    ).toBe("/base/api/raw/a%252Fb%20%2B%3F%23%E4%B8%AD%E6%96%87.mp4");
    expect(() => getDownloadURL({ path: "/lost�.mp4" }, true)).toThrow(
      "原始路径"
    );
    expect(() =>
      getDownloadURL({ path: "/same", wirePath: "/different" }, true)
    ).toThrow("原始路径");
    const unknown = {
      path: "/lost�.mp4",
      wirePath: "/lost%EF%BF%BD.mp4",
      pathVerified: false,
    };
    expect(() => getDownloadURL(unknown, true)).toThrow("原始路径");
  });
});
