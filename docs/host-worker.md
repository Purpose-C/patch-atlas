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

Issue2Test 需要模型凭据，走另一条路：`./scripts/worker.sh` 在宿主 JVM 启动 Worker，连本页已经拉起的 PostgreSQL。不要用 `./scripts/up.sh --worker` 启动 Worker，那条命令会拒绝。缺凭据时 `worker.sh` 打印 `missing:` 并非零退出，不读 `.env`，不回落 FAKE。

停止控制台：

```bash
./scripts/down.sh
```
