#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/stop-all.sh —— 停止双组件（T-052，配合启动脚本）
#
# 顺序：先停 hg-biz（TCP 客户端），再停 hg-trading（TCP 服务端，权威状态源）；
# 各先发 SIGTERM 等待优雅退出，超时再 SIGKILL。不会删除任何数据（只停进程）。
#
# 用法：bash scripts/deploy/stop-all.sh
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

_stop_one() {
  local name="$1" pidfile="$2" pid=""
  if [ ! -f "${pidfile}" ]; then log "${name}：无 pidfile，视为未运行"; return 0; fi
  pid="$(cat "${pidfile}")"
  if ! pid_alive "${pid}"; then log "${name}：进程 ${pid} 已不在，清理 pidfile"; rm -f "${pidfile}"; return 0; fi
  log "${name}：发送 SIGTERM（pid=${pid}）"
  kill "${pid}" 2>/dev/null
  local i=1
  while [ "${i}" -le 15 ]; do pid_alive "${pid}" || break; sleep 1; i=$((i+1)); done
  if pid_alive "${pid}"; then
    warn "${name}：${pid} 未在 15s 内退出，发送 SIGKILL"
    kill -9 "${pid}" 2>/dev/null
  fi
  rm -f "${pidfile}"
  log "${name}：已停止"
}

_stop_one "hg-biz" "${HG_RUN_DIR}/hg-biz.pid"
_stop_one "hg-trading" "${HG_RUN_DIR}/hg-trading.pid"
log "停机完成"
