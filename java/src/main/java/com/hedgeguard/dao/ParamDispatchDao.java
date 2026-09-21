package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ParamDispatch;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code TB-RISK-2 t_param_dispatch} 数据访问（{@code DATA_MODEL.md} §2.8；{@code T-061}）。
 *
 * <p>用途：Java → C++ 的控制指令下发与回执留痕（{@code FR-RISK-9}/{@code FR-RISK-10}/{@code FR-CONFIG-6}、
 * {@code R-CONFIG-3}）。**无回执或失败时该变更不得视为已生效**（业务码 `10014`）。
 *
 * <p><b>⚠️ `param_version` 分配纪律（契约 `D-16`，`#java2` 的 `T-011` 必读）</b>：
 * 本表在 {@code DATA_MODEL.md} 中**没有** {@code UNIQUE(param_version)}（其 3 个索引全为普通索引），
 * 故 §3.5「应用层生成编号 + **`UNIQUE` 兜底** + 冲突重试 ≤3」的兜底前提**在本表不成立**。
 * ⇒ `param_version` 的取号**必须由服务层串行化**（单飞/显式加锁），**不得**依赖唯一约束冲突重试；
 * 并发取号会得到**同号**，进而破坏 `R-CONFIG-3` 的顺序保证与 C++ 侧 `0x0403`（参数版本陈旧）判定。
 * 是否给本表补 `UNIQUE` 属表结构变更，已提请技术负责人裁决（我**不擅自改** `DATA_MODEL.md`）。
 *
 * <p>本接口的方法集与 {@code dao.impl.DmParamDispatchDao} 一一对应；如需扩签名请连带实现一并提交（避免半改）。
 */
public interface ParamDispatchDao extends BaseDao<ParamDispatch, Long> {

    /**
     * 取当前**最大参数版本**（表空时为 {@code 0}）。
     *
     * <p>⚠️ **取号的唯一合法基准**（`CONTRACTS.md` §3.2.2）：新版本 = 本返回值 + 1。
     * 两条硬要求：
     * <ol>
     *   <li>「取值」与「落库」**必须在同一临界区**内完成（本表**无** {@code UNIQUE(param_version)}，
     *       跨临界区的读-写会让并发线程取到**同号**，C++ 侧随后把真实变更判为陈旧（`0x0403`）而**静默丢弃**）；</li>
     *   <li>**不得用行数**（如 {@code count(...)+1}）**代替**：版本号允许有缺口（回滚/重试），
     *       用行数播种会在有缺口时**回退到已用过的号段**。</li>
     * </ol>
     *
     * @return 最大 `param_version`；表空返回 {@code 0}
     */
    long maxParamVersion();

    /**
     * 按版本 + 类型查下发记录（核对全量预加载是否收齐全部批次）。
     *
     * @param paramVersion 参数版本
     * @param dispatchType 下发类型
     * @return 列表（按批次下标、主键升序）
     */
    List<ParamDispatch> findByVersion(long paramVersion, String dispatchType);

    /**
     * 判断某版本 + 类型的**分批下发是否收齐且全部成功**（{@code FR-RISK-9} 验收 3）。
     *
     * @param paramVersion 参数版本
     * @param dispatchType 下发类型
     * @param batchTotal   期望批次总数
     * @return 收齐且全为 {@code SUCCESS} 返回 {@code true}
     */
    boolean isBatchComplete(long paramVersion, String dispatchType, int batchTotal);

    /**
     * 列出"失败 / 未确认"的下发（巡检，{@code R-CONFIG-3}）。
     *
     * @return 列表（按下发时间倒序）
     */
    List<ParamDispatch> findProblems();

    /**
     * 按子账户查下发历史。
     *
     * @param subAccountId 子账户
     * @return 列表（按下发时间倒序）
     */
    List<ParamDispatch> findBySubAccount(int subAccountId);

    /**
     * 写入回执（**只改回执相关列**：`dispatch_status`/`ack_time`/`ack_detail`/`retry_count`）。
     *
     * <p>实现同时推进 `updated_time` 与 `row_version`，但**不覆盖** `updated_by`（保留最后一次人工修改人）。
     *
     * @param id             主键
     * @param dispatchStatus {@code SUCCESS}/{@code FAIL}/{@code UNCONFIRMED}
     * @param ackTime        回执时间（{@code UNCONFIRMED} 时可为 {@code null}）
     * @param ackDetail      回执详情（如 {@code 0x0403}）
     * @param retryCount     重试次数
     * @return 影响行数
     */
    int updateAck(long id, String dispatchStatus, LocalDateTime ackTime, String ackDetail,
                  int retryCount);
}
