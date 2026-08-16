# 领域语言

PatchAtlas 将 Java 仓库中的 Issue 转换为可执行、可追溯的测试证据。本词汇表只定义产品领域语言，不描述具体实现。代码、API、报告与文档统一使用这里的术语。

## Language

**Issue2Test**:
在看不到人类修复内容的前提下，根据 Issue 与 Buggy Revision 生成 Candidate Test Patch 的能力。
_Avoid_: Bug 分析、自动修复、测试生成器

**Historical Verification**:
使用已知的 Buggy Revision 与 Fixed Revision，对同一个 Candidate Test Patch 进行差分验证的模式。
_Avoid_: Bug Replay（指代整个产品时）、历史回放平台

**Live Issue**:
尚无 Fixed Revision 的真实 Issue；系统只能验证候选测试在当前缺陷版本上是否产生目标断言失败。
_Avoid_: Benchmark Case、已验证复现

**Replay Engine**:
在隔离环境中对指定 Revision 执行同一测试并产生结构化执行结果的确定性组件。
_Avoid_: Agent、Issue2Test、Docker Runner

**Verification Run**:
在一组不可变输入与声明的 Run Purpose 下，从取得待验证测试补丁开始，到 Replay Verdict 或验证流程失败为止的一次可恢复工作实例。Issue2Test Run 从生成 Candidate Test Patch 开始；Calibration Run 使用来源明确的 Known Trigger Test Patch，不能混为 Agent 生成结果。
_Avoid_: Replay Verdict、单次 Maven 执行、聊天会话

**Run Purpose**:
声明 Verification Run 的证据用途与聚合边界：普通产品运行、Replay 校准、正式 Agent Benchmark 或诊断重跑。它不改变 Replay Verdict，只决定补丁来源约束以及该 Run 能否进入正式 Benchmark 分母。
_Avoid_: Verification Mode、Replay Verdict、失败分类

**Candidate Test Patch**:
由 Issue2Test 选定并提交、只允许修改测试源码的唯一候选补丁；它已在 Buggy Revision 上通过稳定目标断言失败的预验证，但在完成正式验证前不代表成功复现。
_Avoid_: Candidate Draft、Fix Patch、生产代码补丁、已验证测试

**Known Trigger Test Patch**:
从维护者已知触发测试形成、仅供 Calibration Run 校准 Replay Engine 的测试补丁；它属于 Oracle Data，不能作为 Candidate Test Patch 或 Issue2Test 成功证据。
_Avoid_: Candidate Test Patch、Agent 生成测试、Benchmark 成功案例

**Test Patch Provenance**:
说明待验证测试补丁来自 Agent 独立生成还是维护者已知触发测试的稳定来源事实；正式 Agent Benchmark 只接受 Agent Generated，Calibration 只接受 Known Trigger。
_Avoid_: Run Purpose、模型名称、作者署名

**Candidate Draft**:
模型在一次 Generation Attempt 中产生、尚未提交的结构化候选输出；它可以因策略校验、编译或 Buggy 侧执行结果而被丢弃或用于下一轮修正。
_Avoid_: Candidate Test Patch、模型原始响应、Replay 证据

**Generator Context**:
生成器可以读取的 Issue、Buggy Revision、相关源码与现有测试风格；其中不得包含 Oracle Data。
_Avoid_: 完整案例数据、验证上下文

**影响置信度分级**:
对「某个代码位置与本次分析目标相关」这一结论的证据强度分级，取值为 `CONFIRMED`（AST、符号解析或测试直接证明）、`INFERRED`（由依赖注入、事件或配置语义推断）、`POSSIBLE`（多实现、反射或动态行为导致的不确定影响）。系统不宣称拥有完整调用图，因此每条结论都必须同时给出分级、文件、行号与证据来源。它描述证据强度，不描述模型的自信程度。
_Avoid_: 模型置信度、相关度评分、Replay Verdict

**代码关系图**:
由某个 Revision 的源码机械解析得到的代码实体与关系集合：节点为文件、类与方法，边为导入、调用、继承、依赖注入、事件发布订阅与切面织入，每条边带影响置信度分级。它是（仓库, Revision）的纯函数，不包含 Oracle Data，也不判断代码正确性。
_Avoid_: 完整调用图、AST、代码索引

**Issue 定位**:
在看不到人类修复内容的前提下，依据 Issue 文本与代码关系图，确定哪些源码进入 Generator Context 的过程。它给出相关性，不给出缺陷根因。
_Avoid_: 根因定位、缺陷检测、影响分析

**影响分析**:
依据代码关系图，确定一次代码改动波及了哪些方法、调用方与关联测试的过程；每条结论带影响置信度分级。它服务于测试选择，不判断改动是否正确。
_Avoid_: Issue 定位、代码评审、回归风险评分

**Issue 定位覆盖率**:
Issue 定位选出的代码位置对人类修复位置的覆盖比例。它以人类修复差异为对照，因而属于 Oracle Data 的衍生指标：只在 Historical Verification 上可计算，只进 Evidence Report，绝不进入 Generator Context 或 Generation Feedback；Live Issue 上标注为不适用。
_Avoid_: 复现率、模型置信度、影响置信度分级

**Generation Attempt**:
一次 Verification Run 中已计入全局上限的逻辑模型生成机会；正常完成时，模型基于同一份不可变 Generator Context 产出 Candidate Draft，并接受仅来自 Buggy Revision 的确定性校验。一次 Run 最多有三次，调用期间中断也不返还该机会。它是逻辑机会而非物理调用：一次 Attempt 内部可以包含多次模型调用与工具执行，这些调用共享该次 Attempt 的工具预算，并把 token 累加到同一次 Attempt 名下。
_Avoid_: API 重试、模型单次调用、Replay Attempt、Candidate Test Patch

**Generation Feedback**:
由候选草稿在 Buggy Revision 上的结构化校验事实形成、供下一次 Generation Attempt 修正使用的有界信息；它不得包含 Oracle Data、完整日志或主机环境信息。
_Avoid_: Replay Result、模型评价、Fixed Revision 反馈

**Generation Failure**:
Issue2Test 在提交 Candidate Test Patch 前，因生成轮次耗尽、模型基础设施故障或策略拒绝而终止的结构化结果；它不是 Replay Verdict。
_Avoid_: Not Reproduced、Inconclusive、模型异常原文

**Oracle Data**:
仅供验证器裁决使用的 Fixed Revision、人工修复差异和已知触发测试等答案信息。
_Avoid_: Prompt 上下文、模型工具结果

**Valid Reproduction**:
同一 Candidate Test Patch 在 Buggy Revision 上因目标断言失败、在 Fixed Revision 上通过，并排除编译、环境、超时和偶发失败后的最终结论。
_Avoid_: 测试失败、可能复现、Reproduction Candidate

**Reproduction Candidate**:
Live Issue 模式下，在当前缺陷版本上产生目标断言失败但尚无 Fixed Revision 可供差分验证的候选结果。
_Avoid_: Valid Reproduction、已确认 Bug

**Target Test**:
一次验证中被明确选定并以完整测试类名与方法名标识的候选测试；只有它自身的稳定执行事实能够支持复现结论。
_Avoid_: 任意失败测试、测试套件、已知触发测试

**Replay Attempt**:
正式验证中，Target Test 在指定 Revision 与隔离执行策略下的一次结构化执行事实；它属于可审计证据，不包含生成阶段用于选择候选的预验证。
_Avoid_: Generation Attempt、Maven 进程、预验证结果

**Replay Verdict**:
Replay Engine 根据稳定的目标测试事实，在 Historical Verification 或 Live Issue 中产生的机械裁决。
_Avoid_: Run Outcome、模型判断、日志摘要

**Failed on Both Commits**:
同一个 Target Test 在 Buggy Revision 与 Fixed Revision 上都稳定产生断言失败的裁决；它不构成 Valid Reproduction。
_Avoid_: Valid Reproduction、Flaky Failure

**Not Reproduced**:
Target Test 在当前缺陷版本或 Buggy Revision 上稳定通过，因而没有产生缺陷复现证据的裁决。
_Avoid_: Inconclusive、Valid Reproduction

**Inconclusive**:
由于执行、报告或稳定性证据不足，Replay Engine 无法判断目标测试是否复现缺陷的裁决。
_Avoid_: Not Reproduced、失败测试、系统异常

**Calibration Case**:
使用已知触发测试校准 Replay Engine 的案例；它证明执行与分类流水线可靠，不证明 Issue2Test 能独立生成测试。
_Avoid_: Agent Benchmark 成功案例、主演示案例

**Benchmark Case**:
按照冻结的机械标准纳入评测、并记录完整成功或失败证据的真实历史缺陷案例。
_Avoid_: 精选成功案例、Calibration Case

**Frozen Benchmark Cohort**:
在任何正式模型运行前，由已冻结的数据源版本、资格过滤器、种子与排序算法机械确定的有序 Benchmark Case 集合；冻结后不得因环境、生成或验证结果替换、增删或重排案例。
_Avoid_: 候选案例池、精选案例、成功案例列表

**Evidence Report**:
汇总代码位置、测试补丁、执行命令模板、退出码、日志摘要、失败分类和模型调用元数据的可追溯结果。
_Avoid_: 模型回答、评审意见、聊天记录

**Impact Report**:
依据代码关系图对一次代码改动产出的可追溯结论：改动方法、直接调用方、相关入口与测试关联，每条带影响置信度分级和证据来源。它只做静态分析、不执行测试，因此既不含 Replay Verdict，也不是一次 Verification Run。
_Avoid_: Evidence Report、Verification Run、回归风险评分

**Run Aggregate Metrics**:
从已持久化的 Verification Run 事实派生、可跨进程重启重建的累计度量；同一个 Run 的终态只贡献一次。
_Avoid_: 进程 Counter、执行日志、Benchmark 成功率

**Execution Telemetry**:
系统实际发生的模型调用与沙箱执行活动；恢复后的重新执行是新的资源消耗事实，可以再次计入。
_Avoid_: Run Aggregate Metrics、Replay Verdict、持久化账本

**Recorded Model Usage**:
供应商 usage 已返回且被系统成功记录的 token 事实；缺失表示未知，不能解释为零，也不能覆盖未返回响应或传输重试产生的潜在消耗。
_Avoid_: 模型账单、Generation Attempt、推算 Token

**Recorded Usage Status**:
描述 Verification Run 已记录 usage 与 Generation Attempt 的覆盖关系；它只能说明系统记录了多少次供应商 usage，不能证明供应商没有额外计费。由于一次 Generation Attempt 可含多次模型调用，覆盖关系同时包含「哪些 Attempt 有记录」与「某次 Attempt 内是否每次调用都有记录」两层，任一层不完整都不得报告为完整。
_Avoid_: Usage Complete、账单一致、Token 已知

**Estimated Model Cost**:
使用明确版本的价格依据对 Recorded Model Usage 计算出的费用估算；它不是供应商账单，价格或 usage 不完整时必须保持不可用或明确标注下界。
_Avoid_: 实际费用、账单金额、模型成本事实

**Pricing Reference**:
用于计算 Estimated Model Cost 的当前 provider、model、币种、费率、生效日期与来源；它不是 Verification Run 创建时冻结的历史报价。
_Avoid_: Price Snapshot、供应商账单、实时价格

**Run Correlation ID**:
用于关联同一 Verification Run 的安全标识，规范值为该 Run 的 `runId`；它属于日志上下文，不属于指标维度。
_Avoid_: Request ID、Trace ID、Metric Tag
