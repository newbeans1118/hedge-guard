/*
 * HedgeGuard WebSocket 客户端（ARCHITECTURE.md §4.1.2 与订阅消息 W-1）。
 *
 * 端点：/ws/quote（行情，允许丢帧，仅推送最新值）、/ws/alert（告警，不允许丢，断线重连后补拉 /risk/alert?since=）。
 * 客户端 → 服务端：仅 subscribe / unsubscribe（业务消息不提供）；心跳用 RFC 6455 原生 ping/pong，非业务 JSON。
 * 鉴权：沿用会话 Cookie HGSESSION（握手自动携带）；本层不携带任何凭据。
 * 渲染纪律（前端纪律 §5.3）：高频行情必须节流 / 差量更新，本层提供 throttle 供消费方使用。
 * 零构建链：原生 WebSocket。
 */
import { HG } from './ns.js';

const WS_PATH = { quote: '/ws/quote', alert: '/ws/alert' };

function wsBase() {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return proto + '//' + window.location.host;
}

/**
 * 建立到某端点的连接，带自动重连与重订阅。
 * @param {'quote'|'alert'} endpoint
 * @param {{onMessage?:Function,onOpen?:Function,onClose?:Function,onError?:Function,onSubscribed?:Function}} handlers
 */
function connect(endpoint, handlers) {
  handlers = handlers || {};
  const url = wsBase() + WS_PATH[endpoint];
  let socket = null;
  let closedByUser = false;
  let attempt = 0;
  let lastSub = { channels: [endpoint], instruments: null, subAccounts: null };

  function open() {
    socket = new WebSocket(url);
    socket.addEventListener('open', () => {
      attempt = 0;
      sendSubscription(lastSub); // 重连后重新订阅（§4.1.2）
      if (handlers.onOpen) handlers.onOpen();
    });
    socket.addEventListener('message', (evt) => {
      let msg = null;
      try { msg = JSON.parse(evt.data); } catch (e) { return; }
      if (msg.type === 'subscribed' && handlers.onSubscribed) handlers.onSubscribed(msg);
      if (handlers.onMessage) handlers.onMessage(msg);
    });
    socket.addEventListener('close', () => {
      if (handlers.onClose) handlers.onClose();
      if (!closedByUser) scheduleReconnect();
    });
    socket.addEventListener('error', (e) => { if (handlers.onError) handlers.onError(e); });
  }

  function scheduleReconnect() {
    attempt += 1;
    const delay = Math.min(15000, 500 * Math.pow(2, Math.min(attempt, 5)));
    window.setTimeout(() => { if (!closedByUser) open(); }, delay);
  }

  function send(msg) {
    if (socket && socket.readyState === WebSocket.OPEN) { socket.send(JSON.stringify(msg)); return true; }
    return false;
  }

  function sendSubscription(sub) {
    const msg = { type: 'subscribe', channels: sub.channels || [endpoint] };
    if (sub.instruments && sub.instruments.length) msg.instruments = sub.instruments.map((s) => String(s).toUpperCase());
    if (sub.subAccounts && sub.subAccounts.length) msg.subAccounts = sub.subAccounts;
    send(msg);
  }

  /** 订阅；通道须 ⊆ 端点允许通道（跨端点由服务端回 error，不关闭）。 */
  function subscribe(opts) {
    opts = opts || {};
    lastSub = {
      channels: opts.channels && opts.channels.length ? opts.channels : [endpoint],
      instruments: opts.instruments || null,
      subAccounts: opts.subAccounts || null
    };
    sendSubscription(lastSub);
  }

  function unsubscribe(channels) { send({ type: 'unsubscribe', channels: channels && channels.length ? channels : [endpoint] }); }

  function close() { closedByUser = true; if (socket) socket.close(); }

  open();
  return { subscribe, unsubscribe, close, isOpen: () => !!socket && socket.readyState === WebSocket.OPEN };
}

/** 通用节流：限制单位时间内的调用次数（高频推送消费用）。 */
function throttle(fn, waitMs) {
  let last = 0;
  let timer = null;
  let pendingArgs = null;
  return function throttled() {
    pendingArgs = arguments;
    const now = Date.now();
    const remaining = waitMs - (now - last);
    if (remaining <= 0) {
      last = now; fn.apply(null, pendingArgs); pendingArgs = null;
    } else if (!timer) {
      timer = window.setTimeout(() => {
        timer = null; last = Date.now(); fn.apply(null, pendingArgs); pendingArgs = null;
      }, remaining);
    }
  };
}

/** 尾随去抖：突发推送只保留最后一次（行情「最近值」语义）。 */
function debounce(fn, waitMs) {
  let timer = null;
  return function debounced() {
    const args = arguments;
    if (timer) window.clearTimeout(timer);
    timer = window.setTimeout(() => { timer = null; fn.apply(null, args); }, waitMs);
  };
}

HG.ws = { connect, throttle, debounce, WS_PATH };
export default HG.ws;
