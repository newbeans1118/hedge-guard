// =============================================================================
// T-186（TE-T066-001 修复）：Java 复算 ↔ C++ 事前判定 跨栈对拍（C++ 侧）
//
// 共享 golden 向量文件（tests/golden/*.tsv）同时驱动两侧：
//   · C++ 侧（本文件）：以 LoadBatch 构造快照 + OrderRequest，驱动 RiskEngine::check，
//     断言 verdict（kReject/kWarn/kPass）+ 理由码 + 阈值与向量逐字段一致；
//   · Java 侧：T186RiskParityGoldenTest 以 InMemoryDmEngine 播种同一状态，
//     驱动 RiskRecomputeServiceImpl.recomputeScale/recomputeNetPosition/recomputeTerm
//     （T-066 复算镜面），断言同一组字段。
// 对拍口径与刻意差异量化披露见各 golden 文件头注（单一事实源，两侧不得各自漂移）。
//
// 读文件仅出现在本测试（非判定路径；E4 判据为「判定路径调用构造」——本文件不含
// read/write/send/recv/sleep 等被扫调用形态，文件访问用 ifstream + std::getline）。
// =============================================================================
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "risk_engine_test_util.h"

#ifndef HG_GOLDEN_SCALE
#define HG_GOLDEN_SCALE "parity_scale_golden.tsv"
#endif
#ifndef HG_GOLDEN_NETPOS
#define HG_GOLDEN_NETPOS "parity_netpos_golden.tsv"
#endif
#ifndef HG_GOLDEN_TERM
#define HG_GOLDEN_TERM "parity_term_golden.tsv"
#endif

namespace {

using hg::risk_engine::OffsetFlag;
using hg::risk_engine::OrderDirection;
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::exposure;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::ExposureDirection;
using hg::risk_state::LoadBatch;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionType;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;

// ---- TSV 解析（最小实现；# 与空行跳过；列以 \t 分隔）----
std::vector<std::vector<std::string> > goldenRows(const char* path) {
  std::vector<std::vector<std::string> > rows;
  std::ifstream in(path);
  if (!in.is_open()) {
    std::printf("[FAIL] golden 文件不可读: %s\n", path);
    ++hg::risk_engine::testutil::failures();
    return rows;
  }
  std::string line;
  while (std::getline(in, line)) {
    // 防御：剥离 UTF-8 BOM（若编辑工具引入）再判注释行
    if (line.size() >= 3 && static_cast<unsigned char>(line[0]) == 0xEF &&
        static_cast<unsigned char>(line[1]) == 0xBB &&
        static_cast<unsigned char>(line[2]) == 0xBF) {
      line.erase(0, 3);
    }
    if (line.empty() || line[0] == '#') continue;
    std::vector<std::string> cols;
    std::string cell;
    std::istringstream ls(line);
    while (std::getline(ls, cell, '\t')) cols.push_back(cell);
    while (cols.size() < 10) cols.push_back("");
    rows.push_back(cols);
  }
  return rows;
}

std::int64_t num(const std::string& s) { return std::strtoll(s.c_str(), nullptr, 10); }

std::uint32_t hexCode(const std::string& s) {
  return static_cast<std::uint32_t>(std::strtoul(s.c_str(), nullptr, 0));
}

Category categoryOf(const std::string& s) {
  return (s == "TRADE") ? Category::kTrade : Category::kCommodity;
}

// 统一断言：verdict + 理由码 + 阈值逐字段比对。
//   expect_verdict：REJECT / WARN / PASS；expect_code：hex 串或 "-"；
//   expect_thr：阈值（按规则定点的原始 int64）；has_thr=false 时不断言阈值
//   （PASS 向量 C++ 侧无 entries 项；阈值字段的 Java 侧断言见 T186RiskParityGoldenTest）。
void assertParity(const std::string& id, const RiskResult& r, const std::string& verdict,
                  const std::string& code, bool has_thr, std::int64_t expect_thr,
                  RuleId expect_rule) {
  if (verdict == "REJECT") {
    HG_CHECK(r.rejected());
    HG_CHECK_EQ(r.code, hexCode(code));
    HG_CHECK_EQ(r.rule, expect_rule);
    bool found = false;
    for (int i = 0; i < r.entry_count; ++i) {
      if (r.entries[i].rule == expect_rule && r.entries[i].code == hexCode(code)) {
        found = true;
        if (has_thr) HG_CHECK_EQ(static_cast<std::int64_t>(r.entries[i].threshold), expect_thr);
      }
    }
    HG_CHECK(found);
  } else if (verdict == "WARN") {
    HG_CHECK(r.warned());
    HG_CHECK_EQ(r.rule, expect_rule);
    bool found = false;
    for (int i = 0; i < r.entry_count; ++i) {
      if (r.entries[i].rule == expect_rule) {
        found = true;
        if (has_thr) HG_CHECK_EQ(static_cast<std::int64_t>(r.entries[i].threshold), expect_thr);
      }
    }
    HG_CHECK(found);
  } else {
    HG_CHECK(r.passed());
  }
  if (hg::risk_engine::testutil::failures() != 0) {
    std::printf("       （失败向量 id=%s）\n", id.c_str());
  }
}

// ---- R-RISK-4 规模红线 ----
void parityScale() {
  const std::vector<std::vector<std::string> > rows = goldenRows(HG_GOLDEN_SCALE);
  for (std::size_t k = 0; k < rows.size(); ++k) {
    const std::vector<std::string>& f = rows[k];
    const std::string id = f[0];
    RiskState st;
    FakeClock clk(20260101);
    RiskEngine eng(&clk);
    LoadBatch b = happyBatch(1);
    if (f[1] == "TRADE") b.putSubProductCategory(1, 10, Category::kTrade);
    if (num(f[2]) >= 0) {
      AnnualScaleEntry a;
      a.expected_spot_business_qty = num(f[2]);
      a.accumulated_hedge_qty = num(f[3]);
      a.category = categoryOf(f[1]);
      a.scale_ratio_limit = 0;
      a.scope = ScaleScope::kSubAccount;
      b.putAnnualScale(ScaleScope::kSubAccount, 1, 10, 2026, a);
    }
    if (num(f[4]) >= 0) {
      AnnualScaleEntry t;
      t.expected_spot_business_qty = num(f[4]);
      t.accumulated_hedge_qty = num(f[5]);
      t.category = categoryOf(f[1]);
      t.scale_ratio_limit = 0;
      t.scope = ScaleScope::kTotalAccount;
      b.putAnnualScale(ScaleScope::kTotalAccount, 0, 10, 2026, t);
    }
    HG_CHECK(bind(&st, &eng, b));
    OrderRequest q = baseOrder();
    q.volume = num(f[6]);  // inc（合约乘数 = 换算系数 = 1：手数即实物量）
    const RiskResult r = eng.check(q);
    const std::string thr = f[9];
    assertParity(id, r, f[7], f[8], thr != "-", (thr == "-") ? 0 : num(thr),
                 RuleId::kScaleRedline);
  }
}

// ---- R-RISK-5 时点净持仓 ----
void parityNetPosition() {
  const std::vector<std::vector<std::string> > rows = goldenRows(HG_GOLDEN_NETPOS);
  for (std::size_t k = 0; k < rows.size(); ++k) {
    const std::vector<std::string>& f = rows[k];
    const std::string id = f[0];
    RiskState st;
    FakeClock clk(20260101);
    RiskEngine eng(&clk);
    LoadBatch b = happyBatch(1);
    if (num(f[2]) > 0) {
      PositionEntry p;
      p.position_qty = num(f[2]);
      b.putPosition(1, 100, PositionDirection::kLong, PositionType::kYesterday, p);
    }
    if (num(f[3]) > 0) {
      PositionEntry p;
      p.position_qty = num(f[3]);
      b.putPosition(1, 100, PositionDirection::kShort, PositionType::kYesterday, p);
    }
    if (num(f[5]) >= 0) {
      b.putExposure(1, 10, ExposureDirection::kBuy, exposure(num(f[5]), 555));
    }
    if (num(f[6]) >= 0) {
      b.putExposure(1, 10, ExposureDirection::kSell, exposure(num(f[6]), 555));
    }
    HG_CHECK(bind(&st, &eng, b));
    OrderRequest q = baseOrder();
    q.volume = num(f[4]);  // inc
    q.direction = (f[1] == "SELL") ? OrderDirection::kSell : OrderDirection::kBuy;
    const RiskResult r = eng.check(q);
    const std::string thr = f[9];
    assertParity(id, r, f[7], f[8], thr != "-", (thr == "-") ? 0 : num(thr),
                 RuleId::kNetPosition);
  }
}

// ---- R-RISK-6 期限红线 ----
void parityTerm() {
  const std::vector<std::vector<std::string> > rows = goldenRows(HG_GOLDEN_TERM);
  for (std::size_t k = 0; k < rows.size(); ++k) {
    const std::vector<std::string>& f = rows[k];
    const std::string id = f[0];
    RiskState st;
    FakeClock clk(num(f[1]));  // today
    RiskEngine eng(&clk);
    LoadBatch b = happyBatch(1);
    HG_CHECK(bind(&st, &eng, b));
    OrderRequest q = baseOrder();
    q.open_date = num(f[2]);
    q.term_end_date = num(f[3]);
    q.hedge_contract_expiry = (num(f[4]) >= 0) ? num(f[4]) : 0;
    q.term_extension_approved = (num(f[5]) != 0);
    const RiskResult r = eng.check(q);
    const std::string thr = f[8];
    assertParity(id, r, f[6], f[7], thr != "-", (thr == "-") ? 0 : num(thr), RuleId::kTerm);
  }
}

}  // namespace

int main() {
  parityScale();
  parityNetPosition();
  parityTerm();
  return hg::risk_engine::testutil::report("risk_engine_parity_golden_test");
}
