# Spring 案例源闸门

图引导定位要对照的是「项目是否使用 Spring」，不是「这次修复是否跨组件」。因此在冻结新队列之前，先用同一条机械规则扫描候选数据源：取 buggy revision 的全部 `pom.xml`（含模块），`<dependency>` 的 `groupId` 含 `org.springframework` 则计为使用 Spring。`<parent>` 与 `<plugin>` 坐标不计。不读取仓库名、Issue 正文、测试补丁或修复补丁。

闸门：条件 1 命中 **少于 12** 则停止，不为凑数放宽规则。按其余资格约三分之一通过率，12 个命中才可能留下 4 例可冻结成员。

机器可读扫描结果见 [`benchmark-cases/spring-source-gate/scan.json`](../benchmark-cases/spring-source-gate/scan.json)。判定实现与夹具在默认测试套件中，见 `SpringDependencyPresence`。

## 数据源与许可证

按声明顺序扫描。先到先用：单一数据源不足 12 再打开下一个。许可证允许使用后才扫描 `pom.xml`。

| 数据源 | 许可证（数据集） | 候选 | 扫描成功 | Spring 命中 | 结论 |
| --- | --- | --- | --- | --- | --- |
| GitBug-Java（`fe986fb7919be62c2a6f611ee16659e849646798`） | MIT；案例源码仍受原仓库许可证约束 | 199（其中 194 曾进入上一冻结输入） | 199 | 11（上一输入内 10） | 低于 12，打开下一源 |
| Multi-SWE-bench Java | 数据集 CC0，ByteDance 仍可能主张其中知识产权；案例源码受原仓库许可证约束。评测脚手架 Apache-2.0 | 128 | 128 | 14 | 达到 12 |
| SWE-PolyBench Java | MIT；案例源码受原仓库许可证约束 | 165 | 165 | 70 | 继续计入并集，不回头用名字筛选 |
| TDD-Bench-Java | Apache-2.0；案例源码受原仓库许可证约束 | 250 | 未重复扫描 | （父集合已覆盖） | `id_list.txt` 的 250 个 ID 全部出现在 Multi-SWE-bench Java 或 SWE-PolyBench Java 中 |

可重复性边界与上一冻结队列相同：本系统用公开 HTTPS 克隆、固定 revision、Maven 与受限 Docker 执行，**不**安装各数据集官方的完整离线镜像包。采用前只核数据集许可证与「能否用本系统的 Maven 闸门再验证」；后者属于后续动态资格，不在本闸门放宽或提前代判。

`spring-cloud-openfeign` 诊断案例的父 POM 为 SNAPSHOT，不得进入冻结队列。它不在上述扫描命中集合中。

## 为什么不能靠名字

GitBug-Java 里案例 ID 含 `spring` 的只有 3 个。依赖扫描得到 11 个，多出来的包括 `jitterted/ensembler`、`st-tu-dresden/salespoint`、`IBM/JSONata4Java` 等：仓库名不含 spring，但 `pom.xml` 里有 `org.springframework*` 依赖。

## 并集

按 `(repository, buggyRevision)` 去重后，条件 1 命中 **93** 棵 buggy 树（跨源实例记录 95 条，其中 Multi-SWE-bench 与 SWE-PolyBench 有 1 条 Apache Dubbo 基线重叠，SWE-PolyBench 内部另有 1 对同基线实例）。闸门阈值 12，**通过**。`scan.json` 同时记录 `springPresentInstanceCount`（95）与 `unionSpringPresentCount`（93）。

后续冻结只以本扫描的条件 1 命中为输入，再叠加元数据、父提交校验、Java 版本、许可证、动态 fail→pass 与 20 分钟预算。不得按修复是否跨越 DI / 事件 / AOP 边增删成员。

## 扫描未使用的信号

`scan.json` 的 `inputsNotInspected` 列出了明确未读的字段。每条案例记录只有 `pomCount` 与命中的 `groupId` 列表，没有测试补丁、修复补丁或 Issue 正文。
