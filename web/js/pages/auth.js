/*
 * 用户与权限页面（AUTH）—— 开工骨架（M1，FR-AUTH-1~18）。
 * 说明：登录页独立为 login.html + js/pages/login.js；本文件为 AUTH 模块的**管理界面**骨架
 *       （用户 / 角色 / 授权 / 岗位分离 / 子账户数据权限）。
 * 关键约束：交易 / 风控 / 结算三岗两两互斥；企业负责人不得被授予交易岗；跨子账户查看仅限管理层与审计岗。
 */
import { HG } from '../core/ns.js';

HG.router.register('/auth', HG.pages.stub.renderStub({
  code: 'AUTH', name: '用户与权限', fr: 'FR-AUTH-1~18', milestone: 'M1',
  apiPaths: ['/auth/user', '/auth/role', '/auth/position-group', '/auth/authorization', '/auth/sub-account-perm', '/auth/change-log', '/auth/unauthorized-attempt', '/auth/password'],
  notes: [
    '岗位分离为系统级强制（<code>R-AUTH-3</code>）：互斥岗位同时授予时<strong>拒绝保存</strong>；不提供"一键通办"后门。',
    '子账户数据权限默认软隔离、<strong>安全默认 = 无任何子账户可见</strong>；跨子账户查看仅管理层与审计岗可被授予（须双人复核）。',
    '口令相关内容<strong>一律不展示</strong>；超权尝试记录只增不改不删。'
  ]
}));

export default HG.pages.stub;
