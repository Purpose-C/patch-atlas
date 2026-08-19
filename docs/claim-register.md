# 声明对照表

对外物料里的能力声明必须落在 A（测试）、B（数字+分母+证据文件）或 C（显式未测量）之一。本表覆盖 [`README.md`](../README.md)、[`architecture-diagram.md`](architecture-diagram.md)、[`demo-script.md`](demo-script.md)。落不进三格的句子已删或改写。

| ID | 声明 | 出现位置 | 格 | 证据 |
| --- | --- | --- | --- | --- |
| A01 | 只读 Vue 控制台可列出并查看 Run | README, 架构图, 演示脚本 | A | `frontend/src/views/RunsListView.test.ts`；`RunDetailView.test.ts` |
| A02 | `GET /api/v1/health` 返回平台状态 | README, 演示脚本 | A | `SystemStatusControllerTest` |
| A03 | `POST/GET /api/runs` 异步创建与 keyset 列表 | README | A | `RunControllerTest` |
| A04 | 生成器编译期拿不到 Oracle Data | README, 架构图 | A | `CaseManifestTest`；`BenchmarkOracleBoundaryTest` |
| A05 | Candidate Test Patch 经 Patch Gate 硬校验 | README, 架构图 | A | `PatchGateTest` |
| A06 | 沙箱只跑强类型 Maven 命令 | README, 架构图 | A | `MavenTestCommandTest`；`DockerSandboxRunnerTest` |
| A07 | Historical / Live Replay 机械裁决 | README, 架构图 | A | `HistoricalReplayEngineDockerTest`；`ReplayVerdictMachineTest`；`ExecutionClassifierTest` |
| A08 | 三种评测定位来源 `HEURISTIC` / `TEXT_TOOLS` / `GRAPH_TOOLS` | README, 架构图 | A | `ContextOrigin`；`LocatingCoordinator`；`ArchitectureDiagramMappingTest` |
| A09 | 图发现工具在代码中提供 `find` 与 `expand` | 架构图 | A | `GraphDiscoveryTools`；`GraphDiscoveryToolsTest` |
| A10 | `./scripts/up.sh` 拉起 PostgreSQL + API + Vue，不读 `.env` | README, 演示脚本 | A | `DeliveryUpScriptTest` |
| A11 | `--worker` 在缺凭据时退出并列出 missing 变量 | README, 演示脚本 | A | `DeliveryUpScriptTest.workerFlagListsMissingCredentialsAndDoesNotStartCompose` |
| B01 | 5b 三臂 `VALID_REPRODUCTION` 为 1/6、0/6、0/6 | README, 演示脚本, 导读 | B | [`benchmark-cases/batch5b-three-arm/evidence-report.md`](../benchmark-cases/batch5b-three-arm/evidence-report.md) 各臂 Reproduction rate |
| B02 | 5b 走到 Docker Replay 为 4/18 | README, 演示脚本, 导读 | B | 同上 Run inventory：4 条 `COMPLETED`，其余 14 条未进入 Replay |
| B03 | Spring 案例研究 0/3，三次全 `GENERATION_EXHAUSTED` | README, 演示脚本, 导读 | B | [`benchmark-cases/spring-case-study/evidence-report.md`](../benchmark-cases/spring-case-study/evidence-report.md) |
| B04 | `expand` 使用次数 036 / 5b / Spring 均为 0 | README, 架构图, 演示脚本, 导读 | B | `batch5-three-arm/evidence-report.md`；`batch5b-three-arm/evidence-report.md`；`spring-case-study/evidence-report.md` |
| B05 | Calibration Case 3/3 `VALID_REPRODUCTION`，这是校准不是 Agent 成绩 | README, 演示脚本 | B | [`benchmark-cases/task018/evidence-report.md`](../benchmark-cases/task018/evidence-report.md) 写明 Calibration passed 3/3 与 Agent 0/3 |
| B06 | 一条命令启动的干净目录验证：health UP、`/api/runs` 空列表 | README, 演示脚本 | B | [`docs/up-verification.md`](up-verification.md) |
| B07 | 5b 启发式臂那次真实成功是 18 次里的 1 次 | 演示脚本 | B | `batch5b-three-arm/evidence-report.md` Run `6d7dd641-b730-45d7-890c-3fcdd3559f42` |
| C01 | 所用模型对 GitBug-Java 案例的训练数据污染边界 | README | C | 未测量。5b 协议写明训练数据构成与知识截止时间未公开 |
| C02 | 同一模型标识下权重长期不变 | README | C | 未测量。5b 协议写明供应商可在不改名的前提下更换权重 |

未作为能力声明（因此不进本表）的句子：技术栈版本、目录结构、许可证、单用户无认证的安全提示、一条命令**不**启动 Worker。这些是限制或事实，不是卖点。

明确未声称：图引导定位已经成立、臂间谁更好、Calibration 3/3 等于 Agent 复现率、只展示成功路径。
