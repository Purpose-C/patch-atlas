# 在宿主上跑一次可验证的执行

一条命令 `./scripts/up.sh` 拉起的是只读控制台和空库，**不**启动 Issue2Test Worker，也**不**调用模型。要把 Replay Engine 和 Issue2Test 变成别人能复现的步骤，用下面两条路径。先写**不需要凭据**的那条。

沙箱执行用宿主 Docker 引擎。Compose 栈不挂载 `docker.sock`，`./scripts/up.sh --worker` 会拒绝启动 Worker。这是有意的。

## 路径 A：无凭据的 Replay 校准

只要本机有 Docker。不读 `.env`，不调用模型，不依赖 GitBug 数据源。

它证明的是 **Replay Engine**：同一个测试在 buggy 状态因目标断言失败、在 fixed 状态通过。这是校准，**不是** Agent 在真实 Issue 上的复现率。不要把这里的 `VALID_REPRODUCTION` / `REPLAY OK` 写成 Issue2Test 成绩。

### 1. 拉起只读控制台（可选，但建议）

```bash
./scripts/up.sh
curl -sf http://127.0.0.1:8080/api/v1/health
curl -sf http://127.0.0.1:8080/api/runs
```

期望：

- 健康检查 JSON 含 `"status":"UP"`
- `GET /api/runs` 返回空列表（新库，没有历史 Run）
- 浏览器打开 http://127.0.0.1:8080/runs 能看到空列表

这一步不产生校准结论。控制台在路径 A 里用来确认栈活着；校准结果来自下一步的受限容器，不会写成这个空库里的 Run。

### 2. 用已知触发测试跑 Historical Replay

仓库内的 `fixtures/off-by-one` 是公开、可复制的最小 Maven 项目：fixed 源码在树里，`buggy.patch` 是人类修复的反向补丁。脚本在受限容器里跑同一条 JUnit：

```bash
RUNNER=docker bash fixtures/off-by-one/run-replay.sh
```

期望标准输出含：

```text
REPLAY OK: 同一测试在 buggy 失败、在 fixed 通过。
```

以及 `fixed 通过: 1`、`buggy 通过: 0`。容器不挂载 Docker Socket，也不挂主机 Home。非 root 容器可能打印无法写 `/root/.m2` 的提示，只要结果行是上面这一组即可。

产品里的同等断言是 `HistoricalReplayEngineDockerTest`：off-by-one fixture 在 Historical 模式下得到 `VALID_REPRODUCTION`。那条测试需要本机 JDK 与 Maven Wrapper；上面的 `RUNNER=docker` 命令只需要 Docker。

2026-08-20 按本节命令实测：`RUNNER=docker bash fixtures/off-by-one/run-replay.sh` 退出码 0，结果行为 `REPLAY OK`；`./scripts/up.sh` 之后 `GET /api/v1/health` 含 `"status":"UP"`，`GET /api/runs` 为 `{"items":[],"nextCursor":null}`，`GET /runs` 为 HTTP 200。这是校准，不是 Agent 成绩。

### 路径 A 不证明什么

- 不证明模型能生成测试；
- 不证明某条真实 GitHub Issue 被复现；
- 不把校准成功算进 Agent 复现率。

## 路径 B：带凭据的 Issue2Test

需要本机 JDK 21、Docker，以及模型凭据。Worker 跑在**宿主 JVM** 上，连路径 A 已经拉起的 PostgreSQL（`127.0.0.1:5432`），用宿主 Docker 跑沙箱。不新起第二个数据库，否则控制台看不到这次 Run。

创建 Run 仍走 REST，前端没有写入口。

### 1. 自己把凭据放进当前 shell

脚本**不**读 `.env`。若凭据写在该文件里，由读者自己导入：

```bash
set -a
source .env
set +a
```

然后：

```bash
export PATCHATLAS_WORKER_WORKSPACE_ROOT="$PWD/var/worker-workspaces"
mkdir -p "$PATCHATLAS_WORKER_WORKSPACE_ROOT"
./scripts/worker.sh
```

必需环境变量：`OPENAI_API_KEY`、`PATCHATLAS_OPENAI_MODEL`、`PATCHATLAS_WORKER_WORKSPACE_ROOT`。缺任一则打印 `missing:` 列表并以退出码 2 结束，不回落 FAKE，也不把 key 写进日志。`./scripts/up.sh --worker` 仍然拒绝启动 Worker。

Worker 进程占用 `127.0.0.1:8081`（只为不和控制台的 8080 抢端口）。看 Run 请用控制台 http://127.0.0.1:8080/runs，不要用 8081。

### 2. 用公开案例创建一个 Run

下面的仓库、完整 SHA 和 Issue 正文来自公开 GitHub，不依赖本机 GitBug 数据源。这不是为了「容易成功」挑的：Issue2Test 经常走不到 `VALID_REPRODUCTION`（5b 启发式臂是 1/6）。**不复现同样合格。** 不要为了绿而换案例或重跑。

```bash
curl -sS -X POST http://127.0.0.1:8080/api/runs \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: host-worker-salespoint-412' \
  -d @- <<'EOF'
{
  "mode": "HISTORICAL",
  "repositoryUrl": "https://github.com/st-tu-dresden/salespoint.git",
  "license": "Apache-2.0",
  "issueUrl": "https://github.com/st-tu-dresden/salespoint/issues/412",
  "issueTitle": "Make it clear how to persist updated AccountancyEntrys",
  "issueBody": "In Accountancy, the add(…) method currently has two purposes: 1) adding a new entry and 2) updating/saving an existing entry. However, the latter purpose isn't clear to Salespoint framework users, neither from the method name (i.e., something like save(…) would be better) nor from the javadoc. The AccountancyEntryRepository, which has a save(…) method, is package private.",
  "buggyRevision": "e9c2fe5efee8ad76bf73738f46f911c18eb078b8",
  "fixedRevision": "85a764f892aaca4cfcb6749e55f3131a23cc8f66",
  "modulePath": "",
  "javaVersion": "17",
  "networkMode": "ONLINE"
}
EOF
```

期望：HTTP 202，JSON 含 `runId` 与 `QUEUED`。然后打开 http://127.0.0.1:8080/runs/{runId}，等到 `COMPLETED` 或 `FAILED`。

该看什么（复现与否都看这些）：

- 终态：`state`、`result.verdict` 或 `failureStage` / `failureCategory` / `failureSummary`
- 定位区块：来源、步骤、预算事件、截断；启发式臂工具调用为「—」
- Attempt 明细：sandbox、exit、耗时、超时
- 若生成被拒绝：拒绝原因分布，而不是「再跑一次直到成功」

路径 B 的成功标准是：**产生了一次终态 Run，控制台能看见它和定位轨迹。** 不是 `VALID_REPRODUCTION`。

2026-08-20 按本节命令实测：`POST /api/runs` 返回 202，`runId=040e908e-1f7b-4b27-8e15-6b1d0653e3f5`。终态 `FAILED`，`GENERATION` / `GENERATION_EXHAUSTED`，「generation attempts exhausted」，生成 3 次。定位来源 `HEURISTIC`，轨迹含 `SELECTION` 与 `EXCLUSION`，`toolCallCount` 为空（界面为「—」）。没有 Attempt。不重跑、不换案例。

### 路径 B 不证明什么

- 不把某次终态当成模型排名；
- 不把路径 A 的 `REPLAY OK` 加进这里的分母。

干净目录验证见 [`host-worker-verification.md`](host-worker-verification.md)。

停止控制台：

```bash
./scripts/down.sh
```
