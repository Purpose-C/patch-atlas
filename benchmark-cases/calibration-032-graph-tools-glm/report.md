# 图工具定位调用成本（glm-5.2）

日期：2026-08-18  
端点：`https://ollama.com/v1`  
模型：`glm-5.2`  
测量预算：60 次工具调用 / 15 分钟墙钟  
臂：`GRAPH_TOOLS`（`find` / `expand` / `read` / `submit`）

同模型、同队列、同测量预算的文本臂见 `benchmark-cases/calibration-032-text-tools-glm/`。两臂对照见 `benchmark-cases/calibration-032-text-vs-graph-glm/report.md`。

本报告不计算定位命中率，也不判断提交集合是否找对了文件。

## 结论

18 / 18 次会话全部到达 `SUBMIT`。无人触到放宽上限 60，传输失败 0，N>8 守卫未触发。停止条件均未命中。建图全部成功，没有回退到文本工具（轨迹中无 `SEARCH` / `LIST` / `UNKNOWN_TOOL`）。

「进分位数」= 到达 `SUBMIT` 且轨迹中没有 `BUDGET_WARNING`。这类会话有 **17** 个（≥12）。

调用次数（进分位数）：`[5, 6, 6, 9, 10, 10, 11, 12, 13, 13, 14, 15, 16, 17, 18, 38, 38]`

| 统计量 | 值 |
| --- | --- |
| n | 17 |
| p50（含端点线性插值） | **13** |
| p95（含端点线性插值） | **38** |
| max | **38** |

一条收到 `BUDGET_WARNING` 的会话为 48 次调用（ConfigMe#363 第 2 次），不进分位数。

建图耗时单独记录，不计入上面的工具次数，也不扣测量墙钟。18 次里缓存命中 **14 / 18**。`expand` 在本样本中 **0 次**；发现阶段全部是 `find`。

## 方法

- 冻结队列 6 例，每例重复 3 次。只读 `cohort.json` 与 `generator-context.json`，不读人类修复。
- Issue 文本从 GitHub 公共 API 拉取，按 `SHA-256(title + "\n" + body)` 核对冻结摘要。6 例全部一致，无跳过。
- 提交：`LIVE` + `DIAGNOSTIC` + `GRAPH_TOOLS`。只跑定位。
- 测量上限 60 / 15 分钟。建图超时 5 分钟，失败则 `LOCATING_TOOL_PROTOCOL_ERROR`，不改用文本工具。
- 18 次采样前，同一模型在 whatsapp#100 与 jsoup#2002 上各跑过 1 次会话：均为 `find` → `read` → `submit`。

## 样本量

| 项 | 值 |
| --- | --- |
| 摘要不符而跳过 | 无 |
| 计划 / 完成提交 | 18 / 18 |
| 到达 `SUBMIT` | 18 |
| 进分位数（SUBMIT 且无 `BUDGET_WARNING`） | **17** |
| 收敛但收到 `BUDGET_WARNING` | 1（48 次） |
| 未收敛 | 0 |
| 触到 60 | 否 |
| 传输失败 | 0 |
| N>8 守卫 | 否 |
| 建图失败 | 0 |

## 十八次会话

| 案例 | 次 | 调用 | 建图 ms | 缓存 | 终止 | 类别 |
| --- | --- | --- | --- | --- | --- | --- |
| whatsapp-business-java-api#100 | 1 | 10 | 46 | 命中 | SUBMIT | 进分位数 |
| jsoup#2002 | 1 | 9 | 121 | 命中 | SUBMIT | 进分位数 |
| word-wrap#47 | 1 | 6 | 274 | 未命中 | SUBMIT | 进分位数 |
| jsoup#1902 | 1 | 13 | 2321 | 未命中 | SUBMIT | 进分位数 |
| jsoup#2011 | 1 | 11 | 1435 | 未命中 | SUBMIT | 进分位数 |
| ConfigMe#363 | 1 | 17 | 979 | 未命中 | SUBMIT | 进分位数 |
| whatsapp#100 | 2 | 38 | 14 | 命中 | SUBMIT | 进分位数 |
| jsoup#2002 | 2 | 18 | 75 | 命中 | SUBMIT | 进分位数 |
| word-wrap#47 | 2 | 5 | 3 | 命中 | SUBMIT | 进分位数 |
| jsoup#1902 | 2 | 12 | 54 | 命中 | SUBMIT | 进分位数 |
| jsoup#2011 | 2 | 13 | 65 | 命中 | SUBMIT | 进分位数 |
| ConfigMe#363 | 2 | 48 | 42 | 命中 | SUBMIT | 有警告，不进分位数 |
| whatsapp#100 | 3 | 10 | 20 | 命中 | SUBMIT | 进分位数 |
| jsoup#2002 | 3 | 15 | 67 | 命中 | SUBMIT | 进分位数 |
| word-wrap#47 | 3 | 6 | 2 | 命中 | SUBMIT | 进分位数 |
| jsoup#1902 | 3 | 14 | 79 | 命中 | SUBMIT | 进分位数 |
| jsoup#2011 | 3 | 16 | 100 | 命中 | SUBMIT | 进分位数 |
| ConfigMe#363 | 3 | 38 | 46 | 命中 | SUBMIT | 进分位数 |

时长（进分位数，秒，含克隆工作区，**不含**建图扣预算）：`[9.3, 14.0, 17.6, 18.5, 20.4, 21.2, 21.7, 21.7, 21.9, 23.6, 24.8, 28.3, 28.7, 28.9, 30.8, 63.0, 89.1]`。p50 = 21.9 s，p95 ≈ 68.2 s。

## 分位数怎么算

- p50 位置 `1 + (17-1)×0.50 = 9`：第 9 个 = **13**。
- p95 位置 `1 + (17-1)×0.95 = 16.2`：第 16、17 个都是 38，因此 **38**。

## 首次 `read` 前的发现调用

18 次先手全部是 `FIND`。没有一轮就 submit，也没有调用 `expand`。

第一次 `read` 前的 `find`/`expand` 次数：`[3, 3, 2, 2, 1, 6, 4, 3, 2, 2, 1, 7, 4, 3, 2, 2, 1, 6]`，平均 **3.0**。

## `read_not_submitted` / `submitted_not_read`

18 次成功 submit 的 `read_not_submitted`：`[0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 0, 8, 0, 2, 0, 0, 0, 2]`。平均 0.83。

`submitted_not_read`：`[0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]`。仅 ConfigMe#363 第 1 次为 1，其余为 0。

## 工具步明细

`GRAPH_BUILD`（`SELECTION`）不计入已执行工具。

| 步骤 | OK | ERROR |
| --- | --- | --- |
| FIND | 145 | 0 |
| EXPAND | 0 | 0 |
| READ | 129 | 0 |
| SUBMIT | 18 | 7 |
| SEARCH / LIST | 0 | 0 |
| UNKNOWN_TOOL | 0 | 0 |
| BUDGET_WARNING | 1 | 0 |
| BUDGET_EXHAUSTED | 0 | 0 |

已执行工具（FIND/EXPAND/READ/SUBMIT）299 次，outcome=ERROR 的 7 次都是 submit 闸门拒绝。ToolError 率：**7 / 299 ≈ 2.3%**。FIND / EXPAND / READ 无 ERROR。

submit 被拒（同会话随后都再次提交并被接受）：

- jsoup#2002 三次：`file exceeds 64 KiB`（`Element.java` / `ElementTest.java`）
- ConfigMe#363 第 2、3 次：`at most 12 paths after deduplication`

## 建图开销

| 项 | 值 |
| --- | --- |
| 次数 | 18 |
| 失败 | 0 |
| 缓存命中 | **14 / 18**（约 78%） |
| 全样本耗时 p50 | **66 ms** |
| 未命中耗时 | 274、979、1435、2321 ms |
| 命中耗时 | 2–121 ms |

whatsapp#100 与 jsoup#2002 的第 1 次显示缓存命中，是因为同一缓存目录里已有这两次修订的图（采样前的单会话确认写入）。其余 4 个修订各有一次未命中，之后全命中。

## 已知局限

- 会话 `elapsedMs` 含克隆工作区。建图在工具循环之前，不扣测量墙钟，但墙钟数字仍含克隆。
- 本样本里模型一次都没用 `expand`。对照的是「模型实际调用」，不是「工具是否可用」。
- 本报告不算定位命中率，未读人类补丁。
