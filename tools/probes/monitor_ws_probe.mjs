/*
 * 阶段 3 组件监控 WS 探针（无头桩，非真实浏览器）。
 *
 * 立意：组件告警**复用冻结通道** `/ws/alert` 与既有 `type=alert` 帧
 * （java/CONTRACTS.md §4.1），不新增 WS 端点 / 通道 / 消息形态。
 * 断言：HG.ws.connectComponentAlerts 已导出、订阅 channels=['alert']、
 * `component.` 前缀告警进入 onComponentAlert、其余 alert 帧仍透传 onMessage。
 *
 * 用法：node tools/probes/monitor_ws_probe.mjs → 退出码 0 = 全过。
 */
/* ---------------- 全局桩先行（ns.js / ws.js 顶层读取 window） ---------------- */
globalThis.window = {
  location: { host: 'probe.local', protocol: 'http:' },
  setTimeout: () => 0,
  clearTimeout: () => {},
  addEventListener() {}
};
const sockets = [];
globalThis.WebSocket = class WebSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 1;
    this.sent = [];
    this._l = {};
    sockets.push(this);
  }
  addEventListener(type, fn) {
    (this._l[type] = this._l[type] || []).push(fn);
  }
  send(data) { this.sent.push(data); }
  close() {}
};
globalThis.WebSocket.OPEN = 1;

const { HG } = await import('../../web/js/core/ns.js');
await import('../../web/js/core/ws.js');

let pass = 0;
let fail = 0;
function ok(cond, msg) {
  if (cond) {
    pass += 1;
  } else {
    fail += 1;
    console.error('FAIL: ' + msg);
  }
}

ok(typeof HG.ws.connectComponentAlerts === 'function',
    'HG.ws.connectComponentAlerts 已导出（E6 跨模块引用判据）');
ok(HG.ws.WS_PATH.alert === '/ws/alert', '复用冻结端点 /ws/alert，未新增 WS 端点');

const component = [];
const all = [];
const conn = HG.ws.connectComponentAlerts({
  onComponentAlert: (m) => component.push(m),
  onMessage: (m) => all.push(m)
});

ok(sockets.length === 1, '恰好建立 1 条连接');
ok(sockets[0].url === 'ws://probe.local/ws/alert', '连接 URL = 同源 + /ws/alert（无硬编码 IP:端口）');

const sub = sockets[0].sent.map((s) => JSON.parse(s))
    .find((m) => m.type === 'subscribe');
ok(sub && sub.channels && sub.channels.length === 1 && sub.channels[0] === 'alert',
    "订阅 channels=['alert']（不新增通道名）");

function deliver(frame) {
  const data = JSON.stringify(frame);
  (sockets[0]._l.message || []).forEach((fn) => fn({ data }));
}

const compAlert = { type: 'alert', level: 'CRITICAL', metric: 'component.state',
    value: 'NOT_READY', threshold: 'READY', subAccount: 0, ts: '2026-11-22T21:00:00' };
const otherAlert = { type: 'alert', level: 'WARNING', metric: 'POSITION',
    value: '1', threshold: '0', subAccount: 1, ts: '2026-11-22T21:00:01' };
deliver(compAlert);
deliver(otherAlert);

ok(component.length === 1 && component[0].metric === 'component.state',
    'component. 前缀告警进入 onComponentAlert');
ok(all.length === 2, '全部 alert 帧仍按原语义透传 onMessage（不破坏既有告警页）');
ok(conn && typeof conn.close === 'function', '返回可关闭的连接句柄');
conn.close();

console.log('monitor_ws_probe: PASS=' + pass + ' FAIL=' + fail);
process.exit(fail === 0 ? 0 : 1);
