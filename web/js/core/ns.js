/*
 * HedgeGuard 前端统一命名空间（ARCHITECTURE.md §3.15 建议 `HG.<module>.*`）。
 * 零构建链：原生 ES Module，无打包器 / 无第三方库（NFR-MAINT-1）。
 * 各 core/pages 模块统一 `import { HG } from '../core/ns.js'` 并挂载自身到 HG 下。
 */
export const HG = (window.HG = window.HG || {});
