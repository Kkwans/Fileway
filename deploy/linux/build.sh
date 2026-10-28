#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
tag=${1:-${IMAGE_TAG:-}}
if [[ -z "$tag" ]]; then
  echo '用法：build-image.sh IMAGE_TAG' >&2
  exit 2
fi

image_name=${FILEWAY_IMAGE_NAME:-fileway-linux}
"$script_dir/check-image-tag.sh" "$image_name:$tag"
revision=${IMAGE_REVISION:-$(git -C "$repo_root" rev-parse HEAD)}
created=${IMAGE_CREATED:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo '构建需要明确的源提交 SHA' >&2; exit 1; }
case "${FILEWAY_MEDIA_PROFILE:-portable}" in
  portable) runtime_base=alpine:3.23 ;;
  rockchip) runtime_base=jellyfin/jellyfin:10.11.11@sha256:7536c1009c6ea50dadd2b244165efb357504ca0f2670abefbceb1c773cc7e13d ;;
  *) echo '未知媒体运行环境' >&2; exit 1 ;;
esac
runtime_base=${FILEWAY_RUNTIME_BASE:-$runtime_base}
platform=${FILEWAY_PLATFORM:-linux/$(uname -m)}
case "$platform" in linux/aarch64) platform=linux/arm64 ;; linux/x86_64) platform=linux/amd64 ;; esac
[[ "$platform" == linux/arm64 || "$platform" == linux/amd64 ]] || { echo '明确选择 linux/arm64 或 linux/amd64' >&2; exit 1; }
extra=()
source_stage=source-artifact
if [[ -n "${FILEWAY_BINARY_DIR:-}" ]]; then
  binary_dir=$(CDPATH= cd -- "$FILEWAY_BINARY_DIR" && pwd)
  [[ -f "$binary_dir/fileway-server" && ! -L "$binary_dir/fileway-server" ]] || { echo '缺少已验证的普通二进制文件' >&2; exit 1; }
  shopt -s dotglob nullglob
  for entry in "$binary_dir"/*; do
    case "${entry##*/}" in
      fileway-server|SHA256SUMS|SOURCE_SHA.txt) [[ -f "$entry" && ! -L "$entry" ]] || exit 1 ;;
      *) echo '二进制构建上下文含有无关文件，拒绝上传' >&2; exit 1 ;;
    esac
  done
  shopt -u dotglob nullglob
  metadata=$(go version -m "$binary_dir/fileway-server")
  if ! grep -Fq "CommitSHA=$revision" <<< "$metadata"; then
    # Go 1.26 omits linker flags from trimpath build metadata. For a native
    # artifact, verify the pinned version command instead of dropping the gate.
    host_arch=$(uname -m)
    case "$host_arch" in aarch64) host_arch=arm64 ;; x86_64) host_arch=amd64 ;; esac
    [[ "$platform" == "linux/$host_arch" ]] || { echo '无法核实跨架构二进制的源提交' >&2; exit 1; }
    reported=$("$binary_dir/fileway-server" version)
    [[ "$reported" == "File Browser v$tag/$revision" ]] || { echo '二进制源提交不匹配' >&2; exit 1; }
  fi
  grep -Eq 'build[[:space:]]+GOOS=linux$' <<< "$metadata" || { echo '二进制不是 Linux 程序' >&2; exit 1; }
  grep -Eq "build[[:space:]]+GOARCH=${platform#linux/}$" <<< "$metadata" || { echo '二进制架构不匹配' >&2; exit 1; }
  grep -Eq 'build[[:space:]]+CGO_ENABLED=0$' <<< "$metadata" || { echo '需要已验证的静态 Linux 程序' >&2; exit 1; }
  source_stage=prebuilt-artifact
  extra+=(--build-context "verified-server=$binary_dir")
fi
export IMAGE_VERSION="$tag" IMAGE_REVISION="$revision" IMAGE_CREATED="$created"
docker build --platform "$platform" "${extra[@]}" -f "$script_dir/Dockerfile" \
  --build-arg SERVER_SOURCE="$source_stage" --build-arg RUNTIME_BASE="$runtime_base" \
  --build-arg IMAGE_VERSION="$tag" --build-arg IMAGE_REVISION="$revision" \
  --build-arg IMAGE_CREATED="$created" -t "$image_name:$tag" "$repo_root"

image="$image_name:$tag"
actual=$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}|{{index .Config.Labels "org.opencontainers.image.version"}}|{{index .Config.Labels "org.opencontainers.image.created"}}')
expected="$revision|$tag|$created"
[[ "$actual" == "$expected" ]] || { echo "OCI 元数据不一致：$actual != $expected" >&2; exit 1; }
echo "镜像构建通过：$image ($revision)"
