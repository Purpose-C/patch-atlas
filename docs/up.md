# 一条命令启动

把 PostgreSQL、Spring Boot API 和 Vue 控制台一起拉起来：

```bash
./scripts/up.sh
```

停止：

```bash
./scripts/down.sh
```

要求：本机有 Docker，以及 `docker compose`。不要求本仓库里已有 `target/`、`frontend/node_modules/` 或 `.env`。

启动完成后：

- 控制台：http://127.0.0.1:8080/runs
- 健康检查：http://127.0.0.1:8080/api/v1/health

## 这条命令做什么、不做什么

它启动的是**只读控制台**：空的 PostgreSQL、persistence profile 下的 API、同源 Vue 页面。数据库是新的，不会出现本仓库评测里已经跑过的 Run。

它**不**启动 Issue2Test Worker，**不**调用模型，**不**读取 `.env`。Compose 里的生成器类型固定为 `FAKE`，Worker 固定关闭。

若带 `--worker`，或环境里已经有 `PATCHATLAS_WORKER_ENABLED=true` / `PATCHATLAS_GENERATOR_TYPE=OPENAI`，脚本会拒绝启动，并列出缺的凭据或说明本打包不含 Worker。缺 `OPENAI_API_KEY`、`PATCHATLAS_OPENAI_MODEL` 或 `PATCHATLAS_WORKER_WORKSPACE_ROOT` 时直接报 `missing:`，不会改成 FAKE 假装成功。

重复执行 `./scripts/up.sh` 是安全的。若已有占用 `patchatlas-postgres-1` / `patchatlas-app-1` / `patchatlas-web-1` 的容器，脚本会先删掉这三个容器再拉起，不删 named volume。其它名字的容器（例如评测用的独立 Postgres）不会动。

沙箱执行依赖宿主 Docker 引擎。本 Compose 栈不挂载 Docker Socket，因此即使用户提供了模型凭据，这条命令也不会启动 Worker。

历史评测证据在 `benchmark-cases/`，不在这次拉起的空库里。

要在**没有模型凭据**的情况下校准 Replay Engine，或在宿主 JVM 上启动 Worker，见 [`host-worker.md`](host-worker.md)。

## 干净目录验证

见 [`up-verification.md`](up-verification.md)。
