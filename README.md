# PatchAtlas

[![CI](https://github.com/Purpose-C/patch-atlas/actions/workflows/ci.yml/badge.svg)](https://github.com/Purpose-C/patch-atlas/actions/workflows/ci.yml)

PatchAtlas 把公开 Java Issue 转成候选回归测试，再在隔离环境里对 Buggy / Fixed Revision 执行同一次测试，用执行事实判断是不是真的复现了缺陷。模型回答不是结论。

对外能力声明见 [声明对照表](docs/claim-register.md)。每一条都能指到测试、带分母的实测，或显式标成未测量。

## 评测数字（带分母）

这些是已经入库的观测，不是演示里重跑出来的。

| 观测 | 值 | 证据 |
| --- | --- | --- |
| 5b 三臂 `VALID_REPRODUCTION` | HEURISTIC **1/6**，TEXT_TOOLS **0/6**，GRAPH_TOOLS **0/6** | [5b 报告](benchmark-cases/batch5b-three-arm/evidence-report.md) |
| 5b 走到 Docker Replay | **4/18** | 同上 Run inventory |
| Spring 案例研究 | **0/3**，三次全 `GENERATION_EXHAUSTED` | [Spring 报告](benchmark-cases/spring-case-study/evidence-report.md) |
| `expand` 使用次数（036 / 5b / Spring） | **0 / 0 / 0** | 三份证据报告 |
| Calibration Case | **3/3** `VALID_REPRODUCTION` | [task018 报告](benchmark-cases/task018/evidence-report.md) |

Calibration 的 3/3 用的是已知触发测试，用来校准 Replay Engine，**不是** Agent 生成成绩。同份 task018 报告里 Agent 为 **0/3**。

5b 启发式臂有一次 `VALID_REPRODUCTION`。它是 **18 次里的 1 次**，不是挑选后重跑。n=6 与 n=1 都不支持臂间比较。`expand` 三次都是 0：工具在代码里存在，评测里模型没有调用它。这不能当成图引导定位已经成立。

未测量：所用模型对 GitBug-Java 的训练数据污染边界；同一模型名下权重是否长期不变。

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

生成器只能读取 Issue 与 Buggy Revision。Fixed Revision、人类修复差异和已知触发测试是 Oracle Data，进不了生成上下文。

真正要看的是方法：预言机隔离、预注册判据、不适用不写成 0、定位覆盖率 / 复现率 / 成本不合成一个分数、失败留在分母里。

## 一条命令启动

```bash
./scripts/up.sh
```

这会拉起 PostgreSQL、API 和 Vue 只读控制台：http://127.0.0.1:8080/runs 。**不**启动 Issue2Test Worker，**不**调用模型，**不**读取 `.env`。新库是空的，上面的评测 Run 不会出现在这个列表里。说明、限制和干净目录验证见 [docs/up.md](docs/up.md)。

缺 Worker 凭据时：

```bash
./scripts/up.sh --worker
```

会退出并列出 `missing:` 的变量，不会改成 FAKE 假装成功。

开发时仍可用 `./mvnw spring-boot:run` 与 `frontend` 下的 Vite；那不是一条命令路径。

## 怎么读报告

- [5b 导读](benchmark-cases/batch5b-three-arm/reading-guide.md) → 原文 [`evidence-report.md`](benchmark-cases/batch5b-three-arm/evidence-report.md)
- [Spring 案例导读](benchmark-cases/spring-case-study/reading-guide.md) → 原文 [`evidence-report.md`](benchmark-cases/spring-case-study/evidence-report.md)
- 架构图（每个图元都有类）：[docs/architecture-diagram.md](docs/architecture-diagram.md)
- 演示脚本：[docs/demo-script.md](docs/demo-script.md)
- 演示录制：[docs/demo-recording.md](docs/demo-recording.md)（Tag `demo`）

## 技术栈

Java 21、Spring Boot 4.1、Maven Wrapper、JGit、JUnit 5、Docker、Vue 3、GitHub Actions。

## 验证命令

```bash
./mvnw test
./mvnw test -Dgroups=database
./mvnw test -Dgroups=network
./mvnw test -Dgroups=docker
cd frontend && npm ci && npm run typecheck && npm test && npm run build
```

受控差分校准：`RUNNER=docker bash fixtures/off-by-one/run-replay.sh`

## 文档

- [产品愿景](docs/VISION.md)
- [系统架构](docs/architecture.md)
- [领域语言](docs/domain-language.md)
- [ADR](docs/adr/README.md)
- [Benchmark 方法](benchmark-cases/README.md)

## 安全边界

Issue、源码、构建日志和模型输出都是不可信输入。当前沙箱不挂载主机 Home、Docker Socket 或 API Key。单用户自托管，默认无认证，不要直接暴露到公网。API Key 只经后端环境变量注入，不进前端。不具备公开多租户安全边界。

## License

本项目代码以 [Apache License 2.0](LICENSE) 发布。第三方仓库源码只在本地工作区使用，不纳入版本控制。
