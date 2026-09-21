/*
 * 极简 hash 路由（零构建链，无第三方路由库）。
 * 路由表由各页面模块注册：HG.router.register('/plan/:id', renderFn)。
 * renderFn(container, params) 负责该页面自身的加载 / 空 / 错误三态。
 */
import { HG } from './ns.js';

const routes = [];
let containerEl = null;
let notFound = null;

function compile(pattern) {
  const keys = [];
  const regex = new RegExp('^' + pattern.replace(/:[^/]+/g, (m) => { keys.push(m.slice(1)); return '([^/]+)'; }) + '$');
  return { regex, keys };
}

function register(pattern, renderFn) {
  const c = compile(pattern);
  routes.push({ pattern, regex: c.regex, keys: c.keys, render: renderFn });
}

function setNotFound(fn) { notFound = fn; }

function currentPath() {
  const hash = window.location.hash || '';
  const path = hash.replace(/^#/, '') || '/dashboard';
  return path.charAt(0) === '/' ? path : '/' + path;
}

function match(path) {
  const pathOnly = path.split('?')[0];
  for (let i = 0; i < routes.length; i++) {
    const m = routes[i].regex.exec(pathOnly);
    if (m) {
      const params = {};
      routes[i].keys.forEach((k, idx) => { params[k] = decodeURIComponent(m[idx + 1]); });
      const q = {};
      const qs = path.split('?')[1];
      if (qs) qs.split('&').forEach((pair) => {
        if (!pair) return;
        const kv = pair.split('=');
        q[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || '');
      });
      return { route: routes[i], params, query: q };
    }
  }
  return null;
}

function renderCurrent() {
  const path = currentPath();
  const hit = match(path);
  HG.ui.clear(containerEl);
  window.scrollTo(0, 0);
  if (!hit) { if (typeof notFound === 'function') notFound(containerEl, path); else HG.ui.renderEmpty(containerEl, '未找到页面：' + path); return; }
  const ctx = { path, params: hit.params, query: hit.query };
  try {
    const maybePromise = hit.route.render(containerEl, ctx);
    if (maybePromise && typeof maybePromise.catch === 'function') {
      maybePromise.catch((err) => HG.ui.renderError(containerEl, err, renderCurrent));
    }
  } catch (err) {
    HG.ui.renderError(containerEl, err, renderCurrent);
  }
  highlightNav(path);
}

function navigate(path) {
  const target = path.charAt(0) === '/' ? path : '/' + path;
  if (currentPath() === target) renderCurrent();
  else window.location.hash = '#' + target;
}

function highlightNav(path) {
  const base = '/' + (path.split('?')[0].replace(/^\//, '').split('/')[0] || '');
  document.querySelectorAll('.hg-nav a').forEach((a) => {
    const href = a.getAttribute('href') || '';
    const linkBase = '/' + href.replace(/^#/, '').replace(/^\//, '').split('/')[0];
    a.classList.toggle('is-active', linkBase === base && base !== '/');
  });
}

function start(container) {
  containerEl = container;
  window.addEventListener('hashchange', renderCurrent);
  renderCurrent();
}

HG.router = { register, setNotFound, navigate, start, currentPath };
export default HG.router;
