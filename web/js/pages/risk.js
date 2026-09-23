/*
 * 风险控制页面（RISK）—— 开工骨架（M3，FR-RISK-1~19）。
 * 关键约束：事中告警走 /ws/alert（不允许丢，重连后补拉 /risk/alert?since=）；事前风控在 C++ 全内存，前端不得做风控判定。
 */
import { HG } from '../core/ns.js';
import '../core/router.js';
import './_stub.js';

HG.router.register('/risk', HG.pages.stub.renderStub({
  code: 'RISK', name: '风险控制', fr: 'FR-RISK-1~19', milestone: 'M3',
  apiPaths: ['/risk/dashboard', '/risk/alert', '/risk/alert/{id}/dispose', '/risk/limit-open', '/risk/force-close-hint', '/risk/violation', '/risk/limit-usage', '/risk/mirror-status', '/risk/rule', '/risk/post-report'],
  notes: [
    '告警推送：<code>/ws/alert</code>（事件驱动，不允许丢）；断线重连后补拉 <code>/risk/alert?since=</code>。',
    '限额三层（<code>Q-16</code>）与"只限开仓、不限平仓"（<code>R-RISK-5</code>）须在界面明确标示。',
    '强制平仓仅提示，不自动下单（<code>R-RISK-13</code>）。'
  ]
}));

export default HG.pages.stub;
