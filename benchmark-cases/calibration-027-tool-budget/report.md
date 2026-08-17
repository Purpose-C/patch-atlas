# 定位工具预算校准报告

日期：2026-08-17  
端点：`https://apihub.agnes-ai.com/v1`  
模型：`agnes-2.5-flash`  
测量预算：60 次工具调用 / 15 分钟墙钟（默认值未改，仍为 25 / 5 分钟）

## 结论

**不能根据本次数据改定值。** 第 2 次会话（`jhy/jsoup#2002`）用满了放宽后的 60 次工具调用仍未 `submit`，观测分布右侧被截断。规格要求此时停止，且不得据此设定 `MAX_TOOL_CALLS` 或墙钟。

`MAX_TOOL_CALLS` 与 `TOOL_LOOP_WALL_CLOCK` **保持 25 次 / 5 分钟**。

这些数字只适用于 `agnes-2.5-flash`。换模型必须重测。

## 方法

- 冻结队列 `benchmark-cases/task018/` 的 6 例；只读 `cohort.json` 与 `generator-context.json`，不读人类修复或其它 Oracle Data。
- Issue 标题与正文从 GitHub 公共 API 拉取，按冻结算法 `SHA-256(title + "\n" + body)`（UTF-8 小写十六进制）核对 `issueContentSha256`。
- 本批 6 例摘要均与冻结时一致，无跳过。
- 提交形态：`LIVE` + `DIAGNOSTIC` + `TEXT_TOOLS`，无幂等键；只跑定位，不进入生成或 Replay。
- 测量上限 60 次 / 15 分钟；并行批次按序执行，守卫只在 N>8 时触发。
- 每例计划重复 3 次，目标 18 次会话；任一停止条件触发即停。

## 样本量

| 项 | 值 |
| --- | --- |
| 摘要不符而跳过 | 无 |
| 提交次数 | 2 |
| 到达 `SUBMIT` | 1 |
| 有效会话（开始定位且未截断、非并行、非传输失败） | 1 |
| 触到放宽上限 60 | **是**（第 2 次） |
| 传输失败 | 0 |
| N>8 守卫 | 否 |

分位数只统计到达 `SUBMIT` 的会话。本次仅 1 次，且存在触到 60 的会话，p50 / p95 / max **不得用来定值**。

## 两次会话

| 案例 | 终止 | 工具调用 | 耗时 | 说明 |
| --- | --- | --- | --- | --- |
| Bindambc/whatsapp-business-java-api#100 | `SUBMIT` | 7 | 31 s | `LIST → SEARCH×2 → READ×3 → SUBMIT` |
| jhy/jsoup#2002 | `BUDGET_CALLS` | 60 | 414 s | 未 submit；最后一轮还附带 2 条 `BUDGET_EXHAUSTED` |

## 若忽略停止条件会看到的数字（仅供对照，禁止采用）

到达 `SUBMIT` 的调用次数：`[7]`。单点无法算 p95。jsoup 的观测值是 **≥60**，真实需求未知。

墙钟：submit 会话 31 s；被截断会话 414 s 且仍未结束。

## 工具步明细

本批写入的定位步骤（含耗尽）：

| 步骤 | OK | ERROR | 合计 |
| --- | --- | --- | --- |
| LIST | 1 | 0 | 1 |
| SEARCH | 32 | 0 | 32 |
| READ | 33 | 0 | 33 |
| SUBMIT | 1 | 0 | 1 |
| BUDGET_EXHAUSTED | 2 | 0 | 2 |

ToolError 率：0 / 67 次已执行工具（不含耗尽补响应）。

## 定性观察（给后续图接口设计）

完整到达 `SUBMIT` 的轨迹只有 1 份；另有 1 份用满 60 次的未完成轨迹。下面是实例，不是统计摘要。

### 1. 成功会话：先列目录，再搜，再读，然后提交

`a6d947aa-797e-4ed9-a973-dcda8bd941b1`（whatsapp-business-java-api#100）：

| seq | 工具 | 参数 / 结果 |
| --- | --- | --- |
| 0 | LIST `.` | 9 个条目 |
| 1 | SEARCH `uploadMedia` | 6 命中 |
| 2 | SEARCH `okhttp3` | 15 命中 |
| 3 | READ `pom.xml` | 139 行 / 5284 B，未截断 |
| 4 | READ `.../WhatsappBusinessCloudApi.java` | 119 行 / 5255 B |
| 5 | READ `.../WhatsappApiServiceGenerator.java` | 156 行 / 5212 B |
| 6 | SUBMIT | **只交了 `pom.xml` 一条路径**，accepted |

`read` 之前的探索：1 次 `list` + 2 次 `search`。没有 `ToolError`，也没有 submit 被拒。

值得注意：模型读了两个 Java 实现文件，最终却把 `pom.xml` 当作定位结果提交。文本工具能找到相关文件，但不能约束「提交的是复现所需源码」。图接口若只提高检索精度，解决不了这种「读对了、提交偏了」。

### 2. 被截断会话：很早就锁定一个文件，然后反复读同一文件

`90a54480-9798-470f-ad63-ed7c90a89891`（jsoup#2002）：

- 前两步：`SEARCH removeAttr`（25 命中）→ `SEARCH class Attributes`（2 命中）。**第一次 `read` 之前平均 search 次数：这个会话是 2。**
- 第 3 步起几乎只围着 `src/main/java/org/jsoup/nodes/Attributes.java`：对该文件 `READ` **24 次**。
- 另读 `AttributesTest.java` 4 次、`ElementTest.java` 2 次。
- `SEARCH **/*.java` 23 次、`SEARCH **/Attributes.java` 7 次。
- **从未调用 `submit`。** 第 60 次调用后出现两条 `BUDGET_EXHAUSTED`（`limit=CALLS, used=60, maxCalls=60`），说明最后一轮模型又并行要了至少 2 个调用。

重复自身的起点大约在 seq 6 之后：已经整文件读过 `Attributes.java`（seq 2，400 行、`truncated=true`），仍用不同 `startLine` / `span` 再读 20 多次。典型窗口：

- seq 2：`startLine=1, lines=400, bytes=13070, truncated=true`（命中读上限）
- seq 12 / 33 / 39 / 51：`startLine=1, lines=1, bytes=25`（几乎空读）
- seq 18 / 38 / 42 / 43 / 55：同一窗口 `startLine=48, lines=48, bytes=1768, truncated=true` 连读

搜索侧也在自我重复。同一模式多次出现：

- `modCount`：seq 5 与 seq 53，两次都是 0 命中
- `private void addObject`：seq 17 与 seq 22，都是 1 命中
- 带换行的字面量 `vals[size] = value;\n        size++;`：seq 29、45，都是 0 命中
- 几乎整行注释的 `Object[] vals = new Object[InitialCapacity]; // Genericish...`：seq 28、44、56，都是 1 命中

这更像「读截断 → 猜下一窗口 → 用不含换行语义的正则去对源码」的空转，而不是还在扩大文件集合。图工具若能一次给出带符号的整文件/方法视图，有机会砍掉这 24 次重复 `read`；若只加一种新的 search，模型仍可能围着同一个符号打转。

### 3. ToolError 的实际样貌

本批 **没有** `outcome=ERROR` 的工具步。不是「错误很少」的统计结论，而是样本在第 2 次会话就被 60 截断，还没观察到参数类型错误、路径越界或返回上界拒绝。

两次会话里 `read` 的 `truncated=true` 很常见（读上限 400 行 / 64 KiB），但这是成功截断，不是 ToolError。jsoup 在截断后的反应是再读，而不是换工具或 submit。

### 4. submit 被拒

本批 **0 次** submit 拒绝。唯一一次 submit 被接受（1 条路径）。路径不存在 / 超 12 个 / 超 256 KiB 这三类拒绝，这次没有实例。

### 5. 探索形态对照

| 会话 | 先手 | 第一次 read 前的 search/list | 之后是否扩文件集 | 如何结束 |
| --- | --- | --- | --- | --- |
| whatsapp#100 | LIST 仓库根 | 3 | 读了 3 个不同文件 | 7 步 submit（交的是 pom.xml） |
| jsoup#2002 | SEARCH | 2 | 几乎不再扩，围着 `Attributes.java` | 60 步耗尽，未 submit |

两次会话都是先 search/list 再 read，没有一上来就 submit。差异在「会不会停」：一个 7 步交卷且交偏，一个 60 步仍不交。

## 新常数

| 常数 | 取值 | 依据 |
| --- | --- | --- |
| `MAX_TOOL_CALLS` | **25（不变）** | 有会话触到放宽上限 60，数据右截断；禁止定值 |
| `TOOL_LOOP_WALL_CLOCK` | **5 分钟（不变）** | 同上；被截断会话 6.9 分钟仍未结束，时长分布同样不可用 |

## 已知局限

- 官方测量只有 2 次提交，未跑满 18 次。有效会话 1，低于最低可接受的 12。
- 完整 `SUBMIT` 轨迹不足 3 份，批次 4 只能依赖上面 2 份实例。
- 本批没有 ToolError、也没有 submit 被拒的实例。不能把「本批为 0」读成「生产里也不会有」。
- 测量前的定位循环已能处理 N≤8 的并行调用，并带系统提示词；更早因 N=2 守卫中止的 3 次会话不计入本报告分母。
- 本报告不计算定位命中率，也未读取人类补丁。whatsapp 会话提交 `pom.xml` 是否「定位正确」不在本次裁决范围。

## 建议的下一步

1. **不要用 [7] 或 25 去改常数。** 先处理「单文件空转直到 60」：否则再放宽上限只会得到更长的右截断。
2. 若继续测分布：需要先改变「模型不 submit 就会吃光预算」的局面（提示词、重复调用约束、或图定位），再重跑 60/15 分钟；在那之前放宽到 90 只会更贵，分位数仍然假。
3. 批次 4 的图接口至少要能回答两个已见到的失败：重复 `read` 同一文件的不同窗口，以及「读了实现文件却 submit 了 pom.xml」。
