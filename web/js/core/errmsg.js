/*
 * 错误信息渲染（ARCHITECTURE.md §4.1.1 / §4.3.1 / §8.8）。
 * 硬要求（NFR-USE-3 / NFR-USE-4）：阻断类错误必须渲染四项——
 *   ruleId + current + threshold + nextStep；前端不得只显示"操作不允许"。
 */
import { HG } from './ns.js';
import './format.js'; // 建立依赖顺序：errmsg 顶层读取 HG.format

const F = HG.format;

/** 业务错误码 → 展示用语义（message 仍以后端返回为准，此处仅兜底）。 */
const CODE_HINT = {
  '-1': '请求未完成',
  '10001': '参数校验失败',
  '10002': '交易组件通信不可用',
  '10003': '功能权限不足',
  '10004': '子账户数据权限不足',
  '10005': '岗位分离冲突',
  '10006': '未登录或会话超时',
  '10007': '认证失败',
  '10008': '账号锁定',
  '10009': '状态不允许',
  '10010': '唯一约束冲突',
  '10011': '关联引用冲突',
  '10012': '合规门禁未通过',
  '10013': '加总对账不一致（已阻断）',
  '10014': '参数下发未生效',
  '10015': '数据不足，拒绝计算',
  '10016': '期间已关账',
  '10017': '审计日志写入失败',
  '10018': '审计日志防篡改校验未通过',
  '10019': '导入文件校验失败',
  '10020': '敏感字段操作被拒',
  '10021': '安全配置被拒',
  '10022': '内部错误'
};

/** 是否为「阻断类」错误（须渲染四项）。 */
function isBlocked(err) {
  return !!(err && (err.ruleId || err.current !== undefined || err.threshold !== undefined || err.nextStep));
}

/** 会话失效（须跳转登录）。 */
function isUnauthenticated(err) {
  return !!err && (err.code === 10006 || err.code === 10007 || err.code === 10008);
}

/** 是否为网络 / 未装配导致的失败（非业务错误）。 */
function isTransport(err) { return !!err && err._network === true; }

/**
 * 后端是否**确实不可达**（区别于「后端可达、业务拒绝」）。
 *   - 网络层失败：`fetch` 抛错 / 非统一信封响应（`_network === true`）；
 *   - 服务端错误：HTTP 状态码 ≥ 500。
 * 用途：会话引导据此判定是否降级告警；**10003 等功能权限拒绝不属此项**（后端是活的）。
 */
function isBackendUnavailable(err) {
  if (!err) return false;
  if (isTransport(err)) return true;
  return typeof err._http === 'number' && err._http >= 500;
}

/** 单行摘要文本。 */
function summary(err) {
  if (!err) return '未知错误';
  const hint = CODE_HINT[String(err.code)];
  const msg = err.message || hint || '请求失败';
  return String(err.code) && String(err.code) !== '0' ? `[${err.code}] ${msg}` : msg;
}

/** 渲染为 HTML 片段：普通错误取 message；阻断类展开四项。 */
function render(err) {
  const safeMsg = F.esc(summary(err));
  if (!isBlocked(err)) {
    let extra = '';
    if (isTransport(err)) {
      extra = '<div class="hg-muted">请确认 Java 业务组件已启动且已完成装配，然后重试。</div>';
    } else if (err && err.nextStep) {
      extra = `<div class="hg-muted">下一步：${F.esc(err.nextStep)}</div>`;
    }
    return `<div class="hg-blocked"><div class="hg-blocked__msg">${safeMsg}</div>${extra}</div>`;
  }
  const rows = [];
  if (err.ruleId) rows.push(`<dt>规则编号</dt><dd>${F.esc(err.ruleId)}</dd>`);
  if (err.current !== undefined && err.current !== null) rows.push(`<dt>当前值</dt><dd>${F.esc(err.current)}</dd>`);
  if (err.threshold !== undefined && err.threshold !== null) rows.push(`<dt>阈值</dt><dd>${F.esc(err.threshold)}</dd>`);
  if (err.nextStep) rows.push(`<dt>下一步</dt><dd>${F.esc(err.nextStep)}</dd>`);
  if (err.traceId) rows.push(`<dt>traceId</dt><dd class="hg-mono">${F.esc(err.traceId)}</dd>`);
  return `<div class="hg-blocked"><div class="hg-blocked__msg">${safeMsg}</div><dl>${rows.join('')}</dl></div>`;
}

HG.errmsg = { render, summary, isBlocked, isUnauthenticated, isTransport, isBackendUnavailable, CODE_HINT };
export default HG.errmsg;
