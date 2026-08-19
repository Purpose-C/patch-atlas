# Spring 案例研究：机械资格判定

本目录记录一次案例研究的候选判定，**不是**冻结队列，也**不支持**统计结论。

机器可读结果见 [`candidates.json`](candidates.json)。跑前协议见 [`protocol.json`](protocol.json)。输入是已冻结的源闸门并集 [`../spring-source-gate/scan.json`](../spring-source-gate/scan.json) 与静态/动态审计 [`../spring-v1/selection-audit.json`](../spring-v1/selection-audit.json)。未打开新的数据源。

操作者已确认 `selectedCaseId=st-tu-dresden-salespoint-85a764f892aa`。该确认发生在任何案例研究 Run 之前。

## 机械条件（缺一不可）

| 编号 | 条件 |
| --- | --- |
| C1 | Java + Maven，且能在现有 Docker 沙箱中运行 |
| C2 | 存在 buggy / fixed 两个 revision，且 fixed 的第一父提交等于 buggy |
| C3 | 有对应的 Issue 正文（Issue2Test 的输入） |
| C4 | 生产依赖里有 Spring：`pom.xml` 的 `<dependency>` `groupId` 含 `org.springframework`（不按仓库名） |
| C5 | 人类修复改动了至少一份非测试的 `.java` |

源闸门并集的 93 条已经全部满足 C4。其余条件按上表逐条判定；缺一即不合格。

## 未使用的过滤器

下列信号**没有**进入本判定，也不得当作挑选理由：

- 缺陷是否跨越 DI / AOP / 事件图边
- 冻结队列的最低 4 例
- 同变更必须改测试
- 许可证是否解析
- PatchGate 是否兼容（作为选样过滤器）
- 冻结规则里的诊断案例黑名单本身
- 任何排序、偏好或倾向性挑选

## 结果摘要

| 项 | 数值 |
| --- | --- |
| 输入（源闸门并集） | 93 |
| 源闸门合格 | 1 |
| 源闸门不合格 | 92 |
| 其中因 C2 不合格 | 82 |
| 其中因 C3 不合格 | 10 |
| 额外清单（非并集成员）不合格 | 1 |
| 已确认选定 | `st-tu-dresden-salespoint-85a764f892aa` |

## 合格（源闸门）

### `st-tu-dresden-salespoint-85a764f892aa`

| 条件 | 结果 | 依据 |
| --- | --- | --- |
| C1 | 通过 | GitBug-Java Maven 案例；`pom.xml` 的 `java.version` 为 17；动态探测的预热与离线 replay 阶段均为 `PASSED` |
| C2 | 通过 | `selection-audit.json` 探测阶段 `parent_revision=PASSED` |
| C3 | 通过 | 未被静态过滤器标为 `ISSUE_UNAVAILABLE`；该过滤器要求存在非 PR、标题与正文均非空的 Issue |
| C4 | 通过 | `org.springframework.boot`、`org.springframework.experimental` |
| C5 | 通过 | buggy / fixed 树字节对比（排除 `src/test` 与 `target/`）得到 `src/main/java/org/salespointframework/accountancy/PersistentAccountancy.java` |

仓库：`st-tu-dresden/salespoint`。buggy revision：`e9c2fe5efee8ad76bf73738f46f911c18eb078b8`。

## 不合格：C3（无可用 Issue 正文，10）

GitBug-Java 命中中没有可用的非 PR Issue 正文：

- `IBM-JSONata4Java-1485a41ccf50`
- `jitterted-ensembler-0963194c9ebc`
- `jitterted-ensembler-0e512e5b3677`
- `jitterted-ensembler-14a39138787f`
- `jitterted-ensembler-60ec3bf0273b`
- `jitterted-ensembler-6207c9d46460`
- `jitterted-ensembler-c9faf3fba524`
- `spring-projects-spring-guice-ce15b8e5802a`
- `spring-projects-spring-retry-c89b9516d976`
- `spring-projects-spring-retry-e6091f790c64`

仓库名含 spring 不构成 C4 的额外加分，也不构成放宽 C3 的理由。

## 不合格：C2（无法判定父提交，82）

Multi-SWE-bench Java 与 SWE-PolyBench Java 的记录没有合并提交 SHA，因此无法判定 fixed 的第一父提交等于 buggy。不得用补丁回放冒充该检查。完整 82 条 `caseId` 见 `candidates.json` 中 `failedCondition=C2` 的成员。

## 额外清单（不在源闸门并集）

### `scof-1326`

来源：已有校准记录 [`../spring-cloud-openfeign-1326.md`](../spring-cloud-openfeign-1326.md)，不是源闸门并集成员。

| 条件 | 结果 | 依据 |
| --- | --- | --- |
| C1 | 不通过 | 三臂 `AGENT_BENCHMARK` 回放使用 `MavenNetworkMode.OFFLINE`。该案例父 POM 为 `spring-cloud-build:4.3.3-SNAPSHOT`，校准记录需 `ONLINE` 才能解析。沿现有三臂路径运行将改动 runner |
| C2 | 通过 | 校准记录：fixed 第一父提交等于 buggy |
| C3 | 通过 | 校准记录含 GitHub issue 1326 正文 |
| C4 | 通过 | 生产模块 `org.springframework.cloud.openfeign`（坐标身份，不是仓库名判定） |
| C5 | 通过 | `spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign/support/SpringMvcContract.java` |

## 确认

操作者已确认选定 `st-tu-dresden-salespoint-85a764f892aa`。合格名单在确认时只有这一条源闸门成员；额外清单中的 `scof-1326` 因 C1 不合格。

## 证据强度不对称（跑前登记，跑完不得修改）

选定案例是 Spring 框架本身，修复点 `PersistentAccountancy.java` 位于 DI 密集区，对图臂有利。这**不**改变选样合法性：它是五条机械条件筛出的唯一合格成员，`filtersNotApplied` 已记录未按「是否跨越 DI / AOP / 事件边」筛选。解读时必须带上本表：

| 观测 | 强度 | 含义 |
| --- | --- | --- |
| `expand` 被使用 | 弱 | 有利单例，不可外推 |
| `expand` 仍为 0 | 强 | 最有利条件下都不用，原因锁死在工具设计或提示词 |
