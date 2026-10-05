#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/start-all.sh —— 按架构顺序启动双组件（T-052 验收 2）
#
# 架构规定顺序（docs/ARCHITECTURE.md §2.4.1 / §3.14）：
#   ① hg-trading（C++，TCP **服务端**）先启动，启动即 NOT_READY → 重放流水 → 等 Java 下发
#      全量风控参数 → READY；
#   ② hg-biz（Java，TCP **客户端**）后启动；Java 建连时 C++ 应已在重放/就绪态。
#
# ⚠️ 诚实标注：当前 hg-trading 为工程骨架（不监听/不握手/无就绪态），故本脚本「等待就绪」
#    在骨架期会超时并告警。默认**不阻断** Java 启动（便于前端/后端独立联调），但会以
#    HG_REQUIRE_TRADING_READY=1 强制要求就绪（真联环境应置 1）。
#
# 用法：
#   bash scripts/deploy/start-all.sh
#   HG_REQUIRE_TRADING_READY=1 bash scripts/deploy/start-all.sh   # 真联：必须就绪才启 Java
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

TCP_BIND="$(hg_cfg hg.tcp.bind 127.0.0.1)"
TCP_PORT="$(hg_cfg hg.tcp.port 27400)"
TIMEOUT="${HG_TRADING_READY_TIMEOUT:-60}"

log "① 启动 hg-trading（C++ 交易组件）"
bash "${_here}/start-hg-trading.sh" || die "hg-trading 启动脚本失败"

log "② 等待 hg-trading 就绪：${TCP_BIND}:${TCP_PORT}（最长 ${TIMEOUT}s）"
ready=0
i=1
while [ "${i}" -le "${TIMEOUT}" ]; do
  if port_open "${TCP_BIND}" "${TCP_PORT}"; then ready=1; break; fi
  sleep 1
  i=$((i+1))
done

if [ "${ready}" -eq 1 ]; then
  log "hg-trading 已监听 ${TCP_BIND}:${TCP_PORT}（按架构此时应处于重放/就绪态；就绪语义由 EVT_SYSTEM_STATE/HELLO_ACK 确认）"
else
  warn "未在 ${TIMEOUT}s 内检测到 hg-trading 监听端口 ${TCP_PORT}。"
  warn "成因：当前 hg-trading 为工程骨架（不监听/无就绪态）——真联编排承接点尚未实化（见 README §4）。"
  if [ "${HG_REQUIRE_TRADING_READY:-0}" = "1" ]; then
    die "HG_REQUIRE_TRADING_READY=1 且 hg-trading 未就绪 ⇒ 拒绝启动 hg-biz（防基于陈旧状态判定）"
  fi
  warn "按缺省（非严格）模式继续启动 hg-biz；**此路径不代表已完成双组件联调**。"
fi

log "③ 启动 hg-biz（Java 业务组件）"
bash "${_here}/start-hg-biz.sh"
log "启动编排结束。健康检查：bash ${_here}/health-check.sh"
