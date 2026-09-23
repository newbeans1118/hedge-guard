/*
 * 登录页逻辑（FR-AUTH-14 / FR-AUTH-15；ARCHITECTURE.md §4.1.1 会话）。
 * - POST /auth/login { username, password }；成功 → 跳转主框架。
 * - 统一错误信息（不区分"用户不存在/口令错误"，防枚举）；锁定提示解锁时间。
 * - 不提供"记住我 / 长期免登录"（AUTH §2 登录会话约束）。
 * - 本层只提交与展示；口令校验、失败计数、锁定一律由后端判定（前端不做业务判定）。
 */
import { HG } from '../core/ns.js';
import '../core/routes.js';
import '../core/session.js';

function render(root) {
  const ui = HG.ui;
  const form = ui.el('form', { class: 'hg-login__card', novalidate: 'novalidate' });
  form.appendChild(ui.el('h1', { class: 'hg-login__brand', text: 'HedgeGuard' }));
  form.appendChild(ui.el('p', { class: 'hg-login__sub', text: '企业期货套期保值管理系统' }));

  const alertBox = ui.el('div', { id: 'login-alert', role: 'alert' });
  form.appendChild(alertBox);

  const username = ui.el('input', { id: 'login-username', name: 'username', type: 'text', autocomplete: 'username', required: true });
  const password = ui.el('input', { id: 'login-password', name: 'password', type: 'password', autocomplete: 'current-password', required: true });

  form.appendChild(ui.field({ id: 'login-username', label: '用户名', required: true, control: username }));
  form.appendChild(ui.field({ id: 'login-password', label: '口令', required: true, control: password }));

  const submit = ui.el('button', { class: 'hg-btn', type: 'submit', text: '登录' });
  submit.style.width = '100%';
  submit.style.marginTop = '8px';
  form.appendChild(submit);

  form.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    ui.clear(alertBox);
    ui.setFieldError(username, '');
    ui.setFieldError(password, '');

    const u = username.value.trim();
    const p = password.value;
    let bad = false;
    if (!u) { ui.setFieldError(username, '请输入用户名'); bad = true; }
    if (!p) { ui.setFieldError(password, '请输入口令'); bad = true; }
    if (bad) return;

    submit.disabled = true;
    submit.textContent = '登录中…';
    try {
      const data = await HG.api.post(HG.routes.AUTH.LOGIN, { username: u, password: p });
      // 记录展示用会话摘要（{userId, username, expireTime}；不含凭据）。会话本身在 HttpOnly Cookie。
      HG.session.setSessionSummary(data);
      if (data && data.mustChangePassword) {
        // FR-AUTH-16：首次登录 / 重置后强制修改口令 —— 服务端已限制全部业务端点，前端直接引导至改密 tab。
        ui.toast('请先修改初始口令后再使用系统', 'warn');
        window.location.href = '/web/index.html#/auth?tab=password';
        return;
      }
      ui.toast('登录成功，正在进入系统…', 'ok');
      window.location.href = '/web/index.html';
    } catch (err) {
      alertBox.innerHTML = ui.blockedHTML(err);
      if (err && err.code === 10008) {
        // 账号锁定：后端 message 应含解锁时间；此处不补充业务判定
        ui.toast('账号已锁定', 'error');
      }
      submit.disabled = false;
      submit.textContent = '登录';
    }
  });

  root.appendChild(form);
  username.focus();
}

HG.pages = HG.pages || {};
HG.pages.login = { render };
export default HG.pages.login;
