# Spring 案例研究证据

**n=1，案例研究，不支持统计结论，不得用于臂间比较。**

成功标准不是 `VALID_REPRODUCTION`。本文件要回答的是：在已确认的 Spring 案例上，`expand` 用没用、看到了什么。三次 `AGENT_BENCHMARK` Run 全部入库，无论下游成败。

跑前协议见 [`protocol.json`](protocol.json)。其中的证据强度表在开跑前入库，本报告不得改写。按该表：本例上 `expand` 仍为 0，属于**强**证据。salespoint 是 Spring 框架本身、修复点在 DI 密集区，这是对图臂有利的条件；在该条件下仍不用 `expand`，原因锁死在工具设计或定位提示词。选样本身仍只由五条机械条件决定。

定位覆盖率按终态快照对照地面真值事后计算，未写入 Generator Context、Generation Feedback 或 `locating_trace`。

## 案例

- `caseId`: `st-tu-dresden-salespoint-85a764f892aa`
- 仓库: `st-tu-dresden/salespoint`
- Issue: https://github.com/st-tu-dresden/salespoint/issues/412
- 地面真值（非测试 `.java`）: `src/main/java/org/salespointframework/accountancy/PersistentAccountancy.java`
- 模型: ollama / `glm-5.2`
- 重跑: 无

## `expand` 专章

图臂轨迹里 **`expand` 调用次数为 0**。

**原因不再是队列性质。** 本例生产依赖含 `org.springframework.boot` 与 `org.springframework.experimental`，修复点在 `PersistentAccountancy.java`。expand=0 不能再归因于案例类型。

图已经建起来了：`GRAPH_BUILD` 一次，`durationMs=883`，`cacheHit=false`。`find` 三次返回了可展开的实体（`Accountancy` 50 命中且截断，`AccountancyEntryRepository` 6 命中，`AccountancyEntry` 35 命中）。随后模型没有调用 `expand`，因此：

| 项 | 记录 |
| --- | --- |
| 被展开的实体 | 无（没有 `expand` 调用） |
| 边类型 | 无 |
| 置信度等级 | 无 |
| 该边是否进入最终 `submit` | 不适用：没有走过任何 expand 边 |

轨迹显示模型改用了：

1. `find` 三次，查询为 `Accountancy`、`AccountancyEntryRepository`、`AccountancyEntry`
2. `read` 八次，其中包括 `PersistentAccountancy.java`
3. `submit` 一次，8 个路径，其中含 `PersistentAccountancy.java`

`submit` 路径：

- `src/main/java/org/salespointframework/accountancy/Accountancy.java`
- `src/main/java/org/salespointframework/accountancy/PersistentAccountancy.java`
- `src/main/java/org/salespointframework/accountancy/AccountancyEntryRepository.java`
- `src/main/java/org/salespointframework/accountancy/AccountancyEntry.java`
- `src/main/java/org/salespointframework/accountancy/ProductPaymentEntry.java`
- `src/main/java/org/salespointframework/accountancy/AccountancyOrderEventListener.java`
- `src/test/java/org/salespointframework/accountancy/AccountancyTests.java`
- `src/test/java/org/salespointframework/accountancy/AccountancyRepositoryTests.java`

`submit` 里出现修复文件，是 `find` 加 `read` 的结果，**不是**沿 DI / AOP / 事件边展开的结果。边被走过并对结论有贡献，这一条在本轨迹上不成立，因为边没有被 `expand` 走过。

## 臂 HEURISTIC（并列，不比较）

定位覆盖率：anyHit `false`，recall `0.0000`，precision `0.0000`，selectedCount `12`。

12 次 `SELECTION`（Issue 类名与引用测试），3 次 `EXCLUSION`（`FILE_LIMIT`，含 `AccountancyTests.java`）。选中集不含 `PersistentAccountancy.java`。

复现结果：终态 `FAILED`，`GENERATION_EXHAUSTED`（3 次生成尝试用尽）。无候选补丁，无 `VALID_REPRODUCTION`。

成本（描述性）：生成 token 合计 26147（输入 21711，输出 4436）；墙钟约 686s；locating model tokens: none。

## 臂 TEXT_TOOLS（并列，不比较）

定位覆盖率：anyHit `true`，recall `1.0000`，precision `0.0833`，selectedCount `12`。

轨迹：`search` 9 次、`read` 12 次、`submit` 1 次。选中集含 `PersistentAccountancy.java`。search 模式包括 `class Accountancy`、`AccountancyEntryRepository`、`class AccountancyEntry`。

复现结果：终态 `FAILED`，`GENERATION_EXHAUSTED`（3 次生成尝试用尽）。无候选补丁，无 `VALID_REPRODUCTION`。

成本（描述性）：生成 token 合计 58448（输入 55508，输出 2940）；墙钟约 94s；locating model tokens: unknown。定位用量字段未入账，但 `locating_trace` 有工具步骤，故不把定位成本写成 0。

## 臂 GRAPH_TOOLS（并列，不比较）

定位覆盖率：anyHit `true`，recall `1.0000`，precision `0.1250`，selectedCount `8`。

轨迹：`GRAPH_BUILD` 1、`FIND` 3、`READ` 8、`EXPAND` 0、`SUBMIT` 1。选中集含 `PersistentAccountancy.java`。

复现结果：终态 `FAILED`，`GENERATION_EXHAUSTED`（3 次生成尝试用尽）。无候选补丁，无 `VALID_REPRODUCTION`。

成本（描述性）：生成 token 合计 27157（输入 20369，输出 6788）；墙钟约 79s；locating model tokens: unknown。定位用量字段未入账，但 `locating_trace` 有工具步骤，故不把定位成本写成 0。

## 机器可读事实

见 [`results.json`](results.json)。
