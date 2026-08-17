# 定位工具预算校准报告

日期：2026-08-17  
端点：`https://apihub.agnes-ai.com/v1`  
模型：`agnes-2.5-flash`  
测量预算：60 次工具调用 / 15 分钟墙钟

## 结论

18 次目标没有跑满：第 17 次提交后运行器 `claimNext` 领到了非本会话的 Run，为避免串队列而中止。已完成 **16** 次会话，其中 **15** 次到达 `SUBMIT`，**无人触到放宽上限 60**，传输失败 0，N>8 守卫未触发。

按 2026-08-17 补充口径，「进分位数」= 到达 `SUBMIT` 且轨迹中**没有** `BUDGET_WARNING`。这类会话有 **13** 个（≥12）。

调用次数（进分位数）：`[5, 5, 5, 7, 8, 9, 10, 11, 11, 23, 23, 28, 43]`

| 统计量 | 值 |
| --- | --- |
| n | 13 |
| p50（中位数） | **10** |
| p95（含端点线性插值） | **34** |
| max | **43** |

**新的 `MAX_TOOL_CALLS` = 35**（34 向上取整到 5 的倍数；下限 15、上限 60）。  
**新的墙钟 = 9 分钟**（进分位数时长 p95 ≈ 250 s × 2 ≈ 8.4 min，向上取整；下限 3）。

这些数字只适用于 `agnes-2.5-flash`。换模型必须重测。

13 个点的 p95 被单次 43 拉高。这是规则算出的数，不是为了「显得有改动」。

## 方法

- 冻结队列 6 例，每例计划重复 3 次。只读 `cohort.json` 与 `generator-context.json`，不读人类修复。
- Issue 文本从 GitHub 公共 API 拉取，按 `SHA-256(title + "\n" + body)` 核对冻结摘要。6 例全部一致，无跳过。
- 提交：`LIVE` + `DIAGNOSTIC` + `TEXT_TOOLS`。只跑定位。
- 测量上限 60 / 15 分钟。并行按序执行，守卫只在 N>8 时触发。
- 会话三类：无警告的 `SUBMIT` 进分位数；有 `BUDGET_WARNING` 的 `SUBMIT` 单独报；触到 60 则停。

## 样本量

| 项 | 值 |
| --- | --- |
| 摘要不符而跳过 | 无 |
| 计划 / 完成提交 | 18 / 16 |
| 到达 `SUBMIT` | 15 |
| 进分位数（SUBMIT 且无 `BUDGET_WARNING`） | **13** |
| 收敛但收到 `BUDGET_WARNING` | 2（均为 49 次） |
| 未收敛 | 1（`WORKSPACE_ERROR`，8 次调用后工作区失败，不是 60 截断） |
| 未开跑 | 2（`jsoup-9de27fa7cd82` 第 3 次、`ConfigMe` 第 3 次） |
| 触到 60 | 否 |
| 传输失败 | 0 |
| N>8 守卫 | 否 |

## 十六次会话

| 案例 | 次 | 调用 | 终止 | 类别 |
| --- | --- | --- | --- | --- |
| whatsapp-business-java-api#100 | 1 | 10 | SUBMIT | 进分位数 |
| jsoup#2002 | 1 | 5 | SUBMIT | 进分位数 |
| word-wrap#47 | 1 | 5 | SUBMIT | 进分位数 |
| jsoup#1776 | 1 | 49 | SUBMIT | 有警告，不进分位数 |
| jsoup#1462 | 1 | 11 | SUBMIT | 进分位数 |
| ConfigMe#347 | 1 | 43 | SUBMIT | 进分位数 |
| whatsapp#100 | 2 | 11 | SUBMIT | 进分位数 |
| jsoup#2002 | 2 | 8 | SUBMIT | 进分位数 |
| word-wrap#47 | 2 | 5 | SUBMIT | 进分位数 |
| jsoup#1776 | 2 | 23 | SUBMIT | 进分位数 |
| jsoup#1462 | 2 | 23 | SUBMIT | 进分位数 |
| ConfigMe#347 | 2 | 49 | SUBMIT | 有警告，不进分位数 |
| whatsapp#100 | 3 | 9 | SUBMIT | 进分位数 |
| jsoup#2002 | 3 | 8 | OTHER（工作区失败） | 不进分位数 |
| word-wrap#47 | 3 | 7 | SUBMIT | 进分位数 |
| jsoup#1776 | 3 | 28 | SUBMIT | 进分位数 |

时长（进分位数，秒）：`[21, 34, 35, 43, 47, 62, 63, 65, 70, 72, 128, 250, 251]`。p50 = 63 s，p95 ≈ 250 s。

## 工具步明细

| 步骤 | OK | ERROR |
| --- | --- | --- |
| SEARCH | 120 | 2 |
| LIST | 20 | 0 |
| READ | 137 | 0 |
| SUBMIT | 15 | 0 |
| BUDGET_WARNING | 2 | 0 |
| BUDGET_EXHAUSTED | 0 | 0 |

ToolError 率：2 / 279 次已执行工具（不含警告）。submit 被拒：**0**。`submitted_not_read`：15 次成功 submit 全是 0。

## 新常数怎么来的

进分位数的 13 个调用次数按升序如上。

- p50 取中位数：第 7 个 = 10。
- p95 用含端点线性插值：位置 `1 + (13-1)×0.95 = 12.4`，第 12 个是 28、第 13 个是 43，`28 + 0.4×(43-28) = 34`。
- 34 向上取整到 5 的倍数 = **35**。
- 时长同样插值得 p95 ≈ 250 s，×2 ≈ 8.4 分钟，向上取整 = **9 分钟**。

若改用「最近秩、向上取整」把 p95 当成 max=43，则会得到 45。本报告不用那种算法，因为 n=13 时它直接等于最大值。两种算法都说明：尾巴由 ConfigMe 的 43 决定，不是由 5–11 那一簇决定。

## 定性观察（给后续图接口）

### 1. 短会话：两步 search 就交卷

`24b791e5-…`（jsoup#2002 第 1 次，5 步）：

| seq | 工具 | 结果 |
| --- | --- | --- |
| 0 | SEARCH `removeAttr` `**/*.java` | 25 命中 |
| 1 | SEARCH `class Attributes` | 2 命中 |
| 2 | READ `Attributes.java` | 400 行，`truncated=true` |
| 3 | READ `Attribute.java` | 244 行 |
| 4 | SUBMIT `Attributes.java` | 1 条路径；`read_not_submitted=1`（`Attribute.java` 读了没交） |

第一次 `read` 前 2 次 search。没有重复，没有警告。

### 2. whatsapp：读对了实现，submit 仍带着 `pom.xml`

`8b31dfea-…`（第 1 次，10 步）：`SEARCH uploadMedia` → `SEARCH okhttp` → `LIST .` → 读了 `pom.xml`、两个实现、一个 service、测试和 example → **SUBMIT 2 条，第一条是 `pom.xml`**。`submitted_not_read=0`，`read_not_submitted=4`。

第 2、3 次同样把 `pom.xml` 放进 submit。提示词挡住了「只交 pom」，没挡住「交实现时顺便带上构建文件」。图接口如果只提高检索，解决不了提交集合的取舍。

### 3. 第一次 `read` 前平均先探索 3.8 步

16 次里 14 次先手是 `SEARCH`，2 次是 `LIST`。第一次 `read` 前的 search+list 次数：`[3,2,2,9,2,9,3,3,2,6,2,6,4,3,2,2]`，平均 **3.75**。没有一轮就 submit。

ConfigMe 和 jsoup#1776 会在第一次 read 前连搜 6–9 次（含 `hits=50 truncated=true` 的过宽正则）。

### 4. ToolError 实际样貌（仅 2 例，都在 ConfigMe 第 2 次）

`71c446f1-…` seq 23–24：

- `SEARCH` / `outcome=ERROR` / `errorType=IllegalArgumentException` / `message=unknown tool`  
  模型发了一个循环不认识的工具名。轨迹 `kind` 被记成 `SEARCH`（未知名字的默认分支），所以分工具统计会把「虚构工具」算进 search。
- 紧接着：`message=invalid search pattern` —— 正则编坏了。

没有路径越界，没有返回上界拒绝。失败形态仍主要是「成功但空转」，不是工具报错。

### 5. 重复调用：长会话才密集出现

27 次 `repeat_of`，几乎全在两条 49 步会话里。

ConfigMe 第 2 次从 seq 25 起反复撞同一对文件：

- `SEARCH PropertyListBuilder`（seq 14）在 seq 25 被重复
- `READ PropertyListBuilder.java`（seq 15）在 37、42、45 被重复
- `READ PropertyListBuilderTest.java`（seq 16）在 34、38、41、43、47 被重复

检测按设计工作：不重跑、计预算、轨迹 `OK`。模型收到「already made」后仍继续要同一对文件，直到 seq 46 的 `BUDGET_WARNING`（`used=46, maxCalls=60`），然后 49 步才 submit。重复反馈能省磁盘，**不能单独让它停**。

### 6. `read_not_submitted` 分布

15 次成功 submit：`[4,1,0,1,0,11,4,0,0,0,0,12,1,0,2]`。  
ConfigMe 两次读了十几份、只交 3–5 份（11 和 12）。whatsapp 三次都读了实现却少交。短的 jsoup/word-wrap 经常 0。

`submitted_not_read` 全 0：没有「没读就交」。质量问题是交少了或交杂了，不是交没看过的文件。

### 7. submit 被拒

**0 次。** 路径不存在 / 超 12 个 / 超 256 KiB 仍然没有实例。

### 8. 工作区失败不是定位失败

`5d37a419-…`（jsoup#2002 第 3 次）已经 search/read 了 8 步，随后 `WORKSPACE_ERROR`。轨迹里没有 submit。这不是 60 截断，也不进分位数。

## 已知局限

- 目标 18，实际 16。缺 `jsoup#1462` 第 3 次和 `ConfigMe` 第 3 次。运行器在提交后 `claimNext` 领到了另一条未收尾的 Run（疑与第 3 次 jsoup#2002 的工作区失败残留有关）。没有改定位代码去救。
- n=13 的 p95 对单次 43 敏感。
- 测量时 `BUDGET_WARNING` 在第 46 次才触发；生产 35 次时大约第 27 次就会触发。进分位数的会话都没用到这条提示，所以这次 p95 没有被提示压低。
- 本报告不算定位命中率，未读人类补丁。whatsapp 仍提交 `pom.xml` 是否「定位正确」不在这里裁决。

## 建议的下一步

1. 常数按上面规则取 35 / 9 分钟。不要因为「看起来还是三十多」就改回 25，也不要因为尾巴难看就改成 45。
2. 批次 4 的图接口至少要能对着这些实例说话：过宽 search 的 50 命中截断、同一文件的 `repeat_of` 轰炸、submit 仍夹带 `pom.xml`、虚构工具名被记成 SEARCH。
3. 校准运行器应只领取自己刚提交的 Run；否则下一次长跑还会在租约边界上把自己绊倒。这不是定位循环的问题。
