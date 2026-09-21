/*
 * 系统配置页面（CONFIG）—— 开工骨架（M1，FR-CONFIG-1~15）。
 * 关键约束：敏感字段零落盘（不落 DB / 日志 / 核心转储，NFR-SEC-1）；安全配置不得放宽合规红线（10021）；
 *           参数下发未生效（10014）不得视为已生效。
 */
import { HG } from '../core/ns.js';

HG.router.register('/config', HG.pages.stub.renderStub({
  code: 'CONFIG', name: '系统配置', fr: 'FR-CONFIG-1~15', milestone: 'M1',
  apiPaths: ['/config/item', '/config/ctp', '/config/ctp/test', '/config/sub-account', '/config/dict', '/config/subject', '/config/report-period', '/config/dispatch/{id}', '/config/change-log', '/config/import', '/config/export'],
  notes: [
    '子账户管理（<code>FR-CONFIG-7</code>）：<strong>不可物理删除，仅可停用</strong>（停用条件：资金 0 + 持仓 0 + 无在途指令 + 当日已结算）。',
    'CTP 口令等敏感字段：界面<strong>不回显、不落盘</strong>；仅提交，不读取（<code>NFR-SEC-1</code>）。',
    '参数下发（<code>10014</code>）：无回执 / 回执失败时<strong>不得</strong>视为已生效，须告警并提供重试入口。'
  ]
}));

export default HG.pages.stub;
