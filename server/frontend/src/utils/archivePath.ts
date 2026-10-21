import type { RouteLocationRaw } from "vue-router";
import { normalizeFileKey } from "./fileListing";

const BROWSABLE_ARCHIVE_SUFFIXES = [
  ".tar.bz2",
  ".tar.gz",
  ".tar.xz",
  ".tar.zst",
  ".tar",
  ".zip",
  ".7z",
  ".rar",
] as const;

export function isBrowsableArchivePath(value: string) {
  const normalized = value.toLowerCase();
  return BROWSABLE_ARCHIVE_SUFFIXES.some((suffix) =>
    normalized.endsWith(suffix)
  );
}

export function archiveRoute(
  path: string,
  wirePath?: string
): RouteLocationRaw {
  return {
    path: "/archive",
    query: { path: normalizeFileKey(path), ...(wirePath ? { wirePath } : {}) },
  };
}

export function resourceOpenRoute(resource: {
  isDir: boolean;
  path: string;
  url: string;
  wirePath?: string;
}): RouteLocationRaw {
  if (!resource.isDir && isBrowsableArchivePath(resource.path)) {
    const wire =
      resource.wirePath ||
      (resource.url.startsWith("/files/")
        ? resource.url.slice("/files".length).replace(/\/+$/, "")
        : undefined);
    return archiveRoute(resource.path, wire);
  }
  return { path: resource.url };
}
