#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/health-check.sh —— 双组件健康检查（T-052 验收 3）
#
# 检查项：
#   hg-biz     : 进程存活 + GET /api/v1/ping（公开存活探针，不返回业务数据）+ /web/index.html
#   hg-trading : 进程存活 + HGCP 回环端口可连（CFG-20 hg.tcp.port）+ **就绪态**（日志状态行）
#
# 退出码：0 = 全部通过；1 = 存在 FAIL；2 = 前置缺失（配置/命令）。
#
# ★ T-207 起 `hg-trading` 已实化（长驻 + 监听 + 重放 + 就绪态），本脚本**不再报告**骨架告警；
#   就绪判定读组件自身日志状态行（`state=READY|REPLAYING|NOT_READY|FAILED|BLOCKED`）：
#     · READY           ⇒ PASS（参数已收齐且重放/对账一致）
#     · NOT_READY/REPLAYING ⇒ FAIL：交易指令会被 0x0401 拒绝（R-TRADE-13 «不得排队补发»）
#     · FAILED/BLOCKED  ⇒ FAIL：须人工处置（流水损坏/对账不一致/未闭环未知在途）
#   这是**如实反映**服务可用性：未就绪即视为不健康（前端交易入口本就禁用）。
#
# 用法：bash scripts/deploy/health-check.sh [biz|trading|all]（默认 all）
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

if ! command -v curl >/dev/null 2>&1; then
  printf '[%s] ERROR: 缺少命令：curl（前置缺失，见 docs/DEPLOY/README.md 环境依赖清单）\n' "$(date '+%Y-%m-%d %H:%M:%S')" >&2
  exit 2
fi
WHAT="${1:-all}"

BIND="$(hg_cfg hg.web.bind 127.0.0.1)"
PORT="$(hg_cfg hg.web.port 27402)"
TCP_BIND="$(hg_cfg hg.tcp.bind 127.0.0.1)"
TCP_PORT="$(hg_cfg hg.tcp.port 27400)"
WEB_ROOT="$(hg_cfg hg.web.root ./web)"

pass=0 fail=0 skip=0
_ok()   { pass=$((pass+1)); printf '  [PASS] %s\n' "$1"; }
_bad()  { fail=$((fail+1)); printf '  [FAIL] %s\n' "$1"; }
_skip() { skip=$((skip+1)); printf '  [SKIP] %s\n' "$1"; }

check_biz() {
  printf -- '-- hg-biz --\n'
  local pidfile="${HG_RUN_DIR}/hg-biz.pid" pid=""
  [ -f "${pidfile}" ] && pid="$(cat "${pidfile}")"
  if pid_alive "${pid}"; then _ok "进程存活（pid=${pid}）"; else _bad "进程未运行（pidfile=${pidfile}）"; fi

  local body
  if body="$(curl -fsS -m 5 "http://${BIND}:${PORT}/api/v1/ping" 2>/dev/null)"; then
    _ok "存活探针 GET /api/v1/ping 可达（HTTP 200）：${body}"
  else
    _bad "存活探针 GET http://${BIND}:${PORT}/api/v1/ping 不可达"
  fi

  if curl -fsS -m 5 -o /dev/null "http://${BIND}:${PORT}/web/index.html" 2>/dev/null; then
    _ok "前端静态托管 GET /web/index.html 可达（零构建链，ARCH §7.1.9）"
  else
    _bad "前端静态托管 GET /web/index.html 不可达（检查 hg.web.root=${WEB_ROOT}）"
  fi
}

check_trading() {
  printf -- '-- hg-trading --\n'
  local pidfile="${HG_RUN_DIR}/hg-trading.pid" pid="" log="${HG_LOG_DIR}/hg-trading.log"
  [ -f "${pidfile}" ] && pid="$(cat "${pidfile}")"
  if pid_alive "${pid}"; then _ok "进程存活（pid=${pid}）"; else _bad "进程未运行（pidfile=${pidfile}）"; fi
  if port_open "${TCP_BIND}" "${TCP_PORT}"; then
    _ok "HGCP 回环端口可连 ${TCP_BIND}:${TCP_PORT}（CFG-20）"
  else
    _bad "HGCP 回环端口不可连 ${TCP_BIND}:${TCP_PORT}（组件未监听）"
  fi

  # 就绪态（日志状态行；取最后一条 state=…）
  local state=""
  if [ -f "${log}" ]; then
    state="$(grep -o 'state=[A-Z_]*' "${log}" 2>/dev/null | tail -1)"
  fi
  case "${state}" in
    state=READY)
      _ok "就绪态 = READY（参数已收齐、重放/对账一致）" ;;
    state=NOT_READY|state=REPLAYING)
      _bad "就绪态 = ${state#state=}：交易指令将被拒（0x0401），须等 Java 参数下发/重放完成" ;;
    state=FAILED)
      _bad "就绪态 = FAILED：流水损坏/对账不一致 ⇒ 永不 READY，须人工处置" ;;
    state=BLOCKED)
      _bad "就绪态 = BLOCKED：存在未闭环『状态未知』在途单，须人工双人核验" ;;
    state=STOPPING)
      _bad "就绪态 = STOPPING：进程正在停机" ;;
    "")
      _bad "无法读取就绪态（日志缺 state 行：${log}）" ;;
    *)
      _bad "就绪态未识别：${state}" ;;
  esac
}

case "${WHAT}" in
  biz)     check_biz ;;
  trading) check_trading ;;
  all)     check_biz; check_trading ;;
  *) printf '[%s] ERROR: 未知参数：%s（应为 biz|trading|all）\n' "$(date '+%Y-%m-%d %H:%M:%S')" "${WHAT}" >&2; exit 2 ;;
esac

printf -- "-- 汇总：PASS=${pass} FAIL=${fail} SKIP=${skip} --\n"
[ "${fail}" -eq 0 ]
