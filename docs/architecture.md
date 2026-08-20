# 系统架构与设计决策

## 产品定位

PatchAtlas 是面向 Java 开源仓库的 Issue-to-Test 验证平台。它不把模型回答当成结论，而是用 Git、编译、测试和隔离执行判断候选测试是否真正复现缺陷。

公开开源项目提供真实 Issue、代码、Commit、人工修复与测试结果，因此系统不需要虚构企业工单或生产故障。

## 使用场景

### Historical Verification

输入公开仓库、Issue、Buggy Revision 与 Fixed Revision。生成器只能读取 Issue 和 Buggy Revision；Fixed Revision 仅由验证器使用。

```text
解析 Issue
→ 定位相关代码与现有测试
→ 生成候选测试
→ 在 Buggy Revision 执行
→ 在 Fixed Revision 执行同一测试
→ 分类失败并形成证据报告
```

只有 Buggy 侧出现目标断言失败、Fixed 侧通过，且排除编译、环境、超时与 flaky 后，系统才能输出 `VALID_REPRODUCTION`。

### Live Issue

尚未修复的 Issue 没有 Fixed Revision。候选测试在当前版本产生目标断言失败时，系统只能输出 `REPRODUCTION_CANDIDATE`，等待修复版本完成最终验证。

### PR 影响分析（🔴 未实现）

设计意图是：Issue 定位与影响分析共用同一张代码关系图，前者服务于候选测试的上下文选择，后者作为独立流程输出改动方法、直接调用方、相关入口、测试关联以及分级证据。

🔴 **当前代码里没有 `impact` 包，`Impact Report` 的表与端点都不存在。** 共用的代码关系图（`analysis` 包）已实现并用于 Issue 定位，影响分析出口未实现。它在剩余范围里排最后。

## 总体结构

主链与 Oracle 边界的可核对图见 [`architecture-diagram.md`](architecture-diagram.md)。下面是包级摘要。

```text
Vue 控制台
    ↓
Spring Boot 模块化单体
    ├── repository：仓库获取、Revision 与 Diff
    ├── sandbox：受限 Maven/JUnit 执行
    ├── replay：执行事实、稳定性归约与 Live/Historical 裁决
    ├── agent：模型 adapter、Candidate Draft 解析与 Patch Gate
    ├── run：Verification Run 编排、租约恢复与 PostgreSQL 持久化
    ├── observability：Run 聚合指标、费用估算与结构化领域日志
    ├── benchmark：Frozen Cohort 选样、Generator Context 构造、运行入口与证据导出
    └── shared：当前状态接口等薄入口
```

依赖方向保持为 `run → agent / analysis / replay / repository / sandbox`；`observability` 只读 Run 事实并观察沙箱执行，不改写终态。`RunEvents` 和 `RunCorrelation` 属于 `run` 包（领域事件是 run 的横切关注点），`observability` 通过 `MicrometerSandboxExecutionObserver` 直接调用 `RunEvents`——这是 `observability → run` 的单向依赖。`replay` 只依赖 `sandbox` 的 `SandboxExecutionObserver` seam，不导入 `observability`，因此 `observability ↔ replay` 不存在。`agent` 不依赖 `run`，也无法取得 Fixed Revision 等 Oracle Data。`analysis` 不得依赖 `benchmark`，公开签名不得出现 Oracle 类型。尚未出现真实调用链的 `report` 不提前创建空接口或模块。

## 设计决策

### 模块化单体

初期使用一个 Spring Boot 服务和一个 Vue 应用。项目的主要风险是复现验证是否可信，而不是服务间通信，因此不提前拆分微服务。

### 事实与模型判断分离

JGit、Maven、JUnit、Docker 与静态分析负责产生事实；模型负责理解 Issue、定位候选代码、生成测试和解释证据。模型不能执行任意 Shell，也不能直接修改生产源码。

### 预言机隔离

Generator Context 只能包含 Issue、Buggy Revision、相关源码和现有测试风格。Fixed Revision、人工修复差异和已知触发测试属于 Oracle Data，在类型和调用链上都必须对生成器不可达。

### 不确定影响分级

Spring 项目包含接口多实现、AOP、反射、事件与条件 Bean，因此系统不宣称拥有完整调用图：

- `CONFIRMED`：AST、符号解析或测试直接证明；
- `INFERRED`：由依赖注入、事件或配置语义推断；
- `POSSIBLE`：多实现、反射或动态行为导致的不确定影响。

每条结论应带文件、行号、证据来源、限制说明和相关测试。

### 固定沙箱命令

`sandbox` 模块只接受 `MavenTestCommand` 与 `MavenDependencyWarmupCommand` 两种强类型模板。宿主通过 `ProcessBuilder(List<String>)` 调用 Docker CLI，不经过 shell，也不接受模型或用户传入完整命令。

执行结果只公开 Maven 白名单命令、退出码、耗时、网络模式、日志摘要和资源限制，不暴露 Docker CLI 或宿主路径。

### 精确依赖预热

Maven 本地仓库使用 gitignored 缓存目录跨容器复用。预热通过联网执行精确目标测试完成，而不是对大型多模块项目运行全仓 `dependency:go-offline`；后者既可能遗漏 Surefire 动态 provider，也可能下载大量无关插件。

生产默认缓存位于 `<workspace-root>/.patch-atlas-cache/maven`。它与每次 Run 创建的 `w<run-id>...` 工作区是同级目录，因此关闭单次 workspace session 不会删除缓存；只有部署方清理或替换整个 `workspace-root` 时缓存才会失效。缓存放在可信根内是为了保持 Docker bind mount 边界可校验，同时不挂载用户级 `~/.m2`。

正式执行优先使用 `OFFLINE + --network=none`。包含 SNAPSHOT 等无法离线依赖的案例必须显式选择 `ONLINE`，并在执行结果中记录。

Java 版本与网络模式属于 Verification Run 的不可变执行策略。Java 仅允许 `8/11/17/21`，由强类型 Maven 命令映射到项目控制的 Maven 镜像；恢复执行时从数据库重新加载策略，不读取进程默认值。生产编排器对 OFFLINE 单侧先在尚未应用 Candidate Test Patch 的 revision 上做一次 ONLINE 精确目标预热，再应用候选补丁并产生两次 OFFLINE 证据；模型生成的测试代码不会在联网容器中执行。`SideReplayRunner` 只负责证据双跑，不隐式改变网络模式；ONLINE 单侧直接双跑。

### Run 编排边界

`Issue2TestWorker` 只负责领取 Run 和按状态分发。`CandidateGenerationCoordinator` 拥有生成、预热、Gate 与 Buggy 预验证；`FormalReplayCoordinator` 拥有工作区重建、预热、再次 Gate 与正式 Replay。生成与 Formal Replay 的租约、轮次和终态写入分别经 `GenerationRunSession` 与 `ReplayRunSession`。两条路径共用生产 `DependencyWarmupRunner` 与 `SideReplayRunner`。

启用 Worker 时，项目默认装配 `DockerSandboxRunner` 与基于 Replay Engine 的 `RunReplayer`；生成与 Formal Replay 的接线经 `Issue2TestRuntime`，与正式 Harness 共用。部署可用自定义 sandbox / fetcher adapter 覆盖默认 fallback。workspace 根目录缺失或无效时启动立即失败。

### Run 入口与读取边界

Verification Run 的 REST 入口是异步命令：调用方提供完整、有界且显式包含执行策略的不可变输入，服务端落库后返回 `202 Accepted`，不在 HTTP 请求中等待模型、Git 或 Docker。创建请求使用 PostgreSQL 持久化的 Idempotency-Key 与规范化请求指纹抵抗网络重试；Controller 不直接调用 Worker，也不直接访问 SQL。

Worker 启用时由单进程串行调度入口持续领取 Run；启动恢复与常驻消费复用同一 drain seam，数据库 claim、lease 和 fence 仍是跨进程并发与崩溃恢复的安全边界。关闭开始后不再领取新 Run。当前不引入 MQ、事件流或无限并发。

列表与详情使用独立 REST 读取投影，不序列化持久化对象。列表采用 `created_at + run_id` 的稳定 keyset 分页；详情可以展示 Issue、执行策略、模型用量、Candidate Test Patch 与正式 Replay Attempt，但不返回 Source Snapshots、lease、宿主路径、原始模型响应或生成预验证。Run 的 `FAILED` 是可查询的领域终态，不映射成 GET 请求的 HTTP 失败。

Vue 控制台使用同源 `/api`、稳定的 `/runs` 与 `/runs/:runId` 路由，并只对非终态 Run 做有限轮询。Issue、Patch、日志和诊断均按不可信纯文本渲染。详情页 Generation 区域区分四种 Recorded Usage Status，并在有完整 Pricing Reference 时展示已记录用量的估算费用；价格缺失或 model 不匹配时显示不可用，不把未知 usage 显示成已知零。V1 不提供前端创建表单、宽泛 CORS 或认证，因此默认部署不应直接暴露到公网。

### 可观测性边界

可观测性分成两类，决策见 [ADR-003](adr/ADR-003-persisted-run-metrics-and-execution-telemetry.md)：

- **Run Aggregate Metrics** 从 PostgreSQL 已持久化的 Verification Run 只读派生。终态、Generation Attempt、Recorded Model Usage 与 token 以数据库为权威，可跨重启重建；同一终态 Run 只贡献一次。
- **Execution Telemetry** 记录本进程真实发生的沙箱执行。恢复后重跑按实际活动再次计时，不冒充持久化账本或 Evidence Report。

指标通过 Actuator `/actuator/metrics` 读取。tag 仅使用封闭枚举；`runId`、model、Issue、仓库和异常文本不得成为维度。完整 Pricing Reference 才会注册费用 Gauge；金额是当前配置对已记录 usage 的估算下界，不是账单，也不持久化 Price Snapshot。

默认 console 使用 Spring Boot 内建 Logstash JSON。领域事件经 `RunEvents` 写出固定 `event` 与白名单字段；`run_id` 只放在 MDC，避免与 key-value 重复。日志不回显 Issue 正文、Patch、完整沙箱日志、Idempotency-Key、异常 message 或 API Key。进程崩溃可以漏记或重复日志，不能当作恰好一次账本。

观测失败不得覆盖 `SandboxExecution`、改变 Replay Verdict 或把 Run 写成 `FAILED`。

## 安全模型

仓库源码、Issue、构建日志与模型输出均为不可信数据。

- 构建仅在 Docker 容器内运行；
- 容器使用非 root 用户并丢弃全部 capability；
- 启用 `no-new-privileges`；
- 限制 CPU、内存、PID、日志大小和墙钟时间；
- 不挂载主机 Home、Docker Socket 与 API Key；
- 工作区必须位于配置的可信根目录内；
- Maven 模块和测试选择器通过白名单校验；
- 超时后强制删除容器，并区分清理失败。

当前已知限制：

- 在线模式使用 Docker bridge，尚未限制 Maven 仓库域名；
- 共享 Maven 缓存提升速度，但允许恶意构建污染后续依赖；
- Docker 不是适用于公开多租户的完整安全边界；
- 静态调用关系无法完整覆盖反射与运行时代理。

## 评测原则

- 已知触发测试只用于校准 Replay Engine，不冒充模型生成结果；
- 生成器不得读取 Fixed Revision 或人工补丁；
- 案例选择标准在评测前冻结，模型不能选择自己的考题；
- Benchmark 同时报告成功与失败案例；
- 指标至少包含 Issue 定位覆盖率、测试编译率、Buggy 有效断言失败率、Fixed 通过率、完整复现率、耗时与模型成本。
- 已知缺口已关闭:`ParentRevisionValidator` 已接入 `DynamicCaseQualifier`。动态资格在双方 checkout 之后、应用触发补丁之前检查 Fixed Revision 的第一父提交是否等于 Buggy Revision。先前冻结队列未追溯补检；`spring-v1` 队列使用该闸门。选样规则见 `docs/benchmark-spring-cohort-rules.md`，对照解释见 `docs/benchmark-graph-guidance-interpretation.md`。

## 当前实现状态

已实现：

- Spring Boot 与 Vue 基础纵向能力；
- 公开 GitHub 仓库和完整 Commit SHA 校验；
- Docker Maven Runner、资源限制、超时清理与缓存复用；
- Surefire XML 解析、失败分类与稳定性归约；
- Live/Historical Replay 与机械裁决；
- Candidate Draft 生成、Patch Gate 和最多三轮 Buggy-only 修正；
- PostgreSQL Verification Run、租约 fencing 与崩溃恢复；
- 生产 Worker、可恢复 Maven 执行策略与安全依赖预热；
- Verification Run 的 REST API 与 Vue 列表/详情；
- Run 详情只读展示 `locating_trace`：定位来源、工具调用数（启发式臂为「—」而非 0）、预算事件、逐步步骤、截断标记与空态「无定位记录」。覆盖率与任何 Oracle 衍生量仍只进 Evidence Report。界面能显示 `EXPAND` 行不表示图引导定位已成立（评测中 `expand` 使用 0 次）；
- 详情列出提交给生成器的文件路径（空集为「—」）。启发式臂同样有这份列表；
- Run 聚合指标、沙箱执行遥测、结构化领域日志与估算费用；
- 受控校准案例和一个真实 Spring 历史案例；
- 冻结队列上的正式 Agent Benchmark。3 个 Calibration 案例全部 `VALID_REPRODUCTION`，3 个 Agent 案例全部未复现（`GENERATION_EXHAUSTED`）。失败集中在统一 diff 的输出契约层而非测试设计层：9 次拒绝中 6 次为 `trailing non-patch text`、3 次为 `hunk new count mismatch`。完整证据见 `benchmark-cases/task018/`。
- `LOCATING` 阶段与 `ContextOrigin` 三分（`HEURISTIC` / `TEXT_TOOLS` / `GRAPH_TOOLS`）、自控工具循环、双上限预算与 `locating_trace` 落库；
- JavaParser 代码关系图（仅源码内解析、Spring 语义边按注解名识别、三级置信度、四元组键缓存）与图工具 `find` / `expand`；
- Oracle 隔离的 Issue 定位覆盖率评估器（地面真值由 buggy..fixed 现算并排除测试源；结果只进 Evidence Report）；
- 生成契约经 tool calling 承载、`targetTest` 由补丁机械推导、`finish_reason` 入库并作为判定输入（截断与表头计数错误分属不同拒绝类别）；
- 三臂评测：036 与 5b 两批并列报告、均不作废。5b `VALID_REPRODUCTION` 为 HEURISTIC 1/6、TEXT_TOOLS 0/6、GRAPH_TOOLS 0/6，走到 Docker Replay 4/18。证据见 `benchmark-cases/batch5b-three-arm/`；
- 手挖 Spring 案例定性研究：三臂 0/3，`expand` 使用 0 次。证据见 `benchmark-cases/spring-case-study/`。🔴 **图工具在代码里存在，评测中模型从未调用 `expand`**，不得据此声称图引导定位已成立；
- 一条命令启动只读控制台（PostgreSQL + API + Vue）。命令与干净目录验证见 [`docs/up.md`](up.md)。该命令不启动 Issue2Test Worker，也不调用模型。
- 宿主 JVM 上的 Issue2Test Worker：`./scripts/worker.sh` 连 Compose 的 PostgreSQL，用宿主 Docker 跑沙箱。文档与干净目录验证见 [`host-worker.md`](host-worker.md)。`./scripts/up.sh --worker` 仍然拒绝。无凭据的 Replay 校准与带凭据的 Issue2Test 分开陈述；校准成功不是 Agent 复现率。
- 演示录制见 [`docs/demo-recording.md`](demo-recording.md)。Tag `demo` 在录制时运行的 `4b77189`；该说明提交引用该 Tag，不改定位、生成、Gate 或阈值。
- Chromium 浏览器用例覆盖 [`e2e.md`](e2e.md) 列出的空库冒烟与拦截形态（含未知不显示 0、截断可见、无定位记录）。这不是对全部界面质量的保证。
- 四个端点的契约由代码生成并被测试钉住。访问方式与 swagger-ui 暴露面见 [`openapi.md`](openapi.md)。7 条端点行为在真实 HTTP 栈上被钉住。不改变无鉴权这一限制。

本文件不再把「演示 Tag」、「宿主上跑 Worker 的文档化路径」、Playwright 浏览器用例、四个端点的生成 OpenAPI 或真实 HTTP 栈上的端点行为钉扎列为未交付项。

未实现（2026-08-20 范围定稿后仍在计划内的，按优先级）：

1. 覆盖率增量门禁；
2. Prometheus 指标暴露、token 鉴权（API 当前无鉴权）；
3. PR 影响分析出口（见上文，`impact` 包不存在）。

上表之外的能力已放弃或属于 [`VISION.md`](VISION.md) 的既有非目标，不在计划内。
