# Fixture: off-by-one

一个自包含的最小 Maven + JUnit 5 项目,用于校准 Bug Replay 沙箱执行链路。

- **fixed 状态**(当前源码):`lastChar("abc")` 返回 `'c'`。
- **buggy 状态**:应用 `buggy.patch` 后,`lastChar` 变成 `s.charAt(s.length() - 2)`,返回 `'b'`,触发测试**断言失败**(不是崩溃,不是编译错误)。
- `buggy.patch` 是"人类修复"的**反向补丁**;`patch -R` 可还原到 fixed。

## 运行

```bash
# host 模式(无隔离,开发自检):证明同一测试 fixed 通过、buggy 失败
RUNNER=host bash run-replay.sh

# docker 模式(受限容器,需先启动 Docker 守护进程)
RUNNER=docker bash run-replay.sh
```

成功输出 `REPLAY OK`。脚本结束会自动把 fixture 还原到 fixed 状态。

## 已知 caveat(待后续阶段替换)

- docker 模式当前允许容器联网解析 Maven 依赖;生产版会改用预热的离线 `.m2` + `--network none`。
- 这是可控 fixture,不是真实历史 Bug;真实 Defects4J / Spring 案例见 `benchmark-cases/`。
