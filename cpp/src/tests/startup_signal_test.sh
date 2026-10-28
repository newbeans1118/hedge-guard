#!/usr/bin/env bash
# =============================================================================
# cpp/src/tests/startup_signal_test.sh —— hg-trading **进程级**启动/退出用例（T-207）
#
# 覆盖（进程不可替代的三项判据）：
#   ① 正常启动 + 回环监听 + `SIGTERM` ⇒ **退出码 0**（优雅停机）
#   ② 非回环 bind（0.0.0.0）⇒ **拒绝启动**（非 0 退出 + 告警），不得静默回退
#   ③ 口令/令牌**零日志**：stdout+stderr 均不得出现口令明文（含 `--dump-config`）
#
# 用法：bash startup_signal_test.sh <hg-trading 可执行文件>
# 退出码：0 = 全部通过；1 = 存在失败；2 = 用法错误
# 纪律：本脚本只驱动**已构建产物**，不复制生产逻辑；全部临时文件落 mktemp 目录。
# =============================================================================
set -u

if [ "$#" -lt 1 ]; then
  echo "用法：bash $0 <hg-trading 可执行文件>" >&2
  exit 2
fi
BIN="$1"
if [ ! -x "${BIN}" ]; then
  echo "ERROR: 不是可执行文件：${BIN}" >&2
  exit 2
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/hgt207-signal.XXXXXX")" || exit 2
PID=""
cleanup() {
  if [ -n "${PID}" ] && kill -0 "${PID}" 2>/dev/null; then
    kill -KILL "${PID}" 2>/dev/null || true
  fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

fail=0
ok()  { printf '  [PASS] %s\n' "$1"; }
bad() { printf '  [FAIL] %s\n' "$1"; fail=$((fail+1)); }

wait_for() {  # wait_for <file> <pattern> <budget_seconds>
  local f="$1" pat="$2" budget="$3" i=0
  while [ "${i}" -lt $((budget * 20)) ]; do
    if [ -f "${f}" ] && grep -q -- "${pat}" "${f}"; then return 0; fi
    sleep 0.05
    i=$((i + 1))
  done
  return 1
}

# ---- ① 启动 + 监听 + SIGTERM ⇒ 退出码 0 -------------------------------------
echo "-- ① 启动/监听/SIGTERM --"
port=$(( 28100 + ($$ % 500) ))
started=0
for attempt in 1 2 3 4 5; do
  log="${TMP}/run${attempt}.log"
  HG_FLOW_DIR="${TMP}/flow${attempt}" "${BIN}" \
      --hg.tcp.port "${port}" --hg.tcp.bind 127.0.0.1 >>"${log}" 2>&1 &
  PID=$!
  if wait_for "${log}" "listen=" 8; then
    started=1
    break
  fi
  # 端口占用等启动失败 ⇒ 换端口重试（其余失败原因在下面统一报）
  kill -KILL "${PID}" 2>/dev/null || true
  wait "${PID}" 2>/dev/null || true
  PID=""
  port=$((port + 1))
done

if [ "${started}" -ne 1 ]; then
  bad "进程未在预算内进入监听（见 ${TMP}/run*.log）"
  cat "${log}" 2>/dev/null || true
else
  ok "启动后进入监听（log: $(grep -m1 'listen=' "${log}")）"
  if grep -q 'state=NOT_READY' "${log}"; then
    ok "初始状态 = NOT_READY（等参数下发）"
  else
    bad "未观测到 state=NOT_READY"
  fi
  kill -TERM "${PID}"
  rc=0
  wait "${PID}" || rc=$?
  PID=""
  if [ "${rc}" -eq 0 ]; then
    ok "SIGTERM 后退出码 0（优雅停机）"
  else
    bad "SIGTERM 后退出码 ${rc}（应为 0）"
  fi
  if grep -q 'state=STOPPING' "${log}"; then
    ok "停机经 STOPPING 态（日志可证）"
  else
    bad "未观测到 state=STOPPING"
  fi
fi

# ---- ② 非回环 bind ⇒ 拒绝启动（非 0）---------------------------------------
echo "-- ② 非回环 bind 拒启 --"
HG_FLOW_DIR="${TMP}/flow-bad" "${BIN}" --hg.tcp.bind 0.0.0.0 --hg.tcp.port "${port}" \
    >"${TMP}/bad.log" 2>&1
rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "非回环 bind 拒绝启动（退出码 ${rc}）"
else
  bad "非回环 bind 竟以 0 退出（不得静默回退）"
fi
if grep -qE '拒绝启动|非法|ERROR' "${TMP}/bad.log"; then
  ok "拒绝原因已告警（$(head -1 "${TMP}/bad.log")）"
else
  bad "拒绝启动但无告警输出"
fi

# ---- ③ 口令零日志（含 --dump-config 与常驻日志）----------------------------
echo "-- ③ 口令/令牌零日志 --"
SECRET="S3CRET-PWD-T207-DO-NOT-LOG"
TOKEN="S3CRET-TOKEN-T207-DO-NOT-LOG"
HG_CTP_PASSWORD="${SECRET}" HG_HGCP_AUTH_TOKEN="${TOKEN}" \
  "${BIN}" --dump-config >"${TMP}/dump.out" 2>"${TMP}/dump.err"
rc=$?
if [ "${rc}" -eq 0 ]; then
  ok "--dump-config 正常退出"
else
  bad "--dump-config 退出码 ${rc}"
fi
if grep -q "${SECRET}" "${TMP}/dump.out" "${TMP}/dump.err" 2>/dev/null; then
  bad "脱敏摘要泄露口令明文"
else
  ok "脱敏摘要不含口令明文"
fi
if grep -q "${TOKEN}" "${TMP}/dump.out" "${TMP}/dump.err" 2>/dev/null; then
  bad "脱敏摘要泄露令牌明文"
else
  ok "脱敏摘要不含令牌明文"
fi
if grep -q 'hg.ctp.password=<redacted>' "${TMP}/dump.out"; then
  ok "口令以 <redacted> 呈现"
else
  bad "未观测到 <redacted> 占位"
fi

# 常驻进程日志同样不得含口令（stdout+stderr 全量捕获）
port2=$((port + 100))
HG_CTP_PASSWORD="${SECRET}" HG_CTP_AUTH_CODE="${TOKEN}" \
  HG_FLOW_DIR="${TMP}/flow-log" "${BIN}" --hg.tcp.port "${port2}" --verbose \
  >"${TMP}/live.out" 2>&1 &
PID=$!
if wait_for "${TMP}/live.out" "listen=" 8; then
  kill -TERM "${PID}"
  wait "${PID}" 2>/dev/null || true
  PID=""
  if grep -q "${SECRET}" "${TMP}/live.out" || grep -q "${TOKEN}" "${TMP}/live.out"; then
    bad "常驻日志泄露口令/令牌明文"
  else
    ok "常驻日志（含 --verbose）零口令"
  fi
else
  bad "常驻进程未进入监听（无法验证零日志）"
fi

echo "-- 汇总：FAIL=${fail} --"
[ "${fail}" -eq 0 ]
