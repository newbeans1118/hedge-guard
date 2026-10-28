#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/start-hg-trading.sh —— 启动 C++ 交易组件（T-052 验收 2；T-207 实化同步）
#
# 依据：docs/ARCHITECTURE.md §2.4.1（进程模型：hg-trading 先启动）、§4.5.3（启动时序）、
#       §11.2（配置项 CFG-20/22/28/29）。
#
# ★ T-207 起 `hg-trading` **已实化**（不再是工程骨架）：
#     · 配置装载：CLI > env > 属性文件（`HG_CONFIG_FILE`，缺省 ${HG_ROOT}/hg.properties）> 缺省；
#     · 启动即监听 127.0.0.1:${hg.tcp.port}（默认 27400），初始状态 **NOT_READY**；
#     · 等 Java 分批下发 `CMD_LOAD_RISK_PARAMS` 收齐 → 重放/在途对账 → **READY**；
#       流水损坏/对账不一致 ⇒ **FAILED（永不 READY）**；存在未闭环未知在途 ⇒ **BLOCKED**；
#     · `SIGINT`/`SIGTERM` ⇒ 优雅停机（退出码 0）。
#
# ⚠️ 就绪语义**不得伪装**：本脚本只保证「进程存活 + 已监听」；`READY` 需 Java 侧完成
#    参数下发，故按日志**如实**报告当前状态（NOT_READY/REPLAYING/READY/FAILED/BLOCKED）。
#
# 用法：
#   bash scripts/deploy/start-hg-trading.sh             # 启动（后台）
#   bash scripts/deploy/start-hg-trading.sh --self-test  # 仅运行六模块自检
#   HG_TRADING_BIN=/path/to/hg-trading bash scripts/deploy/start-hg-trading.sh
#   HG_START_WAIT_S=10 bash scripts/deploy/start-hg-trading.sh    # 监听等待预算（秒）
#
# 敏感项仅经环境变量（**零落盘**）：HG_CTP_PASSWORD / HG_CTP_AUTH_CODE / HG_CTP_APP_ID /
#   HG_HGCP_AUTH_TOKEN —— 本脚本不写、不打印、不落盘，需由部署方在启动环境中提供。
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
LOG="${HG_LOG_DIR}/hg-trading.log"
if [ -f "${PIDFILE}" ] && pid_alive "$(cat "${PIDFILE}")"; then
  log "hg-trading 已在运行（pid=$(cat "${PIDFILE}")），跳过"
  exit 0
fi

TCP_BIND="$(hg_cfg hg.tcp.bind 127.0.0.1)"
TCP_PORT="$(hg_cfg hg.tcp.port 27400)"
WAIT_S="${HG_START_WAIT_S:-10}"

# 属性文件（非敏感）经 `--config` 显式传入（与 hg-biz 共用同一份部署配置）。
args=()
if [ -n "${HG_CONFIG_FILE:-}" ] && [ -f "${HG_CONFIG_FILE}" ]; then
  args+=(--config "${HG_CONFIG_FILE}")
  log "配置来源：${HG_CONFIG_FILE}"
else
  warn "未发现部署属性文件（${HG_CONFIG_FILE:-<未设置>}）⇒ 仅用 env/内置缺省"
fi

log "启动 hg-trading：${BIN}（日志 ${LOG}；监听 ${TCP_BIND}:${TCP_PORT}）"
# 日志按追加写：就绪判定只看**本次启动之后**的追加段，避免历史日志造成假就绪。
mark="$(wc -l <"${LOG}" 2>/dev/null || echo 0)"
setsid "${BIN}" ${args[@]+"${args[@]}"} >>"${LOG}" 2>&1 &
pid=$!
echo "${pid}" >"${PIDFILE}"

# 等待「已监听」（预算 HG_START_WAIT_S 秒）。
i=0
listening=0
while [ "${i}" -lt $((WAIT_S * 10)) ]; do
  if ! pid_alive "${pid}"; then break; fi
  if tail -n +"$((mark + 1))" "${LOG}" 2>/dev/null | grep -q '^\[hg-trading\] listen='; then
    listening=1
    break
  fi
  sleep 0.1
  i=$((i + 1))
done

if ! pid_alive "${pid}"; then
  warn "hg-trading 启动后已退出（pid=${pid}）——见 ${LOG} 末尾；常见原因：端口被占用 /"
  warn "bind 非回环 / 流水目录不可写 / 配置非法（均应以非 0 退出码拒绝启动，不静默回退）。"
  tail -n 5 "${LOG}" 2>/dev/null | sed 's/^/    /' >&2
  exit 1
fi

if [ "${listening}" -ne 1 ]; then
  warn "hg-trading 在 ${WAIT_S}s 内未报告 listen= ——进程存活但未监听，按失败处理。"
  exit 1
fi

state="$(tail -n +"$((mark + 1))" "${LOG}" 2>/dev/null | grep -o 'state=[A-Z_]*' | tail -1)"
log "hg-trading 已监听（pid=${pid}，${TCP_BIND}:${TCP_PORT}，当前状态 ${state:-UNKNOWN}）"
case "${state}" in
  state=READY)
    log "交易组件已就绪（READY）：参数已收齐且重放/对账一致。" ;;
  state=NOT_READY|state=REPLAYING)
    log "交易组件尚未就绪（${state}）：等待 Java 下发 CMD_LOAD_RISK_PARAMS 并完成重放。" ;;
  state=FAILED)
    warn "交易组件 FAILED（永不 READY）：流水损坏/对账不一致 ⇒ 拒绝一切交易指令，须人工处置。" ;;
  state=BLOCKED)
    warn "交易组件 BLOCKED：存在『状态未知』在途单，须人工双人核验闭环后方可 READY。" ;;
  *)
    log "状态未识别（${state:-UNKNOWN}）；以 health-check.sh 复核。" ;;
esac
exit 0
