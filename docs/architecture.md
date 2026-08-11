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

### PR 影响分析

代码定位与影响分析首先服务于候选测试上下文选择。未来作为独立流程时，将输出改动方法、直接调用方、相关入口、测试关联以及分级证据。

## 总体结构

```text
Vue 控制台
    ↓
Spring Boot 模块化单体
    ├── repository：仓库获取、Revision 与 Diff
    ├── sandbox：受限 Maven/JUnit 执行
    ├── replay：跨版本编排与失败分类
    ├── analysis：代码定位与影响证据
    ├── agent：候选测试生成
    └── report：证据报告与 Benchmark
```

当前代码已经实现 `repository` 和 `sandbox` 两个模块。其余模块会在出现真实调用链时建立，不提前创建空接口或微服务。

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

正式执行优先使用 `OFFLINE + --network=none`。包含 SNAPSHOT 等无法离线依赖的案例必须显式选择 `ONLINE`，并在执行结果中记录。

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
- 指标至少包含定位率、测试编译率、Buggy 有效断言失败率、Fixed 通过率、完整复现率、耗时与模型成本。

## 当前实现状态

已实现：

- Spring Boot 与 Vue 基础纵向阶段；
- 公开 GitHub 仓库和完整 Commit SHA 校验；
- Docker Maven Runner、资源限制、超时清理与缓存复用；
- 受控校准案例和一个真实 Spring 历史案例。

尚未实现：

- Surefire XML 失败分类；
- Buggy/Fixed 自动差分编排；
- 候选测试生成与 Patch 校验；
- 持久化任务、执行时间线与完整 Benchmark 看板。
