/*
 * 通用 UI 工具（DOM 构造 + 三态渲染 + 表格 + 提示）。
 *
 * 三态纪律（前端纪律 §5.1）：任何数据获取界面必须同时实现
 *   加载态 / 空态 / 错误态——缺一即未完成。
 * 阻断类错误一律经 HG.errmsg.render 渲染四项（不得只显示"操作不允许"）。
 * 零构建链：原生 DOM API，无框架、无第三方库。
 */
import { HG } from './ns.js';
import './format.js'; // 建立依赖顺序：ui 顶层读取 HG.format

const F = HG.format;

/** 创建元素：el('div', {class:'x', onclick:fn}, [child|string])。 */
function el(tag, attrs, children) {
  const node = document.createElement(tag);
  if (attrs) {
    Object.keys(attrs).forEach((k) => {
      const v = attrs[k];
      if (v === null || v === undefined || v === false) return;
      if (k === 'class') node.className = v;
      else if (k === 'text') node.textContent = v;
      else if (k === 'html') node.innerHTML = v;
      else if (k.slice(0, 2) === 'on' && typeof v === 'function') node.addEventListener(k.slice(2), v);
      else if (k === 'for') node.htmlFor = v;
      else node.setAttribute(k, v === true ? '' : v);
    });
  }
  appendChildren(node, children);
  return node;
}

function appendChildren(node, children) {
  if (children === null || children === undefined) return;
  (Array.isArray(children) ? children : [children]).forEach((c) => {
    if (c === null || c === undefined || c === false) return;
    node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  });
}

function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }

/** 加载态。 */
function renderLoading(container, text) {
  clear(container);
  container.appendChild(el('div', { class: 'hg-state', role: 'status', 'aria-live': 'polite' }, [
    el('span', { class: 'hg-spinner', 'aria-hidden': 'true' }),
    el('span', { text: text || '正在加载…' })
  ]));
}

/** 空态。 */
function renderEmpty(container, text) {
  clear(container);
  container.appendChild(el('div', { class: 'hg-state' }, [
    el('div', { class: 'hg-state__title', text: '暂无数据' }),
    el('div', { text: text || '当前筛选条件下没有记录。' })
  ]));
}

/** 错误态（含重试入口）。 */
function renderError(container, err, onRetry) {
  clear(container);
  const box = el('div', { class: 'hg-state hg-state--error', role: 'alert' });
  box.appendChild(el('div', { class: 'hg-state__title', text: '加载失败' }));
  box.appendChild(el('div', { html: HG.errmsg.render(err) }));
  if (typeof onRetry === 'function') {
    box.appendChild(el('div', { class: 'hg-banner__actions' }, [
      el('button', { class: 'hg-btn hg-btn--ghost hg-btn--sm', type: 'button', text: '重试', onclick: onRetry })
    ]));
  }
  container.appendChild(box);
}

/** 内联提示条（非阻断场景）。 */
function banner(text, kind, actions) {
  const node = el('div', { class: 'hg-banner' + (kind ? ' hg-banner--' + kind : ''), role: 'note' });
  node.appendChild(el('div', { html: text }));
  if (actions && actions.length) {
    node.appendChild(el('div', { class: 'hg-banner__actions' }, actions));
  }
  return node;
}

/** 状态徽标：按业务状态文本归类着色（仅展示，不做业务判定）。 */
const STATUS_CLASS = [
  [/草稿|待执行/, 'draft'],
  [/待审批|待复核|调整中|待套保/, 'pending'],
  [/已批准|生效|有效|启用|执行中|部分覆盖|已覆盖|已完成|已排产/, 'active'],
  [/冻结|挂起/, 'frozen'],
  [/已关闭|已终止|已撤销|已失效/, 'done'],
  [/已作废|驳回|停用/, 'void'],
  [/拒绝|违规|不一致|告警/, 'danger']
];

function statusBadge(text) {
  const t = String(text || '');
  let cls = 'draft';
  for (let i = 0; i < STATUS_CLASS.length; i++) {
    if (STATUS_CLASS[i][0].test(t)) { cls = STATUS_CLASS[i][1]; break; }
  }
  return `<span class="hg-badge hg-badge--${cls}">${F.esc(t || '—')}</span>`;
}

/** 生成表格 HTML。columns: [{key,title,num,render(row)}] */
function tableHTML(columns, rows) {
  const head = columns.map((c) => `<th${c.num ? ' class="hg-num"' : ''}>${F.esc(c.title)}</th>`).join('');
  const body = rows.map((row) => {
    const cells = columns.map((c) => {
      let v;
      if (typeof c.render === 'function') v = c.render(row);
      else v = F.esc(row[c.key]);
      return `<td${c.num ? ' class="hg-num"' : ''}>${v === undefined || v === null ? '' : v}</td>`;
    }).join('');
    return `<tr>${cells}</tr>`;
  }).join('');
  return `<div class="hg-table-wrap"><table class="hg-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

/** 轻提示。 */
function toast(message, kind) {
  let host = document.querySelector('.hg-toast-host');
  if (!host) { host = el('div', { class: 'hg-toast-host' }); document.body.appendChild(host); }
  const node = el('div', { class: 'hg-toast' + (kind ? ' hg-toast--' + kind : ''), role: 'status', text: message });
  host.appendChild(node);
  window.setTimeout(() => { if (node.parentNode) node.parentNode.removeChild(node); }, 3200);
}

/** 二次确认弹窗（危险操作必需，前端纪律 §5.4）。返回 Promise<boolean>。 */
function confirm(message, opts) {
  opts = opts || {};
  return new Promise((resolve) => {
    const overlay = el('div', { class: 'hg-modal', role: 'dialog', 'aria-modal': 'true' });
    const box = el('div', { class: 'hg-modal__box' });
    box.appendChild(el('h3', { class: 'hg-modal__title', text: opts.title || '请确认' }));
    box.appendChild(el('div', { html: message }));
    const actions = el('div', { class: 'hg-form-actions' });
    const cancel = el('button', { class: 'hg-btn hg-btn--ghost', type: 'button', text: opts.cancelText || '取消' });
    const ok = el('button', { class: 'hg-btn' + (opts.danger ? ' hg-btn--danger' : ''), type: 'button', text: opts.okText || '确认' });
    function close(val) { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); document.removeEventListener('keydown', onKey); resolve(val); }
    function onKey(e) { if (e.key === 'Escape') close(false); }
    cancel.addEventListener('click', () => close(false));
    ok.addEventListener('click', () => close(true));
    overlay.addEventListener('click', (e) => { if (e.target === overlay) close(false); });
    document.addEventListener('keydown', onKey);
    actions.appendChild(cancel); actions.appendChild(ok);
    box.appendChild(actions);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    ok.focus();
  });
}

/** 字段容器 + 内联说明 + 即时校验位。 */
function field(opts) {
  const id = opts.id;
  const wrap = el('div', { class: 'hg-field' });
  wrap.appendChild(el('label', { for: id, class: opts.required ? 'hg-req' : '', text: opts.label }));
  wrap.appendChild(opts.control);
  if (opts.help) wrap.appendChild(el('div', { class: 'hg-field__help', text: opts.help }));
  const errNode = el('div', { class: 'hg-field__error', id: id + '-err', 'aria-live': 'polite' });
  wrap.appendChild(errNode);
  return wrap;
}

function setFieldError(control, message) {
  if (!control) return;
  const errNode = control.form && control.form.querySelector(`#${control.id}-err`) ||
    document.getElementById((control.id || '') + '-err');
  if (message) { control.classList.add('is-invalid'); control.setAttribute('aria-invalid', 'true'); }
  else { control.classList.remove('is-invalid'); control.removeAttribute('aria-invalid'); }
  if (errNode) errNode.textContent = message || '';
}

/** 阻断错误的内联呈现（表单提交失败时置于表单顶部）。 */
function blockedHTML(err) { return HG.errmsg.render(err); }

HG.ui = {
  el, clear, renderLoading, renderEmpty, renderError, banner, statusBadge,
  tableHTML, toast, confirm, field, setFieldError, blockedHTML, esc: F.esc
};
export default HG.ui;
