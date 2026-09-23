/*
 * 用户与权限页面（AUTH）—— 管理界面（FR-AUTH-1~18；T-021）。
 *
 * 说明：登录页独立为 login.html + js/pages/login.js；本文件为 AUTH 模块的**管理界面**
 *       （用户 / 角色 / 岗位组 / 人员授权 / 子账户数据权限 / 变更留痕 / 越权尝试 / 口令管理）。
 *
 * ## 字段名以已注册 Handler 的请求 / 视图类为准（camelCase；契约逐字对齐）
 *   GET  /auth/user?page&size&sort&status&leader      → PageData<UserView>
 *        UserView {id, username, realName, department, isEnterpriseLeader(Y/N), status,
 *                  lastLoginTime, roleCodes[], subAccountScope[]}（**无任何口令字段**）
 *   POST /auth/user  {username, realName, naturalPersonKey, department, isEnterpriseLeader, initialPassword} → id
 *   PUT  /auth/user  {action:UPDATE|DISABLE|ENABLE, userId, realName?, department?,
 *                     isEnterpriseLeader?, reason?, reviewerId?} → null（ENABLE → {oneTimePassword}）
 *   GET  /auth/role?page&size&sort                     → PageData<Role>
 *        Role {id, roleCode, roleName, positionGroupId, isPreset, isBusinessRole, hasTradingOperation, status}
 *   POST /auth/role  {action:DEFINE|GRANT, roleCode?, roleName?, positionGroupId?, hasTradingOperation?,
 *                     userId?, roleId?, reviewerId}
 *   GET  /auth/position-group?page&size&sort           → {groups:[PositionGroup], conflicts:PageData<PositionConflict>}
 *        PositionGroup {id, groupCode, groupName, frontMidBack, isConflictEnforced, status}
 *        PositionConflict {id, groupAId, groupBId, conflictReason, isMandatory, reviewerId}
 *   POST /auth/position-group {groupAId, groupBId, reason, reviewerId}
 *   GET  /auth/authorization?page&size&sort&userId     → PageData<AuthorizationView>
 *        AuthorizationView {id, userId, subAccountId, productId, quotaLimit(字符串), effectiveFrom,
 *                           effectiveTo, status, reviewerId}
 *   POST /auth/authorization {userId, subAccountId, productId, quota(字符串), effectiveFrom,
 *                             effectiveTo?, reviewerId}
 *   GET  /auth/sub-account-perm?userId                 → UserSubAccountPerm[]（**bare 数组**）
 *        {id, userId, subAccountId, permType, grantedBy, reviewerId, effectiveFrom, effectiveTo}
 *   POST /auth/sub-account-perm {userId, subAccountId, permType:LIMITED|CROSS_READONLY,
 *                                effectiveFrom, effectiveTo?, reviewerId}
 *   GET  /auth/change-log?page&size&sort&userId        → PageData<PermissionChangeLog>
 *   GET  /auth/unauthorized-attempt?page&size&sort&operatorId → PageData<UnauthorizedAttempt>
 *   POST /auth/password {oldPassword, newPassword}     → null（本人改口令）
 *   PUT  /auth/password {userId}                       → {oneTimePassword}（管理员重置）
 *   POST /auth/login、POST /auth/logout 见 login.js / app.js。
 *
 * ## 端点的 (方法,路径) 对来源（`ROUTES` 对象只含路径不含方法，方法取自 Java `router.register`）
 *   AuthRoutes.java:53/54/55 → GET|POST|PUT /auth/user；:57/58 → GET|POST /auth/role；
 *   :60/61 → GET|POST /auth/position-group；:63/64 → GET|POST /auth/authorization；
 *   :66/67 → GET|POST /auth/sub-account-perm；:69 → GET /auth/change-log；
 *   :70 → GET /auth/unauthorized-attempt；:73/74 → POST|PUT /auth/password。
 *
 * ## 权限域纪律（T-021 特有）
 *   · **子账户隔离（R-AUTH-5 / NFR-SEC-7）**：数据权限与功能权限**独立**；跨子账户查看仅
 *     管理层 / 审计岗且**默认只读**（查看 ≠ 操作）。**本页仅提供界面，不做授权判定**——
 *     越权由后端以 10003 / 10004 拒绝并留痕（R-AUTH-8）。
 *   · **岗位互斥（FR-AUTH-9 / CH-010）**：操盘拒绝判据为「**析取**」——「被标记企业负责人」与
 *     「持有管理层（审批人）角色」是**两个各自独立**的拒绝条件，满足**任一**即拒绝（**非**「且」）。
 *   · **零落盘（NFR-SEC-1/2）**：口令字段一律 `type=password` 遮蔽、不回显、不写
 *     localStorage/sessionStorage/URL；界面**不提供**"显示明文 / 保存凭证"入口。
 *   · 层③账户级额度写面授权见 `CH-012`（本页仅界面；额度维护在 PRODUCT 页）。
 *
 * 纪律：不直连 DB / C++，只经 HG.api；无口令字段参与任何展示运算；失败统一经 HG.errmsg（阻断四项）；
 *       数据界面必配 加载 / 空 / 错误 三态。
 */
import { HG } from '../core/ns.js';
import '../core/routes.js';

const ui = HG.ui;
const F = HG.format;
const R = HG.routes.AUTH;

/* ------------------------------------------------------------------ *
 * 取值域（以 Handler / AuthService 实测为准）
 * ------------------------------------------------------------------ */
const LEADER = { Y: '是', N: '否' };
const USER_STATUS = { ENABLED: '启用', DISABLED: '停用' };
const PERM_TYPE = { LIMITED: '限定子账户（LIMITED）', CROSS_READONLY: '跨子账户只读（CROSS_READONLY）' };
const CHANGE_TARGET = {
  USER: '用户', ROLE: '角色', AUTHORIZATION: '人员授权', SUB_ACCOUNT_PERM: '子账户数据权限',
  POSITION_CONFLICT: '岗位互斥', PASSWORD: '口令'
};
const USER_SORTS = [
  { v: 'created_time', t: '创建时间' }, { v: 'username', t: '登录名' },
  { v: 'real_name', t: '姓名' }, { v: 'last_login_time', t: '最后登录时间' }, { v: 'status', t: '状态' }
];
const ROLE_SORTS = [
  { v: 'role_code', t: '角色编码' }, { v: 'role_name', t: '角色名称' }, { v: 'status', t: '状态' }
];
const AUTHZ_SORTS = [
  { v: 'id', t: '主键' }, { v: 'user_id', t: '被授权人' }, { v: 'product_id', t: '品种' },
  { v: 'effective_from', t: '生效起始日' }
];
const CHANGE_SORTS = [
  { v: 'change_time', t: '变更时间' }, { v: 'id', t: '主键' }, { v: 'change_target', t: '变更对象' }
];
const ATTEMPT_SORTS = [
  { v: 'attempt_time', t: '尝试时间' }, { v: 'operator_id', t: '操作人' }, { v: 'id', t: '主键' }
];
const CONFLICT_SORTS = [
  { v: 'group_a_id', t: '岗位组 A' }, { v: 'group_b_id', t: '岗位组 B' }, { v: 'id', t: '主键' }
];

/* ------------------------------------------------------------------ *
 * 通用小工具
 * ------------------------------------------------------------------ */
function normalizePage(data, fallback) {
  if (Array.isArray(data)) return { items: data, total: data.length, page: 1, size: data.length || fallback.size };
  const items = (data && Array.isArray(data.items)) ? data.items : [];
  return {
    items: items,
    total: (data && typeof data.total === 'number') ? data.total : items.length,
    page: (data && typeof data.page === 'number' && data.page > 0) ? data.page : fallback.page,
    size: (data && typeof data.size === 'number' && data.size > 0) ? data.size : fallback.size
  };
}

/** 分页控件（Prev/Next + 计数）。 */
function attachPager(host, pg, go) {
  ui.clear(host);
  const totalPages = Math.max(1, Math.ceil(pg.total / pg.size));
  host.appendChild(ui.el('span', { class: 'hg-pager__info', text: '共 ' + pg.total + ' 条，第 ' + pg.page + ' / ' + totalPages + ' 页' }));
  const prev = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '上一页', disabled: pg.page <= 1 });
  prev.addEventListener('click', () => { if (pg.page > 1) go(pg.page - 1); });
  const next = ui.el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '下一页', disabled: pg.page >= totalPages });
  next.addEventListener('click', () => { if (pg.page < totalPages) go(pg.page + 1); });
  host.appendChild(prev);
  host.appendChild(next);
}

/** 生成「排序字段 + 方向 + 每页条数」控件组，值变化即回调 load(1)。 */
function sortControls(bar, state, fields, load) {
  const sortSel = ui.el('select', { id: 'au-sort-' + fields[0].v, 'aria-label': '排序字段' });
  fields.forEach((s) => sortSel.appendChild(ui.el('option', { value: s.v, text: s.t })));
  sortSel.value = state.sortField;
  sortSel.addEventListener('change', () => { state.sortField = sortSel.value; load(1); });
  bar.appendChild(ui.field({ id: sortSel.id, label: '排序字段', control: sortSel }));

  const dirSel = ui.el('select', { id: 'au-dir-' + fields[0].v, 'aria-label': '排序方向' });
  dirSel.appendChild(ui.el('option', { value: 'desc', text: '降序' }));
  dirSel.appendChild(ui.el('option', { value: 'asc', text: '升序' }));
  dirSel.value = state.sortDir;
  dirSel.addEventListener('change', () => { state.sortDir = dirSel.value; load(1); });
  bar.appendChild(ui.field({ id: dirSel.id, label: '方向', control: dirSel }));

  const sizeSel = ui.el('select', { id: 'au-size-' + fields[0].v, 'aria-label': '每页条数' });
  HG.api.PAGE_SIZES.forEach((s) => sizeSel.appendChild(ui.el('option', { value: String(s), text: s + ' 条/页' })));
  sizeSel.value = String(state.size);
  sizeSel.addEventListener('change', () => { state.size = Number(sizeSel.value); load(1); });
  bar.appendChild(ui.field({ id: sizeSel.id, label: '每页条数', control: sizeSel }));
}

/** 数值输入（ID 类字段）。 */
function numInput(id, props) {
  return ui.el('input', Object.assign({ id: id, type: 'number', min: '1', inputmode: 'numeric' }, props || {}));
}

/** 复核人字段（双人复核：须为他人）。 */
function reviewerInput(id, required) {
  return ui.field({
    id: id, label: '复核人 ID（双人复核）', required: !!required,
    help: '须为他人且 ≠ 操作人；留空则后端可能以"复核缺失"拒绝', control: numInput(id)
  });
}

/** 子账户下拉；会话不可得时退化为数值输入（仍由后端做权限与存在性校验）。 */
function subAccountControl(id, opts) {
  opts = opts || {};
  const list = (HG.session && HG.session.state && HG.session.state.subAccounts) || [];
  let control;
  if (!list.length) {
    control = numInput(id); // 会话未取到子账户（降级/未连接）：数值输入，后端仍校验
  } else {
    control = ui.el('select', { id: id });
    control.appendChild(ui.el('option', { value: '', text: opts.required ? '请选择子账户' : '（不指定）' }));
    if (opts.sentinel) control.appendChild(ui.el('option', { value: '0', text: '账户级哨兵 0（仅 CROSS_READONLY 可用）' }));
    list.forEach((s) => control.appendChild(ui.el('option', { value: String(s.id), text: (s.code ? s.code + ' ' : '') + (s.name || ('#' + s.id)) })));
  }
  return control;
}

/** 渲染「一次性口令」结果卡（遮蔽展示；不提供显示明文入口；不写任何浏览器存储）。 */
function oneTimePasswordCard(res) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '一次性口令（仅返回一次）' }));
  const val = res && res.oneTimePassword ? String(res.oneTimePassword) : '';
  const inp = ui.el('input', {
    id: 'au-otp', type: 'password', readonly: true, value: val,
    autocomplete: 'off', 'aria-label': '一次性口令（遮蔽）'
  });
  card.appendChild(ui.field({ id: 'au-otp', label: '新口令（遮蔽，不提供明文显示）', required: false, control: inp }));
  card.appendChild(ui.el('p', {
    class: 'hg-muted',
    text: '按 FR-AUTH-16 / NFR-SEC-2：一次性口令不落库、不入日志，界面不以明文展示，且本页不提供'
      + '"显示明文 / 保存凭证"入口、不写入 localStorage / sessionStorage / URL。请通过受控渠道交付本人，'
      + '并要求其首次登录强制修改。'
  }));
  return card;
}

/* ------------------------------------------------------------------ *
 * Tab 1：用户（FR-AUTH-1~5 / 16）
 * ------------------------------------------------------------------ */
function usersTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '用户不可物理删除，只能停用（R-AUTH-1）；用户名不可修改；列表与详情均不含任何口令字段（FR-AUTH-5 验收 3）。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'created_time', sortDir: 'desc', status: '', leader: '', raw: [] };

  const bar = ui.el('div', { class: 'hg-toolbar' });
  const statusSel = ui.el('select', { id: 'au-f-status' });
  statusSel.appendChild(ui.el('option', { value: '', text: '全部状态' }));
  Object.keys(USER_STATUS).forEach((k) => statusSel.appendChild(ui.el('option', { value: k, text: USER_STATUS[k] })));
  statusSel.addEventListener('change', () => { state.status = statusSel.value; load(1); });
  bar.appendChild(ui.field({ id: 'au-f-status', label: '状态（服务端）', control: statusSel }));

  const leaderSel = ui.el('select', { id: 'au-f-leader' });
  leaderSel.appendChild(ui.el('option', { value: '', text: '全部' }));
  leaderSel.appendChild(ui.el('option', { value: 'Y', text: '仅企业负责人' }));
  leaderSel.appendChild(ui.el('option', { value: 'N', text: '仅非企业负责人' }));
  leaderSel.addEventListener('change', () => { state.leader = leaderSel.value; load(1); });
  bar.appendChild(ui.field({ id: 'au-f-leader', label: '企业负责人（服务端）', control: leaderSel }));

  sortControls(bar, state, USER_SORTS, load);
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  const toggle = ui.el('button', { class: 'hg-btn', type: 'button', text: '新增用户' });
  bar.appendChild(toggle);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);

  const createCard = createUserPanel(() => load(1));
  createCard.classList.add('hg-hidden');
  container.appendChild(createCard);
  toggle.addEventListener('click', () => createCard.classList.toggle('hg-hidden'));

  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '用户分页' });
  container.appendChild(pager);

  const actCard = ui.el('div');
  container.appendChild(actCard);

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载用户列表…');
    ui.clear(pager);
    ui.clear(actCard);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.status) query.status = state.status;
    if (state.leader) query.leader = state.leader;
    return HG.api.get(R.USER, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      renderRows(pg);
    }).catch((err) => {
      ui.renderError(body, err, () => load(state.page));
      ui.clear(pager);
    });
  }

  function renderRows(pg) {
    pg = pg || { items: state.raw, total: state.raw.length, page: state.page, size: state.size };
    if (!pg.items.length) {
      ui.renderEmpty(body, '暂无用户。可点击「新增用户」创建账号。');
      attachPager(pager, pg, load);
      return;
    }
    const columns = [
      { title: 'ID', render: (r) => '#' + F.esc(r.id) },
      { title: '登录名', render: (r) => F.esc(r.username) },
      { title: '姓名', render: (r) => F.esc(r.realName) },
      { title: '部门', render: (r) => F.esc(r.department || '—') },
      { title: '企业负责人', render: (r) => ui.statusBadge(LEADER[r.isEnterpriseLeader] || r.isEnterpriseLeader || '—') },
      { title: '状态', render: (r) => ui.statusBadge(USER_STATUS[r.status] || r.status) },
      { title: '角色', render: (r) => (Array.isArray(r.roleCodes) && r.roleCodes.length ? F.esc(r.roleCodes.join('、')) : '（无）') },
      { title: '可见子账户', render: (r) => subScopeText(r.subAccountScope) },
      { title: '最后登录', render: (r) => F.dateTime(r.lastLoginTime) || '—' },
      { title: '操作', render: (r) => rowActions(r) }
    ];
    ui.clear(body);
    body.innerHTML = ui.tableHTML(columns, pg.items);
    // 绑定行内按钮（innerHTML 后按 data 属性重挂监听）
    Array.prototype.forEach.call(body.querySelectorAll('button[data-act]'), (btn) => {
      btn.addEventListener('click', () => {
        const id = Number(btn.getAttribute('data-uid'));
        const u = pg.items.filter((x) => String(x.id) === String(id))[0];
        if (!u) return;
        const act = btn.getAttribute('data-act');
        if (act === 'reset') doResetPassword(u);
        else openAction(u, act);
      });
    });
    attachPager(pager, pg, load);
  }

  function subScopeText(scope) {
    if (!Array.isArray(scope) || !scope.length) return '<span class="hg-muted">无任何子账户（安全默认）</span>';
    if (scope.indexOf(0) !== -1) return '跨账户只读（含哨兵 0）';
    return F.esc(scope.map((x) => '#' + x).join('、'));
  }

  function rowActions(u) {
    const canEnable = u.status === 'DISABLED';
    return '<button class="hg-btn hg-btn--ghost hg-btn--sm" data-act="edit" data-uid="' + F.esc(u.id) + '">变更</button> ' +
      '<button class="hg-btn hg-btn--ghost hg-btn--sm" data-act="' + (canEnable ? 'enable' : 'disable') + '" data-uid="' + F.esc(u.id) + '">' +
      (canEnable ? '启用' : '停用') + '</button> ' +
      '<button class="hg-btn hg-btn--ghost hg-btn--sm" data-act="reset" data-uid="' + F.esc(u.id) + '">重置口令</button>';
  }

  function openAction(u, act) {
    ui.clear(actCard);
    ui.clear(alertBox);
    const card = ui.el('div', { class: 'hg-card' });
    card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '用户变更（FR-AUTH-2/3/4）——' + (u.username || ('#' + u.id)) }));
    const dl = ui.el('dl', { class: 'hg-desc' });
    [['用户 ID', '#' + u.id], ['登录名（不可修改）', u.username], ['姓名', u.realName],
      ['部门', u.department || '—'], ['企业负责人', LEADER[u.isEnterpriseLeader] || u.isEnterpriseLeader],
      ['状态', USER_STATUS[u.status] || u.status]].forEach((r) => {
      dl.appendChild(ui.el('dt', { text: r[0] }));
      dl.appendChild(ui.el('dd', { text: r[1] }));
    });
    card.appendChild(dl);

    const form = ui.el('form', { novalidate: 'novalidate' });
    const fAlert = ui.el('div', { role: 'alert' });
    form.appendChild(fAlert);

    const actionSel = ui.el('select', { id: 'au-a-action' });
    [['UPDATE', '修改（姓名/部门/企业负责人标记）'], ['DISABLE', '停用'], ['ENABLE', '启用（须重置口令）']]
      .forEach((o) => actionSel.appendChild(ui.el('option', { value: o[0], text: o[1] })));
    if (u.status === 'DISABLED') actionSel.value = 'ENABLE';

    const realName = ui.el('input', { id: 'au-a-name', type: 'text', maxlength: '64', value: u.realName || '' });
    const dept = ui.el('input', { id: 'au-a-dept', type: 'text', maxlength: '128', value: u.department || '' });
    const leader = ui.el('select', { id: 'au-a-leader' });
    leader.appendChild(ui.el('option', { value: 'N', text: '非企业负责人' }));
    leader.appendChild(ui.el('option', { value: 'Y', text: '企业负责人' }));
    leader.value = u.isEnterpriseLeader === 'Y' ? 'Y' : 'N';
    const reason = ui.el('input', { id: 'au-a-reason', type: 'text', maxlength: '200' });

    const grid = ui.el('div', { class: 'hg-grid-2' });
    grid.appendChild(ui.field({ id: 'au-a-action', label: '动作', required: true, control: actionSel }));
    grid.appendChild(ui.field({ id: 'au-a-name', label: '姓名', required: false, control: realName }));
    grid.appendChild(ui.field({ id: 'au-a-dept', label: '部门', required: false, control: dept }));
    grid.appendChild(ui.field({
      id: 'au-a-leader', label: '企业负责人标记', required: false,
      help: '标记变更须双人复核（R-AUTH-2）；被标记者不得被授予交易岗、不得直接下单（FR-AUTH-9）', control: leader
    }));
    grid.appendChild(ui.field({ id: 'au-a-reason', label: '停用原因（DISABLE 必填）', required: false, control: reason }));
    grid.appendChild(reviewerInput('au-a-reviewer', false));
    form.appendChild(grid);

    const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交变更' });
    form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));

    function sync() {
      const a = actionSel.value;
      realName.disabled = a !== 'UPDATE';
      dept.disabled = a !== 'UPDATE';
      leader.disabled = a !== 'UPDATE';
      reason.disabled = a !== 'DISABLE';
    }
    actionSel.addEventListener('change', sync);
    sync();

    form.addEventListener('submit', async (evt) => {
      evt.preventDefault();
      ui.clear(fAlert);
      const a = actionSel.value;
      const payload = { action: a, userId: Number(u.id) };
      const rev = document.getElementById('au-a-reviewer');
      if (a === 'UPDATE') {
        if (!realName.value.trim()) { ui.setFieldError(realName, '请填写姓名'); return; }
        payload.realName = realName.value.trim();
        payload.department = dept.value.trim() || null;
        payload.isEnterpriseLeader = leader.value;
        if (rev.value) payload.reviewerId = Number(rev.value);
      } else if (a === 'DISABLE') {
        if (!reason.value.trim()) { ui.setFieldError(reason, '停用须填写原因（FR-AUTH-3）'); return; }
        payload.reason = reason.value.trim();
      } else if (a === 'ENABLE') {
        if (!rev.value) { ui.setFieldError(rev, '启用须重置口令，须双人复核（复核人 ID 必填）'); return; }
        payload.reviewerId = Number(rev.value);
      }
      // 危险操作二次确认（前端纪律 §5.4）：停用 / 启用（启用将重置口令）
      if (a === 'DISABLE' || a === 'ENABLE') {
        const yes = await ui.confirm(
          '确认对用户 <strong>' + F.esc(u.username) + '</strong> 执行「' +
          (a === 'DISABLE' ? '停用（立即失效登录会话）' : '启用（须重置口令）') + '」？',
          { title: a === 'DISABLE' ? '停用用户' : '启用用户', okText: '确认', danger: a === 'DISABLE' }
        );
        if (!yes) return;
      }
      submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
      try {
        const res = await HG.api.put(R.USER, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
        if (a === 'ENABLE' && res && res.oneTimePassword) {
          ui.clear(actCard);
          actCard.appendChild(oneTimePasswordCard(res));
        } else {
          ui.toast('用户变更已提交（action=' + a + '）', 'ok');
          ui.clear(actCard);
        }
        alertBox.appendChild(ui.banner('变更已提交：' + a + '。变更留痕见「变更留痕」Tab。', 'ok'));
        load(state.page);
      } catch (err) {
        fAlert.innerHTML = ui.blockedHTML(err);
        window.scrollTo(0, 0);
      } finally {
        submit.disabled = false; submit.textContent = orig;
      }
    });

    const resetBtn = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '管理员重置口令（PUT /auth/password）' });
    resetBtn.addEventListener('click', () => doResetPassword(u));
    card.appendChild(form);
    card.appendChild(ui.el('div', { class: 'hg-form-actions' }, resetBtn));
    actCard.appendChild(card);

    if (act === 'disable' || act === 'enable') actionSel.value = act === 'disable' ? 'DISABLE' : 'ENABLE';
    sync();
  }

  async function doResetPassword(u) {
    const yes = await ui.confirm('确认重置用户 <strong>' + F.esc(u.username) + '</strong> 的口令？系统将生成一次性口令（仅回一次）。', { title: '重置口令', okText: '确认重置', danger: true });
    if (!yes) return;
    try {
      const res = await HG.api.put(R.PASSWORD, { userId: Number(u.id) }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.clear(actCard);
      actCard.appendChild(oneTimePasswordCard(res));
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    }
  }

  load(1);
}

function createUserPanel(onDone) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '新增用户（FR-AUTH-1）' }));
  card.appendChild(ui.el('p', {
    class: 'hg-muted',
    text: '用户名全系统唯一；一个自然人只应有一个账号（R-AUTH-2）；初始口令以加盐哈希存储、界面不回显；'
      + '新增后默认不授予任何子账户数据权限（安全默认，FR-AUTH-1 验收 3）。'
  }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);

  const username = ui.el('input', { id: 'au-n-user', type: 'text', maxlength: '64', autocomplete: 'off' });
  const realName = ui.el('input', { id: 'au-n-name', type: 'text', maxlength: '64' });
  const npk = ui.el('input', { id: 'au-n-npk', type: 'text', maxlength: '64' });
  const dept = ui.el('input', { id: 'au-n-dept', type: 'text', maxlength: '128' });
  const leader = ui.el('select', { id: 'au-n-leader' });
  leader.appendChild(ui.el('option', { value: 'N', text: '非企业负责人（默认）' }));
  leader.appendChild(ui.el('option', { value: 'Y', text: '企业负责人' }));
  const pwd = ui.el('input', { id: 'au-n-pwd', type: 'password', maxlength: '128', autocomplete: 'new-password' });

  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-n-user', label: '用户名', required: true, help: '全系统唯一；不可修改', control: username }));
  grid.appendChild(ui.field({ id: 'au-n-name', label: '姓名', required: true, help: '一个自然人一个账号', control: realName }));
  grid.appendChild(ui.field({ id: 'au-n-npk', label: '自然人唯一键', required: true, help: '用于检测同一自然人重复建号（R-AUTH-2）', control: npk }));
  grid.appendChild(ui.field({ id: 'au-n-dept', label: '部门', required: false, control: dept }));
  grid.appendChild(ui.field({ id: 'au-n-leader', label: '企业负责人标记', required: true, help: '须显式选择；标记者不得被授予交易岗、不得下单', control: leader }));
  grid.appendChild(ui.field({ id: 'au-n-pwd', label: '初始口令', required: true, help: '仅内存提交；首次登录强制修改；强度 ≥12 且含大小写/数字/符号', control: pwd }));
  form.appendChild(grid);

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '创建用户' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!username.value.trim()) { ui.setFieldError(username, '请填写用户名'); ok = false; } else ui.setFieldError(username, '');
    if (!realName.value.trim()) { ui.setFieldError(realName, '请填写姓名'); ok = false; } else ui.setFieldError(realName, '');
    if (!npk.value.trim()) { ui.setFieldError(npk, '请填写自然人唯一键'); ok = false; } else ui.setFieldError(npk, '');
    if (!pwd.value) { ui.setFieldError(pwd, '请填写初始口令'); ok = false; } else ui.setFieldError(pwd, '');
    if (!ok) return;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const id = await HG.api.post(R.USER, {
        username: username.value.trim(), realName: realName.value.trim(), naturalPersonKey: npk.value.trim(),
        department: dept.value.trim() || null, isEnterpriseLeader: leader.value, initialPassword: pwd.value
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      pwd.value = ''; // 口令不驻留
      ui.toast('用户已创建（id=' + id + '）；请到「角色」Tab 执行 GRANT 授予初始角色', 'ok');
      form.reset();
      if (typeof onDone === 'function') onDone();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
      window.scrollTo(0, 0);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });
  return card;
}

/* ------------------------------------------------------------------ *
 * Tab 2：角色（FR-AUTH-6 / 8）
 * ------------------------------------------------------------------ */
function rolesTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '系统预置六类业务角色 + 系统管理员；预置角色不可删除（R-AUTH-10）；系统管理员角色权限不含交易/审批/风控（防止"一键通办"后门）；角色须归属岗位组以支持互斥判定（FR-AUTH-6 验收 3）。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'role_code', sortDir: 'asc', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, ROLE_SORTS, load);
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '角色分页' });
  container.appendChild(pager);

  container.appendChild(roleDefinePanel(() => load(1), alertBox));
  container.appendChild(roleGrantPanel(alertBox));

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载角色列表…');
    ui.clear(pager);
    return HG.api.get(R.ROLE, { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir })
      .then((data) => {
        const pg = normalizePage(data, state);
        state.page = pg.page; state.size = pg.size; state.raw = pg.items;
        if (!pg.items.length) { ui.renderEmpty(body, '暂无角色。可下方定义角色。'); }
        else {
          ui.clear(body);
          body.innerHTML = ui.tableHTML([
            { title: 'ID', render: (r) => '#' + F.esc(r.id) },
            { title: '角色编码', render: (r) => F.esc(r.roleCode) },
            { title: '角色名称', render: (r) => F.esc(r.roleName) },
            { title: '岗位组 ID', render: (r) => F.esc(r.positionGroupId) },
            { title: '预置', render: (r) => ui.statusBadge(r.isPreset === 'Y' ? '是' : '否') },
            { title: '业务角色', render: (r) => ui.statusBadge(r.isBusinessRole === 'Y' ? '是' : '否') },
            { title: '含交易能力', render: (r) => ui.statusBadge(r.hasTradingOperation === 'Y' ? '是（不得授予企业负责人/管理层）' : '否') },
            { title: '状态', render: (r) => ui.statusBadge(r.status) }
          ], pg.items);
        }
        attachPager(pager, pg, load);
      })
      .catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

function roleDefinePanel(onDone, alertBox) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '定义角色（POST /auth/role action=DEFINE）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const roleCode = ui.el('input', { id: 'au-r-code', type: 'text', maxlength: '64' });
  const roleName = ui.el('input', { id: 'au-r-name', type: 'text', maxlength: '64' });
  const pgSel = ui.el('select', { id: 'au-r-pg' });
  pgSel.appendChild(ui.el('option', { value: '', text: '（加载岗位组…）' }));
  const hasTrade = ui.el('select', { id: 'au-r-trade' });
  hasTrade.appendChild(ui.el('option', { value: 'N', text: '否（默认）' }));
  hasTrade.appendChild(ui.el('option', { value: 'Y', text: '是（交易下单能力）' }));
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-r-code', label: '角色编码', required: true, control: roleCode }));
  grid.appendChild(ui.field({ id: 'au-r-name', label: '角色名称', required: true, control: roleName }));
  grid.appendChild(ui.field({ id: 'au-r-pg', label: '岗位组', required: true, help: '互斥判定以岗位组为单位（R-AUTH-3）', control: pgSel }));
  grid.appendChild(ui.field({ id: 'au-r-trade', label: '含交易下单能力', required: true, help: '含交易能力的角色不得授予企业负责人/管理层（FR-AUTH-9）', control: hasTrade }));
  grid.appendChild(reviewerInput('au-r-reviewer', false));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '定义角色' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);

  HG.api.get(R.POSITION_GROUP, { page: 1, size: 100, sort: 'group_a_id,asc' }).then((data) => {
    const groups = (data && data.groups) || [];
    pgSel.innerHTML = '';
    pgSel.appendChild(ui.el('option', { value: '', text: groups.length ? '请选择岗位组' : '（无岗位组，请先在岗位组 Tab 配置）' }));
    groups.forEach((g) => pgSel.appendChild(ui.el('option', { value: String(g.id), text: '#' + g.id + ' ' + (g.groupName || g.groupCode || '') })));
  }).catch(() => { pgSel.innerHTML = ''; pgSel.appendChild(ui.el('option', { value: '', text: '（岗位组不可达，请重试）' })); });

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    if (!roleCode.value.trim()) { ui.setFieldError(roleCode, '请填写角色编码'); return; }
    if (!roleName.value.trim()) { ui.setFieldError(roleName, '请填写角色名称'); return; }
    if (!pgSel.value) { ui.setFieldError(pgSel, '角色须选择岗位组（FR-AUTH-6 验收 3）'); return; }
    const payload = {
      action: 'DEFINE', roleCode: roleCode.value.trim(), roleName: roleName.value.trim(),
      positionGroupId: Number(pgSel.value), hasTradingOperation: hasTrade.value
    };
    const rev = document.getElementById('au-r-reviewer');
    if (rev.value) payload.reviewerId = Number(rev.value);
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const id = await HG.api.post(R.ROLE, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('角色已定义（id=' + id + '）', 'ok');
      form.reset();
      if (typeof onDone === 'function') onDone();
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });
  return card;
}

function roleGrantPanel(alertBox) {
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '授予角色（POST /auth/role action=GRANT）' }));
  card.appendChild(ui.el('p', { class: 'hg-muted', text: '分配角色时系统自动校验岗位互斥——冲突即拒绝并记入越权尝试记录（FR-AUTH-8）；不提供绕过通道。' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const userId = numInput('au-g-user');
  const roleId = numInput('au-g-role');
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-g-user', label: '目标用户 ID', required: true, control: userId }));
  grid.appendChild(ui.field({ id: 'au-g-role', label: '角色 ID', required: true, control: roleId }));
  grid.appendChild(reviewerInput('au-g-reviewer', true));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '授予' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    if (!userId.value) { ui.setFieldError(userId, '请填写目标用户 ID'); return; }
    if (!roleId.value) { ui.setFieldError(roleId, '请填写角色 ID'); return; }
    const rev = document.getElementById('au-g-reviewer');
    if (!rev.value) { ui.setFieldError(rev, '双人复核：复核人 ID 必填且 ≠ 操作人'); return; }
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      await HG.api.post(R.ROLE, {
        action: 'GRANT', userId: Number(userId.value), roleId: Number(roleId.value), reviewerId: Number(rev.value)
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('角色已授予', 'ok');
      form.reset();
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err); // 岗位互斥冲突为 10005，阻断四项在此渲染
      window.scrollTo(0, 0);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });
  return card;
}

/* ------------------------------------------------------------------ *
 * Tab 3：岗位组与互斥（FR-AUTH-7）
 * ------------------------------------------------------------------ */
function positionTab(container) {
  container.appendChild(ui.banner(
    '<strong>互斥为系统级强制（R-AUTH-3）</strong>：交易岗、风控岗、结算岗两两互斥；资金岗与交易岗互斥。'
      + '系统中<strong>不存在</strong>关闭互斥的开关或配置项（FR-AUTH-7 验收 2）——本页<strong>不提供</strong>任何"关闭互斥"入口。',
    'warn'
  ));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'group_a_id', sortDir: 'asc', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  sortControls(bar, state, CONFLICT_SORTS, load);
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);

  const groupsHost = ui.el('div');
  container.appendChild(groupsHost);
  const confBody = ui.el('div');
  container.appendChild(confBody);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '互斥矩阵分页' });
  container.appendChild(pager);

  const defCard = ui.el('div', { class: 'hg-card' });
  defCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '配置互斥（POST /auth/position-group）' }));
  defCard.appendChild(ui.el('p', { class: 'hg-muted', text: '矩阵单向存储（a<b）；须双人复核；不得关闭互斥（FR-AUTH-7 验收 3）。' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const gA = numInput('au-p-a');
  const gB = numInput('au-p-b');
  const reason = ui.el('input', { id: 'au-p-reason', type: 'text', maxlength: '200' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-p-a', label: '岗位组 A ID', required: true, control: gA }));
  grid.appendChild(ui.field({ id: 'au-p-b', label: '岗位组 B ID', required: true, help: '须 A < B（矩阵单向存储）', control: gB }));
  grid.appendChild(ui.field({ id: 'au-p-reason', label: '互斥依据', required: false, help: '如 8 号文前中后台分离', control: reason }));
  grid.appendChild(reviewerInput('au-p-reviewer', true));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交互斥' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  defCard.appendChild(form);
  container.appendChild(defCard);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    if (!gA.value || !gB.value) { ui.setFieldError(gA, '请填写岗位组 A/B ID'); return; }
    const rev = document.getElementById('au-p-reviewer');
    if (!rev.value) { ui.setFieldError(rev, '双人复核：复核人 ID 必填且 ≠ 操作人'); return; }
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      await HG.api.post(R.POSITION_GROUP, {
        groupAId: Number(gA.value), groupBId: Number(gB.value),
        reason: reason.value.trim() || null, reviewerId: Number(rev.value)
      }, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('互斥关系已提交', 'ok');
      form.reset();
      load(state.page);
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load(page) {
    state.page = page;
    ui.renderLoading(confBody, '正在加载岗位组与互斥矩阵…');
    ui.clear(groupsHost);
    ui.clear(pager);
    return HG.api.get(R.POSITION_GROUP, { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir })
      .then((data) => {
        const groups = (data && data.groups) || [];
        ui.clear(groupsHost);
        const gCard = ui.el('div', { class: 'hg-card' });
        gCard.appendChild(ui.el('h3', { class: 'hg-card__title', text: '岗位组清单' }));
        if (!groups.length) {
          gCard.appendChild(ui.el('p', { class: 'hg-muted', text: '暂无岗位组。' }));
        } else {
          gCard.innerHTML += ui.tableHTML([
            { title: 'ID', render: (r) => '#' + F.esc(r.id) },
            { title: '编码', render: (r) => F.esc(r.groupCode) },
            { title: '名称', render: (r) => F.esc(r.groupName) },
            { title: '前台/中台/后台', render: (r) => ui.statusBadge(r.frontMidBack || '—') },
            { title: '互斥强制', render: (r) => ui.statusBadge(r.isConflictEnforced === 'Y' ? '强制（不可关闭）' : '—') },
            { title: '状态', render: (r) => ui.statusBadge(r.status) }
          ], groups);
        }
        groupsHost.appendChild(gCard);

        const pg = normalizePage(data && data.conflicts, state);
        state.page = pg.page; state.size = pg.size; state.raw = pg.items;
        if (!pg.items.length) {
          ui.renderEmpty(confBody, '暂无互斥矩阵记录。');
        } else {
          ui.clear(confBody);
          confBody.innerHTML = ui.tableHTML([
            { title: 'ID', render: (r) => '#' + F.esc(r.id) },
            { title: '岗位组 A', render: (r) => '#' + F.esc(r.groupAId) },
            { title: '岗位组 B', render: (r) => '#' + F.esc(r.groupBId) },
            { title: '互斥依据', render: (r) => F.esc(r.conflictReason || '—') },
            { title: '强制', render: (r) => ui.statusBadge(r.isMandatory === 'Y' ? '强制' : '—') },
            { title: '复核人', render: (r) => (r.reviewerId == null ? '—' : '#' + F.esc(r.reviewerId)) }
          ], pg.items);
        }
        attachPager(pager, pg, load);
      })
      .catch((err) => { ui.renderError(confBody, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 4：人员授权（FR-AUTH-10）
 * ------------------------------------------------------------------ */
function authzTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '授权必须明确 人员名单 + 品种 + 额度 三者，缺一不可（R-AUTH-4）；额度须 > 0；变更须双人复核；授权不得超出该用户角色能力范围（FR-AUTH-10）。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'id', sortDir: 'desc', userId: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const uFilter = numInput('au-az-ufilter', { min: '1' });
  uFilter.addEventListener('change', () => { state.userId = uFilter.value; load(1); });
  bar.appendChild(ui.field({ id: 'au-az-ufilter', label: '按被授权人筛选（服务端 userId）', required: false, control: uFilter }));
  sortControls(bar, state, AUTHZ_SORTS, load);
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '授权分页' });
  container.appendChild(pager);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '配置人员授权（POST /auth/authorization）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const userId = numInput('au-az-user');
  const sub = subAccountControl('au-az-sub', { required: true });
  const productId = numInput('au-az-product');
  const quota = ui.el('input', { id: 'au-az-quota', type: 'text', inputmode: 'decimal' });
  const from = ui.el('input', { id: 'au-az-from', type: 'date' });
  const to = ui.el('input', { id: 'au-az-to', type: 'date' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-az-user', label: '人员（被授权用户 ID）', required: true, control: userId }));
  grid.appendChild(ui.field({ id: 'au-az-sub', label: '子账户', required: true, control: sub }));
  grid.appendChild(ui.field({ id: 'au-az-product', label: '品种 ID', required: true, control: productId }));
  grid.appendChild(ui.field({ id: 'au-az-quota', label: '额度（字符串化十进制，> 0）', required: true, control: quota }));
  grid.appendChild(ui.field({ id: 'au-az-from', label: '生效起始日', required: true, control: from }));
  grid.appendChild(ui.field({ id: 'au-az-to', label: '生效截止日', required: false, control: to }));
  grid.appendChild(reviewerInput('au-az-reviewer', true));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '提交授权' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    if (!userId.value) { ui.setFieldError(userId, '请填写被授权用户 ID'); return; }
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); return; }
    if (!productId.value) { ui.setFieldError(productId, '请填写品种 ID'); return; }
    if (!/^\d+(\.\d+)?$/.test(quota.value.trim()) || Number(quota.value) === 0) { ui.setFieldError(quota, '额度须为十进制数字且 > 0'); return; }
    if (!from.value) { ui.setFieldError(from, '请选择生效起始日'); return; }
    const rev = document.getElementById('au-az-reviewer');
    if (!rev.value) { ui.setFieldError(rev, '双人复核：复核人 ID 必填且 ≠ 操作人'); return; }
    const payload = {
      userId: Number(userId.value), subAccountId: Number(sub.value), productId: Number(productId.value),
      quota: quota.value.trim(), effectiveFrom: from.value, reviewerId: Number(rev.value)
    };
    if (to.value) payload.effectiveTo = to.value;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      const id = await HG.api.post(R.AUTHORIZATION, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('授权已提交（id=' + id + '），待复核生效并下发 C++ 内存', 'ok');
      form.reset();
      load(1);
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载授权列表…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.userId) query.userId = state.userId;
    return HG.api.get(R.AUTHORIZATION, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无人员授权记录。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: 'ID', render: (r) => '#' + F.esc(r.id) },
          { title: '被授权人', render: (r) => '#' + F.esc(r.userId) },
          { title: '子账户', render: (r) => (r.subAccountId === 0 ? '账户级（0）' : '#' + F.esc(r.subAccountId)) },
          { title: '品种', render: (r) => '#' + F.esc(r.productId) },
          { title: '额度', num: true, render: (r) => F.decimal(r.quotaLimit) },
          { title: '生效起始', render: (r) => F.date(r.effectiveFrom) },
          { title: '生效截止', render: (r) => F.date(r.effectiveTo) || '—' },
          { title: '状态', render: (r) => ui.statusBadge(r.status) },
          { title: '复核人', render: (r) => (r.reviewerId == null ? '—' : '#' + F.esc(r.reviewerId)) }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 5：子账户数据权限（FR-AUTH-12 / 13）
 * ------------------------------------------------------------------ */
function subPermTab(container) {
  container.appendChild(ui.banner(
    '<strong>安全默认（R-AUTH-5）</strong>：未被授权的用户<strong>默认看不到任何子账户数据</strong>；'
      + '跨子账户查看仅<strong>管理层 / 审计岗</strong>可被授予且<strong>默认只读</strong>（查看 ≠ 操作）；'
      + '数据权限与功能权限<strong>独立</strong>。本页仅提供界面，授权判定由后端负责。',
    'warn'
  ));
  const state = { userId: '', list: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const uInput = numInput('au-sp-user', { min: '1' });
  uInput.addEventListener('change', () => { state.userId = uInput.value; load(); });
  bar.appendChild(ui.field({ id: 'au-sp-user', label: '用户 ID（缺省为当前用户）', required: false, control: uInput }));
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '查询' });
  reload.addEventListener('click', () => { state.userId = uInput.value; load(); });
  bar.appendChild(reload);
  container.appendChild(bar);

  const alertBox = ui.el('div', { role: 'alert' });
  container.appendChild(alertBox);
  const body = ui.el('div');
  container.appendChild(body);

  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '授予子账户数据权限（POST /auth/sub-account-perm）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const fAlert = ui.el('div', { role: 'alert' });
  form.appendChild(fAlert);
  const userId = numInput('au-sp-tuser');
  const permSel = ui.el('select', { id: 'au-sp-type' });
  Object.keys(PERM_TYPE).forEach((k) => permSel.appendChild(ui.el('option', { value: k, text: PERM_TYPE[k] })));
  const sub = subAccountControl('au-sp-sub', { required: true, sentinel: true });
  const from = ui.el('input', { id: 'au-sp-from', type: 'date' });
  const to = ui.el('input', { id: 'au-sp-to', type: 'date' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-sp-tuser', label: '目标用户 ID', required: true, control: userId }));
  grid.appendChild(ui.field({ id: 'au-sp-type', label: '权限类型', required: true, help: 'CROSS_READONLY 仅管理层/审计岗；默认只读', control: permSel }));
  grid.appendChild(ui.field({ id: 'au-sp-sub', label: '子账户', required: true, help: 'CROSS_READONLY 时可传哨兵 0', control: sub }));
  grid.appendChild(ui.field({ id: 'au-sp-from', label: '生效起始日', required: true, control: from }));
  grid.appendChild(ui.field({ id: 'au-sp-to', label: '生效截止日', required: false, control: to }));
  grid.appendChild(reviewerInput('au-sp-reviewer', true));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '授予' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(fAlert);
    if (!userId.value) { ui.setFieldError(userId, '请填写目标用户 ID'); return; }
    if (!sub.value) { ui.setFieldError(sub, '请选择子账户'); return; }
    if (!from.value) { ui.setFieldError(from, '请选择生效起始日'); return; }
    const rev = document.getElementById('au-sp-reviewer');
    if (!rev.value) { ui.setFieldError(rev, '双人复核：复核人 ID 必填且 ≠ 操作人'); return; }
    const payload = {
      userId: Number(userId.value), subAccountId: Number(sub.value), permType: permSel.value,
      effectiveFrom: from.value, reviewerId: Number(rev.value)
    };
    if (to.value) payload.effectiveTo = to.value;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      await HG.api.post(R.SUB_ACCOUNT_PERM, payload, { idempotencyKey: HG.api.newIdempotencyKey() });
      ui.toast('子账户数据权限已授予', 'ok');
      form.reset();
      load();
    } catch (err) {
      fAlert.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });

  function load() {
    ui.renderLoading(body, '正在加载子账户数据权限…');
    const query = {};
    if (state.userId) query.userId = state.userId;
    return HG.api.get(R.SUB_ACCOUNT_PERM, query).then((data) => {
      const items = Array.isArray(data) ? data : ((data && data.items) || []);
      state.list = items;
      if (!items.length) { ui.renderEmpty(body, '该用户暂无任何子账户数据权限（安全默认：看不到任何子账户数据）。'); return; }
      ui.clear(body);
      body.innerHTML = ui.tableHTML([
        { title: 'ID', render: (r) => '#' + F.esc(r.id) },
        { title: '用户', render: (r) => '#' + F.esc(r.userId) },
        { title: '子账户', render: (r) => (r.subAccountId === 0 ? '跨账户哨兵 0' : '#' + F.esc(r.subAccountId)) },
        { title: '权限类型', render: (r) => ui.statusBadge(PERM_TYPE[r.permType] || r.permType) },
        { title: '授权人', render: (r) => '#' + F.esc(r.grantedBy) },
        { title: '复核人', render: (r) => (r.reviewerId == null ? '—' : '#' + F.esc(r.reviewerId)) },
        { title: '生效起始', render: (r) => F.date(r.effectiveFrom) },
        { title: '生效截止', render: (r) => F.date(r.effectiveTo) || '—' }
      ], items);
    }).catch((err) => ui.renderError(body, err, load));
  }
  load();
}

/* ------------------------------------------------------------------ *
 * Tab 6：权限变更留痕（FR-AUTH-11 / 18）
 * ------------------------------------------------------------------ */
function changeLogTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '权限变更留痕只增不改不删，含前后值与复核人（R-AUTH-4 / FR-AUTH-11）；记录中不存在编辑或删除入口（验收 2）。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'change_time', sortDir: 'desc', userId: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const uInput = numInput('au-cl-user', { min: '1' });
  uInput.addEventListener('change', () => { state.userId = uInput.value; load(1); });
  bar.appendChild(ui.field({ id: 'au-cl-user', label: '按用户筛选（服务端 userId）', required: false, control: uInput }));
  sortControls(bar, state, CHANGE_SORTS, load);
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '权限变更留痕分页' });
  container.appendChild(pager);

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载权限变更留痕…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.userId) query.userId = state.userId;
    return HG.api.get(R.CHANGE_LOG, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无权限变更记录。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: '时间', render: (r) => F.dateTime(r.changeTime) },
          { title: '变更对象', render: (r) => ui.statusBadge(CHANGE_TARGET[r.changeTarget] || r.changeTarget || '—') },
          { title: '对象 ID', render: (r) => (r.targetId == null ? '—' : '#' + F.esc(r.targetId)) },
          { title: '目标用户', render: (r) => (r.targetUserId == null ? '—' : '#' + F.esc(r.targetUserId)) },
          { title: '子账户', render: (r) => (r.subAccountId == null ? '—' : '#' + F.esc(r.subAccountId)) },
          { title: '变更前', render: (r) => F.esc(r.beforeValue || '—') },
          { title: '变更后', render: (r) => F.esc(r.afterValue || '—') },
          { title: '操作人', render: (r) => '#' + F.esc(r.operatorId) },
          { title: '复核人', render: (r) => (r.reviewerId == null ? '—' : '#' + F.esc(r.reviewerId)) },
          { title: '拒绝', render: (r) => (r.rejected === 'Y' ? ui.statusBadge('已拒绝：' + (r.rejectReason || '')) : '—') },
          { title: '下发', render: (r) => ui.statusBadge(r.dispatchStatus || '—') },
          { title: '原因', render: (r) => F.esc(r.changeReason || '—') }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 7：越权尝试（FR-AUTH-18）
 * ------------------------------------------------------------------ */
function attemptTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '越权尝试记录只增不改不删，含操作人、时间、尝试的操作与拒绝原因（R-AUTH-8 / FR-AUTH-18）；无删除入口。' }));
  const state = { page: 1, size: HG.api.DEFAULT_PAGE_SIZE, sortField: 'attempt_time', sortDir: 'desc', operatorId: '', raw: [] };
  const bar = ui.el('div', { class: 'hg-toolbar' });
  const oInput = numInput('au-ua-op', { min: '1' });
  oInput.addEventListener('change', () => { state.operatorId = oInput.value; load(1); });
  bar.appendChild(ui.field({ id: 'au-ua-op', label: '按操作人筛选（服务端 operatorId）', required: false, control: oInput }));
  sortControls(bar, state, ATTEMPT_SORTS, load);
  const reload = ui.el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: '刷新' });
  reload.addEventListener('click', () => load(state.page));
  bar.appendChild(reload);
  container.appendChild(bar);

  const body = ui.el('div');
  container.appendChild(body);
  const pager = ui.el('div', { class: 'hg-pager', role: 'navigation', 'aria-label': '越权尝试分页' });
  container.appendChild(pager);

  function load(page) {
    state.page = page;
    ui.renderLoading(body, '正在加载越权尝试记录…');
    ui.clear(pager);
    const query = { page: page, size: state.size, sort: state.sortField + ',' + state.sortDir };
    if (state.operatorId) query.operatorId = state.operatorId;
    return HG.api.get(R.UNAUTHORIZED_ATTEMPT, query).then((data) => {
      const pg = normalizePage(data, state);
      state.page = pg.page; state.size = pg.size; state.raw = pg.items;
      if (!pg.items.length) { ui.renderEmpty(body, '暂无越权尝试记录。'); }
      else {
        ui.clear(body);
        body.innerHTML = ui.tableHTML([
          { title: '时间', render: (r) => F.dateTime(r.attemptTime) },
          { title: '操作人', render: (r) => '#' + F.esc(r.operatorId) },
          { title: '尝试类型', render: (r) => F.esc(r.attemptType || '—') },
          { title: '尝试操作', render: (r) => F.esc(r.attemptDesc || '—') },
          { title: '目标资源', render: (r) => F.esc(r.targetResource || '—') },
          { title: '目标子账户', render: (r) => (r.subAccountId == null ? '—' : '#' + F.esc(r.subAccountId)) },
          { title: '拒绝码', render: (r) => ui.statusBadge(r.rejectCode || '—') },
          { title: '拒绝原因', render: (r) => F.esc(r.rejectReason || '—') },
          { title: '来源 IP', render: (r) => F.esc(r.clientIp || '—') }
        ], pg.items);
      }
      attachPager(pager, pg, load);
    }).catch((err) => { ui.renderError(body, err, () => load(state.page)); ui.clear(pager); });
  }
  load(1);
}

/* ------------------------------------------------------------------ *
 * Tab 8：本人口令管理（FR-AUTH-16）
 * ------------------------------------------------------------------ */
function passwordTab(container) {
  container.appendChild(ui.el('p', { class: 'hg-muted', text: '口令以加盐哈希存储；明文不得出现在任何日志与持久化文件中（NFR-SEC-2）；强度默认 ≥12 且含大小写字母、数字、符号，且不得与最近 5 次相同；首次登录与重置后强制修改。' }));
  const card = ui.el('div', { class: 'hg-card' });
  card.appendChild(ui.el('h3', { class: 'hg-card__title', text: '修改我的口令（POST /auth/password）' }));
  const form = ui.el('form', { novalidate: 'novalidate' });
  const alertBox = ui.el('div', { role: 'alert' });
  form.appendChild(alertBox);
  const oldPwd = ui.el('input', { id: 'au-pw-old', type: 'password', autocomplete: 'current-password' });
  const newPwd = ui.el('input', { id: 'au-pw-new', type: 'password', autocomplete: 'new-password' });
  const confirmPwd = ui.el('input', { id: 'au-pw-confirm', type: 'password', autocomplete: 'new-password' });
  const grid = ui.el('div', { class: 'hg-grid-2' });
  grid.appendChild(ui.field({ id: 'au-pw-old', label: '原口令', required: true, control: oldPwd }));
  grid.appendChild(ui.field({ id: 'au-pw-new', label: '新口令', required: true, help: '强度由后端判定；界面不做业务判定', control: newPwd }));
  grid.appendChild(ui.field({ id: 'au-pw-confirm', label: '确认新口令', required: true, control: confirmPwd }));
  form.appendChild(grid);
  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '修改口令' });
  form.appendChild(ui.el('div', { class: 'hg-form-actions' }, submit));
  card.appendChild(form);
  container.appendChild(card);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    let ok = true;
    if (!oldPwd.value) { ui.setFieldError(oldPwd, '请输入原口令'); ok = false; } else ui.setFieldError(oldPwd, '');
    if (!newPwd.value) { ui.setFieldError(newPwd, '请输入新口令'); ok = false; } else ui.setFieldError(newPwd, '');
    if (newPwd.value !== confirmPwd.value) { ui.setFieldError(confirmPwd, '两次输入的新口令不一致'); ok = false; } else ui.setFieldError(confirmPwd, '');
    if (!ok) return;
    submit.disabled = true; const orig = submit.textContent; submit.textContent = '提交中…';
    try {
      await HG.api.post(R.PASSWORD, { oldPassword: oldPwd.value, newPassword: newPwd.value }, { idempotencyKey: HG.api.newIdempotencyKey() });
      oldPwd.value = ''; newPwd.value = ''; confirmPwd.value = ''; // 口令不驻留
      alertBox.appendChild(ui.banner('口令已修改。', 'ok'));
      ui.toast('口令已修改', 'ok');
      form.reset();
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
    } finally {
      submit.disabled = false; submit.textContent = orig;
    }
  });
}

/* ------------------------------------------------------------------ *
 * 页面装配
 * ------------------------------------------------------------------ */
const TABS = [
  { key: 'users', label: '用户', build: usersTab },
  { key: 'roles', label: '角色', build: rolesTab },
  { key: 'position', label: '岗位组与互斥', build: positionTab },
  { key: 'authz', label: '人员授权', build: authzTab },
  { key: 'subperm', label: '子账户数据权限', build: subPermTab },
  { key: 'changelog', label: '权限变更留痕', build: changeLogTab },
  { key: 'attempt', label: '越权尝试', build: attemptTab },
  { key: 'password', label: '我的口令', build: passwordTab }
];

function renderAuth(root, ctx) {
  root.appendChild(ui.el('h2', { class: 'hg-page__title', text: '用户与权限（AUTH）' }));
  root.appendChild(ui.el('p', { class: 'hg-page__desc', text: '用户 / 角色 / 岗位组互斥 / 人员授权 / 子账户数据权限 / 变更留痕（FR-AUTH-1~18）。' }));
  root.appendChild(ui.banner(
    '<strong>界面边界声明</strong>：本页<strong>仅提供界面，不做授权判定</strong>。功能权限、数据权限（子账户隔离）、'
      + '额度权限的判定与子账户可见性过滤<strong>一律由后端负责</strong>——越权请求由后端以 <code>10003</code>/<code>10004</code> '
      + '拒绝并留痕（<code>R-AUTH-8</code>）。前端不隐藏越权入口，只如实呈现后端结论。'
      + '岗位互斥的操盘拒绝判据为<strong>析取</strong>（<code>CH-010</code>）：被标记企业负责人与持有管理层（审批人）角色'
      + '是<strong>两个各自独立</strong>的拒绝条件，满足<strong>任一</strong>即拒绝（<strong>非</strong>「且」）。',
    'warn'
  ));

  const tabBar = ui.el('div', { class: 'hg-tabs', role: 'tablist' });
  const panel = ui.el('div');
  let active = (ctx && ctx.query && ctx.query.tab) || 'users';
  if (!TABS.some((t) => t.key === active)) active = 'users';

  function select(key) {
    Array.prototype.forEach.call(tabBar.children, (b) => {
      const on = b.dataset.key === key;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    ui.clear(panel);
    TABS.filter((t) => t.key === key)[0].build(panel);
  }
  TABS.forEach((t) => {
    const b = ui.el('button', { type: 'button', role: 'tab', text: t.label, onclick: () => HG.router.navigate('/auth?tab=' + t.key) });
    b.dataset.key = t.key;
    tabBar.appendChild(b);
  });

  root.appendChild(tabBar);
  root.appendChild(panel);
  select(active);
}

HG.pages = HG.pages || {};
HG.pages.auth = { renderAuth };
HG.router.register('/auth', renderAuth);
export default HG.pages.auth;
