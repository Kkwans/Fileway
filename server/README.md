# 栖卷 · Fileway 服务端

基于 [File Browser](https://github.com/filebrowser/filebrowser) 开发的文件与媒体
服务端。Linux、Windows 使用同一套 Go 后端、Vue Web UI 和 HTTP API；NAS 是
Linux 的部署环境，不是独立维护的产品分支。macOS 尚待原生运行验收。

## 职责与目录

| 目录 | 职责 |
| --- | --- |
| `backend` | 认证、权限、文件与媒体操作、任务、回收站和持久化 |
| `frontend` | 所有服务端共用的 Web UI |
| `docker` | 容器初始化、健康检查及默认配置 |
| `scripts` | 构建与镜像质量门禁 |

客户端网络模块在 [`clients/shared/core`](../clients/shared/core/README.md)，Android UI 在
[`clients/android`](../clients/android/README.md)，它们不是这里的后端或 Web 副本。
平台边界见 [架构说明](../docs/server-architecture.md)。
字幕目录和文件的受限读取契约见 [字幕资源接口](docs/subtitle-resources.md)。

## 开发与验证

使用各组件锁定的 Go、Node.js 和 pnpm 版本。从仓库根目录执行：

```sh
cd server/frontend
corepack pnpm install --frozen-lockfile
corepack pnpm run lint
corepack pnpm run test
corepack pnpm run build
cd ../..
node server/scripts/embed-web.mjs
cd server/backend
go test -p 2 ./...
go vet ./...
go build .
```

Web 构建产物不进 Git。`backend/frontend/dist/README.txt` 只保证 Go embed 路径
可编译，不是可用的 Web UI。构建可运行服务前必须嵌入新 Web 产物；不能用旧
哈希文件充当最新页面。CI 只构建一次 Web，再供两端服务包共同嵌入。

开发前端可在 `frontend` 执行 `corepack pnpm dev`；后端启动时显式配置文件根目录
及仓库外的数据库路径，不在源码目录存放真实用户数据。

## 部署

- [Linux 容器部署](../deploy/linux/README.md)：通用配置与可选硬件加速。
- [Windows 服务端部署](../deploy/windows/README.md)：保留启动器、数据和媒体工具。

同一 API 的路径值随平台变化，例如 Windows `/C/...` 和 Linux `/data/...`。
创建时间、盘符和卷标是可选原生元数据，不是不同的 API。账号权限、用户范围、
Range 请求及旧持久化标识在升级中必须保留。

项目使用 [Apache-2.0](LICENSE)。后续开发仅在
[Kkwans/fileway](https://github.com/Kkwans/fileway) 进行。凭据、数据库、运行
记录和会话计划均放在仓库外，不作为源码提交。
