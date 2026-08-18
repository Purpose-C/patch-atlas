# Spring 使用队列冻结记录

按 [`docs/benchmark-spring-cohort-rules.md`](../../docs/benchmark-spring-cohort-rules.md) 对源闸门并集做静态过滤，再按冻结种子哈希顺序动态验证。探测上限 24，目标 6，最低 4。选样只认 buggy revision 的 `pom.xml` 依赖是否使用 Spring；不按修复是否跨组件挑选成员。

## 结果

| 项 | 数值 |
| --- | --- |
| 输入（条件 1 并集） | 93 |
| 静态淘汰 | 92 |
| 动态探测 | 1 |
| 动态合格 | 1 |
| 冻结成员 | 无（1 < 4） |

合格成员不足最低 4 例，冻结停止。不放宽资格条件，不扩大探测预算，不写入 `cohort.json`。预先声明的解释见 [`docs/benchmark-graph-guidance-interpretation.md`](../../docs/benchmark-graph-guidance-interpretation.md)：Java/Spring 生态缺少可按本系统闸门机械复现的公开缺陷数据。

机器可读过程见 `selection-audit.json`。上一冻结队列 `benchmark-cases/task018/` 未改动。

## 淘汰原因（可复算）

- `ISSUE_UNAVAILABLE`（10）：GitBug-Java 命中里没有可用的非 PR Issue 正文。
- `METADATA_INVALID`（82）：Multi-SWE-bench Java 与 SWE-PolyBench Java 记录没有合并提交 SHA，无法判定 Fixed Revision 的第一父提交等于 Buggy Revision。不得用补丁回放冒充该闸门。
- 动态探测 `st-tu-dresden-salespoint-85a764f892aa`：checkout、父提交、触发测试、预热与离线 replay 全部通过，记为 `ELIGIBLE`。它不能单独构成冻结队列。

## 描述性统计（未进入过滤器）

只对动态合格的那 1 例记录，**不**据此增删成员。

| 例 | 模块数 | Spring 依赖 `groupId` | DI 注解 | 事件注解 | AOP 注解 | 修复涉及文件数 |
| --- | --- | --- | --- | --- | --- | --- |
| `st-tu-dresden-salespoint-85a764f892aa` | 1 | `org.springframework.boot`、`org.springframework.experimental` | 有（54 个源文件） | 有（2 个源文件） | 无 | 2 |

注解按 buggy revision 源文件中的简单名统计：`Autowired` / `Inject` / `Component` / `Service` / `Repository` / `Controller` / `RestController` / `Bean` / `Configuration` / `Qualifier` 计为 DI；`EventListener` / `TransactionalEventListener` / `ApplicationListener` 计为事件；`Aspect` / `Around` / `Before` / `After*` / `Pointcut` 计为 AOP。
