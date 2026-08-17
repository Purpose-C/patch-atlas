# 定位工具预算校准报告

日期：2026-08-17  
端点：`https://apihub.agnes-ai.com/v1`  
模型：`agnes-2.5-flash`  
测量预算：60 次工具调用 / 15 分钟墙钟（默认值未改，仍为 25 / 5 分钟）

## 结论

**不能根据本次数据改定值。** 第 3 次会话触发了 N>1 并行工具调用守卫：模型在同一轮返回了 `read` 与 `search` 两次 `tool_calls`，尽管请求带了 `parallel_tool_calls: false`。规格要求此时停止，整批数据不可用。

`MAX_TOOL_CALLS` 与 `TOOL_LOOP_WALL_CLOCK` **保持 25 次 / 5 分钟**。

这些数字只适用于 `agnes-2.5-flash`。换模型必须重测。

## 方法

- 冻结队列 `benchmark-cases/task018/` 的 6 例；只读 `cohort.json` 与 `generator-context.json`，不读人类修复或其它 Oracle Data。
- Issue 标题与正文从 GitHub 公共 API 拉取，按冻结算法 `SHA-256(title + "\n" + body)`（UTF-8 小写十六进制）核对 `issueContentSha256`。
- 6 例摘要均与冻结时一致，无跳过。
- 提交形态：`LIVE` + `DIAGNOSTIC` + `TEXT_TOOLS`，无幂等键；只跑定位，不进入生成或 Replay。
- 每例计划重复 3 次，目标 18 次会话；任一停止条件触发即停。

## 样本量

| 项 | 值 |
| --- | --- |
| 摘要不符而跳过 | 无 |
| 提交次数 | 3 |
| 到达 `SUBMIT` | 0 |
| 有效会话（完成且未截断、非并行、非传输失败） | 0 |
| 触到放宽上限 60 | 否 |
| 传输失败 | 0 |
| N>1 守卫 | **是**（第 3 次） |

分位数要求只统计到达 `SUBMIT` 的会话。本次为 0，p50 / p95 / max 无法计算。

## 三次会话

| 案例 | 终止 | 工具调用 | 耗时 |
| --- | --- | --- | --- |
| Bindambc/whatsapp-business-java-api#100 | `LOCATING_NO_CONTEXT`（轨迹为空） | 0 | 26 s |
| jhy/jsoup#2002 | `LOCATING_NO_CONTEXT`（轨迹为空） | 0 | 36 s |
| davidmoten/word-wrap#47 | 并行守卫：`received 2 [read, search]` | 0（拒绝整批，未记轨迹） | 18 s |

前两次：模型完成了一轮回复，但没有进入可记录的单步工具执行。第三次：同一轮并行发出 `read` 与 `search`，守卫拒绝后 Run 失败。

## 定性观察（给后续图接口设计）

完整有序轨迹不足 3 份——三次会话的 `locating_trace` 都是空的。能确定的只有：

1. **并行**：上游在声明 `parallel_tool_calls: false` 后仍返回 N=2。守卫按设计拒绝整批，因此看不到参数与结果。这一批不能拿来比较「轮数」与「调用数」。
2. **前两轮零工具**：不是传输失败。可能是模型先用自然语言作答，或工具调用形状未被执行器接受。现有轨迹字段在未执行到 `SEARCH`/`LIST`/`READ`/`SUBMIT` 时给不出更细的原因。
3. **ToolError / submit 被拒**：本次没有记录到任何一步工具执行，因此没有实例。
4. 轨迹的 `detail` 目前固定为 `{}`，即便跑通，参数摘要与 ToolError 正文也不会落库。图接口设计若依赖「错误长什么样」，需要先让轨迹写下拒绝原因。

## 新常数

| 常数 | 取值 | 依据 |
| --- | --- | --- |
| `MAX_TOOL_CALLS` | **25（不变）** | 并行守卫触发，数据不可用；禁止在截断或污染样本上定值 |
| `TOOL_LOOP_WALL_CLOCK` | **5 分钟（不变）** | 同上；没有到达 `SUBMIT` 的时长分布 |

## 已知局限

- 官方测量只有上述 3 次提交；未跑满 18 次。
- 测量前发现定位请求会合并生成器的 Candidate Draft `json_schema`，并在未显式带模型名时落到框架默认模型。已在定位选项里改为 `TEXT` 并带上模型名后再测。更早的探测不计入本报告分母。
- 定位阶段的 `ChatModel` 默认选项仍与生成器共用同一工厂；定位必须覆盖 `response_format` 与 `model`，否则还会复发。
- 一次 Worker 进程里的 `LocalizationBudget` 在装配时创建，跨 Run 会共用计数与截止时间。本次校准每个会话新建预算，不受影响；常驻 Worker 若连续跑定位会偏紧。
- 本报告不计算定位命中率，也未读取人类补丁。

## 建议的下一步

1. 在确认上游重新遵守 `parallel_tool_calls: false` 之前，不要用这批数据设计图工具，也不要改预算常数。
2. 若必须在当前端点上继续测，需要先决定：是继续拒绝并行（数据仍会在守卫处中断），还是改变协议。改变协议超出本次校准范围。
3. 轨迹应记下 ToolError / submit 拒绝原因，否则即使跑通也难支撑图接口取舍。
