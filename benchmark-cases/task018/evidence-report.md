# Benchmark Evidence Report

- Dataset: GitBug-Java @ fe986fb7919be62c2a6f611ee16659e849646798
- Cohort SHA-256: `7bc71bb5b7fee0182f8e423386d208aa9ba29c61eabf84962a1a446dee297f53`
- Selector: task018-v1
- Seed: `cc279be0a2cfe38a327d24d828a49b8425ae37e7`
- Model provider: agnes
- Model: agnes-2.5-flash
- Endpoint: https://apihub.agnes-ai.com/v1
- Protocol family: OpenAI-compatible

## Protocol Limitations

- 该 model 标识无日期版本锚点，供应商可在不改名的前提下更换权重，因此本批次结果不保证长期可复现。
- 该模型的训练数据构成与知识截止时间未公开，无法论证其对 GitBug-Java 案例的污染边界。
- 最大 completion token 为 32768。原定上限在 DIAGNOSTIC 演练中被证明不足：该模型将 reasoning token 计入 completion 额度，一次修正轮实测 reasoning 占用 88%（3231/3666）。过低的上限会导致推理未完成即截断、text_tokens 归零、产出空内容——那是测量误差而非能力信号。本调整在任何正式 Agent 调用之前依据演练与探针证据确定。探针确认端点接受 32768。
- 正式批次依赖本机挂载的 GitBug-Java 数据源读取已知触发测试补丁与 Issue 正文；仅凭公开仓库与本仓库冻结元数据无法复现校准运行。

## Failure Handling

Patch Gate 的策略性拒绝可修正并进入下一次 Generation Attempt（三轮上限不变）；仅 WORKSPACE_UNSAFE 立即终态。该规则在任何正式模型调用之前确定。

## Summary

| Metric | Value |
| --- | --- |
| Calibration passed | 3 / 3 |
| Agent VALID_REPRODUCTION | 0 / 3 |
| Agent non-reproduction / failure | 3 / 3 |

## Per-Case Results

| # | Role | Case | State | Verdict | Failure | Attempts | Model | Tokens (in/out/total) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | CALIBRATION | `Bindambc-whatsapp-business-java-api-362caf5eb33c` | COMPLETED | VALID_REPRODUCTION | — | 0 | — | 0/0/0 |
| 2 | CALIBRATION | `jhy-jsoup-91b630f86b5c` | COMPLETED | VALID_REPRODUCTION | — | 0 | — | 0/0/0 |
| 3 | CALIBRATION | `davidmoten-word-wrap-e59eedf0bac7` | COMPLETED | VALID_REPRODUCTION | — | 0 | — | 0/0/0 |
| 4 | AGENT_BENCHMARK | `jhy-jsoup-a96ebc95f9ad` | FAILED | — | GENERATION_EXHAUSTED | 3 | agnes/agnes-2.5-flash | 86125/3189/89314 |
| 5 | AGENT_BENCHMARK | `jhy-jsoup-9de27fa7cd82` | FAILED | — | GENERATION_EXHAUSTED | 3 | agnes/agnes-2.5-flash | 3082/2329/5411 |
| 6 | AGENT_BENCHMARK | `AuthMe-ConfigMe-7bf10c513479` | FAILED | — | GENERATION_EXHAUSTED | 3 | agnes/agnes-2.5-flash | 50482/1570/52052 |

## Estimated Model Cost

Estimated Model Cost is unavailable. Provider `agnes` has no Pricing Reference in this batch. Missing cost must not be shown as a zero bill. Token columns above are recorded usage, not a bill.

## Interpretation Limits

Failed Candidate Drafts are not persisted (ADR-002). The system records token counts and Patch Gate summaries, but not the rejected patch text. A generation failure therefore cannot be read as proof that the test design itself was wrong.

## Generation Rejection Distribution

Counts are computed from structured `generation.attempt.rejected` records.

| feedback_summary | Count |
| --- | --- |
| trailing non-patch text | 6 |
| hunk new count mismatch | 3 |
| total | 9 |

These two rejection classes are not equivalent. `hunk new count mismatch` is a safety-relevant integrity check: a hunk whose declared line count does not match its body can corrupt a file and must be rejected. `trailing non-patch text` is a strictness choice, not a safety property: a parser can stop after the last hunk. This Gate therefore measures whether a model can emit a patch that satisfies this Gate, including a non-safety cleanliness rule. A different Gate tolerance would change the numbers. This batch does not change the Gate; the observation belongs to follow-up work.

## Agent Outcome Layer

该模型在各轮中均产出了实质性补丁内容，但始终未满足统一 diff 的格式契约；失败发生在输出契约层，本次证据不足以判断其测试设计能力。

