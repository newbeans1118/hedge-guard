#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/health-check.sh —— 双组件健康检查（T-052 验收 3）
#
# 检查项：
#   hg-biz     : 进程存活 + GET /api/v1/ping（公开存活探针，不返回业务数据）+ /web/index.html
#   hg-trading : 进程存活 + HGCP 回环端口可连（CFG-20 hg.tcp.port）
#
# 退出码：0 = 全部通过；1 = 存在 FAIL；2 = 前置缺失（配置/命令）。
#
# ⚠️ 骨架期说明：hg-trading 尚未实化（不监听），其进程/端口检查会 FAIL —— 这是**如实反映**
#    组件未生产就绪，不是脚本缺陷。
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
  local pidfile="${HG_RUN_DIR}/hg-trading.pid" pid=""
  [ -f "${pidfile}" ] && pid="$(cat "${pidfile}")"
  if pid_alive "${pid}"; then _ok "进程存活（pid=${pid}）"; else _bad "进程未运行（骨架期进程不常驻；pidfile=${pidfile}）"; fi
  if port_open "${TCP_BIND}" "${TCP_PORT}"; then
    _ok "HGCP 回环端口可连 ${TCP_BIND}:${TCP_PORT}（CFG-20）"
  else
    _bad "HGCP 回环端口不可连 ${TCP_BIND}:${TCP_PORT}（骨架未实化 ⇒ 如实反映未就绪）"
  fi
}

case "${WHAT}" in
  biz)     check_biz ;;
  trading) check_trading ;;
  all)     check_biz; check_trading ;;
  *) printf '[%s] ERROR: 未知参数：%s（应为 biz|trading|all）\n' "$(date '+%Y-%m-%d %H:%M:%S')" "${WHAT}" >&2; exit 2 ;;
esac

printf -- "-- 汇总：PASS=${pass} FAIL=${fail} SKIP=${skip} --\n"
[ "${fail}" -eq 0 ]
