#!/usr/bin/env bash
# =============================================================================
# scripts/deploy/env-check.sh —— 环境依赖探测与版本记录（T-052 验收 1/6/7）
#
# 对应 NFR-DEPLOY-5：文档化并**实测** DM JDBC 驱动版本、CTP SDK 版本、JDK 11、
# CMake、protobuf 供给版本、浏览器要求。本脚本只做探测与打印，不修改任何文件。
#
# 退出码：0 = 构建必需项齐备；1 = 缺构建必需项（java/javac/mvn/cmake/g++）。
#   DM JDBC 驱动 / CTP SDK / DM8 实例属部署期由客户提供项，缺位记 WARN（不判失败）。
#
# 用法：bash scripts/deploy/env-check.sh
# =============================================================================
set -u
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "${_here}/common.sh"

rc=0

echo "== 构建必需项 =="
if command -v java >/dev/null 2>&1; then echo "  JDK        : $(java -version 2>&1 | head -1)"; else echo "  JDK        : MISSING"; rc=1; fi
if command -v javac >/dev/null 2>&1; then echo "  javac      : present"; else echo "  javac      : MISSING"; rc=1; fi
if command -v mvn >/dev/null 2>&1; then echo "  Maven      : $(mvn -v 2>&1 | head -1)"; else echo "  Maven      : MISSING"; rc=1; fi
if command -v cmake >/dev/null 2>&1; then
  cmv="$(cmake --version 2>&1 | head -1)"
  echo "  CMake      : ${cmv}"
  case "${cmv}" in
    *"version 3."*|*"version 4."*) : ;;
    *) echo "    WARN: CMake < 3.16 不满足 NFR-DEPLOY-3"; rc=1 ;;
  esac
else echo "  CMake      : MISSING"; rc=1; fi
if command -v g++ >/dev/null 2>&1; then echo "  g++        : $(g++ --version 2>&1 | head -1)"; else echo "  g++        : MISSING"; rc=1; fi

echo "== protobuf 供给（验收 6：系统包 3.21.12 / FetchContent 离线回退）=="
if command -v protoc >/dev/null 2>&1; then echo "  protoc     : $(protoc --version 2>&1)"; else echo "  protoc     : MISSING（系统包轨不可用；可用 -DCTP_USE_FETCH_DEPS=ON 走 FetchContent）"; fi
if [ -f /usr/include/google/protobuf/stubs/common.h ]; then
  echo "  sys headers: GOOGLE_PROTOBUF_VERSION=$(awk '/define GOOGLE_PROTOBUF_VERSION /{print $3}' /usr/include/google/protobuf/stubs/common.h | head -1)"
fi
ldconfig -p 2>/dev/null | grep -E 'libprotobuf(-lite)?\.so' | sed 's/^/  /' || echo "  libprotobuf: 未在 ldconfig 中（系统包轨不可用时改 FetchContent）"

echo "== CTP SDK（验收 7：实盘版本复测）=="
_ctp_root="${CTP_SDK_ROOT:-}"
if [ -z "${_ctp_root}" ]; then
  _ctp_root="$(ls -1d "${HG_ROOT}"/sdk/*api* 2>/dev/null | head -1)"
fi
if [ -n "${_ctp_root}" ] && [ -d "${_ctp_root}" ]; then
  echo "  CTP SDK root: ${_ctp_root}"
  find "${_ctp_root}" -maxdepth 2 \( -name 'ThostFtdcTraderApi.h' -o -name 'ThostFtdcMdApi.h' \) 2>/dev/null | sed 's/^/    /'
  echo "  复测命令: CTP_SDK_ROOT=${_ctp_root} bash ${HG_ROOT}/cpp/ctp_smoke/ci/run_probe.sh"
else
  echo "  CTP SDK    : WARN 未发现（开发可用 stub 轨；生产需客户提供官方分离式 SDK）"
fi

echo "== DM8 数据库（验收 5；NFR-DEPLOY-5）=="
_dmjar="$(ls -1 "${HG_ROOT}"/java/lib/DmJdbcDriver*.jar 2>/dev/null | head -1)"
if [ -n "${_dmjar}" ]; then
  echo "  DM JDBC 驱动: ${_dmjar}  md5=$(md5sum "${_dmjar}" | awk '{print $1}')"
else
  echo "  DM JDBC 驱动: WARN 未发现（java/lib/DmJdbcDriver*.jar 由客户提供，版本须与 DM8 实例匹配）"
fi
if (exec 3<>/dev/tcp/127.0.0.1/5236) 2>/dev/null; then
  echo "  DM8 实例    : 127.0.0.1:5236 可达"
else
  echo "  DM8 实例    : WARN 127.0.0.1:5236 不可达（本环境实测无 DM8；CH-016：不得以 SQLite 冒充 DM8 生产验证）"
fi

echo "== 可选/开发 =="
command -v sqlite3 >/dev/null 2>&1 && echo "  sqlite3    : $(sqlite3 --version 2>&1)（仅 dev/test，CH-016）" || echo "  sqlite3    : 未安装（dev/test 可选）"
command -v curl >/dev/null 2>&1 && echo "  curl       : present（健康检查用）" || echo "  curl       : MISSING"
echo "  浏览器     : 建议 Chrome/Edge/Firefox 近两年版本（原生 ES2015+ / RFC 6455）"

exit "${rc}"
