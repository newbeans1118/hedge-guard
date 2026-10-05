#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/start-hg-trading.sh —— 启动 C++ 交易组件（T-052 验收 2）
#
# 依据：docs/ARCHITECTURE.md §2.4.1（进程模型：hg-trading 先启动）、§3.14、§4.5.3。
#
# ⚠️ 诚实标注（不得谎称已联调）：当前 hg-trading 为**工程骨架**（cpp/src/main.cc）——
#    它只装配/打印六模块清单并以 --self-test 自检，**尚不加载配置、不重放流水、不监听
#    HGCP 端口、不做握手/就绪态**。真实启动编排（配置装载 → 重放 → 等待 Java 下发 →
#    READY）由「真联编排」承接（见 docs/DEPLOY/README.md §4）。本脚本按架构规定的顺序
#    交付，并在检测到骨架行为时**显式告警**，不伪装就绪。
#
# 用法：
#   bash scripts/deploy/start-hg-trading.sh            # 启动（后台）
#   bash scripts/deploy/start-hg-trading.sh --self-test # 仅运行骨架自检
#   HG_TRADING_BIN=/path/to/hg-trading bash scripts/deploy/start-hg-trading.sh
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

MODE="${1:-}"

BIN="$(find_hg_trading)" || die "未找到 hg-trading 可执行文件。
  构建（本机 / 部署机均可，需 CMake >= 3.16）：
    cmake -S ${HG_ROOT}/cpp -B ${HG_ROOT}/cpp/build -DCMAKE_BUILD_TYPE=Release -DCTP_USE_FETCH_DEPS=OFF
    cmake --build ${HG_ROOT}/cpp/build -j
  或显式指定：HG_TRADING_BIN=<路径>"

if [ "${MODE}" = "--self-test" ]; then
  log "运行 hg-trading --self-test：${BIN}"
  exec "${BIN}" --self-test
fi

PIDFILE="${HG_RUN_DIR}/hg-trading.pid"
if [ -f "${PIDFILE}" ] && pid_alive "$(cat "${PIDFILE}")"; then
  log "hg-trading 已在运行（pid=$(cat "${PIDFILE}")），跳过"
  exit 0
fi

warn "hg-trading 当前为工程骨架：不加载配置 / 不重放流水 / 不监听 HGCP 端口。真联编排尚未实化。"
log "启动 hg-trading：${BIN}（日志 ${HG_LOG_DIR}/hg-trading.log）"
setsid "${BIN}" >>"${HG_LOG_DIR}/hg-trading.log" 2>&1 &
pid=$!
echo "${pid}" >"${PIDFILE}"

sleep 1
if pid_alive "${pid}"; then
  log "hg-trading 已启动（pid=${pid}）"
  exit 0
fi

warn "hg-trading 骨架进程已退出（pid=${pid}）——这是当前工程骨架的**预期行为**（无长驻循环/无监听）。"
warn "真联编排（实化 main.cc：配置装载→重放→等待 Java→READY）完成前，本脚本不能提供就绪保证。"
exit 0
