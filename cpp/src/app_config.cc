// hg-trading 启动配置装载实现（T-207）。
//
// 分层：① 属性文件（非敏感，缺省 ./hg.properties，可用 --config / HG_CONFIG_FILE 指定）
//       ② 环境变量（`hg.tcp.port` → `HG_TCP_PORT`；**敏感项仅此层**）
//       ③ CLI（`--hg.tcp.port 27400` / `--hg.tcp.port=27400`）
//   应用顺序 = ① → ② → ③ ⇒ 后者覆盖前者，即 **CLI > env > 文件 > 内置缺省**。
// 安全：敏感键出现在 CLI / 属性文件 ⇒ **拒绝启动**（防 shell history / ps / 落盘泄露）；
//   原文与长度/片段一律不进日志（`NFR-SEC-1` / `R-CONFIG-5`）。
#include "app_config.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <vector>

#include "hg/flow_store/replay.h"
#include "hg/hgcp/endpoint.h"

namespace hg {
namespace app {

namespace {

// 已知配置键 → 环境变量名（`secret==true` ⇒ 仅允许经 env 提供）。
struct KeyDef {
  const char* key;
  const char* env;
  bool secret;
};

const KeyDef kKeys[] = {
    // CFG-20
    {"hg.tcp.bind", "HG_TCP_BIND", false},
    {"hg.tcp.port", "HG_TCP_PORT", false},
    // CFG-28
    {"hg.tcp.heartbeat_ms", "HG_TCP_HEARTBEAT_MS", false},
    {"hg.cmd_timeout_ms", "HG_CMD_TIMEOUT_MS", false},
    {"hg.trade.confirm_timeout_ms", "HG_TRADE_CONFIRM_TIMEOUT_MS", false},
    // CFG-29
    {"hg.flow.dir", "HG_FLOW_DIR", false},
    {"hg.flow.fsync_mode", "HG_FLOW_FSYNC_MODE", false},
    {"hg.flow.retain_days", "HG_FLOW_RETAIN_DAYS", false},
    // §4.5.3 性能注
    {"hg.replay.query_timeout_ms", "HG_REPLAY_QUERY_TIMEOUT_MS", false},
    // CFG-22
    {"hg.ctp.env_type", "HG_CTP_ENV_TYPE", false},
    // CTP 连接（非敏感；CFG-22 同族最小键集）
    {"hg.ctp.trade_front", "HG_CTP_TRADE_FRONT", false},
    {"hg.ctp.md_front", "HG_CTP_MD_FRONT", false},
    {"hg.ctp.broker_id", "HG_CTP_BROKER_ID", false},
    {"hg.ctp.user_id", "HG_CTP_USER_ID", false},
    {"hg.ctp.flow_path", "HG_CTP_FLOW_PATH", false},
    // 敏感（仅 env）
    {"hg.ctp.password", "HG_CTP_PASSWORD", true},
    {"hg.ctp.auth_code", "HG_CTP_AUTH_CODE", true},
    {"hg.ctp.app_id", "HG_CTP_APP_ID", true},
    {"hg.hgcp.auth_token", "HG_HGCP_AUTH_TOKEN", true},
};

const std::size_t kKeyCount = sizeof(kKeys) / sizeof(kKeys[0]);

std::string trim(const std::string& s) {
  std::string::size_type b = 0;
  while (b < s.size() && (s[b] == ' ' || s[b] == '\t')) ++b;
  std::string::size_type e = s.size();
  while (e > b && (s[e - 1] == ' ' || s[e - 1] == '\t')) --e;
  return s.substr(b, e - b);
}

std::string lower(const std::string& s) {
  std::string out(s);
  for (std::size_t i = 0; i < out.size(); ++i) {
    const char c = out[i];
    if (c >= 'A' && c <= 'Z') out[i] = static_cast<char>(c - 'A' + 'a');
  }
  return out;
}

bool contains(const std::string& hay, const char* needle) {
  return hay.find(needle) != std::string::npos;
}

// 敏感键名判定（用于**拒绝**属性文件承载敏感项）：键名含口令类关键词即视为敏感。
// 与 Java 侧 `Config.rejectSecrets` 同口径（T-052 先例）。
bool isSecretKeyName(const std::string& key) {
  const std::string k = lower(key);
  return contains(k, "password") || contains(k, "passwd") || contains(k, "token") ||
         contains(k, "secret") || contains(k, "auth_code") || contains(k, "authcode") ||
         contains(k, "app_id") || contains(k, "passphrase");
}

const KeyDef* findKey(const std::string& key) {
  for (std::size_t i = 0; i < kKeyCount; ++i) {
    if (key == kKeys[i].key) return &kKeys[i];
  }
  return 0;
}

bool parseInt64(const std::string& s, std::int64_t* out) {
  if (s.empty() || out == 0) return false;
  errno = 0;
  char* end = 0;
  const long long v = std::strtoll(s.c_str(), &end, 10);
  if (end == 0 || *end != '\0' || errno == ERANGE) return false;
  *out = static_cast<std::int64_t>(v);
  return true;
}

bool parseInt(const std::string& s, int* out) {
  std::int64_t v = 0;
  if (!parseInt64(s, &v)) return false;
  if (v < -2147483647LL || v > 2147483647LL) return false;
  *out = static_cast<int>(v);
  return true;
}

// 单键赋值（值域错误 ⇒ ok=false，错误信息只含键名与非敏感值）。
bool applyKey(const std::string& key, const std::string& value, AppConfig* cfg,
              ConfigOutcome* oc) {
  const KeyDef* def = findKey(key);
  if (def == 0) {
    oc->notes.push_back("未知配置键（已忽略）：" + key);
    return true;
  }
  if (key == "hg.tcp.bind") {
    cfg->tcp_bind = value;
  } else if (key == "hg.tcp.port") {
    int v = 0;
    if (!parseInt(value, &v)) {
      oc->ok = false;
      oc->error = "hg.tcp.port 非整数：" + value;
      return false;
    }
    if (v <= 0 || v > 65535) {
      oc->ok = false;
      oc->error = "hg.tcp.port 超出 1..65535：" + value;
      return false;
    }
    cfg->tcp_port = static_cast<std::uint16_t>(v);
  } else if (key == "hg.tcp.heartbeat_ms") {
    if (!parseInt64(value, &cfg->heartbeat_ms)) {
      oc->ok = false;
      oc->error = "hg.tcp.heartbeat_ms 非整数：" + value;
      return false;
    }
  } else if (key == "hg.cmd_timeout_ms") {
    if (!parseInt64(value, &cfg->cmd_timeout_ms)) {
      oc->ok = false;
      oc->error = "hg.cmd_timeout_ms 非整数：" + value;
      return false;
    }
  } else if (key == "hg.trade.confirm_timeout_ms") {
    if (!parseInt64(value, &cfg->confirm_timeout_ms)) {
      oc->ok = false;
      oc->error = "hg.trade.confirm_timeout_ms 非整数：" + value;
      return false;
    }
  } else if (key == "hg.flow.dir") {
    cfg->flow_dir = value;
  } else if (key == "hg.flow.fsync_mode") {
    cfg->flow_fsync_mode = value;
  } else if (key == "hg.flow.retain_days") {
    if (!parseInt(value, &cfg->flow_retain_days)) {
      oc->ok = false;
      oc->error = "hg.flow.retain_days 非整数：" + value;
      return false;
    }
  } else if (key == "hg.replay.query_timeout_ms") {
    if (!parseInt64(value, &cfg->replay_query_timeout_ms)) {
      oc->ok = false;
      oc->error = "hg.replay.query_timeout_ms 非整数：" + value;
      return false;
    }
  } else if (key == "hg.ctp.env_type") {
    cfg->ctp_env_type = value;
  } else if (key == "hg.ctp.trade_front") {
    cfg->ctp_trade_front = value;
  } else if (key == "hg.ctp.md_front") {
    cfg->ctp_md_front = value;
  } else if (key == "hg.ctp.broker_id") {
    cfg->ctp_broker_id = value;
  } else if (key == "hg.ctp.user_id") {
    cfg->ctp_user_id = value;
  } else if (key == "hg.ctp.flow_path") {
    cfg->ctp_flow_path = value;
  } else if (key == "hg.ctp.password") {
    cfg->ctp_password = value;  // 仅 env 可达（CLI/文件已在更外层拒绝）
  } else if (key == "hg.ctp.auth_code") {
    cfg->ctp_auth_code = value;
  } else if (key == "hg.ctp.app_id") {
    cfg->ctp_app_id = value;
  } else if (key == "hg.hgcp.auth_token") {
    cfg->hgcp_auth_token = value;
  } else {
    oc->notes.push_back("未知配置键（已忽略）：" + key);
  }
  (void)def;
  return true;
}

bool fileExists(const std::string& path) {
  std::ifstream probe(path.c_str());
  return probe.good();
}

// 属性文件装载（非敏感；敏感键名 ⇒ 拒绝）。
bool loadPropertiesFile(const std::string& path, bool required, AppConfig* cfg,
                        ConfigOutcome* oc) {
  std::ifstream in(path.c_str());
  if (!in) {
    if (required) {
      oc->ok = false;
      oc->error = "属性文件不可读：" + path;
      return false;
    }
    return true;  // 缺省文件缺失 ⇒ 静默跳过（notes 由调用方补）
  }
  if (!in.is_open()) {
    if (required) {
      oc->ok = false;
      oc->error = "属性文件不可读：" + path;
      return false;
    }
    return true;
  }
  cfg->config_file = path;
  std::string line;
  int lineno = 0;
  while (std::getline(in, line)) {
    ++lineno;
    if (!line.empty() && line[line.size() - 1] == '\r') line.resize(line.size() - 1);
    const std::string t = trim(line);
    if (t.empty() || t[0] == '#' || t[0] == '!') continue;
    const std::string::size_type p = t.find('=');
    if (p == std::string::npos) {
      char buf[64];
      std::snprintf(buf, sizeof(buf), "属性文件第 %d 行缺少 '='（已忽略）", lineno);
      oc->notes.push_back(std::string(buf));
      continue;
    }
    const std::string key = trim(t.substr(0, p));
    const std::string value = trim(t.substr(p + 1));
    if (key.empty()) continue;
    if (isSecretKeyName(key)) {
      // 只报键名，不回显值（零落盘纪律）。
      oc->ok = false;
      oc->error = "属性文件不得承载敏感项（口令/令牌仅经环境变量注入）：" + key;
      return false;
    }
    if (findKey(key) == 0) {
      oc->notes.push_back("属性文件含未知键（已忽略）：" + key);
      continue;
    }
    if (!applyKey(key, value, cfg, oc)) return false;
  }
  return true;
}

bool applyEnv(AppConfig* cfg, ConfigOutcome* oc) {
  for (std::size_t i = 0; i < kKeyCount; ++i) {
    const char* v = std::getenv(kKeys[i].env);
    if (v == 0) continue;
    if (!applyKey(kKeys[i].key, std::string(v), cfg, oc)) {
      oc->error += "（来源 env:" + std::string(kKeys[i].env) + "）";
      return false;
    }
  }
  return true;
}

// CLI 预扫描：只取 --config（决定属性文件路径）。
bool prescanConfigPath(int argc, char** argv, std::string* path, bool* explicit_path,
                       ConfigOutcome* oc) {
  for (int i = 0; i < argc; ++i) {
    const std::string a = argv[i] == 0 ? std::string() : std::string(argv[i]);
    if (a == "--config") {
      if (i + 1 >= argc || argv[i + 1] == 0) {
        oc->ok = false;
        oc->error = "--config 缺少路径";
        return false;
      }
      *path = argv[i + 1];
      *explicit_path = true;
    } else if (a.compare(0, 9, "--config=") == 0) {
      *path = a.substr(9);
      *explicit_path = true;
    }
  }
  return true;
}

bool applyCli(int argc, char** argv, AppConfig* cfg, ConfigOutcome* oc) {
  for (int i = 0; i < argc; ++i) {
    if (argv[i] == 0) continue;
    const std::string a = argv[i];
    if (a == "--verbose") {
      cfg->verbose = true;
      continue;
    }
    // main 侧处理的开关（此处跳过，避免被当键值对）。
    if (a == "--help" || a == "--self-test" || a == "--dump-config") continue;
    if (a == "--config") {
      ++i;  // 值已在预扫描消费
      continue;
    }
    if (a.compare(0, 9, "--config=") == 0) continue;
    if (a.compare(0, 2, "--") != 0) {
      oc->notes.push_back("忽略非选项参数：" + a);
      continue;
    }
    std::string key;
    std::string value;
    const std::string::size_type eq = a.find('=');
    if (eq != std::string::npos) {
      key = a.substr(2, eq - 2);
      value = a.substr(eq + 1);
    } else {
      key = a.substr(2);
      const KeyDef* def = findKey(key);
      // 仅已知键才消费下一个参数（避免误吞位置参数 / 开关）。
      if (def == 0 || def->secret) {
        oc->ok = false;
        oc->error = def != 0 ? ("敏感项不得经 CLI 传入（仅 env）：" + key)
                             : ("未知 CLI 选项：" + a);
        return false;
      }
      if (i + 1 >= argc || argv[i + 1] == 0) {
        oc->ok = false;
        oc->error = "选项缺少取值：" + a;
        return false;
      }
      value = argv[++i];
    }
    const KeyDef* def = findKey(key);
    if (def != 0 && def->secret) {
      oc->ok = false;
      oc->error = "敏感项不得经 CLI 传入（仅 env）：" + key;
      return false;
    }
    if (!applyKey(key, value, cfg, oc)) return false;
  }
  return true;
}

}  // namespace

AppConfig::AppConfig()
    : tcp_bind("127.0.0.1"),
      tcp_port(hg::hgcp::kDefaultHgTcpPort),
      heartbeat_ms(1000),
      cmd_timeout_ms(3000),
      confirm_timeout_ms(60000),
      flow_dir("./data/flow"),
      flow_fsync_mode("BATCH"),
      flow_retain_days(90),
      replay_query_timeout_ms(20000),
      replay_progress_every(1000),
      ctp_env_type("SIMULATION"),
      pump_timeout_ms(50),
      verbose(false) {}

hg::ctp_adapter::CtConfig AppConfig::toCtConfig() const {
  hg::ctp_adapter::CtConfig c;
  c.trade_front = ctp_trade_front;
  c.md_front = ctp_md_front;
  c.broker_id = ctp_broker_id;
  c.user_id = ctp_user_id;
  c.password = ctp_password;  // 仅内存；日志一律走 redacted()
  c.flow_path = ctp_flow_path;
  c.auth_code = ctp_auth_code;
  c.app_id = ctp_app_id;
  c.env_type = (ctp_env_type == "PRODUCTION") ? hg::ctp_adapter::CtEnvType::kProduction
                                             : hg::ctp_adapter::CtEnvType::kSimulation;
  return c;
}

ConfigOutcome loadAppConfig(int argc, char** argv, AppConfig* out) {
  ConfigOutcome oc;
  if (out == 0) {
    oc.ok = false;
    oc.error = "loadAppConfig: out 为空";
    return oc;
  }

  // ① 属性文件（路径：CLI --config > env HG_CONFIG_FILE > ./hg.properties）
  std::string path;
  bool explicit_path = false;
  if (!prescanConfigPath(argc, argv, &path, &explicit_path, &oc)) return oc;
  if (!explicit_path) {
    const char* env_path = std::getenv("HG_CONFIG_FILE");
    if (env_path != 0 && env_path[0] != '\0') {
      path = env_path;
      explicit_path = true;
    } else {
      path = "./hg.properties";
    }
  }
  if (explicit_path && !fileExists(path)) {
    oc.ok = false;
    oc.error = "指定的属性文件不存在：" + path;
    return oc;
  }
  if (!loadPropertiesFile(path, explicit_path, out, &oc)) return oc;
  if (!explicit_path && out->config_file.empty()) {
    oc.notes.push_back("未发现缺省属性文件 " + path + "（仅用 env/CLI/内置缺省）");
  }

  // ② env（敏感项唯一来源）
  if (!applyEnv(out, &oc)) return oc;

  // ③ CLI（最高优先级）
  if (!applyCli(argc, argv, out, &oc)) return oc;

  return oc;
}

ConfigOutcome validateAppConfig(const AppConfig& cfg) {
  ConfigOutcome oc;

  // CFG-20：bind 白名单仅 127.0.0.1；端口 1..65535（端口 0 在配置口径非法，
  // 内核分配仅属 TcpListener 原语层测试语义——见 hgcp/tcp.h「校验层级」）。
  hg::hgcp::TcpConfig tcp;
  tcp.bind_address = cfg.tcp_bind;
  tcp.port = cfg.tcp_port;
  const hg::hgcp::BindStatus bs = hg::hgcp::validate_bind(tcp);
  if (bs != hg::hgcp::BindStatus::kOk) {
    oc.ok = false;
    oc.error = std::string("HGCP 监听配置非法（") + hg::hgcp::to_string(bs) +
               "）：hg.tcp.bind=" + cfg.tcp_bind + " hg.tcp.port=" +
               std::to_string(static_cast<unsigned>(cfg.tcp_port)) +
               "；仅允许 127.0.0.1:1..65535，拒绝启动（FR-CONFIG-9）";
    return oc;
  }

  // CFG-28：心跳 / 命令超时 / 确认超时
  if (cfg.heartbeat_ms < 100 || cfg.heartbeat_ms > 60000) {
    oc.ok = false;
    oc.error = "hg.tcp.heartbeat_ms 须在 100..60000（实得 " +
               std::to_string(static_cast<long long>(cfg.heartbeat_ms)) + "）";
    return oc;
  }
  if (cfg.cmd_timeout_ms <= 0) {
    oc.ok = false;
    oc.error = "hg.cmd_timeout_ms 须 > 0";
    return oc;
  }
  if (cfg.confirm_timeout_ms <= 0) {
    oc.ok = false;
    oc.error = "hg.trade.confirm_timeout_ms 须 > 0";
    return oc;
  }

  // CFG-29：流水目录 / fsync 模式 / 保留天数
  if (cfg.flow_dir.empty()) {
    oc.ok = false;
    oc.error = "hg.flow.dir 不得为空";
    return oc;
  }
  bool fsync_ok = false;
  (void)hg::flow_store::parseFlowFsyncMode(cfg.flow_fsync_mode.c_str(), &fsync_ok);
  if (!fsync_ok) {
    oc.ok = false;
    oc.error = "hg.flow.fsync_mode 非法（应为 EVERY_RECORD/BATCH/ASYNC）：" +
               cfg.flow_fsync_mode;
    return oc;
  }
  if (cfg.flow_retain_days < 1) {
    oc.ok = false;
    oc.error = "hg.flow.retain_days 须 >= 1（0 会删除全部分片）";
    return oc;
  }

  // 重放参数
  if (cfg.replay_query_timeout_ms <= 0) {
    oc.ok = false;
    oc.error = "hg.replay.query_timeout_ms 须 > 0";
    return oc;
  }
  if (cfg.replay_progress_every == 0) {
    oc.ok = false;
    oc.error = "replay_progress_every 须 > 0";
    return oc;
  }

  // CFG-22：env_type 仅 SIMULATION / PRODUCTION（缺省 SIMULATION = 评测前置，保守）
  if (cfg.ctp_env_type != "SIMULATION" && cfg.ctp_env_type != "PRODUCTION") {
    oc.ok = false;
    oc.error = "hg.ctp.env_type 非法（应为 SIMULATION/PRODUCTION）：" + cfg.ctp_env_type;
    return oc;
  }

  // CTP 前置形态（可空 = 不登录 ⇒ DEGRADED，不阻塞启动）
  const std::string* fronts[2] = {&cfg.ctp_trade_front, &cfg.ctp_md_front};
  for (int i = 0; i < 2; ++i) {
    if (!fronts[i]->empty() && fronts[i]->find("://") == std::string::npos) {
      oc.ok = false;
      oc.error = "CTP 前置地址须形如 tcp://ip:port：" + *fronts[i];
      return oc;
    }
  }

  // 主循环节拍
  if (cfg.pump_timeout_ms < 1 || cfg.pump_timeout_ms > 1000) {
    oc.ok = false;
    oc.error = "pump_timeout_ms 须在 1..1000";
    return oc;
  }

  return oc;
}

std::string redactedSummary(const AppConfig& cfg) {
  const std::string empty_mark = "<empty>";
  const std::string redacted = "<redacted>";
  std::string s;
  s += "hg.tcp.bind=" + cfg.tcp_bind;
  s += " hg.tcp.port=" + std::to_string(static_cast<unsigned>(cfg.tcp_port));
  s += " hg.tcp.heartbeat_ms=" + std::to_string(static_cast<long long>(cfg.heartbeat_ms));
  s += " hg.cmd_timeout_ms=" + std::to_string(static_cast<long long>(cfg.cmd_timeout_ms));
  s += " hg.trade.confirm_timeout_ms=" +
       std::to_string(static_cast<long long>(cfg.confirm_timeout_ms));
  s += " hg.flow.dir=" + cfg.flow_dir;
  s += " hg.flow.fsync_mode=" + cfg.flow_fsync_mode;
  s += " hg.flow.retain_days=" + std::to_string(cfg.flow_retain_days);
  s += " hg.replay.query_timeout_ms=" +
       std::to_string(static_cast<long long>(cfg.replay_query_timeout_ms));
  s += " hg.ctp.env_type=" + cfg.ctp_env_type;
  s += " hg.ctp.trade_front=" + (cfg.ctp_trade_front.empty() ? empty_mark : cfg.ctp_trade_front);
  s += " hg.ctp.md_front=" + (cfg.ctp_md_front.empty() ? empty_mark : cfg.ctp_md_front);
  s += " hg.ctp.broker_id=" + (cfg.ctp_broker_id.empty() ? empty_mark : cfg.ctp_broker_id);
  s += " hg.ctp.user_id=" + (cfg.ctp_user_id.empty() ? empty_mark : cfg.ctp_user_id);
  s += " hg.ctp.flow_path=" + (cfg.ctp_flow_path.empty() ? empty_mark : cfg.ctp_flow_path);
  // 敏感项：只报「是否已提供」，永不回显值/长度/片段。
  s += " hg.ctp.password=" + (cfg.ctp_password.empty() ? empty_mark : redacted);
  s += " hg.ctp.auth_code=" + (cfg.ctp_auth_code.empty() ? empty_mark : redacted);
  s += " hg.ctp.app_id=" + (cfg.ctp_app_id.empty() ? empty_mark : redacted);
  s += " hg.hgcp.auth_token=" + (cfg.hgcp_auth_token.empty() ? empty_mark : redacted);
  s += " config_file=" + (cfg.config_file.empty() ? empty_mark : cfg.config_file);
  return s;
}

}  // namespace app
}  // namespace hg
