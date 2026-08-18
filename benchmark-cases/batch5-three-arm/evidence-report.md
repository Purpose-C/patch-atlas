# Three-Arm Locating Evaluation Evidence

## Known limitations

These three limitations were frozen before any evaluation run.

1. 图臂沿边遍历在本队列测不到。先前轨迹中 expand 使用次数为 0。冻结队列六例均为小型单模块普通 Java 库（jsoup 三例、word-wrap、ConfigMe、whatsapp-api），无依赖注入、无 AOP、无事件，没有可供遍历的边。因此本批比较的是图入口工具 find 与文本工具 search 加 list，不是图引导遍历与文本搜索。
1. 文本臂的分页、重复检测、收敛压力与 submit 指引，曾依据本队列中 jsoup#2002 与 whatsapp#100 的轨迹调整。对照因此对文本臂有利。另选一份未经过该文本臂调整的合格队列当时不可行。
1. 每臂仅 6 个二元复现观测；复现率只作描述性报告，不得用于臂间比较。比较放在定位覆盖率（每例可测、成对可比）与成本（连续量）上。

## Protocol

- Dataset: GitBug-Java @ fe986fb7919be62c2a6f611ee16659e849646798
- Cohort SHA-256: `7bc71bb5b7fee0182f8e423386d208aa9ba29c61eabf84962a1a446dee297f53`
- Model provider: ollama
- Model: glm-5.2
- Endpoint: https://ollama.com/v1
- Failure handling: Patch Gate 的策略性拒绝可修正并进入下一次 Generation Attempt（三轮上限不变）；仅 WORKSPACE_UNSAFE 立即终态。该规则在任何正式模型调用之前确定。

Other protocol limitations:

- 该 model 标识无日期版本锚点，供应商可在不改名的前提下更换权重，因此本批次结果不保证长期可复现。
- 该模型的训练数据构成与知识截止时间未公开，无法论证其对 GitBug-Java 案例的污染边界。
- 最大 completion token 为 32768，与生成器工厂常数一致；该上限在本批次任何正式调用之前已固定。
- 正式运行依赖本机挂载的 GitBug-Java 数据源读取 Issue 正文与缺陷修订；仅凭公开仓库与本仓库冻结元数据无法复现。

## Arm HEURISTIC

### Localization coverage

| # | Case | anyHit | recall | precision | selectedCount |
| --- | --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false | 0.0000 | 0.0000 | 12 |
| 2 | `jhy-jsoup-91b630f86b5c` | true | 1.0000 | 0.1111 | 9 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true | 1.0000 | 0.3333 | 3 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | false | 0.0000 | 0.0000 | 12 |
| 5 | `jhy-jsoup-9de27fa7cd82` | false | 0.0000 | 0.0000 | 0 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | false | 0.0000 | 0.0000 | 12 |

- anyHit: 2 / 6
- mean recall: 0.3333
- mean precision: 0.0741
- mean selectedCount: 8

### Reproduction rate

VALID_REPRODUCTION 0 / 6
Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.

### Cost

- generation token median: 39297.50
- locating model tokens: none
- locating tool calls: —

## Arm TEXT_TOOLS

### Localization coverage

| # | Case | anyHit | recall | precision | selectedCount |
| --- | --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false | 0.0000 | 0.0000 | 5 |
| 2 | `jhy-jsoup-91b630f86b5c` | true | 1.0000 | 0.2000 | 5 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true | 1.0000 | 1.0000 | 1 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | true | 1.0000 | 0.5000 | 2 |
| 5 | `jhy-jsoup-9de27fa7cd82` | true | 0.5000 | 0.3333 | 3 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | true | 1.0000 | 0.0833 | 12 |

- anyHit: 5 / 6
- mean recall: 0.7500
- mean precision: 0.3528
- mean selectedCount: 4.67

### Reproduction rate

VALID_REPRODUCTION 0 / 6
Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.

### Cost

- generation token median: 42256
- locating model tokens: unknown
- locating tool calls p50 / p95: 11 / 28.75

## Arm GRAPH_TOOLS

### Localization coverage

| # | Case | anyHit | recall | precision | selectedCount |
| --- | --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false | 0.0000 | 0.0000 | 5 |
| 2 | `jhy-jsoup-91b630f86b5c` | true | 1.0000 | 0.5000 | 2 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true | 1.0000 | 0.5000 | 2 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | true | 1.0000 | 0.5000 | 2 |
| 5 | `jhy-jsoup-9de27fa7cd82` | true | 0.5000 | 0.5000 | 2 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | true | 1.0000 | 0.1111 | 9 |

- anyHit: 5 / 6
- mean recall: 0.7500
- mean precision: 0.3519
- mean selectedCount: 3.67

### Reproduction rate

VALID_REPRODUCTION 0 / 6
Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.

### Cost

- generation token median: 33214
- locating model tokens: unknown
- locating tool calls p50 / p95: 14 / 26.25
- expand count: 0
- graph-build duration p50 / cache hits: 472.50 / 0 / 6

## Paired localization coverage

Same case, three locating origins. Numbers only; this table is not a ranking.

| # | Case | HEURISTIC anyHit / recall / precision / selectedCount | TEXT_TOOLS anyHit / recall / precision / selectedCount | GRAPH_TOOLS anyHit / recall / precision / selectedCount |
| --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false / 0.0000 / 0.0000 / 12 | false / 0.0000 / 0.0000 / 5 | false / 0.0000 / 0.0000 / 5 |
| 2 | `jhy-jsoup-91b630f86b5c` | true / 1.0000 / 0.1111 / 9 | true / 1.0000 / 0.2000 / 5 | true / 1.0000 / 0.5000 / 2 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true / 1.0000 / 0.3333 / 3 | true / 1.0000 / 1.0000 / 1 | true / 1.0000 / 0.5000 / 2 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | false / 0.0000 / 0.0000 / 12 | true / 1.0000 / 0.5000 / 2 | true / 1.0000 / 0.5000 / 2 |
| 5 | `jhy-jsoup-9de27fa7cd82` | false / 0.0000 / 0.0000 / 0 | true / 0.5000 / 0.3333 / 3 | true / 0.5000 / 0.5000 / 2 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | false / 0.0000 / 0.0000 / 12 | true / 1.0000 / 0.0833 / 12 | true / 1.0000 / 0.1111 / 9 |

## Paired cost

Same case, three locating origins. Generation tokens are recorded usage, not a bill.

| # | Case | HEURISTIC gen tokens / locating tools | TEXT_TOOLS gen tokens / locating tools | GRAPH_TOOLS gen tokens / locating tools / graph-build ms |
| --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | 20090 / — | 27699 / 12 | 27325 / 21 / 328 |
| 2 | `jhy-jsoup-91b630f86b5c` | 83729 / — | 51393 / 30 | 11983 / 8 / 763 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | 29525 / — | 16627 / 3 | 27359 / 5 / 37 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | 78950 / — | 38797 / 6 | 39069 / 7 / 711 |
| 5 | `jhy-jsoup-9de27fa7cd82` | 0 / — | 57408 / 10 | 44815 / 20 / 617 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | 49070 / — | 45715 / 25 | 39671 / 28 / 283 |

## First-round generation rejections

Counts are first `generation.attempt.rejected` records (attempt_ordinal = 1). Runs with no first-round rejection are omitted from this denominator.

### HEURISTIC

| feedback_summary | Count |
| --- | --- |
| compilation failed on buggy | 1 |
| hunk new count mismatch | 4 |
| total | 5 |

### TEXT_TOOLS

| feedback_summary | Count |
| --- | --- |
| application failure | 2 |
| hunk new count mismatch | 2 |
| 补丁只改了已有测试方法体，无法确定目标 | 2 |
| total | 6 |

### GRAPH_TOOLS

| feedback_summary | Count |
| --- | --- |
| application failure | 1 |
| hunk new count mismatch | 4 |
| total | 5 |

## Preregistered criteria

### `hunk-count-mismatch`

- Measured: 10 / 16 first-round rejections
- Holds: true
- Declared conclusion: 另开工作把 hunk 计数改为从正文重算，并以 finish_reason 等于 length 作为截断的独立判据。

### `graph-expand-unused`

- Measured: expand count: 0
- Holds: true
- Declared conclusion: 确认本队列不提供沿边遍历机会；不得据此断言图没有信息量。

## Run inventory

| Origin | # | Case | Run | State | Verdict | Failure | Attempts | Tokens (in/out/total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HEURISTIC | 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | `ff8a01cf-7eeb-428f-850c-c92072f8718c` | FAILED | — | GENERATION_EXHAUSTED | 3 | 16400/3690/20090 |
| HEURISTIC | 2 | `jhy-jsoup-91b630f86b5c` | `fb7593e8-3b9e-46b5-a181-716aae32f07f` | FAILED | — | GENERATION_EXHAUSTED | 3 | 78188/5541/83729 |
| HEURISTIC | 3 | `davidmoten-word-wrap-e59eedf0bac7` | `4cd186f8-88da-4d25-ac0a-5f6a26ff0886` | FAILED | — | GENERATION_EXHAUSTED | 3 | 27114/2411/29525 |
| HEURISTIC | 4 | `jhy-jsoup-a96ebc95f9ad` | `ba7e69b1-26f5-46c9-9eab-f5b9c3463bd7` | FAILED | — | GENERATION_EXHAUSTED | 3 | 77534/1416/78950 |
| HEURISTIC | 5 | `jhy-jsoup-9de27fa7cd82` | `0892e004-64c4-4891-89db-f5e8294ae273` | FAILED | — | LOCATING_NO_CONTEXT | 0 | 0/0/0 |
| HEURISTIC | 6 | `AuthMe-ConfigMe-7bf10c513479` | `a479a90e-bc4d-4aa2-b975-f2b85327ae32` | FAILED | — | GENERATION_EXHAUSTED | 3 | 45467/3603/49070 |
| TEXT_TOOLS | 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | `3dd86ba1-b29f-42cc-a586-b7de30256a09` | FAILED | — | GENERATION_EXHAUSTED | 3 | 24689/3010/27699 |
| TEXT_TOOLS | 2 | `jhy-jsoup-91b630f86b5c` | `0796da4a-b7fa-4e3d-8464-e578f1077200` | FAILED | — | GENERATION_EXHAUSTED | 3 | 48854/2539/51393 |
| TEXT_TOOLS | 3 | `davidmoten-word-wrap-e59eedf0bac7` | `ca8b280c-a179-4473-82ae-b8e72e51d2c1` | FAILED | — | GENERATION_EXHAUSTED | 3 | 15119/1508/16627 |
| TEXT_TOOLS | 4 | `jhy-jsoup-a96ebc95f9ad` | `346d3698-f5d2-4212-81b3-da3a8c8afa5e` | FAILED | — | GENERATION_EXHAUSTED | 3 | 37703/1094/38797 |
| TEXT_TOOLS | 5 | `jhy-jsoup-9de27fa7cd82` | `4e04ed91-ad2e-42e8-9a7c-94e033f647e6` | FAILED | — | GENERATION_EXHAUSTED | 3 | 54725/2683/57408 |
| TEXT_TOOLS | 6 | `AuthMe-ConfigMe-7bf10c513479` | `b657735b-7eb7-4826-80c3-d06506bc6270` | FAILED | — | GENERATION_EXHAUSTED | 3 | 43814/1901/45715 |
| GRAPH_TOOLS | 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | `0fdf9cdc-1558-4012-842c-10b7a58b4bd5` | FAILED | — | GENERATION_EXHAUSTED | 3 | 24680/2645/27325 |
| GRAPH_TOOLS | 2 | `jhy-jsoup-91b630f86b5c` | `b4708e5c-cf6d-4c9e-8f52-022217666eb1` | COMPLETED | INCONCLUSIVE | — | 1 | 11500/483/11983 |
| GRAPH_TOOLS | 3 | `davidmoten-word-wrap-e59eedf0bac7` | `bbe80f3b-141d-4b72-91ce-f2763c5d0356` | FAILED | — | GENERATION_EXHAUSTED | 3 | 25922/1437/27359 |
| GRAPH_TOOLS | 4 | `jhy-jsoup-a96ebc95f9ad` | `416e5050-381e-42f7-8ed7-c40d12fd7f5f` | FAILED | — | GENERATION_EXHAUSTED | 3 | 38006/1063/39069 |
| GRAPH_TOOLS | 5 | `jhy-jsoup-9de27fa7cd82` | `8972ef07-8f8d-4b19-a31c-99be827abe3e` | FAILED | — | GENERATION_EXHAUSTED | 3 | 38390/6425/44815 |
| GRAPH_TOOLS | 6 | `AuthMe-ConfigMe-7bf10c513479` | `9d3b16f1-df5a-4b2a-a59f-085edcfd4bff` | FAILED | — | GENERATION_EXHAUSTED | 3 | 36503/3168/39671 |

## Evaluation notes

- No evaluation cell was rerun.
- Stale diagnostic leases were marked failed so they would not be claimed ahead of this queue.
- GRAPH_BUILD locating-trace kind was applied to the evaluation database before the graph arm.
- No Docker, environment, or transfer stop condition occurred. One HEURISTIC run ended in LOCATING_NO_CONTEXT; that is a locating outcome, not an unrelated pipeline failure.

