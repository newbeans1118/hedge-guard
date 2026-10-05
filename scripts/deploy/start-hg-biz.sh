#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/start-hg-biz.sh —— 启动 Java 业务组件（T-052 验收 2/4）
#
# 依据：docs/ARCHITECTURE.md §2.4.1（hg-biz 后启动）、§3.7、§11（配置项）。
#
# 敏感字段零落盘（NFR-SEC-1 / NFR-DEPLOY-4 / 附录 B 红线 8）：
#   · 数据库口令：只从环境变量 HG_DB_PASSWORD 读取（Config.secret()），本脚本不落盘、不回显；
#   · HGCP 鉴权令牌：只从环境变量 HG_HGCP_AUTH_TOKEN 读取，本脚本不落盘、不回显；
#   · 属性文件 hg.properties 只允许承载**非敏感**配置；键名含 password/token/auth_code 的项
#     会被 Config.rejectSecrets 拒绝装载（启动即失败），本脚本不代其绕过。
#
# 核心转储关闭（NFR-SEC-1 ④）：以 -XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null 启动，
#   并置 -Dhg.coredump.off=true —— 缺该参数时进程会告警「存在敏感信息落盘风险」。
#
# 用法：
#   HG_DB_PASSWORD=... HG_HGCP_AUTH_TOKEN=... bash scripts/deploy/start-hg-biz.sh
#   HG_CONFIG_FILE=/etc/hedgeguard/hg.properties bash scripts/deploy/start-hg-biz.sh
#   HG_BIZ_CLASSPATH=... bash scripts/deploy/start-hg-biz.sh   # 覆盖 classpath 组装
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

require_cmd java

# JDK 11 校验（NFR-DEPLOY-5）
_java_ver="$(java -version 2>&1 | head -1)"
case "${_java_ver}" in
  *'"11.'*) : ;;
  *) warn "当前 java 非 JDK 11（实测：${_java_ver}）；本项目编译目标为 JDK 11（PROJECT.md §6）" ;;
esac

CP="$(find_hg_biz_cp)" || die "未找到 hg-biz 运行 classpath（target/classes 或 java/lib）。
  构建：cd ${HG_ROOT}/java && mvn -B -o -Punit -DskipTests package
  依赖落位（可选，或直接设 HG_BIZ_CLASSPATH）：
    mvn -B -o dependency:copy-dependencies -DoutputDirectory=target/dependency
  并将 DM JDBC 驱动放到 java/lib/（见 README §3.1 环境依赖清单）。"

BIND="$(hg_cfg hg.web.bind 127.0.0.1)"
PORT="$(hg_cfg hg.web.port 27402)"
WEB_ROOT="$(hg_cfg hg.web.root ./web)"

# 敏感项：只判「是否已注入」，绝不打印值
if [ -z "${HG_DB_PASSWORD:-}" ]; then
  warn "未设置 HG_DB_PASSWORD：按 CFG-31「密码不落盘」，口令仅经环境变量注入；缺位将导致**不装配数据库**（仅 /api/v1/ping 与静态资源可用）。"
fi
if [ -z "${HG_HGCP_AUTH_TOKEN:-}" ]; then
  warn "未设置 HG_HGCP_AUTH_TOKEN：将**不装配 HGCP 桥**（无法连 C++ 交易组件）。"
fi

PIDFILE="${HG_RUN_DIR}/hg-biz.pid"
if [ -f "${PIDFILE}" ] && pid_alive "$(cat "${PIDFILE}")"; then
  log "hg-biz 已在运行（pid=$(cat "${PIDFILE}")），跳过"
  exit 0
fi

JVM_FLAGS=(
  -XX:-CreateCoredumpOnCrash
  -XX:ErrorFile=/dev/null
  -Dhg.coredump.off=true
)
if [ -n "${HG_CONFIG_FILE:-}" ]; then
  JVM_FLAGS+=("-Dhg.config=${HG_CONFIG_FILE}")
fi

log "启动 hg-biz：bind=${BIND} port=${PORT} web.root=${WEB_ROOT}"
log "classpath 元素数：$(printf '%s' "${CP}" | awk -F: '{print NF}')（内容不打印，避免泄漏路径细节）"

# 以仓库根为工作目录启动，使缺省 ./hg.properties / ./web 相对路径成立
cd "${HG_ROOT}" || die "无法进入 ${HG_ROOT}"
setsid java "${JVM_FLAGS[@]}" -cp "${CP}" com.hedgeguard.web.Bootstrap \
  >>"${HG_LOG_DIR}/hg-biz.log" 2>&1 &
pid=$!
echo "${pid}" >"${PIDFILE}"

# 就绪探测：轮询 /api/v1/ping
for i in $(seq 1 "${HG_BIZ_START_TIMEOUT:-30}"); do
  if pid_alive "${pid}" && curl -fsS -m 2 "http://${BIND}:${PORT}/api/v1/ping" >/dev/null 2>&1; then
    log "hg-biz 已就绪（pid=${pid}；http://${BIND}:${PORT}/api/v1/ping）"
    exit 0
  fi
  if ! pid_alive "${pid}"; then
    die "hg-biz 启动失败（pid=${pid} 已退出）；见日志 ${HG_LOG_DIR}/hg-biz.log"
  fi
  sleep 1
done

die "hg-biz 启动超时（${HG_BIZ_START_TIMEOUT:-30}s 内未就绪）；见日志 ${HG_LOG_DIR}/hg-biz.log"
