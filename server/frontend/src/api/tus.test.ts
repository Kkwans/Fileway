import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  previous: [] as Array<{
    uploadUrl: string;
    size: number;
    urlStorageKey: string;
  }>,
  instances: [] as Array<{
    options: { headers: Record<string, string> };
    start: ReturnType<typeof vi.fn>;
    resumeFromPreviousUpload: ReturnType<typeof vi.fn>;
  }>,
}));
vi.mock("@/utils/constants", () => ({
  origin: "https://owned.example",
  baseURL: "/nas",
  tusEndpoint: "/api/tus",
  tusSettings: { chunkSize: 1024, retryCount: 2 },
}));
vi.mock("@/router", () => ({ default: { push: vi.fn() } }));
vi.mock("@/stores/auth", () => ({
  useAuthStore: () => ({ jwt: "owned.fixture.token" }),
}));
vi.mock("@/api/utils", () => ({
  removePrefix: (value: string) => value.replace(/^\/files/, ""),
}));
vi.mock("tus-js-client", () => ({
  Upload: class {
    options: {
      headers: Record<string, string>;
      onSuccess: () => void;
      urlStorage?: { removeUpload: ReturnType<typeof vi.fn> };
    };
    resumeFromPreviousUpload = vi.fn();
    start = vi.fn(() => this.options.onSuccess());
    constructor(
      _content: Blob,
      options: { headers: Record<string, string>; onSuccess: () => void }
    ) {
      this.options = options;
      mocks.instances.push(this);
    }
    findPreviousUploads = async () => mocks.previous;
  },
}));

import { upload } from "./tus";

describe("TUS durable upload identity", () => {
  beforeEach(() => {
    mocks.previous = [];
    mocks.instances = [];
    vi.stubGlobal("window", { confirm: vi.fn(() => true) });
  });
  afterEach(() => vi.unstubAllGlobals());

  it("uses a distinct session for two explicit fresh uploads of the same file", async () => {
    const content = new Blob(["original"]);
    await upload("/files/owned.bin", content, false, () => {});
    await upload("/files/owned.bin", content, false, () => {});
    const first = mocks.instances[0].options.headers["X-Transfer-ID"];
    const second = mocks.instances[1].options.headers["X-Transfer-ID"];
    expect(first).not.toBe(second);
    expect(first.length).toBeLessThanOrEqual(220);
    expect(second.length).toBeLessThanOrEqual(220);
  });

  it("restores the original transfer header from the same-origin previous upload URL", async () => {
    mocks.previous = [
      {
        uploadUrl:
          "https://owned.example/nas/api/tus/owned.bin?transfer=original-session",
        size: 8,
        urlStorageKey: "owned-key",
      },
    ];
    await upload("/files/owned.bin", new Blob(["original"]), false, () => {});
    expect(mocks.instances[0].options.headers["X-Transfer-ID"]).toBe(
      "original-session"
    );
    expect(mocks.instances[0].resumeFromPreviousUpload).toHaveBeenCalledWith(
      mocks.previous[0]
    );
    expect(mocks.instances[0].start).toHaveBeenCalledOnce();
  });

  it("does not send credentials to a persisted upload URL on another origin or target", async () => {
    for (const uploadUrl of [
      "https://foreign.example/nas/api/tus/owned.bin?transfer=owned",
      "https://owned.example/nas/api/tus/other.bin?transfer=owned",
    ]) {
      mocks.previous = [{ uploadUrl, size: 8, urlStorageKey: "owned-key" }];
      await expect(
        upload("/files/owned.bin", new Blob(["original"]), false, () => {})
      ).rejects.toThrow("未发送请求");
      expect(mocks.instances.at(-1)?.start).not.toHaveBeenCalled();
    }
  });
});
