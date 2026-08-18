# 文本工具定位调用成本（glm-5.2）

日期：2026-08-18  
端点：`https://ollama.com/v1`  
模型：`glm-5.2`  
测量预算：60 次工具调用 / 15 分钟墙钟  
臂：`TEXT_TOOLS`（`search` / `list` / `read` / `submit`）

同口径的 `agnes-2.5-flash` 记录见 `benchmark-cases/calibration-027-tool-budget/`。换模型后两套数字不可比，本文件只报 glm 文本臂。图工具臂的对照另文给出。

本报告不计算定位命中率，也不判断提交集合是否「找对了文件」。

## 结论

18 / 18 次会话全部到达 `SUBMIT`。无人触到放宽上限 60，传输失败 0，N>8 守卫未触发。停止条件均未命中。

「进分位数」= 到达 `SUBMIT` 且轨迹中没有 `BUDGET_WARNING`。这类会话有 **16** 个（≥12）。

调用次数（进分位数）：`[4, 5, 6, 7, 7, 8, 9, 9, 10, 10, 11, 11, 14, 23, 27, 37]`

| 统计量 | 值 |
| --- | --- |
| n | 16 |
| p50（含端点线性插值） | **9.5** |
| p95（含端点线性插值） | **29.5** |
| max | **37** |

两条收到 `BUDGET_WARNING` 的会话均为 47 次调用，单独列出，不进分位数。

生产定位预算仍是 35 次 / 9 分钟（按 agnes 样本定的）。本文件不改生产常数；数字只用于后续与图工具臂对照。

## 方法

- 冻结队列 6 例，每例重复 3 次。只读 `cohort.json` 与 `generator-context.json`，不读人类修复。
- Issue 文本从 GitHub 公共 API 拉取，按 `SHA-256(title + "\n" + body)` 核对冻结摘要。6 例全部一致，无跳过。
- 提交：`LIVE` + `DIAGNOSTIC` + `TEXT_TOOLS`。只跑定位。
- 测量上限 60 / 15 分钟。并行按序执行，守卫只在 N>8 时触发。
- 18 次采样前，同一模型在 whatsapp#100 与 jsoup#2002 上各跑过 1 次会话：均为 `search`/`list` → `read` → `submit`，不是一轮直接提交。

## 样本量

| 项 | 值 |
| --- | --- |
| 摘要不符而跳过 | 无 |
| 计划 / 完成提交 | 18 / 18 |
| 到达 `SUBMIT` | 18 |
| 进分位数（SUBMIT 且无 `BUDGET_WARNING`） | **16** |
| 收敛但收到 `BUDGET_WARNING` | 2（均为 47 次） |
| 未收敛 | 0 |
| 触到 60 | 否 |
| 传输失败 | 0 |
| N>8 守卫 | 否 |

## 十八次会话

| 案例 | 次 | 调用 | 终止 | 类别 |
| --- | --- | --- | --- | --- |
| whatsapp-business-java-api#100 | 1 | 8 | SUBMIT | 进分位数 |
| jsoup#2002 | 1 | 10 | SUBMIT | 进分位数 |
| word-wrap#47 | 1 | 11 | SUBMIT | 进分位数 |
| jsoup#1902 | 1 | 23 | SUBMIT | 进分位数 |
| jsoup#2011 | 1 | 47 | SUBMIT | 有警告，不进分位数 |
| ConfigMe#363 | 1 | 47 | SUBMIT | 有警告，不进分位数 |
| whatsapp#100 | 2 | 10 | SUBMIT | 进分位数 |
| jsoup#2002 | 2 | 7 | SUBMIT | 进分位数 |
| word-wrap#47 | 2 | 7 | SUBMIT | 进分位数 |
| jsoup#1902 | 2 | 9 | SUBMIT | 进分位数 |
| jsoup#2011 | 2 | 9 | SUBMIT | 进分位数 |
| ConfigMe#363 | 2 | 37 | SUBMIT | 进分位数 |
| whatsapp#100 | 3 | 11 | SUBMIT | 进分位数 |
| jsoup#2002 | 3 | 5 | SUBMIT | 进分位数 |
| word-wrap#47 | 3 | 4 | SUBMIT | 进分位数 |
| jsoup#1902 | 3 | 6 | SUBMIT | 进分位数 |
| jsoup#2011 | 3 | 14 | SUBMIT | 进分位数 |
| ConfigMe#363 | 3 | 27 | SUBMIT | 进分位数 |

时长（进分位数，秒，含克隆工作区）：`[6.0, 10.4, 11.0, 14.0, 14.6, 16.4, 19.2, 20.8, 22.2, 22.5, 26.7, 30.4, 30.4, 41.8, 47.3, 57.1]`。p50 = 21.5 s，p95 = 49.75 s。

## 分位数怎么算

进分位数的 16 个调用次数按升序如上。

- p50 位置 `1 + (16-1)×0.50 = 8.5`：第 8 个是 9、第 9 个是 10，`9 + 0.5×(10-9) = 9.5`。
- p95 位置 `1 + (16-1)×0.95 = 15.25`：第 15 个是 27、第 16 个是 37，`27 + 0.25×(37-27) = 29.5`。

两条 47 次会话不进分位数，因此 max=37 不是全样本最大值。

## 首次 `read` 前的发现调用

18 次里 14 次先手是 `SEARCH`，4 次是 `LIST`。没有一轮就 submit。

第一次 `read` 前的 `search`+`list` 次数：`[2, 2, 1, 2, 1, 8, 2, 2, 1, 3, 1, 5, 2, 2, 1, 3, 1, 6]`，平均 **2.5**。

ConfigMe 三次在第一次 `read` 前分别探索 8 / 5 / 6 步；其余案例多为 1–3。

## `read_not_submitted` / `submitted_not_read`

18 次成功 submit 的 `read_not_submitted`：`[0, 0, 0, 0, 2, 19, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 0, 4]`。平均 1.83。非零值都在 jsoup#2011 与 ConfigMe：读了很多文件，提交集合更小。

`submitted_not_read`：`[0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0]`。3 次会话各有 1 条「没读就交」的路径；其余为 0。

## 工具步明细

| 步骤 | OK | ERROR |
| --- | --- | --- |
| SEARCH | 85 | 0 |
| LIST | 16 | 0 |
| READ | 171 | 0 |
| SUBMIT | 18 | 2 |
| BUDGET_WARNING | 2 | 0 |
| BUDGET_EXHAUSTED | 0 | 0 |

已执行工具（SEARCH/LIST/READ/SUBMIT）292 次，其中 outcome=ERROR 的 2 次都是 submit 闸门拒绝，不是 search/list/read 抛错。ToolError 率：**2 / 292 ≈ 0.68%**。

submit 被拒 2 次，随后同会话都再次提交并被接受：

- jsoup#2002 第 1 次：`file exceeds 64 KiB`（`Element.java`）
- ConfigMe#363 第 2 次：`at most 12 paths after deduplication`

轨迹里没有 `repeat_of`。SEARCH / LIST / READ 无 ERROR。

## 已知局限

- 会话 `elapsedMs` 含克隆工作区，不是纯模型墙钟。对照时两臂必须用同一口径。
- 两条 47 次会话触发了测量预算的 `BUDGET_WARNING`（约第 46 次）。它们不进分位数；若把生产上限 35 套到这两次上，会提前截断，本文件不据此改生产常数。
- 本报告不算定位命中率，未读人类补丁。
