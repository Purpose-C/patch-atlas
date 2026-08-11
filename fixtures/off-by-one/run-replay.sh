#!/usr/bin/env bash
#
# 沙箱 Bug Replay 校准脚本。
#
# 证明同一个 JUnit 测试:在 buggy 状态失败、在 fixed 状态通过。
# buggy.patch 是"人类修复"的反向补丁:应用它把 fixture 从 fixed 变为 buggy,
# `patch -R` 再还原回 fixed。
#
# 两种 runner:
#   RUNNER=host   (默认)在宿主机上跑 `mvn test`,无隔离,仅开发自检。
#   RUNNER=docker 在受限容器中运行(需要 Docker 守护进程)。
#
# 成功 = fixed 通过 且 buggy 因测试/断言失败(而非编译、环境或超时错误)。

set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
FIXTURE="$REPO_ROOT/fixtures/off-by-one"
RUNNER="${RUNNER:-host}"
IMAGE="maven:3.9-eclipse-temurin-21"

run_tests() {
  case "$RUNNER" in
    host)
      "$REPO_ROOT/mvnw" -q -B -f "$FIXTURE/pom.xml" test
      ;;
    docker)
      # 受限沙箱:不挂载主机 Home、不挂 Docker Socket、丢弃全部 capability、
      # 限制 CPU/内存/PID、非 root。非 root 需要可写的 HOME 与本地仓库,否则
      # Maven 无法创建 local repository(会被误判成 fixed 也失败)。注意
      # 此脚本保留网络以便 Maven 解析依赖;产品 Runner 使用预热缓存,并根据
      # 案例依赖显式选择离线或联网模式。
      docker run --rm \
        --user "$(id -u):$(id -g)" \
        --cpus=1 --memory=1g --pids-limit=256 \
        --cap-drop=ALL --security-opt=no-new-privileges \
        -e HOME=/tmp \
        -v "$FIXTURE":/work -w /work \
        "$IMAGE" \
        mvn -q -B -Dmaven.repo.local=/tmp/repo test
      ;;
    *)
      echo "未知 RUNNER=$RUNNER(可选 host 或 docker)" >&2
      exit 2
      ;;
  esac
}

report() { printf -- '----- %s -----\n' "$*"; }

restore() {
  patch -Rsp1 -d "$FIXTURE" <"$FIXTURE/buggy.patch" 2>/dev/null || true
  find "$FIXTURE" -name '*.orig' -delete 2>/dev/null || true
}

# 1. fixed 状态必须通过。
report "RUNNER=$RUNNER  fixed 状态 -> 期望 通过"
if run_tests; then fixed_pass=1; else fixed_pass=0; fi

# 2. 应用反向补丁进入 buggy 状态,必须失败。
report "应用 buggy.patch -> buggy 状态 -> 期望 失败"
patch -sp1 -d "$FIXTURE" <"$FIXTURE/buggy.patch"
trap restore EXIT
if run_tests; then buggy_pass=1; else buggy_pass=0; fi

# 3. 还原 fixed 状态。
restore
trap - EXIT

report "结果"
echo "fixed 通过: $fixed_pass (期望 1)"
echo "buggy 通过: $buggy_pass (期望 0)"
if [[ "$fixed_pass" == 1 && "$buggy_pass" == 0 ]]; then
  echo "REPLAY OK: 同一测试在 buggy 失败、在 fixed 通过。"
else
  echo "REPLAY NOT REPRODUCED."
  exit 1
fi
