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

- Spring Boot 状态接口与 Vue 状态页；
- 公开 GitHub HTTPS 仓库获取与固定 Commit 校验；
- 仓库 URL、目标目录和 Revision 输入边界校验；
- 强类型 Maven 测试与依赖预热命令；
- Docker 沙箱、超时清理、有界日志与 Maven 缓存复用；
- 受控 off-by-one 校准案例；
- spring-cloud-openfeign #1326 真实历史案例与缓存数据。

目前尚未实现候选测试生成、Surefire 失败分类、跨 Revision 自动编排、持久化任务与完整 Benchmark。README 只陈述已经存在且能够验证的能力。

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
├── docs/                产品架构与领域模型
├── graphify-out/        可交互代码知识图与结构报告
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

Vite 开发服务器会把 `/api` 请求代理到 Spring Boot。

## 验证命令

```bash
# 默认离线测试，不访问 GitHub、不要求 Docker
./mvnw test

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
- [领域模型](docs/domain-model.md)
- [Benchmark 方法与指标](benchmark-cases/README.md)
- [spring-cloud-openfeign #1326 案例](benchmark-cases/spring-cloud-openfeign-1326.md)
- [代码知识图报告](graphify-out/GRAPH_REPORT.md)
- [交互式代码知识图](graphify-out/graph.html)

知识图只索引公开产品代码，并提交最终报告、交互页面和机器可读 JSON。生成缓存、查询记忆和本地路径清单不会进入仓库。

## 安全边界

Issue、源码、构建日志和模型输出均视为不可信输入。当前沙箱不挂载主机 Home、Docker Socket 或 API Key，并限制 CPU、内存、PID 与执行时间。

V1 是单用户、自托管工具。联网容器仍使用 Docker bridge，共享 Maven 缓存也存在依赖投毒风险，因此当前实现不具备公开多租户安全边界。
