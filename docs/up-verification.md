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
