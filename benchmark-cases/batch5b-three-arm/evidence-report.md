# Three-Arm Locating Evaluation Evidence

## Rerun context

本批是修复流水线缺陷后的重跑，不是按结果重跑。触发提交为 82070df、6efab8b、c177721；verifyAlreadyApplied 的完成证据显式化见 1129a56。036 的产物保留、不作废，与本批并列报告。

## Known limitations

These four limitations were frozen before any evaluation run.

1. 图臂沿边遍历在本队列测不到。先前轨迹中 expand 使用次数为 0。冻结队列六例均为小型单模块普通 Java 库（jsoup 三例、word-wrap、ConfigMe、whatsapp-api），无依赖注入、无 AOP、无事件，没有可供遍历的边。因此本批比较的是图入口工具 find 与文本工具 search 加 list，不是图引导遍历与文本搜索。
1. 文本臂的分页、重复检测、收敛压力与 submit 指引，曾依据本队列中 jsoup#2002 与 whatsapp#100 的轨迹调整。对照因此对文本臂有利。另选一份未经过该文本臂调整的合格队列当时不可行。
1. 每臂仅 6 个二元复现观测；复现率只作描述性报告，不得用于臂间比较。比较放在定位覆盖率（每例可测、成对可比）与成本（连续量）上。
1. 本批在看过 036 的结果之后才跑。任何事后看起来很合理的解读都天然可疑。对冲手段是本协议与预注册判据在第一次正式 Run 之前入库，跑完不得修改。

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
| 5 | `jhy-jsoup-9de27fa7cd82` | false | 0.0000 | N/A | 0 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | false | 0.0000 | 0.0000 | 12 |

- anyHit: 2 / 6
- mean recall: 0.3333 (n=6)
- mean precision: 0.0889 (n=5)
- mean selectedCount: 8 (n=6)

### Reproduction rate

VALID_REPRODUCTION 1 / 6
Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.

### Cost

- generation token median: 39489 (n=5)
- runs that never reached generation: 1
- locating model tokens: none
- locating tool calls: —

## Arm TEXT_TOOLS

### Localization coverage

| # | Case | anyHit | recall | precision | selectedCount |
| --- | --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false | 0.0000 | 0.0000 | 3 |
| 2 | `jhy-jsoup-91b630f86b5c` | true | 1.0000 | 0.2500 | 4 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true | 1.0000 | 1.0000 | 1 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | true | 1.0000 | 0.3333 | 3 |
| 5 | `jhy-jsoup-9de27fa7cd82` | true | 0.5000 | 0.5000 | 2 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | true | 1.0000 | 0.0833 | 12 |

- anyHit: 5 / 6
- mean recall: 0.7500 (n=6)
- mean precision: 0.3611 (n=6)
- mean selectedCount: 4.17 (n=6)

### Reproduction rate

VALID_REPRODUCTION 0 / 6
Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.

### Cost

- generation token median: 44153.50 (n=6)
- runs that never reached generation: 0
- locating model tokens: unknown
- locating tool calls p50 / p95: 10 / 24.25

## Arm GRAPH_TOOLS

### Localization coverage

| # | Case | anyHit | recall | precision | selectedCount |
| --- | --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false | 0.0000 | 0.0000 | 3 |
| 2 | `jhy-jsoup-91b630f86b5c` | true | 1.0000 | 0.5000 | 2 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true | 1.0000 | 0.5000 | 2 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | true | 1.0000 | 0.2000 | 5 |
| 5 | `jhy-jsoup-9de27fa7cd82` | true | 0.5000 | 0.5000 | 2 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | true | 1.0000 | 0.0909 | 11 |

- anyHit: 5 / 6
- mean recall: 0.7500 (n=6)
- mean precision: 0.2985 (n=6)
- mean selectedCount: 4.17 (n=6)

### Reproduction rate

VALID_REPRODUCTION 0 / 6
Denominator: every AGENT_BENCHMARK run on this arm, including locating failure and non-valid replay verdicts.

### Cost

- generation token median: 35184 (n=6)
- runs that never reached generation: 0
- locating model tokens: unknown
- locating tool calls p50 / p95: 13 / 28.25
- expand count: 0
- graph-build duration p50 / cache hits: 1034.50 / 0 / 6

## Paired localization coverage

Same case, three locating origins. Numbers only; this table is not a ranking.

| # | Case | HEURISTIC anyHit / recall / precision / selectedCount | TEXT_TOOLS anyHit / recall / precision / selectedCount | GRAPH_TOOLS anyHit / recall / precision / selectedCount |
| --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | false / 0.0000 / 0.0000 / 12 | false / 0.0000 / 0.0000 / 3 | false / 0.0000 / 0.0000 / 3 |
| 2 | `jhy-jsoup-91b630f86b5c` | true / 1.0000 / 0.1111 / 9 | true / 1.0000 / 0.2500 / 4 | true / 1.0000 / 0.5000 / 2 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | true / 1.0000 / 0.3333 / 3 | true / 1.0000 / 1.0000 / 1 | true / 1.0000 / 0.5000 / 2 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | false / 0.0000 / 0.0000 / 12 | true / 1.0000 / 0.3333 / 3 | true / 1.0000 / 0.2000 / 5 |
| 5 | `jhy-jsoup-9de27fa7cd82` | false / 0.0000 / N/A / 0 | true / 0.5000 / 0.5000 / 2 | true / 0.5000 / 0.5000 / 2 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | false / 0.0000 / 0.0000 / 12 | true / 1.0000 / 0.0833 / 12 | true / 1.0000 / 0.0909 / 11 |

## Paired cost

Same case, three locating origins. Generation tokens are recorded usage, not a bill.

| # | Case | HEURISTIC gen tokens / locating tools | TEXT_TOOLS gen tokens / locating tools | GRAPH_TOOLS gen tokens / locating tools / graph-build ms |
| --- | --- | --- | --- | --- |
| 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | 39489 / — | 45796 / 6 | 27047 / 10 / 577 |
| 2 | `jhy-jsoup-91b630f86b5c` | 26982 / — | 14513 / 11 | 11886 / 11 / 2030 |
| 3 | `davidmoten-word-wrap-e59eedf0bac7` | 29669 / — | 19059 / 5 | 29018 / 7 / 49 |
| 4 | `jhy-jsoup-a96ebc95f9ad` | 79043 / — | 48451 / 9 | 80153 / 26 / 2058 |
| 5 | `jhy-jsoup-9de27fa7cd82` | 0 / — | 42511 / 13 | 41350 / 15 / 1492 |
| 6 | `AuthMe-ConfigMe-7bf10c513479` | 89828 / — | 60617 / 28 | 49257 / 29 / 250 |

## Cross-batch localization coverage

036 (`benchmark-cases/batch5-three-arm`) versus this batch. The locating path was unchanged; a difference of 2 or more anyHit cases on one arm is a stop condition, not a ranking.

| Arm | 036 anyHit | this batch anyHit | delta |
| --- | --- | --- | --- |
| HEURISTIC | 2 / 6 | 2 / 6 | +0 |
| TEXT_TOOLS | 5 / 6 | 5 / 6 | +0 |
| GRAPH_TOOLS | 5 / 6 | 5 / 6 | +0 |

## First-round generation rejections

Counts are first `generation.attempt.rejected` records (attempt_ordinal = 1). Runs with no first-round rejection are omitted from this denominator.

### HEURISTIC

| feedback_summary | Count |
| --- | --- |
| application failure | 1 |
| compilation failed on buggy | 2 |
| target test stable passed on buggy | 1 |
| total | 4 |

### TEXT_TOOLS

| feedback_summary | Count |
| --- | --- |
| application failure | 1 |
| compilation failed on buggy | 2 |
| unsupported git file header | 1 |
| 补丁只改了已有测试方法体，无法确定目标 | 1 |
| total | 5 |

### GRAPH_TOOLS

| feedback_summary | Count |
| --- | --- |
| application failure | 3 |
| compilation failed on buggy | 1 |
| invalid hunk line prefix | 1 |
| total | 5 |

## Preregistered criteria

### `response-truncated-share`

- Measured: 0 / 14 first-round rejections
- Holds: false
- Declared conclusion: 不超过三分之一则不改 token 预算与上下文体积上限。

### `graph-expand-unused`

- Measured: expand count: 0
- Holds: true
- Declared conclusion: 再次确认本队列不提供沿边遍历机会；不得据此断言图没有信息量。

### `reproduction-still-zero`

- Measured: HEURISTIC 1 / 6; TEXT_TOOLS 0 / 6; GRAPH_TOOLS 0 / 6
- Holds: false
- Declared conclusion: 按臂描述 VALID_REPRODUCTION 计数；不得做臂间比较，也不得因此再开一轮重跑。

### `locating-anyhit-drift`

- Measured: HEURISTIC 2/6 vs 2/6 (delta 0); TEXT_TOOLS 5/6 vs 5/6 (delta 0); GRAPH_TOOLS 5/6 vs 5/6 (delta 0)
- Holds: false
- Declared conclusion: 各臂与 036 的 anyHit 差值小于 2 例，视为定位链路未改时的噪音，不构成停止条件。

## Run inventory

| Origin | # | Case | Run | State | Verdict | Failure | Attempts | Tokens (in/out/total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| HEURISTIC | 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | `478329e0-c924-4fe6-b44e-5485345c0203` | FAILED | — | GENERATION_EXHAUSTED | 3 | 17190/22299/39489 |
| HEURISTIC | 2 | `jhy-jsoup-91b630f86b5c` | `e95d999e-090e-4012-a722-4262d18bd678` | COMPLETED | INCONCLUSIVE | — | 1 | 26052/930/26982 |
| HEURISTIC | 3 | `davidmoten-word-wrap-e59eedf0bac7` | `467eed79-e2f3-4649-8afc-6f4bae5e01ec` | FAILED | — | GENERATION_EXHAUSTED | 3 | 27130/2539/29669 |
| HEURISTIC | 4 | `jhy-jsoup-a96ebc95f9ad` | `7d362e29-98ce-43e6-ab19-f8087d2ffde6` | FAILED | — | GENERATION_EXHAUSTED | 3 | 77763/1280/79043 |
| HEURISTIC | 5 | `jhy-jsoup-9de27fa7cd82` | `7ff92d8b-b46c-4a99-99ee-9fea76d0a442` | FAILED | — | LOCATING_NO_CONTEXT | 0 | 0/0/0 |
| HEURISTIC | 6 | `AuthMe-ConfigMe-7bf10c513479` | `6d7dd641-b730-45d7-890c-3fcdd3559f42` | COMPLETED | VALID_REPRODUCTION | — | 3 | 46314/43514/89828 |
| TEXT_TOOLS | 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | `e251abcc-ad2b-4e70-886f-46f46a534a54` | FAILED | — | GENERATION_EXHAUSTED | 3 | 10617/35179/45796 |
| TEXT_TOOLS | 2 | `jhy-jsoup-91b630f86b5c` | `5a0acb6b-337b-4d6d-8e9d-641ba2cf9202` | COMPLETED | INCONCLUSIVE | — | 1 | 14041/472/14513 |
| TEXT_TOOLS | 3 | `davidmoten-word-wrap-e59eedf0bac7` | `3916449b-a403-4f64-b95d-8a320a3e2635` | FAILED | — | GENERATION_EXHAUSTED | 3 | 15831/3228/19059 |
| TEXT_TOOLS | 4 | `jhy-jsoup-a96ebc95f9ad` | `953eb267-6671-4273-9204-02541aa18c8d` | FAILED | — | GENERATION_EXHAUSTED | 3 | 47018/1433/48451 |
| TEXT_TOOLS | 5 | `jhy-jsoup-9de27fa7cd82` | `dabb9e9f-2c21-452e-b6aa-55d654eb8dd6` | FAILED | — | GENERATION_EXHAUSTED | 3 | 39044/3467/42511 |
| TEXT_TOOLS | 6 | `AuthMe-ConfigMe-7bf10c513479` | `d3552471-0f13-4696-94ff-940ab23d4be4` | FAILED | — | GENERATION_EXHAUSTED | 3 | 47516/13101/60617 |
| GRAPH_TOOLS | 1 | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | `5d8f0f6f-1b72-45ff-bf54-344eb57836eb` | FAILED | — | GENERATION_EXHAUSTED | 3 | 10107/16940/27047 |
| GRAPH_TOOLS | 2 | `jhy-jsoup-91b630f86b5c` | `e2656c25-1e09-4b71-8df0-73345e8746bc` | COMPLETED | INCONCLUSIVE | — | 1 | 11500/386/11886 |
| GRAPH_TOOLS | 3 | `davidmoten-word-wrap-e59eedf0bac7` | `3e4f7e3f-aa91-4739-914a-18670dfe1ece` | FAILED | — | GENERATION_EXHAUSTED | 3 | 26549/2469/29018 |
| GRAPH_TOOLS | 4 | `jhy-jsoup-a96ebc95f9ad` | `c18903fe-51e0-4723-945e-8860db90edd0` | FAILED | — | GENERATION_EXHAUSTED | 3 | 78875/1278/80153 |
| GRAPH_TOOLS | 5 | `jhy-jsoup-9de27fa7cd82` | `ad376e8b-44e6-4925-b1bc-b42b609701af` | FAILED | — | GENERATION_EXHAUSTED | 3 | 38960/2390/41350 |
| GRAPH_TOOLS | 6 | `AuthMe-ConfigMe-7bf10c513479` | `1b043fd5-bc63-4a1a-af26-0a55cce4c758` | FAILED | — | GENERATION_EXHAUSTED | 3 | 45951/3306/49257 |

## Evaluation notes

- No evaluation cell was rerun.
- 036 file artifacts in benchmark-cases/batch5-three-arm were not modified.
- Sampling production revision stayed at 302fe81.
- No Docker, environment, or transfer stop condition occurred. One HEURISTIC run ended in LOCATING_NO_CONTEXT; that is a locating outcome, not an unrelated pipeline failure.
- After verifyAlreadyApplied takes caller diagnostics, PatchGate.inspect still writes unknown() inside the method. Production callers are known-trigger inspection only. This batch did not change inspect.
- First-round refusals were compilation, application, and patch policy; RESPONSE_TRUNCATED count is reported under preregistered criteria.
- 041 sampling temporarily prefixed the 036 `case_id` values so `findRunByCase` would not resume those rows. Those prefixes have been removed; the database `case_id` again matches the 036 report. Batch disambiguation now uses the `evaluation_id` column, which is not copied into evidence reports.

