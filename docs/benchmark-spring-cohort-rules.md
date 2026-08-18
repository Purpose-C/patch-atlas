# Spring 使用队列的冻结规则

这些规则在动态探测之前定稿，由 `SpringCohortFreezeRules` 与 `FrozenCohortSelector` 执行。脚本按序过滤；模型不挑选成员。上一冻结队列的选样器构造保持原口径（不要求 Spring、最多 18 次探测），本规则只作用于 `spring-v1`。

机器可读常量见源码；条件 1 的扫描记录见 [`benchmark-cases/spring-source-gate/scan.json`](../benchmark-cases/spring-source-gate/scan.json)。对照实验的解释表见 [`benchmark-graph-guidance-interpretation.md`](benchmark-graph-guidance-interpretation.md)。

## 输入

输入是源闸门条件 1 的命中集合：buggy revision 全部 `pom.xml` 中，至少一条 `<dependency>` 的 `groupId` 含 `org.springframework`。不读仓库名、Issue 正文、测试补丁或修复补丁。`<parent>` 与 `<plugin>` 坐标不计。

排序身份 `ranking_revision` 是上述扫描里三个数据源 `instanceIdsSha256`（顺序：GitBug-Java、Multi-SWE-bench Java、SWE-PolyBench Java）按固定前置串做 SHA-256 后取前 40 位十六进制。种子与上一队列相同，便于复算哈希排序，但候选集合不同。

## 静态过滤（声明的优先级，命中即停）

1. 元数据无效
2. 非 Maven
3. 不受支持的 Java（需 17 或 21）
4. SNAPSHOT 依赖
5. Issue 不可用
6. 同变更未改测试
7. 许可证未解析
8. 补丁闸门不兼容
9. 诊断案例：`caseId` 含 `spring-cloud-openfeign` 或 `scof-1326`（父 POM 为 SNAPSHOT，不得进入冻结队列）
10. 条件 1 未命中（防御：输入本应已是命中集合）

动态资格另检：Fixed Revision 的第一父提交等于 Buggy Revision；再按固定顺序做 checkout、触发测试、预热与离线 replay。每例最多 20 分钟。

## 探测预算

静态合格后按 `SHA-256(ranking_revision + "\n" + caseId + "\n" + seed)` 排序，严格按序探测。最多 24 例，目标 6 例，最低 4 例。达到 6 即停；不足 4 则停止并报告，不放宽条件、不扩大预算。冻结后不得替换、增删或重排。
