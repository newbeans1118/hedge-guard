/*
 * 会话与「当前子账户 / 汇总视图」状态。
 *
 * 与已实现契约对齐（实测 Handler）：
 *  - 登录：POST /auth/login {username,password} → data {userId, username, expireTime}，并下发 HGSESSION Cookie（HttpOnly）。
 *  - 会话有效性探针：GET /config/sub-account（**已注册且需认证**）。未认证时后端对任何 /api/v1/** 一律回 10006
 *    （先认证再路由，不区分路径是否存在）——故探测即可判定登录态，且同时取得子账户清单。
 *  - `GET /auth/user`、`GET /auth/sub-account-perm` 亦**已注册**（`T-010` 补齐；见 `routes.js` 镜像），
 *    本层**不依赖**它们 —— 统一以 `/config/sub-account` 作认证探针并同时取得子账户清单。
 *  - 子账户：软隔离（AUTH §2 / NFR-SEC-7）；切换器形态「下拉 + 汇总选项」——1 次点开 + 1 次选择 = ≤ 2 次点击（NFR-USE-5）。
 *
 * 本层不做权限判定（越权由后端 10004 拒绝并留痕）；只持**展示用**会话摘要（不含任何凭据）。
 */
import { HG } from './ns.js';
import './routes.js';

const ALL = 'ALL';
const SEL_KEY = 'hg.subAccount.current';
const SUMMARY_KEY = 'hg.session.summary';
// FR-AUTH-16「须改口令」门禁标记：落 **localStorage** 以便**跨标签页 / 新窗口**共享——
// 新标签页无 sessionStorage 摘要时，仍能识别门禁态，不会把服务端门禁的 10003 误报为「后端不可达」。
// ⚠️ 该标记仅是**界面引导**用（布尔值，非凭据）；门禁与放行**一律以服务端为唯一权威**。
const GATE_KEY = 'hg.session.gate.mustChangePassword';

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

/** 读取「须改口令」门禁标记（跨标签页共享；localStorage 不可用时降级为「无标记」）。 */
function readGate() {
  try { return window.localStorage.getItem(GATE_KEY) === '1'; } catch (e) { return false; }
}

/** 写入 / 清除「须改口令」门禁标记（存储被禁用时静默降级：仅依赖本次会话内存态）。 */
function writeGate(on) {
  try {
    if (on) window.localStorage.setItem(GATE_KEY, '1');
    else window.localStorage.removeItem(GATE_KEY);
  } catch (e) { /* 隐私模式 / 存储被禁用：忽略（服务端仍是权威） */ }
}

/** 记录登录摘要（登录成功后由 login 页调用；不含会话 ID / 口令）。 */
function setSessionSummary(body) {
  if (!body) { state.user = null; writeGate(false); try { window.sessionStorage.removeItem(SUMMARY_KEY); } catch (e) {} return; }
  state.user = {
    userId: body.userId, username: body.username, expireTime: body.expireTime,
    // FR-AUTH-16：登录响应含 mustChangePassword；门禁由服务端强制，此处仅用于把用户引导到改密界面。
    mustChangePassword: !!body.mustChangePassword
  };
  writeGate(state.user.mustChangePassword); // 同步跨标签页门禁标记（新标签页据此识别门禁态）
  try { window.sessionStorage.setItem(SUMMARY_KEY, JSON.stringify(state.user)); } catch (e) { /* 忽略 */ }
}

function restoreSummary() {
  try { const raw = window.sessionStorage.getItem(SUMMARY_KEY); if (raw) state.user = JSON.parse(raw); } catch (e) { /* 忽略 */ }
}

function clearSessionSummary() {
  state.user = null;
  writeGate(false);
  try { window.sessionStorage.removeItem(SUMMARY_KEY); } catch (e) { /* 忽略 */ }
}

/**
 * 当前会话是否处于「须强制修改口令」状态（FR-AUTH-16；服务端强制，此处仅用于界面引导）。
 * 读**跨标签页**的 localStorage 标记（新标签页无 sessionStorage 摘要时同样成立），
 * 并兼容同一标签页内存中的会话摘要。
 */
function mustChangePassword() { return readGate() || !!(state.user && state.user.mustChangePassword); }

/** 清除「须强制修改口令」状态（改密成功后由 auth 页调用；与服务端解除门禁保持一致）。 */
function clearMustChangePassword() {
  writeGate(false); // 跨标签页标记：新标签页（state.user 为 null）亦须一并清除
  if (!state.user) return;
  state.user.mustChangePassword = false;
  try { window.sessionStorage.setItem(SUMMARY_KEY, JSON.stringify(state.user)); } catch (e) { /* 忽略 */ }
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
 * 门禁标记（localStorage，跨标签页）用于**给拒绝定性**；但**服务端是唯一权威**：
 *   探针成功           ⇒ 会话有效且未门禁（若标记仍在则自愈清除）；
 *   10006/10007/10008 ⇒ 未登录 / 失效 ⇒ 跳登录页（**覆盖**陈旧标记，不回落到门禁态）；
 *   网络错 / 5xx      ⇒ 真·后端不可达 ⇒ 降级告警；
 *   其余业务拒绝 + 门禁标记 ⇒ FR-AUTH-16 门禁 ⇒ 引导改密（**不**报「后端不可达」）；
 *   其余业务拒绝 且无标记   ⇒ 中立态（典型 10003 功能权限不足），如实呈现。
 * @returns {Promise<{ok:boolean, unauthenticated:boolean, degraded:boolean,
 *   mustChangePassword?:boolean, forbidden?:boolean, error:object|null}>}
 */
async function bootstrap() {
  restoreSelection();
  restoreSummary();
  const gated = mustChangePassword(); // 跨标签页门禁标记：新标签页无 sessionStorage 摘要时同样读到
  try {
    const data = await HG.api.get(HG.routes.CONFIG.SUB_ACCOUNT);
    // 探针成功 ⇒ 会话有效且未被服务端门禁；标记若仍在（另一标签页已改密 / 服务端已解除）⇒ 自愈清除。
    if (gated) clearMustChangePassword();
    state.subAccounts = normalize(data);
    state.degraded = false;
    state.loaded = true;
    if (state.current !== ALL && !state.subAccounts.some((s) => String(s.id) === String(state.current))) setCurrent(ALL);
    emit();
    return { ok: true, unauthenticated: false, degraded: false, error: null };
  } catch (err) {
    if (HG.errmsg.isUnauthenticated(err)) {
      // 未登录 / 会话失效优先于门禁标记（陈旧标记不得把用户挡在登录页之外）。
      state.loaded = true;
      return { ok: false, unauthenticated: true, degraded: false, error: err };
    }
    if (HG.errmsg.isBackendUnavailable(err)) {
      // 真·后端不可达（fetch 网络错 / 5xx / 非信封响应）—— 如实降级告警（不得为消除误报而吞掉真故障）。
      state.subAccounts = [];
      state.degraded = true;
      state.loaded = true;
      emit();
      return { ok: false, unauthenticated: false, degraded: true, error: err };
    }
    if (gated) {
      // 门禁标记 + 探针被业务拒绝（10003）⇒ 判定 FR-AUTH-16 门禁态：引导改密，**不得**误报「后端不可达」。
      state.loaded = true;
      return { ok: false, unauthenticated: false, degraded: false, mustChangePassword: true, error: err };
    }
    // 后端可达但本请求被业务拒绝且**非**门禁（典型 10003 功能权限不足）：中立态，如实呈现后端结论。
    state.subAccounts = [];
    state.degraded = false;
    state.loaded = true;
    emit();
    return { ok: false, unauthenticated: false, degraded: false, forbidden: true, error: err };
  }
}

function userLabel() {
  if (!state.user) return state.degraded ? '（未连接）' : '（已登录）';
  return state.user.username || ('#' + state.user.userId);
}

HG.session = {
  ALL, state, onChange, currentId, isAggregate, setCurrent, bootstrap,
  setSessionSummary, clearSessionSummary, userLabel, mustChangePassword, clearMustChangePassword
};
export default HG.session;
