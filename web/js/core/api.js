/*
 * HedgeGuard 前端 API 客户端（ARCHITECTURE.md §4.1.1）。
 *
 * 契约要点：
 *  - 基础路径 /api/v1；响应信封 { code, message, data, traceId }，code=0 为成功。
 *  - 会话 Cookie HGSESSION（HttpOnly，同源自动携带）；头 X-HG-Session 亦可由服务端设置。
 *  - 金额 / 数量为字符串化十进制——本层原样透传，不做 Number 转换。
 *  - 分页 ?page=&size=&sort=field,asc；size ∈ {10,20,50,100}，默认 20。
 *  - 写类接口（出入金 / 划拨 / 下单 / 参数下发）必须带幂等头 Idempotency-Key。
 *  - 阻断类错误（含 ruleId/current/threshold/nextStep）原样向上抛出，交由 errmsg 渲染。
 *
 * 边界（NFR-MAINT-5）：前端只与本组件的 /api/v1/** 与 /ws/** 通信；
 * 不直连数据库、不直连 C++ 组件；不做业务判定（风控 / 额度 / 会计口径以后端返回为准）。
 * 零构建链：原生 ES Module + fetch（NFR-MAINT-1）。
 */
import { HG } from './ns.js';

export const BASE = '/api/v1';

/** 允许的分页大小（§4.1.1）。 */
export const PAGE_SIZES = [10, 20, 50, 100];
export const DEFAULT_PAGE_SIZE = 20;

/** 生成幂等键（写类接口必需）。 */
function newIdempotencyKey() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID();
  }
  const rnd = () => Math.floor((1 + Math.random()) * 0x10000).toString(16).slice(1);
  return `${Date.now().toString(16)}-${rnd()}${rnd()}-${rnd()}${rnd()}`;
}

/** 组装查询串；仅接受约定字段。 */
function buildQuery(query) {
  if (!query) return '';
  const parts = [];
  Object.keys(query).forEach((k) => {
    const v = query[k];
    if (v === undefined || v === null || v === '') return;
    if (k === 'size' && PAGE_SIZES.indexOf(Number(v)) === -1) {
      throw { code: 10001, message: `size 取值非法：仅允许 ${PAGE_SIZES.join('/')}`, nextStep: '请改用允许的分页大小。' };
    }
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  });
  return parts.length ? '?' + parts.join('&') : '';
}

async function request(method, path, opts) {
  opts = opts || {};
  const headers = Object.assign({ Accept: 'application/json' }, opts.headers || {});
  if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;
  const options = { method, credentials: 'same-origin', headers };
  if (opts.body !== undefined && opts.body !== null) {
    headers['Content-Type'] = 'application/json; charset=UTF-8';
    options.body = JSON.stringify(opts.body);
  }

  // 查询串在 try 外构建：分页 size 校验等「客户端参数错误」不得被误判为网络故障。
  const url = BASE + path + buildQuery(opts.query);

  let res;
  try {
    res = await fetch(url, options);
  } catch (e) {
    throw { code: -1, message: '无法连接后端服务', _network: true, nextStep: '请确认 Java 业务组件已启动并完成装配，然后重试。' };
  }

  if (res.status === 204) return null;

  const text = await res.text();
  let env = null;
  if (text) {
    try { env = JSON.parse(text); }
    catch (e) {
      throw {
        code: -1, message: '响应不是合法 JSON（后端可能尚未装配业务层）',
        _network: true, _http: res.status, _raw: text.slice(0, 300),
        nextStep: '请确认后端已装配业务层并返回统一响应信封。'
      };
    }
  }
  if (!env || typeof env !== 'object') {
    throw { code: -1, message: '空响应', _network: true, _http: res.status };
  }
  if (env.code === 0) return env.data;

  const err = Object.assign({ _http: res.status }, env);
  throw err;
}

/** GET 查询。 */
function get(path, query) { return request('GET', path, { query }); }

/** POST 新增 / 动作。 */
function post(path, body, opts) {
  opts = opts || {};
  return request('POST', path, { body, query: opts.query, idempotencyKey: opts.idempotencyKey });
}

/** PUT 修改。 */
function put(path, body, opts) {
  opts = opts || {};
  return request('PUT', path, { body, query: opts.query, idempotencyKey: opts.idempotencyKey });
}

/** DELETE（仅限草稿类物理删除）。 */
function del(path, opts) {
  opts = opts || {};
  return request('DELETE', path, { query: opts.query, idempotencyKey: opts.idempotencyKey });
}

/**
 * 分页查询便捷封装：返回 { items, total, page, size }（响应缺位时按空结果兜底）。
 * 注意：不做业务判定，仅做结构兜底，避免界面崩溃。
 */
async function list(path, params) {
  const p = Object.assign({ page: 1, size: DEFAULT_PAGE_SIZE }, params || {});
  const data = await get(path, p);
  return {
    items: (data && data.items) || [],
    total: data && typeof data.total === 'number' ? data.total : ((data && data.items) || []).length,
    page: data && data.page ? data.page : p.page,
    size: data && data.size ? data.size : p.size
  };
}

/** 从 Content-Disposition 取文件名（导出类响应，§4.1.1「请求体 … 导出除外」）。 */
function filenameFromDisposition(header) {
  if (!header) return null;
  const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  if (!m) return null;
  try { return decodeURIComponent(m[1].trim()); } catch (e) { return m[1].trim(); }
}

/**
 * 下载类接口（非 JSON 响应，如 CSV 导出）。
 * 返回 { blob, filename }；若服务端返回统一 JSON 信封（业务错误 / 未登录），
 * 按普通错误抛出，交由 errmsg 渲染四项（不吞错误、不静默失败）。
 *
 * 边界：仍是前端**唯一出网通道**（同源 fetch），不新增任何直连（NFR-MAINT-5 / E6）。
 */
async function download(path, query) {
  const url = BASE + path + buildQuery(query);
  let res;
  try {
    res = await fetch(url, {
      method: 'GET', credentials: 'same-origin',
      headers: { Accept: 'text/csv, application/json;q=0.9, */*;q=0.8' }
    });
  } catch (e) {
    throw { code: -1, message: '无法连接后端服务', _network: true, nextStep: '请确认 Java 业务组件已启动并完成装配，然后重试。' };
  }

  const ct = (res.headers.get('Content-Type') || '').toLowerCase();
  if (ct.indexOf('json') !== -1) {
    const text = await res.text();
    let env = null;
    try { env = JSON.parse(text); } catch (e) { env = null; }
    if (env && typeof env === 'object') {
      if (env.code === 0) return { blob: new Blob([text], { type: ct }), filename: filenameFromDisposition(res.headers.get('Content-Disposition')) };
      throw Object.assign({ _http: res.status }, env);
    }
    throw {
      code: -1, message: '导出响应不是合法信封', _network: true, _http: res.status,
      _raw: text.slice(0, 300), nextStep: '请确认后端已装配业务层并返回统一响应信封。'
    };
  }
  return { blob: await res.blob(), filename: filenameFromDisposition(res.headers.get('Content-Disposition')) };
}

HG.api = { BASE, PAGE_SIZES, DEFAULT_PAGE_SIZE, newIdempotencyKey, get, post, put, del, list, download, request };
export default HG.api;
