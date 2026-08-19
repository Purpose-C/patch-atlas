# 一条命令启动的干净目录验证

日期：2026-08-19

这条记录证明 `./scripts/up.sh` 不依赖开发工作区里的 `target/`、`frontend/node_modules/` 或 `.env`。验证目录是一份不含这些缓存与私有文件的临时副本，正文不写本机家目录路径。

## 缺凭据

在验证目录执行，且未导出模型凭据：

```bash
./scripts/up.sh --worker
```

期望：退出码 2，标准错误包含：

- `refusing to start Worker: missing credentials or workspace`
- `missing: OPENAI_API_KEY`
- `missing: PATCHATLAS_OPENAI_MODEL`
- `missing: PATCHATLAS_WORKER_WORKSPACE_ROOT`
- `this command does not read .env files`

实际：退出码 2。标准错误为：

```text
refusing to start Worker: missing credentials or workspace
  missing: OPENAI_API_KEY
  missing: PATCHATLAS_OPENAI_MODEL
  missing: PATCHATLAS_WORKER_WORKSPACE_ROOT
this command does not read .env files and does not silently switch to FAKE
```

未读取 `.env`，也未执行 `docker compose up`。

## 拉起控制台

同一验证目录，无 `.env`、无 `target/`、无 `frontend/node_modules/`：

```bash
./scripts/up.sh
curl -sf http://127.0.0.1:8080/api/v1/health
curl -sf http://127.0.0.1:8080/api/runs
curl -sf -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/runs
./scripts/down.sh
```

期望：

- `./scripts/up.sh` 退出码 0
- 健康检查 JSON 含 `"status":"UP"`
- `/api/runs` 返回空列表（新库，没有历史 Run）
- `/runs` HTTP 200
- 验证目录内不存在 `.env`

实际：

- `./scripts/up.sh` 退出码 0。镜像在验证目录内构建，Maven 依赖在镜像内下载，未使用开发工作区的 `target/`。
- `GET /api/v1/health` → `{"name":"PatchAtlas","status":"UP","version":"0.1.0-SNAPSHOT",...}`
- `GET /api/runs` → `{"items":[],"nextCursor":null}`
- `GET /runs` → HTTP 200
- 验证目录始终没有 `.env`
- `./scripts/down.sh` 退出码 0

## 连跑两次（2026-08-20）

同一类干净副本（无 `.env`、无 `target/`、无 `frontend/node_modules/`），在第一次已经拉起成功之后立刻再执行一次，不先 `down`：

```bash
./scripts/up.sh
curl -sf http://127.0.0.1:8080/api/v1/health
./scripts/up.sh
curl -sf http://127.0.0.1:8080/api/v1/health
./scripts/down.sh
```

期望：两次 `./scripts/up.sh` 都是退出码 0；第二次会清掉占用 `patchatlas-postgres-1` / `patchatlas-app-1` / `patchatlas-web-1` 的容器再拉起；健康检查仍为 `"status":"UP"`；`/api/runs` 仍是空列表。

实际：

- 第一次退出码 0。`GET /api/v1/health` → `{"name":"PatchAtlas","status":"UP",...}`；`GET /api/runs` → `{"items":[],"nextCursor":null}`；`GET /runs` → HTTP 200
- 第二次标准错误含：

  ```text
  removing leftover container patchatlas-postgres-1 so compose can start
  removing leftover container patchatlas-app-1 so compose can start
  removing leftover container patchatlas-web-1 so compose can start
  ```

  随后退出码 0。健康检查再次 `"status":"UP"`，`/api/runs` 仍为 `{"items":[],"nextCursor":null}`，`/runs` HTTP 200
- `./scripts/down.sh` 退出码 0
- 验证目录始终没有 `.env`

这只证明启动脚本重复执行不再因同名容器失败。`docs/demo-recording.md` 里那次 Compose 冲突失败仍按当时真实发生保留，没有重录。
