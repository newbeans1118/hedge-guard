/*
 * 主框架引导（index.html）—— 顶栏 + 子账户/汇总切换 + 侧栏 + 路由。
 *
 * 依据：ARCHITECTURE.md §3.15（前端结构）、§8.8（易用性）。
 * NFR-USE-5：子账户 / 汇总视图切换 ≤ 2 次点击——顶部下拉 1 次点开 + 1 次选择，「汇总」为选项之一。
 * 会话：启动时探测 /auth/user；未登录 → 跳登录页；后端不可达 / 未装配 → 降级为界面预览并明确告警。
 */
import { HG } from './core/ns.js';
import './core/format.js';
import './core/errmsg.js';
import './core/api.js';
import './core/ui.js';
import './core/session.js';
import './core/router.js';
import './core/routes.js';
import './core/ws.js';

// 页面模块（注册各自路由）
import './pages/_stub.js';
import './pages/_dashboard.js';
import './pages/product.js';
import './pages/spot.js';
import './pages/plan.js';
import './pages/trade.js';
import './pages/settle.js';
import './pages/risk.js';
import './pages/audit.js';
import './pages/acct.js';
import './pages/fund.js';
import './pages/auth.js';
import './pages/config.js';

const ui = HG.ui;

const NAV = [
  { group: '业务主线', items: [
    { href: '#/dashboard', label: '工作台' },
    { href: '#/product', label: '品种管理' },
    { href: '#/spot', label: '现货录入' },
    { href: '#/spot/exposure', label: '实货敞口' },
    { href: '#/plan', label: '套保计划' },
    { href: '#/trade', label: '交易执行' },
    { href: '#/settle', label: '结算' },
    { href: '#/fund', label: '资金管理' }
  ] },
  { group: '风控与合规', items: [
    { href: '#/risk', label: '风险控制' },
    { href: '#/audit', label: '审计' },
    { href: '#/acct', label: '套保会计' }
  ] },
  { group: '系统支撑', items: [
    { href: '#/auth', label: '用户与权限' },
    { href: '#/config', label: '系统配置' }
  ] }
];

function buildTopbar() {
  const bar = ui.el('header', { class: 'hg-topbar', role: 'banner' });
  bar.appendChild(ui.el('div', { class: 'hg-topbar__brand', text: 'HedgeGuard 套期保值管理' }));
  bar.appendChild(ui.el('div', { class: 'hg-topbar__spacer' }));

  // 子账户 / 汇总 切换器
  const wrap = ui.el('div', { class: 'hg-subacct' });
  wrap.appendChild(ui.el('label', { for: 'hg-sub-select', text: '视图' }));
  const sel = ui.el('select', { id: 'hg-sub-select', 'aria-label': '子账户 / 汇总视图切换' });
  wrap.appendChild(sel);
  sel.addEventListener('change', () => { HG.session.setCurrent(sel.value); });
  bar.appendChild(wrap);

  const userBox = ui.el('div', { class: 'hg-topbar__user' });
  const userLabel = ui.el('span', { id: 'hg-user-label' });
  userBox.appendChild(userLabel);
  const logout = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '登出', onclick: doLogout });
  userBox.appendChild(logout);
  bar.appendChild(userBox);

  function refreshSubSelect() {
    const prev = sel.value || HG.session.state.current || HG.session.ALL;
    sel.innerHTML = '';
    sel.appendChild(ui.el('option', { value: HG.session.ALL, text: '汇总（全部授权子账户）' }));
    HG.session.state.subAccounts.forEach((s) => {
      sel.appendChild(ui.el('option', { value: String(s.id), text: (s.code ? s.code + ' ' : '') + (s.name || ('#' + s.id)) }));
    });
    sel.value = (prev === HG.session.ALL || HG.session.state.subAccounts.some((s) => String(s.id) === String(prev))) ? prev : HG.session.ALL;
  }
  refreshSubSelect();
  HG.session.onChange(refreshSubSelect);

  function refreshUser() {
    const u = HG.session.userLabel();
    const r = HG.session.roleLabel();
    userLabel.textContent = r ? (u + '（' + r + '）') : u;
  }
  refreshUser();
  HG.session.onChange(refreshUser);

  return bar;
}

async function doLogout() {
  const ok = await ui.confirm('确认退出登录？', { okText: '退出', danger: false });
  if (!ok) return;
  try { await HG.api.post(HG.routes.AUTH.LOGOUT, {}); } catch (e) { /* 登出失败也回登录页（会话在服务端可能已失效） */ }
  HG.session.clearSessionSummary();
  window.location.href = '/web/login.html';
}

function buildSidebar() {
  const aside = ui.el('aside', { class: 'hg-sidebar' });
  const nav = ui.el('nav', { 'aria-label': '主导航' });
  NAV.forEach((g) => {
    nav.appendChild(ui.el('div', { class: 'hg-nav__group', text: g.group }));
    const ul = ui.el('ul', { class: 'hg-nav' });
    g.items.forEach((it) => ul.appendChild(ui.el('li', {}, [ui.el('a', { href: it.href, text: it.label })])));
    nav.appendChild(ul);
  });
  aside.appendChild(nav);
  return aside;
}

async function main() {
  const app = document.getElementById('hg-app');
  app.appendChild(buildTopbar());
  const layout = ui.el('div', { class: 'hg-layout' });
  layout.appendChild(buildSidebar());
  const mainEl = ui.el('main', { class: 'hg-main', id: 'hg-main', role: 'main' });
  layout.appendChild(mainEl);
  app.appendChild(layout);

  const loading = ui.el('div', { class: 'hg-state' });
  mainEl.appendChild(loading);
  ui.renderLoading(loading, '正在初始化会话…');

  const res = await HG.session.bootstrap();
  if (res.unauthenticated) {
    window.location.href = '/web/login.html';
    return;
  }
  if (res.degraded) {
    const b = ui.banner(
      '<strong>后端不可达 / 尚未装配</strong>：无法加载用户与子账户（' + ui.esc(HG.errmsg.summary(res.error)) + '）。' +
      '当前为<strong>界面预览</strong>模式，任何数据操作都会失败——这是已知状态（Java 侧业务层尚未接线）。',
      'error',
      [ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '重试', onclick: () => window.location.reload() })]
    );
    layout.insertBefore(b, layout.firstChild.nextSibling);
  }

  HG.router.start(mainEl);
}

main();
