#!/usr/bin/env bash
# shellcheck shell=bash
# =============================================================================
# release-unknown-inflight.sh —— 「未知在途」人工核验释放请求投递（T-211）
#
# 依据：ARCHITECTURE.md §4.5.3 ⑤⑥（在途对账 / 保守处理 / 人工介入）、§11.3
#       （`NFR-DEPLOY-4`：本地 admin 可持久化）；docs/DEPLOY/README.md §4.2。
#
# 背景：hg-trading 若在重放期发现「状态未知」在途单（`query_ == nullptr ⇒ kTimeout
#       ⇒ kUnknownKeepFrozen`），会停在 **BLOCKED**（0x0401，拒绝一切交易指令），
#       **重启亦无用**（该单仍为 unknown）。此时唯一人工闭环手段 = 双人复核后向
#       `<hg.flow.dir>/admin/release-request.json` 投递释放请求（**进程内管理入口**，
#       形态 (b)，**不扩协议**）。
#
# 本脚本只做「生成请求文件 + 置权限 0600」；守卫与释放由 hg-trading 主循环低频轮询
#   （≥1s）执行——**脚本本身不直连进程**（无 stdin / 无 socket）。
#
# 用法：
#   bash scripts/deploy/release-unknown-inflight.sh \
#       --order-id <id> --ref <client_order_ref> \
#       --operator-a <id> --operator-b <id> --reason "<核验结论>"
#
#   双人复核纪律（服务端机械守卫，缺一即拒）：
#     · 双操作人标识**均非零**；
#     · **双人不得同人**（复核独立性）；
#     · `ref` 须与台账一致（防错单）；
#     · `reason` 非空（留痕完整性）；
#     · 台账命中且**未释放**（幂等拒绝）。
#
# 请求文件权限**必须恰为 0600**（属主可读写、组/其他零权限）：本脚本以 `umask 077`
#   创建 + `chmod 600` 双保险。权限不符 ⇒ hg-trading **拒绝处理并告警**
#   （`NFR-DEPLOY-4` 同族）。
#
# 归档（可审计）：处理后被 hg-trading 移出请求路径 ——
#   成功 ⇒ `<admin>/processed/release-request.json[.N]`；
#   拒绝 ⇒ `<admin>/failed/release-request.json[.N]`（同一文件不会被重复处理）。
#
# 敏感项：本脚本**不含**任何口令/令牌；`reason` 为核验结论（不得写入口令类内容）。
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

ORDER_ID=""
REF=""
OP_A=""
OP_B=""
REASON=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --order-id)  ORDER_ID="${2:-}"; shift 2 ;;
    --order-id=*) ORDER_ID="${1#--order-id=}"; shift ;;
    --ref)       REF="${2:-}"; shift 2 ;;
    --ref=*)     REF="${1#--ref=}"; shift ;;
    --operator-a) OP_A="${2:-}"; shift 2 ;;
    --operator-a=*) OP_A="${1#--operator-a=}"; shift ;;
    --operator-b) OP_B="${2:-}"; shift 2 ;;
    --operator-b=*) OP_B="${1#--operator-b=}"; shift ;;
    --reason)    REASON="${2:-}"; shift 2 ;;
    --reason=*)  REASON="${1#--reason=}"; shift ;;
    -h|--help)
      sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "未知参数：$1（见 --help）" ;;
  esac
done

[ -n "${ORDER_ID}" ] || die "缺少 --order-id"
[ -n "${REF}" ] || die "缺少 --ref"
[ -n "${OP_A}" ] || die "缺少 --operator-a"
[ -n "${OP_B}" ] || die "缺少 --operator-b"
[ -n "${REASON}" ] || die "缺少 --reason"
case "${ORDER_ID}" in (*[!0-9]*) die "--order-id 须为非负整数：${ORDER_ID}" ;; esac
case "${OP_A}" in (*[!0-9]*) die "--operator-a 须为非负整数：${OP_A}" ;; esac
case "${OP_B}" in (*[!0-9]*) die "--operator-b 须为非负整数：${OP_B}" ;; esac
[ "${OP_A}" != "0" ] || die "--operator-a 不得为 0（双人复核前提）"
[ "${OP_B}" != "0" ] || die "--operator-b 不得为 0（双人复核前提）"
[ "${OP_A}" != "${OP_B}" ] || die "复核人与审批人不得同人（--operator-a != --operator-b）"

FLOW_DIR="$(hg_cfg hg.flow.dir ./data/flow)"
ADMIN_DIR="${FLOW_DIR%/}/admin"
REQ="${ADMIN_DIR}/release-request.json"

if [ -e "${REQ}" ]; then
  die "请求文件已存在（上一请求尚未被处理）：${REQ}
  请等待 hg-trading 轮询处理（≥1s），或先人工确认其未被阻塞。"
fi

mkdir -p "${ADMIN_DIR}/processed" "${ADMIN_DIR}/failed" || die "无法创建管理目录：${ADMIN_DIR}"

# reason 中的 `"` / `\` 转义（平面 JSON 字符串；服务端解析器识别 `\` 转义）。
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

umask 077  # 组/其他零权限（0600 的缺省承载）
printf '{"order_id": %s, "client_order_ref": "%s", "operator_a_id": %s, "operator_b_id": %s, "reason": "%s"}\n' \
  "${ORDER_ID}" "$(esc "${REF}")" "${OP_A}" "${OP_B}" "$(esc "${REASON}")" >"${REQ}" \
  || die "写入请求文件失败：${REQ}"
chmod 600 "${REQ}" || die "设置 0600 权限失败：${REQ}"

log "已投递人工核验释放请求：${REQ}（权限 $(stat -c '%a' "${REQ}" 2>/dev/null || echo 600)）"
log "  order_id=${ORDER_ID} ref=${REF} A=${OP_A} B=${OP_B}"
log "  hg-trading 将于 ≤1s 内轮询处理；结果见其日志与 ${ADMIN_DIR}/{processed,failed}/。"
log "  成功 ⇒ 状态由 BLOCKED 自动迁移 READY 并补发 EVT_SYSTEM_STATE(READY)。"
