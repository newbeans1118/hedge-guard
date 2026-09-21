/*
 * 会话与「当前子账户 / 汇总视图」状态。
 *
 * 与已实现契约对齐（实测 Handler）：
 *  - 登录：POST /auth/login {username,password} → data {userId, username, expireTime}，并下发 HGSESSION Cookie（HttpOnly）。
 *  - 会话有效性探针：GET /config/sub-account（**已注册且需认证**）。未认证时后端对任何 /api/v1/** 一律回 10006
 *    （先认证再路由，不区分路径是否存在）——故探测即可判定登录态，且同时取得子账户清单。
 *  - ⚠️ GET /auth/user 与 /auth/sub-account-perm **未注册**（见 Q-FE-001），本层**不调用**。
 *  - 子账户：软隔离（AUTH §2 / NFR-SEC-7）；切换器形态「下拉 + 汇总选项」——1 次点开 + 1 次选择 = ≤ 2 次点击（NFR-USE-5）。
 *
 * 本层不做权限判定（越权由后端 10004 拒绝并留痕）；只持**展示用**会话摘要（不含任何凭据）。
 */
import { HG } from './ns.js';
import './routes.js';

const ALL = 'ALL';
const SEL_KEY = 'hg.subAccount.current';
const SUMMARY_KEY = 'hg.session.summary';

const state = {
  user: null,           // { userId, username, expireTime }（展示用；不含凭据）
  subAccounts: [],      // [{ id, code, name, category, status, initialFund }]
  current: ALL,         // ALL = 汇总视图
  degraded: false,      // 后端不可达
  loaded: false
};

const listeners = [];

function emit() { listeners.forEach((fn) => { try { fn(state); } catch (e) { /* 订阅者异常不阻断广播 */ } }); }
function onChange(fn) { listeners.push(fn); return () => { const i = listeners.indexOf(fn); if (i >= 0) listeners.splice(i, 1); }; }

function currentId() { return state.current === ALL ? null : state.current; }
function isAggregate() { return state.current === ALL; }

function setCurrent(value) {
  state.current = value || ALL;
  try { window.sessionStorage.setItem(SEL_KEY, state.current); } catch (e) { /* 忽略 */ }
  emit();
}

function restoreSelection() {
  try { const saved = window.sessionStorage.getItem(SEL_KEY); if (saved) state.current = saved; } catch (e) { /* 忽略 */ }
}

/** 记录登录摘要（登录成功后由 login 页调用；不含会话 ID / 口令）。 */
function setSessionSummary(body) {
  if (!body) { state.user = null; try { window.sessionStorage.removeItem(SUMMARY_KEY); } catch (e) {} return; }
  state.user = { userId: body.userId, username: body.username, expireTime: body.expireTime };
  try { window.sessionStorage.setItem(SUMMARY_KEY, JSON.stringify(state.user)); } catch (e) { /* 忽略 */ }
}

function restoreSummary() {
  try { const raw = window.sessionStorage.getItem(SUMMARY_KEY); if (raw) state.user = JSON.parse(raw); } catch (e) { /* 忽略 */ }
}

function clearSessionSummary() {
  state.user = null;
  try { window.sessionStorage.removeItem(SUMMARY_KEY); } catch (e) { /* 忽略 */ }
}

/** 子账户列表（bare 数组，字段 code/name/category/status，见 SubAccountHandler.SubAccountView）。 */
function normalize(data) {
  const items = Array.isArray(data) ? data : (data && data.items) || [];
  return items.map((it) => ({
    id: it.id,
    code: it.code || it.subAccountCode || '',
    name: it.name || it.subAccountName || '',
    category: it.category || '',
    status: it.status || ''
  })).filter((s) => s.id !== undefined && s.id !== null);
}

/**
 * 引导会话：以 GET /config/sub-account 为认证探针。
 * @returns {Promise<{ok:boolean, unauthenticated:boolean, degraded:boolean, error:object|null}>}
 */
async function bootstrap() {
  restoreSelection();
  restoreSummary();
  try {
    const data = await HG.api.get(HG.routes.CONFIG.SUB_ACCOUNT);
    state.subAccounts = normalize(data);
    state.degraded = false;
    state.loaded = true;
    if (state.current !== ALL && !state.subAccounts.some((s) => String(s.id) === String(state.current))) setCurrent(ALL);
    emit();
    return { ok: true, unauthenticated: false, degraded: false, error: null };
  } catch (err) {
    if (HG.errmsg.isUnauthenticated(err)) {
      state.loaded = true;
      return { ok: false, unauthenticated: true, degraded: false, error: err };
    }
    state.subAccounts = [];
    state.degraded = true;
    state.loaded = true;
    emit();
    return { ok: false, unauthenticated: false, degraded: true, error: err };
  }
}

function userLabel() {
  if (!state.user) return state.degraded ? '（未连接）' : '（已登录）';
  return state.user.username || ('#' + state.user.userId);
}

HG.session = {
  ALL, state, onChange, currentId, isAggregate, setCurrent, bootstrap,
  setSessionSummary, clearSessionSummary, userLabel
};
export default HG.session;
