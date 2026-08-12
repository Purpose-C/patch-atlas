# ADR-002:在 Buggy 侧预验证后提交唯一候选

## 状态

已接受

## 日期

2026-08-12

## 背景

把模型输出限制为 Candidate Test Patch，并由 Patch Gate 保护 workspace；又规定每个 Verification Run 最多持久化一个候选，并在候选提交时原子进入 `REPLAYING`。需要允许模型根据 Buggy 侧失败事实最多修正三轮。

如果第一份语法合法的模型输出立刻成为已提交候选，后续修正就会要求覆盖候选行、让状态从 `REPLAYING` 倒退，或为一次 Run 保存多个候选。这三种做法都会破坏 的唯一候选与恢复不变量。

另一方面，如果把生成阶段的执行直接当作正式 Replay 证据，崩溃恢复、workspace 生命周期和证据持久化将耦合在一起。Historical Verification 也更容易让 Fixed 侧事实越过 Oracle 边界进入修正循环。

## 决策

模型每次产生的是未提交的 **Candidate Draft**，不是 Candidate Test Patch。独立的生成编排器在新的 Buggy workspace 上完成 Patch Gate 和两次 Target Test 执行。只有两次都形成稳定 `TARGET_ASSERTION_FAILURE` 的草稿，才会被选定为该 Run 唯一的 **Candidate Test Patch**，并与 `GENERATING → REPLAYING` 在同一事务提交。

生成阶段遵循以下边界：

- 修正最多进行三个 Run 级 Generation Attempt，即首轮加最多两轮修正；
- Generation Feedback 只来自 Buggy 侧的结构化事实，不含 Fixed Revision、Fixed 结果、人类 patch 或已知触发测试；
- 未通过的 Candidate Draft、原始 prompt、模型原始响应、完整日志和逐轮聊天记录不持久化；
- 每次外部模型调用前先持久化预占全局额度，崩溃也不返还额度；
- 崩溃恢复后不重建已丢失的草稿或反馈，而是用剩余额度开启新的无反馈修正链；
- 正式 Replay 使用新的 workspace 重新执行 Buggy，并在 Historical Verification 中按既有短路规则决定是否执行 Fixed；
- 生成预验证事实不写入 `replay_attempt`，也不能作为 Replay Verdict 的证据。

## 考虑过的替代方案

### 第一份通过 Patch Gate 的输出立即提交

优点是控制流简单，也不重复执行 Buggy。缺点是编译失败、目标测试缺失或稳定通过之后无法在不覆盖候选、不倒退状态的情况下修正。它把“补丁策略合法”错误地等同于“具备复现价值”。

### 持久化每轮 Candidate Draft 与反馈

它可以完整恢复修正对话并精确审计每轮模型行为，但需要第四张业务表或把不可信的大文本塞进 JSONB；同时扩大敏感数据、prompt injection 内容和失败 patch 的存储面。V1 的三张表与当前任务范围不支持这项复杂度。

### 用生成预验证代替正式 Buggy Replay

它可以省去两次 Maven 执行，但会让候选选择事实和最终裁决证据共享临时 workspace，增加崩溃恢复和持久化耦合；也会让 Live/Historical 的正式 Replay 不再遵循同一入口。该优化不足以抵消证据语义变模糊的成本。

### 崩溃后重置三轮额度

它能从首轮完整重建修正链，但无法给单个 Run 提供严格的调用与成本上限。供应商可能已经处理了崩溃前请求，因此重置会隐藏重复调用和重复计费。

## 后果

- `candidate_test_patch` 继续保持每个 Run 唯一，`GENERATING` 与 `REPLAYING` 状态不倒退；
- 模型 adapter 保持浅能力边界：只完成一次结构化调用，不接触 workspace、Replay、数据库或 Oracle Data；
- Buggy 侧会在候选提交前执行两次，并在正式 Replay 中再次执行两次。系统明确接受这项时间成本，以换取候选质量门槛和独立的最终证据；
- 进程崩溃可能丢失上一轮修正上下文，也可能少记已经收到但尚未提交的 token usage；系统不声称逐轮对话可恢复或计费绝对精确；
- 恢复不会突破三个逻辑模型调用额度，但崩溃和传输重试仍可能造成供应商侧重复处理；
- 失败草稿不入库，降低了不可信模型文本、完整源码片段和潜在秘密的持久化风险；
- 若未来确实需要逐轮可审计恢复，必须以新的任务重新评估数据模型、保留策略、脱敏和第四张表，而不能悄悄扩大 `verification_run` JSONB。
