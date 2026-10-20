import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
const owned = vi.hoisted(() => ({
  current: true,
  activity: vi.fn(),
  response: vi.fn(),
}));
vi.mock("../auth", () => ({
  captureSessionActivity: () => ({
    token: "owned-token",
    current: () => owned.current,
    activity: owned.activity,
    response: owned.response,
  }),
}));
vi.mock("../constants", () => ({ baseURL: "/base" }));
import { bindResourceActivity, bindXHRActivity } from "../sessionActivity";

class Resource extends EventTarget {
  currentSrc = "https://service.example.test/base/api/raw/owned";
  end = 1;
  buffered = { length: 1, start: () => 0, end: () => this.end };
  getAttribute() {
    return this.currentSrc;
  }
}
class Request extends EventTarget {
  responseURL = "https://service.example.test/base/api/resources/owned";
  readyState = 1;
  status = 200;
  getResponseHeader() {
    return "true";
  }
}
describe("actual authenticated resource activity", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    owned.current = true;
    vi.stubGlobal("window", {
      location: {
        href: "https://service.example.test/base/files/",
        origin: "https://service.example.test",
      },
    });
  });
  afterEach(() => vi.unstubAllGlobals());
  it("uses resource progress rather than playback time and removes hooks without reloading source", () => {
    const resource = new Resource();
    const original = resource.currentSrc;
    const stop = bindResourceActivity(resource as unknown as HTMLElement);
    resource.dispatchEvent(new Event("timeupdate"));
    resource.dispatchEvent(new Event("progress"));
    resource.dispatchEvent(new Event("progress"));
    resource.end = 2;
    resource.dispatchEvent(new Event("progress"));
    expect(owned.activity).toHaveBeenCalledTimes(2);
    expect(resource.currentSrc).toBe(original);
    stop();
    resource.dispatchEvent(new Event("loadedmetadata"));
    expect(owned.activity).toHaveBeenCalledTimes(2);
  });
  it.each([
    "https://other.example.test/base/api/raw/owned",
    "blob:https://service.example.test/owned",
    "https://service.example.test/base/static/icon.svg",
    "https://service.example.test/base/api/public/dl/anonymous",
  ])("ignores resources outside the authenticated API: %s", (source) => {
    const resource = new Resource();
    resource.currentSrc = source;
    bindResourceActivity(resource as unknown as HTMLElement);
    resource.dispatchEvent(new Event("load"));
    expect(owned.activity).not.toHaveBeenCalled();
  });
  it("processes XHR headers once before completion and keeps the originating token", () => {
    const request = new Request();
    bindXHRActivity(request as unknown as XMLHttpRequest);
    request.dispatchEvent(new Event("readystatechange"));
    expect(owned.response).not.toHaveBeenCalled();
    request.readyState = 2;
    request.dispatchEvent(new Event("readystatechange"));
    request.readyState = 4;
    request.dispatchEvent(new Event("readystatechange"));
    expect(owned.response).toHaveBeenCalledExactlyOnceWith(
      "true",
      "owned-token"
    );
  });
  it("does not treat failed XHR headers as successful resource activity", () => {
    const request = new Request();
    request.status = 401;
    bindXHRActivity(request as unknown as XMLHttpRequest);
    request.readyState = 2;
    request.dispatchEvent(new Event("readystatechange"));
    expect(owned.response).not.toHaveBeenCalled();
  });
  it("does not extend login for external or anonymous XHR responses", () => {
    for (const url of [
      "https://other.example.test/base/api/raw/owned",
      "https://service.example.test/base/api/public/dl/anonymous",
    ]) {
      const request = new Request();
      request.responseURL = url;
      bindXHRActivity(request as unknown as XMLHttpRequest);
      request.readyState = 2;
      request.dispatchEvent(new Event("readystatechange"));
    }
    expect(owned.response).not.toHaveBeenCalled();
  });
});
