# ADR-003：分离持久化 Run 指标与实际执行遥测

## 状态

已接受

## 日期

2026-08-13

## 背景

PatchAtlas 的 Verification Run 可以在 Worker 崩溃、lease 过期和进程重启后恢复。若 verdict、generation attempt 与 token 只在领域动作返回后递增进程内 Counter，数据库提交后到 Counter 递增前的崩溃会造成永久漏计；若恢复时重放事件，又可能让同一个终态 Run 重复计数。

沙箱执行具有不同语义。依赖预热、生成预验证和正式 Replay 都会实际消耗时间与资源；恢复后重新执行不是重复统计错误，而是新的执行事实。把它们强行按 Run 去重会隐藏真实资源消耗。

## 决策

PatchAtlas 将可观测性分成两类：

- **Run Aggregate Metrics** 从 PostgreSQL 已持久化的 Verification Run 事实只读派生。Verdict、Failure、Generation Attempt、Recorded Model Usage 与 Token 累计量以数据库为权威，可跨进程重启重建；同一个终态 Run 只贡献一次。
- **Execution Telemetry** 记录本进程实际发生的沙箱活动及其耗时、状态和超时。恢复后发生的重新执行再次记录，因为它确实消耗了资源；这类数据不冒充持久化账本或 Evidence Report。

两类指标都通过现有 Spring Boot Actuator/Micrometer 读取，但保持不同生命周期。Run 的稳定 `runId` 用作结构化日志的 Run Correlation ID，不作为 metric tag。不为恰好一次指标引入 outbox、消息队列、事件总线或额外持久化表。

## 考虑过的替代方案

### 所有指标都在领域动作后递增 Micrometer Counter

实现简单，但数据库事务与 MeterRegistry 之间没有原子提交。崩溃窗口必然造成漏计或恢复重放后的重复计数，无法支撑可恢复 Run 的历史累计语义。

### 所有数据都从 PostgreSQL 派生

它能重建终态和正式 Replay Attempt，却看不到未持久化到 `replay_attempt` 的依赖预热与生成预验证，也会把恢复重跑的实际资源消耗压缩成一个逻辑 Run。

### 为观测事件增加 outbox 或第四张业务表

它可以保存更完整的事件流，但会引入发布、消费、去重和保留策略，超出单用户模块化单体的基础可观测性需求。现有持久化事实足以支撑 Run 累计指标，进程 Timer 足以支撑实际执行诊断。

## 后果

- Run Aggregate Metrics 的读取会执行小型只读聚合 SQL；不同 meter 请求不保证属于同一数据库快照。
- Execution Telemetry 随进程重启归零，可能因崩溃漏失最后一条记录；这是明确限制，不影响 PostgreSQL Run 事实。
- 当前没有 Run 删除路径，数据库累计量满足 FunctionCounter 的单调前提。未来增加删除或数据保留策略前必须重新评估 meter 类型。
- 恢复语义不再含糊：逻辑终态对 Run Aggregate Metrics 去重，实际重新执行对 Execution Telemetry 再计数。
- 指标 tag 保持有限枚举；`runId`、model name、Issue、仓库、Revision、Target Test、日志和异常文本不得成为维度。
