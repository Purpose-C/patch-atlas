# 演示脚本

本脚本给录屏用。不重跑评测。不打开 `.env`。不执行会打印环境变量的命令。

同屏必须出现分母。5b 启发式臂的 `VALID_REPRODUCTION` 是 **18 次里的 1 次**（三臂合计 1/6、0/6、0/6；走到 Docker Replay 4/18）。

## 录制前

1. 新开一个干净 shell，不要复用已经 `source .env` 的会话。
2. 编辑器不打开 `.env`。
3. 不运行 `env`、`printenv`、`set`、`echo $OPENAI_API_KEY` 或任何会把密钥打到屏幕上的命令。
4. 工作目录是本仓库根。提示符若含本机家目录，改成只显示仓库名。

## 步骤

1. 说明：接下来用一条命令拉起只读控制台。它**不**启动 Worker、**不**调用模型。数据库是空的，看不到下面要读的那次成功 Run。
2. 先演示缺凭据拒绝（不要导出密钥）：

   ```bash
   ./scripts/up.sh --worker
   ```

   期望：退出码 2，输出 `missing: OPENAI_API_KEY` 等，且写明不读 `.env`。
3. 拉起控制台：

   ```bash
   ./scripts/up.sh
   ```

4. 浏览器打开 http://127.0.0.1:8080/runs 。列表为空。同屏说：这是新库，不是评测库。
5. 打开 `benchmark-cases/batch5b-three-arm/evidence-report.md`（或导读 `reading-guide.md` 再进原文）。指出：
   - HEURISTIC `VALID_REPRODUCTION` **1/6**
   - TEXT_TOOLS **0/6**
   - GRAPH_TOOLS **0/6**
   - 走到 Docker Replay **4/18**
   - 唯一成功 Run：`6d7dd641-b730-45d7-890c-3fcdd3559f42`（`AuthMe-ConfigMe-7bf10c513479`）
   - **这是 18 次里的 1 次**，不是挑选后重跑的结果
6. 打开 `benchmark-cases/spring-case-study/evidence-report.md`。指出：n=1；`expand` **0**；三臂 0/3 全 `GENERATION_EXHAUSTED`；原因不再是队列性质。
7. 打开 `benchmark-cases/task018/evidence-report.md`。指出 Calibration **3/3** 是已知触发测试校准 Replay Engine，**不是** Agent 生成成绩。同页 Agent 为 **0/3**。
8. 指出 `expand` 在 036、5b、Spring 案例均为 **0 / 0 / 0**。不要声称图引导定位已经成立。
9. 若现场 `./scripts/up.sh` 失败：把失败录进去，不要换命令反复重跑到成功。评测数字仍用已入库报告。
10. `./scripts/down.sh`

## 录完

回看一遍：无密钥、无 `.env` 正文、无本机家目录路径、无 `env` 输出。然后才提交录屏。
