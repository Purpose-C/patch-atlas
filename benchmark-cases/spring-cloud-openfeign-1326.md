# Calibration Case:spring-cloud-openfeign #1326

> **类型**:Calibration Case —— 用已知触发测试校准 Replay Engine,**不是** Issue2Test 成功证据(触发测试是人类维护者写的)。
> 本文件只存元数据,**不保存第三方源码副本**。第三方仓库只读克隆到 gitignored 的 `samples/`,用完即弃。

## Generator Context(可进入定位/生成)

```text
caseId:            scof-1326
repositoryUrl:     https://github.com/spring-cloud/spring-cloud-openfeign
license:           Apache-2.0
issueUrl:          https://github.com/spring-cloud/spring-cloud-openfeign/issues/1326
issueText:         带单个 URI 类型参数的 @GetMapping 方法在启动时被 SpringMvcContract
                   误判为"存在未标注参数"并打印警告日志。
buggyRevision:     3f6cd2eb9b5a9675a3b5fd0a0987ad8cfc3e8398
modulePath:        spring-cloud-openfeign-core
javaVersion:       17
```

## Oracle Data(只进验证器,绝不进生成上下文 —— AD-006 预言机隔离)

```text
fixedRevision:     a91d8f565ed3682b9bc363f9f36745d30957c09d   # buggyRevision 的子提交(squash 单父,SHA 无歧义)
humanFixFiles:     spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign/support/SpringMvcContract.java
knownTriggerTest:  spring-cloud-openfeign-core/src/test/java/org/springframework/cloud/openfeign/support/SpringMvcContractTests.java#getWithSingleUriParameterShouldNotWarn
                   # 仅存在于 fixedRevision(fix 新增);buggy 侧需覆盖后再跑
expectedFailure:   buggy 上断言失败(误报 "OpenFeign Warning");fixed 上通过。
```

## 复现流程(因触发测试是 fix 新增,buggy 里不存在)

```bash
REPO=samples/spring-cloud-openfeign
TEST='SpringMvcContractTests#getWithSingleUriParameterShouldNotWarn'
TESTFILE=spring-cloud-openfeign-core/src/test/java/org/springframework/cloud/openfeign/support/SpringMvcContractTests.java
CMD="./mvnw -B -pl spring-cloud-openfeign-core -am -Dtest=$TEST test"

# buggy 侧:buggy 代码 + 覆盖 fixed 的触发测试 → 期望 ASSERTION_FAILURE
git -C $REPO checkout -q 3f6cd2eb9b5a9675a3b5fd0a0987ad8cfc3e8398
git -C $REPO checkout a91d8f565ed3682b9bc363f9f36745d30957c09d -- $TESTFILE
(cd $REPO && $CMD)   # 期望 Failures: 1

# fixed 侧:期望 PASS
git -C $REPO checkout -q a91d8f565ed3682b9bc363f9f36745d30957c09d
(cd $REPO && $CMD)   # 期望 Failures: 0
```

## 本地验证证据(2026-07-22,真实执行,非网页推断)

- fixed 侧:`Tests run: 1, Failures: 0, Errors: 0` → **PASS**(构建 SUCCESS,约 5 min)
- buggy 侧(覆盖触发测试):`Tests run: 1, Failures: 1, Errors: 0` → **ASSERTION_FAILURE**
- 判定:**`VALID_REPRODUCTION`** ✓
- git 事实:fixed^ == buggy(单父);同一 PR #1327 改主代码 `SpringMvcContract.java`(+19)并新增测试(+69);标题 `...Fixes #1326`。

## Docker 侧验证证据(2026-07-22,真实执行,非网页推断)

> 容器加固与 `fixtures/off-by-one/run-replay.sh` 保持一致:非 root(`--user $(id -u):$(id -g)`)、
> `--cap-drop=ALL --security-opt=no-new-privileges`、`--cpus=2 --memory=2g --pids-limit=512`、
> `-e HOME=/tmp`、`-Dmaven.repo.local=/tmp/repo`,只挂载临时工作区(`samples/`,gitignored),
> 不挂主机 Home / Docker Socket。镜像 `maven:3.9-eclipse-temurin-17`。因需解析
> `spring-cloud-build:4.3.3-SNAPSHOT`,本次执行显式保留网络并记录为 `ONLINE`。

- buggy 侧(容器内,覆盖触发测试后):`Tests run: 1, Failures: 1, Errors: 0` → **BUILD FAILURE**(断言失败,非编译/环境/超时错误),总耗时 10:24 min(含冷 `/tmp/repo` 依赖下载)。
- fixed 侧(容器内):`Tests run: 1, Failures: 0, Errors: 0` → **BUILD SUCCESS**,总耗时 9:41 min。
- 判定:与 host 侧结果一致 → **`VALID_REPRODUCTION`** ✓。
- 每次 `docker run --rm` 都是全新容器,`/tmp/repo` 本地仓库缓存不跨 run 复用,两次都从零下载依赖 —— 这是耗时集中在依赖解析阶段的原因,非构建本身慢。

## 保留意见 / 风险

- 父 POM = `org.springframework.cloud:spring-cloud-build:4.3.3-SNAPSHOT`。**2026-07-22 可解析并构建**(host 与 Docker 两侧均已验证),但 SNAPSHOT 可变/可能被 Spring 快照仓库清理,**长期可复现性无保证**。作长期基准前需锁定或 vendor 依赖(否则该案例可能某天突然构建失败)。
- 构建耗时约 5 min/模块(host)、约 10 min/侧(Docker 冷缓存),依赖下载量中等。

## 缓存验证证据(2026-08-11)

使用 Java `DockerSandboxRunner`,镜像 `maven:3.9-eclipse-temurin-17`,限制为 2 CPU / 2 GiB / 512 PID,固定目标测试与 gitignored Maven 缓存目录:

- 全仓 `dependency:go-offline` 方案在 10:04 达到墙钟上限,日志显示它仍在解析与目标测试无关的 Maven 插件图;Runner 返回超时并确认容器已清理。该失败方案未作为成功数据使用。
- 改为联网执行精确目标测试预热(测试断言结果忽略):465.116s。
- 同一缓存上的正式 fixed 执行:7.399s,`Tests run: 1, Failures: 0, Errors: 0`。
- 最新实现复跑:预热 7.778s,正式 fixed 执行 7.029s,结果仍为通过；公开执行事实只保留 Maven 白名单命令与 `networkMode=ONLINE`,不泄漏 Docker CLI 或宿主路径。
- 相对历史 Docker 冷基线 581s,暖执行下降约 98.7%,满足 ≤6min 且下降 ≥40% 的门槛。
- 因 `spring-cloud-build:4.3.3-SNAPSHOT` 不能承诺完全离线,本次正式执行明确使用 `--network=bridge`;结构化结果记录 `networkMode=ONLINE`,没有静默联网。

这组数据只证明缓存与 Runner 执行链路,不改变本案例的 Calibration Case 身份,也不构成 Agent 生成测试成功证据。
