# 宿主 Worker 路径的干净目录验证

日期：2026-08-20

这条记录证明 [`host-worker.md`](host-worker.md) 不依赖开发工作区里的 `target/`、`frontend/node_modules/` 或 `.env`。验证目录是一份不含这些缓存与私有文件的临时 git clone，正文不写本机家目录路径。

本机此前已经用开发树跑过同一条路径，同名 Compose 卷里还留着那次 Run。验证开始前删掉了 `patchatlas_postgres-data`，否则路径 A 的空列表期望和路径 B 的幂等键都会撞上旧数据。这不是文档里的步骤，是本机残留清理。

## 缺凭据

在验证目录执行，且未导出模型凭据：

```bash
./scripts/worker.sh
```

期望：退出码 2，标准错误包含 `missing:` 列表，不读 `.env`，不回落 FAKE。

实际：退出码 2。标准错误为：

```text
refusing to start Worker: missing credentials or workspace
  missing: OPENAI_API_KEY
  missing: PATCHATLAS_OPENAI_MODEL
  missing: PATCHATLAS_WORKER_WORKSPACE_ROOT
this command does not read .env files and does not silently switch to FAKE
```

未启动宿主 JVM，也未调用模型。验证目录始终没有 `.env`。

## 路径 A：无凭据的 Replay 校准

同一验证目录，无 `.env`、无 `target/`、无 `frontend/node_modules/`，未导出模型凭据：

```bash
./scripts/up.sh
curl -sf http://127.0.0.1:8080/api/v1/health
curl -sf http://127.0.0.1:8080/api/runs
curl -sf -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/runs
RUNNER=docker bash fixtures/off-by-one/run-replay.sh
```

期望：控制台健康且空库；校准结果来自 Replay 脚本的 `REPLAY OK`，**不会**写成这个空库里的 Run。这是校准，不是 Agent 成绩。

实际：

- `./scripts/up.sh` 退出码 0。镜像在验证目录内构建。
- `GET /api/v1/health` → `{"name":"PatchAtlas","status":"UP","version":"0.1.0-SNAPSHOT","runtime":{"javaVersion":"21.0.11",...}}`
- `GET /api/runs` → `{"items":[],"nextCursor":null}`
- `GET /runs` → HTTP 200
- `RUNNER=docker bash fixtures/off-by-one/run-replay.sh` 标准输出含 `fixed 通过: 1 (期望 1)`、`buggy 通过: 0 (期望 0)`、`REPLAY OK: 同一测试在 buggy 失败、在 fixed 通过。`
- 容器打印了无法写 `/root/.m2` 的提示，结果行仍是上面这一组
- 验证目录始终没有 `.env`

控制台此时仍是空列表。路径 A 的终态是脚本的 `REPLAY OK`，不是 Vue 里的 `VALID_REPRODUCTION` Run。

## 路径 B：带凭据的 Issue2Test

验证目录没有 `.env`。文档里的 `set -a; source .env; set +a` 是读者在自己有该文件时的一步。本次把 `OPENAI_API_KEY`、`PATCHATLAS_OPENAI_MODEL` 从验证目录**之外**的环境变量导入，没有把 `.env` 拷进验证目录。然后按文档：

```bash
export PATCHATLAS_WORKER_WORKSPACE_ROOT="$PWD/var/worker-workspaces"
mkdir -p "$PATCHATLAS_WORKER_WORKSPACE_ROOT"
./scripts/worker.sh
```

以及文档中的 `POST /api/runs`（公开仓库 `st-tu-dresden/salespoint`、Issue 412、完整 SHA、`Idempotency-Key: host-worker-salespoint-412`）。

期望：HTTP 202，随后在控制台看到终态 Run 与定位轨迹。**不是** `VALID_REPRODUCTION`。不换案例、不重跑到成功。

实际：

- 验证目录在启动 Worker 之前仍无 `.env`、无 `target/`、无 `frontend/node_modules/`。`./scripts/worker.sh` 会在验证目录内编译，这之后才会出现 `target/`。
- 宿主 Worker 健康检查在 `127.0.0.1:8081` 为 `"status":"UP"`，Java `21.0.11`。看 Run 用控制台 8080。
- `POST /api/runs` → HTTP 202，`{"runId":"9a205591-2eb9-4a57-bc82-269e41a300e0","state":"QUEUED"}`
- 终态 `FAILED`。`result.failureStage=GENERATION`，`failureCategory=GENERATION_EXHAUSTED`，`failureSummary=generation attempts exhausted`
- 生成 `attemptCount=3`，`usageStatus=RECORDED_FOR_ALL_ATTEMPTS`
- 定位：`contextOrigin=HEURISTIC`，`recorded=true`，`toolCallsApplicable=false`，`toolCallCount` 为空（界面为「—」），步骤 `SELECTION`×12 + `EXCLUSION`×2，无预算事件，未截断
- Attempt 列表为空（生成未产出可 Replay 的 Candidate）
- `GET /api/runs` 列表 1 条：上述 `runId`、`FAILED`、`GENERATION_EXHAUSTED`
- `GET /runs/{runId}` → HTTP 200
- 验证目录始终没有 `.env`
- 不重跑、不换案例

这 1 次终态 Run 证明外部使用者能按文档产生一次可查看的执行，并把定位轨迹留在控制台。它不是 Agent 复现率，也不能把路径 A 的 `REPLAY OK` 加进这里的分母。

## 停止

```bash
./scripts/down.sh
```

实际：退出码 0。验证目录始终没有 `.env`。
