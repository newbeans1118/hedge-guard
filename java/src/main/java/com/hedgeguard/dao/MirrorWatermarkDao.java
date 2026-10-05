package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.MirrorWatermark;

/**
 * {@code TB-RISK-3 t_mirror_watermark} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}）。
 *
 * <p>镜像状态水位（Java 侧已消费事件序号）。本接口方法集与 {@code dao.impl.DmMirrorWatermarkDao} 一一对应。
 */
public interface MirrorWatermarkDao extends BaseDao<MirrorWatermark, Long> {

    /**
     * 按 组件 + 流类型 取唯一一行（{@code uk_mirror_watermark}）。
     *
     * @param componentId 组件标识
     * @param streamType  流类型（{@code EVENT} / {@code QUOTE}）
     * @return 行；无则 {@code null}
     */
    MirrorWatermark findByComponentAndStream(String componentId, String streamType);
}
