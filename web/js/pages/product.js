/*
 * 品种管理页面（PRODUCT）—— 开工骨架（M1，FR-PRODUCT-1~14）。
 * 关键约束：品种-现货品类映射是实货敞口匹配衍生品的唯一入口（FR-PRODUCT-10）；
 * 三层限额展示须标明命中的层级（Q-16）。
 */
import { HG } from '../core/ns.js';

HG.router.register('/product', HG.pages.stub.renderStub({
  code: 'PRODUCT', name: '品种管理', fr: 'FR-PRODUCT-1~14', milestone: 'M1',
  apiPaths: ['/product', '/product/{id}', '/product/{id}/contracts', '/product/mapping', '/product/whitelist', '/product/quota', '/product/change-log', '/product/import', '/product/export'],
  notes: [
    '**路径口径**：PRODUCT 在 <code>ApiRoutes.java</code> 中为 <strong>9 个路径 / 13 个 (方法, 路径) 对</strong>（旧口径"6"是 <code>ModuleRoutesConsistencyTest</code> 的覆盖下限清单，非全量）。铺页面以 <code>ApiRoutes.java</code> 逐条路径为准，不用总数。',
    '品种-现货品类映射（<code>FR-PRODUCT-10</code>）：无映射的现货品类不得参与套保计划编制，界面须明确提示先建立映射。',
    '额度三层展示（<code>Q-16</code>）：子账户+品种 / 子账户+合约 / 账户+品种，**须标明命中的层级**（<code>limit_layer</code>）。',
    '变更留痕：编码 / 分类 / 计量单位换算关系被下游引用时不得静默变更。'
  ]
}));

export default HG.pages.stub;
