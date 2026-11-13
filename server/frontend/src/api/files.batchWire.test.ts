import { describe, expect, it, vi, beforeEach } from "vitest";
import { fetchBatch } from "./files";
const mocks = vi.hoisted(() => ({ fetchURL: vi.fn() }));
vi.mock("./utils", () => ({
  fetchURL: mocks.fetchURL,
  createURL: vi.fn(),
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

describe("readonly batch wire input and acknowledgement", () => {
  beforeEach(() => mocks.fetchURL.mockReset());
  it("keeps same-display inputs and missing error rows in source order", async () => {
    const wires = ["/%D6%D0.txt", "/%E4%B8%AD.txt", "/missing.txt"];
    mocks.fetchURL.mockResolvedValue({
      json: async () =>
        wires.map((wirePath, index) => ({
          path: index === 2 ? "/missing.txt" : "/中.txt",
          wirePath,
          status: index === 2 ? 404 : 200,
        })),
    });
    const rows = await fetchBatch(
      ["/中.txt", "/中.txt", "/missing.txt"],
      undefined,
      wires
    );
    expect(rows.map((row) => row.wirePath)).toEqual(wires);
    expect(rows[2].status).toBe(404);
    const body = JSON.parse(mocks.fetchURL.mock.calls[0][1].body);
    expect(body.wirePaths).toEqual(wires);
    expect(body).not.toHaveProperty("paths");
    mocks.fetchURL.mockResolvedValueOnce({
      json: async () => [{ path: "/other", wirePath: "/other", status: 404 }],
    });
    await expect(
      fetchBatch(["/missing.txt"], undefined, ["/missing.txt"])
    ).rejects.toThrow("顺序不匹配");
  });
  it("splits oversized encoded bodies before sending and retains input order", async () => {
    const prefix = "/" + Array(16).fill("\u0001".repeat(200)).join("/");
    const paths = Array.from(
      { length: 100 },
      (_, index) => `${prefix}/${index}.txt`
    );
    const wires = paths.map((path) =>
      path.split("/").map(encodeURIComponent).join("/")
    );
    mocks.fetchURL.mockImplementation(
      async (_endpoint: string, options: { body: string }) => ({
        json: async () => {
          const body = JSON.parse(options.body);
          return body.paths.map((path: string, index: number) => ({
            path,
            wirePath: body.wirePaths[index],
            status: 404,
          }));
        },
      })
    );
    const rows = await fetchBatch(paths, undefined, wires);
    expect(mocks.fetchURL.mock.calls.length).toBeGreaterThan(1);
    expect(
      mocks.fetchURL.mock.calls.every(
        (call) => new TextEncoder().encode(call[1].body).length <= 768 * 1024
      )
    ).toBe(true);
    expect(rows.map((row) => row.wirePath)).toEqual(wires);
    await expect(fetchBatch(Array(501).fill("/a"))).rejects.toThrow("500");
  });
});
