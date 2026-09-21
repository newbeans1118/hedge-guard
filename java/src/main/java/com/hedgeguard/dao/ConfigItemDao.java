package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ConfigItem;

import java.util.List;

/** {@code TB-CONFIG-2 t_config_item} 数据访问（{@code DATA_MODEL.md} §2.1）。 */
public interface ConfigItemDao extends BaseDao<ConfigItem, Long> {

    /**
     * 按 Key 取配置项（下发与读取热路径）。
     *
     * @param configKey 配置键
     * @return 配置项；不存在返回 {@code null}
     */
    ConfigItem findByKey(String configKey);

    /**
     * 按分类列出配置项（配置界面浏览，{@code FR-CONFIG-11}）。
     *
     * @param category 分类
     * @return 列表
     */
    List<ConfigItem> findByCategory(String category);

    /**
     * 列出全部需下发 C++ 的配置项（启动比对 / 全量重下发）。
     *
     * @return 列表（{@code dispatch_to_ctp = 'Y'}）
     */
    List<ConfigItem> findDispatchable();

    /**
     * 推进生效版本（{@code param_version} 单调递增，用于下发）。
     *
     * @param configKey 配置键
     * @return 影响行数
     */
    int bumpEffectiveVersion(String configKey);
}
