# 演示录制

日期：2026-08-19

录制时实际运行的提交：`4b77189`（声明对照表与 README 改写之后、本文件入库之前的 HEAD）。本文件是同一次会话的可读记录，不是另一次重跑。

## 录制条件

- 新开的命令会话，未读取 `.env`
- 仓库副本里没有 `.env`
- 未执行 `env` / `printenv` / `echo $OPENAI_API_KEY`
- 提示符为 `patch-atlas$`，正文无本机家目录路径

## 凭据自查

回看完整 typescript（约 2.2 MiB 的 Compose 构建日志，不入库）后：无本机家目录绝对路径、无 API Key 赋值、无常见云厂商密钥前缀、无 dotenv 文件正文。

结论：未入镜凭据，可提交。

## 会话摘要（真实发生，未为成功而重跑）

1. `./scripts/up.sh --worker` 退出码 2：

   ```text
   refusing to start Worker: missing credentials or workspace
     missing: OPENAI_API_KEY
     missing: PATCHATLAS_OPENAI_MODEL
     missing: PATCHATLAS_WORKER_WORKSPACE_ROOT
   this command does not read .env files and does not silently switch to FAKE
   ```

2. `./scripts/up.sh` 构建镜像后，Compose 因容器名占用失败：

   ```text
   Error response from daemon: Conflict. The container name "/patchatlas-postgres-1" is already in use
   ```

   `curl` 健康检查与 `/runs` 因此失败（HTTP 码 `000`）。按演示脚本：现场失败就录失败，不为录屏再跑到成功。

3. 同屏用已入库报告说明分母（控制台是空库，成功 Run 不在这次失败的 Compose 里）：

   - 5b `VALID_REPRODUCTION`：HEURISTIC 1/6，TEXT_TOOLS 0/6，GRAPH_TOOLS 0/6
   - 走到 Docker Replay：4/18
   - 唯一成功 Run `6d7dd641-b730-45d7-890c-3fcdd3559f42` 是 **18 次里的 1 次**
   - `expand count: 0`（5b）
   - Calibration passed 3/3，Agent VALID_REPRODUCTION 0/3（校准不是 Agent 成绩）
   - expand 036 / 5b / Spring：0 / 0 / 0；图引导定位未成立

4. `./scripts/down.sh` 删除了这次会话创建以及冲突的 `patchatlas-*` 容器。

一条命令在干净目录里曾经拉起成功，见 [`up-verification.md`](up-verification.md)。本次录制没有覆盖那次成功，也没有为了画面重跑。

## Tag

`demo` 打在录制时实际运行的提交 `4b77189` 上。
本说明首次入库于 `ea49e43`，引用该 Tag；不是把 Tag 打在说明提交上。
相对 `4b77189` 只增加录制说明，不改定位、生成、Gate 或阈值。
