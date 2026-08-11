# 领域模型

PatchAtlas 将 Java 仓库中的 Issue 转换为可执行、可追溯的测试证据。以下术语用于代码、文档和报告，避免把候选结果误称为已经验证的结论。

## 核心术语

### Issue2Test

在看不到人类修复内容的前提下，根据 Issue 与 Buggy Revision 生成 Candidate Test Patch 的能力。

### Historical Verification

使用 Buggy Revision 与 Fixed Revision，对同一个 Candidate Test Patch 进行差分验证的模式。

### Live Issue

尚无 Fixed Revision 的真实 Issue。系统只能验证候选测试在当前缺陷版本上是否产生目标断言失败。

### Replay Engine

在隔离环境中对指定 Revision 执行同一测试并产生结构化执行结果的确定性模块。

### Candidate Test Patch

由 Issue2Test 生成、只允许修改测试源码的候选补丁。它在通过差分验证前不代表成功复现。

### Generator Context

生成器可以读取的 Issue、Buggy Revision、相关源码与现有测试风格，其中不得包含 Oracle Data。

### Oracle Data

仅供验证器裁决使用的 Fixed Revision、人工修复差异和已知触发测试等答案信息。

### Valid Reproduction

同一 Candidate Test Patch 在 Buggy Revision 上因目标断言失败、在 Fixed Revision 上通过，并排除编译、环境、超时和偶发失败后的最终结论。

### Reproduction Candidate

Live Issue 模式下，在当前缺陷版本上产生目标断言失败但尚无 Fixed Revision 可供差分验证的候选结果。

### Calibration Case

使用已知触发测试校准 Replay Engine 的案例。它证明执行与分类流水线可靠，不证明 Issue2Test 能独立生成测试。

### Benchmark Case

按照冻结标准纳入评测，并记录完整成功或失败证据的真实历史缺陷案例。

### Evidence Report

汇总代码位置、测试补丁、执行命令模板、退出码、日志摘要、失败分类和模型调用元数据的可追溯结果。

## 结论边界

- Historical Verification 同时满足 Buggy 目标断言失败、Fixed 通过以及假失败排除后，才能输出 `VALID_REPRODUCTION`。
- Live Issue 没有 Fixed Revision，最多输出 `REPRODUCTION_CANDIDATE`。
- Calibration Case 使用已知测试，只能证明 Replay Engine，不计入 Agent 生成成功率。
