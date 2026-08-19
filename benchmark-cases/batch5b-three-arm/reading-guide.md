# 怎么读这份 5b 报告

这是导读，**不是**新的评测结果。数字、局限和措辞以同目录 [`evidence-report.md`](evidence-report.md) 为准，本文件不重排、不删局限。

先读原文开头的 Known limitations 与 Protocol，再看各臂数字。

## 必须带着分母读的数

- 三臂 `VALID_REPRODUCTION`：**1/6**（HEURISTIC）、**0/6**（TEXT_TOOLS）、**0/6**（GRAPH_TOOLS）。
- 走到 Docker Replay：**4/18**（Run inventory 里 4 条 `COMPLETED`，14 条在定位或生成阶段终态）。
- 图臂 `expand` 次数：**0**。
- 唯一一次 `VALID_REPRODUCTION` 是启发式臂 `AuthMe-ConfigMe-7bf10c513479`，Run `6d7dd641-b730-45d7-890c-3fcdd3559f42`。它是 **18 次里的 1 次**。

n=6 不支持臂间比较。原文把定位覆盖率、复现率、成本分开写，不合成一个分数。

Calibration 的 3/3 不在本目录；那是 `../task018/` 的 Replay Engine 校准，不是本批 Agent 成绩。
