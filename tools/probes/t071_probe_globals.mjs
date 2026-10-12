/*
 * T-071 探针全局桩（在 ns.js 等模块求值前加载）：window / document 最小实现。
 * 仅服务探针；不参与交付物。document 的完整桩在探针主文件定义并覆盖 document.body 等。
 */
const minimalDoc = {
  createElement(tag) {
    return {
      tagName: String(tag).toUpperCase(), children: [], dataset: {}, style: {}, attrs: {}, value: '',
      listeners: {}, parentNode: null,
      classList: { _s: new Set(), add(c) { this._s.add(c); }, remove(c) { this._s.delete(c); }, toggle(c, on) { on ? this._s.add(c) : this._s.delete(c); }, contains(c) { return this._s.has(c); } },
      set className(v) { this.classList._s = new Set(String(v).split(/\s+/).filter(Boolean)); },
      get className() { return Array.from(this.classList._s).join(' '); },
      set textContent(v) { this._t = String(v); this.children = []; },
      get textContent() { return (this._t || '') + (this._h || '') + this.children.map((c) => c.textContent).join(''); },
      set innerHTML(v) { this._h = String(v); this.children = []; },
      get innerHTML() { return this._h || ''; },
      setAttribute(k, v) { this.attrs[k] = String(v); }, getAttribute(k) { return this.attrs[k] ?? null; },
      removeAttribute(k) { delete this.attrs[k]; },
      appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
      insertBefore(c, ref) { c.parentNode = this; const i = ref ? this.children.indexOf(ref) : -1; if (i === -1) this.children.push(c); else this.children.splice(i, 0, c); return c; },
      removeChild(c) { const i = this.children.indexOf(c); if (i !== -1) this.children.splice(i, 1); c.parentNode = null; return c; },
      remove() { if (this.parentNode) this.parentNode.removeChild(this); },
      addEventListener(t, fn) { (this.listeners[t] = this.listeners[t] || []).push(fn); },
      click() { (this.listeners.click || []).forEach((fn) => fn({ target: this })); },
      focus() {}, querySelector() { return null; },
      get firstChild() { return this.children[0] || null; }, get form() { return null; }
    };
  },
  createTextNode(t) { const n = minimalDoc.createElement('#text'); n._t = String(t); return n; },
  body: null, addEventListener() {}, removeEventListener() {},
  getElementById() { return null; }, querySelector() { return null; }
};
minimalDoc.body = minimalDoc.createElement('body');
minimalDoc.body.querySelector = () => null;
minimalDoc.body.appendChild = function (c) { c.parentNode = this; this.children.push(c); return c; };

globalThis.window = {
  crypto: { randomUUID: () => 'probe-uuid' },
  setTimeout: () => 0,
  location: { host: 'probe.local', search: '' },
  addEventListener() {}
};
globalThis.__RealURL = URL; // 保留真 URL（页面下载桩会覆盖 URL）

/** 全局 id 注册表：setAttribute('id', v) 与 appendChild 时自动登记，供探针 document.getElementById 用。 */
const byId = {};
function registerIdsDeep(node) {
  if (!node || typeof node !== 'object') return;
  if (node.attrs && node.attrs.id && !byId[node.attrs.id]) byId[node.attrs.id] = node;
  if (Array.isArray(node.children)) node.children.forEach(registerIdsDeep);
}
const origSetAttribute = minimalDoc.createElement;
minimalDoc.createElement = function (tag) {
  const n = origSetAttribute.call(this, tag);
  const origAttr = n.setAttribute.bind(n);
  n.setAttribute = function (k, v) { origAttr(k, v); if (k === 'id') byId[v] = n; };
  return n;
};
const origAppend = minimalDoc.body.appendChild.bind(minimalDoc.body);

globalThis.document = minimalDoc;
globalThis.__probeById = byId;
globalThis.__probeRegisterIdsDeep = registerIdsDeep;
export default minimalDoc;
