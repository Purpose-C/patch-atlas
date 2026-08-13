# PatchAtlas

[![CI](https://github.com/Purpose-C/patch-atlas/actions/workflows/ci.yml/badge.svg)](https://github.com/Purpose-C/patch-atlas/actions/workflows/ci.yml)

PatchAtlas 是面向 Java 开源仓库的 Issue-to-Test 验证平台。它根据真实 Issue 生成候选回归测试，并通过隔离环境中的跨版本执行，判断测试是否真正复现缺陷，而不是编译错误、环境故障或偶发失败。

## 核心思路

```text
公开 GitHub 仓库 + Issue + Buggy Revision
                    ↓
             定位相关代码与测试
                    ↓
          生成 Candidate Test Patch
                    ↓
              Replay Engine
             ├─ Buggy Revision：目标断言失败
             └─ Fixed Revision：同一测试通过
                    ↓
             VALID_REPRODUCTION
```

生成器只能读取 Issue 与 Buggy Revision。Fixed Revision、人类修复差异和已知触发测试属于验证数据，不会进入生成上下文。

## 项目亮点

- **真实开源场景**：输入来自公开 Java 仓库、Issue 和固定 Commit，不依赖虚构业务数据。
- **证据驱动**：JGit、Maven、JUnit 与 Docker 产生可复核事实，模型结论不能替代真实执行。
- **假失败识别**：目标是区分有效断言失败、编译失败、环境失败、超时和 flaky 噪音。
- **受限执行**：只允许强类型 Maven 命令模板；容器以非 root、无 capability、资源受限方式运行。
- **诚实评测**：历史 Bug Benchmark 同时保留成功与失败案例，并隔离人类修复等预言机数据。

## 已实现能力

- Spring Boot 健康检查与 Vue 运行控制台（列表/详情，只读）；
- 公开 GitHub HTTPS 仓库获取与固定 Commit 校验；
- 仓库 URL、目标目录和 Revision 输入边界校验；
- 强类型 Maven 测试与依赖预热命令；
- Docker 沙箱、超时清理、有界日志与 Maven 缓存复用；
- Surefire XML 解析、七类执行结果与 flaky 归约；
- Live/Historical Replay、Candidate Patch Gate 与 PostgreSQL Run 恢复；
- Fake/真实模型 adapter，以及最多三轮 Buggy-only 生成修正；
- 可恢复的 Java/网络执行策略、生产 Docker/Replay adapter 与 Worker 完整装配；
- `POST/GET /api/runs` 幂等创建、keyset 列表与可审计详情；
- Run 详情区分四种 Recorded Usage Status，并在有价格配置时展示已记录用量的估算费用；
- Actuator 指标、沙箱执行 Timer 与 Logstash 结构化领域日志；
- 受控 off-by-one 校准案例；
- spring-cloud-openfeign #1326 真实历史案例与缓存数据。

目前尚未实现前端提交表单、SSE/指标看板、真实 Agent Benchmark 与交付包装。README 只陈述已经存在且能够验证的能力。估算费用不是账单。

**安全提示**：本实例为单用户自托管，默认无认证。不要直接暴露到公网。API Key 仅经后端环境变量注入，永不进入前端。

Worker 默认将 Maven 缓存放在 `<workspace-root>/.patch-atlas-cache/maven`。单次 Run 清理只删除其独立工作区，不删除共享缓存；若部署时重建整个 workspace root，缓存也会随之失效。

## 技术栈

- Java 21、Spring Boot 4.1、Maven Wrapper；
- JGit、JUnit 5、Mockito；
- Docker；
- Vue 3、TypeScript、Vite、Vitest；
- GitHub Actions。

## 项目结构

```text
patch-atlas/
├── src/                 Spring Boot 产品代码与测试
├── frontend/            Vue 控制台
├── fixtures/            可控的缺陷校准仓库
├── benchmark-cases/     真实历史案例元数据与执行证据
├── docs/                产品架构与工程决策
├── .github/workflows/   持续集成
├── pom.xml
└── README.md
```

## 本地运行

要求：JDK 21、Node.js 22+。Docker 集成验证还需要 Docker Desktop。

```bash
./mvnw test
./mvnw spring-boot:run
```

另开终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

Vite 开发服务器会把 `/api` 请求代理到 Spring Boot。控制台路由为 `/runs` 与 `/runs/:runId`。

### 持久化与 Worker（可选）

```bash
export SPRING_PROFILES_ACTIVE=persistence
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/patchatlas
export SPRING_DATASOURCE_USERNAME=...
export SPRING_DATASOURCE_PASSWORD=...
# Worker（执行队列；需要 workspace 根目录与 Docker）
export PATCHATLAS_WORKER_ENABLED=true   # 或 application 配置 patchatlas.worker.enabled=true
# patchatlas.worker.workspace-root=/var/patchatlas/workspaces
```

可选费用估算（全部给出才启用；缺省表示费用不可用，不是 `$0`）：

```bash
export PATCHATLAS_PRICING_PROVIDER=openai
export PATCHATLAS_PRICING_MODEL=gpt-4.1-mini
export PATCHATLAS_PRICING_INPUT_USD_PER_MILLION_TOKENS=0.40
export PATCHATLAS_PRICING_OUTPUT_USD_PER_MILLION_TOKENS=1.60
export PATCHATLAS_PRICING_EFFECTIVE_DATE=2026-08-13
export PATCHATLAS_PRICING_SOURCE=operator-config
```

默认 console 为 Logstash JSON。领域事件只带白名单字段，例如：

```json
{"@timestamp":"2026-08-13T11:34:06.544444+09:00","message":"run claimed","logger_name":"io.github.patchatlas.observability.RunEvents","level":"INFO","run_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","event":"run.claimed","mode":"LIVE","recovery_count":0}
```

`run_id` 是唯一 Run Correlation ID。日志不包含 Issue 正文、Patch、完整沙箱日志、Idempotency-Key、异常 message 或 API Key。

启用 persistence 后可读取 Run 聚合指标（tag 仅为封闭枚举）：

```http
GET /actuator/metrics/patchatlas.run.completed?tag=mode:live&tag=verdict:valid_reproduction
GET /actuator/metrics/patchatlas.run.failed
GET /actuator/metrics/patchatlas.generation.attempts?tag=provider:openai
GET /actuator/metrics/patchatlas.model.usage.records?tag=provider:openai
GET /actuator/metrics/patchatlas.model.usage.runs?tag=provider:openai&tag=status:recorded_for_all_attempts
GET /actuator/metrics/patchatlas.model.tokens?tag=provider:openai&tag=type:total
GET /actuator/metrics/patchatlas.sandbox.execution.duration?tag=command_type:test&tag=timed_out:false
```

完整定价配置存在时还有 `patchatlas.model.cost.estimated`。沙箱 Timer 随进程重启归零，恢复重跑按实际执行再计一次；终态 Run 计数以 PostgreSQL 为准，不因恢复或重复 terminal 调用翻倍。

创建 Run（异步，不在 HTTP 中等待模型/Docker）：

```http
POST /api/runs
Idempotency-Key: demo-1
Content-Type: application/json
```

## 验证命令

```bash
# 默认离线测试，不访问 GitHub、不要求 Docker
./mvnw test

# PostgreSQL / Flyway / 幂等与分页
./mvnw test -Dgroups=database

# 真实公开仓库获取
./mvnw test -Dgroups=network

# Docker 沙箱集成测试
./mvnw test -Dgroups=docker

# 前端
cd frontend
npm run typecheck
npm test
npm run build
```

受控差分校准：

```bash
RUNNER=docker bash fixtures/off-by-one/run-replay.sh
```

## 文档

- [系统架构与设计决策](docs/architecture.md)
- [ADR-003：Run 聚合指标与执行遥测](docs/adr/ADR-003-persisted-run-metrics-and-execution-telemetry.md)
- [领域语言](docs/domain-language.md)
- [Benchmark 方法与指标](benchmark-cases/README.md)
- [spring-cloud-openfeign #1326 案例](benchmark-cases/spring-cloud-openfeign-1326.md)

## 安全边界

Issue、源码、构建日志和模型输出均视为不可信输入。当前沙箱不挂载主机 Home、Docker Socket 或 API Key，并限制 CPU、内存、PID 与执行时间。

V1 是单用户、自托管工具。联网容器仍使用 Docker bridge，共享 Maven 缓存也存在依赖投毒风险，因此当前实现不具备公开多租户安全边界。

## License

本项目代码以 [Apache License 2.0](LICENSE) 发布。第三方仓库源码仅在本地工作区使用，不纳入版本控制。
