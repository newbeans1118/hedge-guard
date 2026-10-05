#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/common.sh —— 部署脚本公共函数（T-052）
#
# 依据：docs/ARCHITECTURE.md §3.14（部署与运维）、§2.4.1（进程模型）、§11（配置项）。
# 被 start-*.sh / stop-all.sh / health-check.sh / env-check.sh source。
# 敏感字段零落盘：本文件只读非敏感配置；口令/令牌一律经环境变量注入，绝不写盘、绝不打印。
# =============================================================================
set -u

_hg_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HG_ROOT="${HG_ROOT:-$(cd "${_hg_script_dir}/../.." && pwd)}"
HG_DEPLOY_DIR="${HG_DEPLOY_DIR:-${HG_ROOT}/docs/DEPLOY}"
HG_CONFIG_FILE="${HG_CONFIG_FILE:-${HG_ROOT}/hg.properties}"
HG_RUN_DIR="${HG_RUN_DIR:-${HG_ROOT}/run}"
HG_LOG_DIR="${HG_LOG_DIR:-${HG_ROOT}/logs}"

mkdir -p "${HG_RUN_DIR}" "${HG_LOG_DIR}"

log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
warn() { printf '[%s] WARN: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
die()  { printf '[%s] ERROR: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1（见 docs/DEPLOY/README.md 环境依赖清单）"
}

# 读非敏感配置：hg_cfg <key> <default>
hg_cfg() {
  local key="$1" def="${2:-}" file="${HG_CONFIG_FILE:-}" v=""
  if [ -n "${file}" ] && [ -f "${file}" ]; then
    v="$(awk -v k="${key}" '
      /^[[:space:]]*[#!]/ { next }
      {
        line=$0; sub(/^[[:space:]]+/, "", line)
        p=index(line, "="); if (p==0) next
        kk=substr(line,1,p-1); sub(/[[:space:]]+$/, "", kk)
        if (kk==k) { vv=substr(line,p+1); sub(/^[[:space:]]+/, "", vv); sub(/[[:space:]]+$/, "", vv); print vv; exit }
      }' "${file}")"
  fi
  if [ -n "${v}" ]; then printf '%s' "${v}"; else printf '%s' "${def}"; fi
}

pid_alive() {
  local pid="${1:-}"
  [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null
}

# 端口是否可连（回环）
port_open() {
  local host="$1" port="$2"
  (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null && return 0
  return 1
}

# 定位 C++ 交易组件可执行文件：HG_TRADING_BIN > cpp/build*/hg-trading
find_hg_trading() {
  if [ -n "${HG_TRADING_BIN:-}" ] && [ -x "${HG_TRADING_BIN}" ]; then
    printf '%s' "${HG_TRADING_BIN}"; return 0
  fi
  local f
  f="$(ls -1t "${HG_ROOT}"/cpp/build*/hg-trading "${HG_ROOT}"/cpp/build*/src/hg-trading 2>/dev/null | head -1)"
  if [ -n "${f}" ] && [ -x "${f}" ]; then printf '%s' "${f}"; return 0; fi
  return 1
}

# 组装 hg-biz 运行 classpath：HG_BIZ_CLASSPATH > target/classes + java/lib + target/dependency
find_hg_biz_cp() {
  if [ -n "${HG_BIZ_CLASSPATH:-}" ]; then printf '%s' "${HG_BIZ_CLASSPATH}"; return 0; fi
  local cp="" d jar
  [ -d "${HG_ROOT}/java/target/classes" ] && cp="${HG_ROOT}/java/target/classes"
  for d in "${HG_ROOT}/java/target/dependency" "${HG_ROOT}/java/lib"; do
    [ -d "${d}" ] || continue
    for jar in "${d}"/*.jar; do
      [ -e "${jar}" ] || continue
      cp="${cp:+${cp}:}${jar}"
    done
  done
  [ -n "${cp}" ] || return 1
  printf '%s' "${cp}"
}
