# 怎么读这份 Spring 案例研究报告

这是导读，**不是**新的评测结果。数字、局限和措辞以同目录 [`evidence-report.md`](evidence-report.md) 与跑前 [`protocol.json`](protocol.json) 为准。

## 必须带着分母读的数

- n=1，案例研究，不支持统计结论，不得用于臂间比较。
- 三臂终态：**0/3**，全部 `GENERATION_EXHAUSTED`。
- 图臂 `expand` 调用次数：**0**。没有被展开的实体、边类型或置信度；「边是否进入 submit」不适用。
- 成功标准不是 `VALID_REPRODUCTION`。轨迹已经回答 expand 用没用。

原文写明：**原因不再是队列性质。** 按跑前协议，有利条件下仍为 0 属于强证据，锁在工具设计或定位提示词。不能当成图引导定位已经成立。

036 与 5b 的 `expand` 次数也是 0，见 `../batch5-three-arm/evidence-report.md` 与 `../batch5b-three-arm/evidence-report.md`。
