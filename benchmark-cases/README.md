# Benchmark Cases

这里保存 Historical Verification 与 Replay Engine 校准案例的元数据,不保存第三方源码副本。每个案例必须记录公开仓库 URL、Issue、Buggy/Fixed Revision、构建方式与许可证信息。

`fixtures/` 保存可控校准案例；真实仓库源码只克隆到 gitignored 工作区，仓库内仅保留案例元数据与可核验执行证据。

## 案例数据边界

一个历史案例必须把数据分成两部分:

- **Generator Context**:Issue、Buggy Revision、允许读取的源码和现有测试风格;
- **Oracle Data**:Fixed Revision、人类修复差异、已知触发测试和最终裁决结果。

Generator Context 可以进入定位与生成流程;Oracle Data 只能进入 Replay 验证器。案例文件、DTO 与工具接口都必须保持这条边界,不能只靠 Prompt 提醒模型。

建议元数据至少包含:

```text
caseId
repositoryUrl
license
issueUrl / issueText
buggyRevision
fixedRevision            # Oracle Data
modulePath
javaVersion
testCommandTemplate
knownTriggerTest         # Oracle Data,仅校准使用
expectedFailureSummary   # Oracle Data
```

## 两条方法论红线(决定 Benchmark 可信度)

- **预言机隔离**:Issue2Test 的生成器只能看 Issue + Buggy Revision,**绝不能看到 Fixed Revision**(diff 即答案)。Fixed 只供验证器裁决。案例数据结构必须让 Generator Context 与 Oracle Data 物理隔离。
- **训练数据污染控制**:厂商通常不公开完整训练集,无法排除商用 API 模型见过 Defects4J 的 Buggy/Fixed Revision 或 Issue 讨论。因此**诚实评测集不能主要依赖 Defects4J**。

## 案例来源:不乱找、不让 AI 选,分两层

- **引擎/校准层 → Defects4J 候选**:只用于 Replay Engine 校准和污染对照;采用前必须核对官方元数据、许可证、Java 版本与可重复构建方式。
- **诚实评测层 → 待核对候选数据源**:**GitBug-Java**、**BugSwarm**、**SWE-bench Java 子集 / Multi-SWE-bench**。在来源、许可证、案例结构和可重复性核验完成前,不得把它们写成既定评测集。
- **Spring 主演示 → 手挖 1–2 个真实 Spring 案例**(承载"面向 Spring 遗留项目"定位,并让影响分析的置信度分级有用武之地)。

**质量闸门(机械,非主观)**:高质量 = 可复现 + 有区分性触发测试,而非"著名"。黄金信号 = 合并 PR 声明修某 issue **且**同 PR 新增/改测试;父提交=buggy、合并=fixed;`checkout 父 → 该测试失败`、`checkout 合并 → 通过` 才收。选样标准冻结、脚本机械执行(Maven / 限时可构建 / Java 17/21 / 有 fail→pass 测试),过滤器留下什么就用什么,杜绝挑样本。

**红线**:AI 只能帮写挖掘/过滤脚本,**不能选择案例**(被评测者选考题 = 利益冲突 + 加重污染)。

## 真实 GitHub 使用方式

- **Historical Verification**:公开仓库 + Issue + Buggy/Fixed Revision;满足差分标准后可以判定 `VALID_REPRODUCTION`,并进入 Benchmark。
- **Live Issue**:公开仓库 + 尚未修复的 Issue + 当前 Revision;只能输出 `REPRODUCTION_CANDIDATE`,不进入完整复现率。获得并隔离 Fixed Revision 后,它才能转成 Historical Verification 案例。

V1 不要求 GitHub App。用户粘贴 Issue 内容并提供公开 HTTPS 仓库与固定 Revision,已经能够形成可审计的真实输入。

## 度量口径

每个案例除元数据外,记录以下指标,使"证据驱动"可被验证而非停留在口号:

- **Issue 定位覆盖率**:Agent 定位到的相关方法/文件是否覆盖人类修复位置。它需要人类修复差异作为对照,因此属于 Oracle 指标——只在 Historical Verification 上可计算,只进 Evidence Report,**绝不反馈给生成器**;Live Issue 上标注为不适用。
- **测试编译率**:生成的候选测试能否编译。
- **Buggy 有效断言失败率**:在 buggy 上因**目标断言**失败(而非编译/环境/超时/双侧失败)。
- **Fixed 通过率**:同一测试在 fixed 上通过。
- **完整复现率(VALID_REPRODUCTION)**:同时满足上述、判为成功复现的比例。
- **成本**:平均每案例模型调用次数、Token、费用与耗时。
- **失败分类计数**:`COMPILE_FAILURE / ENVIRONMENT_FAILURE / ASSERTION_FAILURE / TIMEOUT / FLAKY_FAILURE / FAILED_ON_BOTH_COMMITS / VALID_REPRODUCTION` 各类数量。

亮点不是"完整复现率一定高",而是**建立了一套能量化 Agent 是否真的复现 Bug 的评价体系**。保留 10 个案例的成功与失败数据;失败与成功同等重要。可做"只给 buggy 代码 vs 额外给提示"的消融,区分背题与推理。可选增强(SZZ 引入提交定位等)若实现,再单列指标。
