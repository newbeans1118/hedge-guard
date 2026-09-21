package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AccountSubject;

import java.util.List;

/** {@code TB-CONFIG-5 t_account_subject} 数据访问（{@code DATA_MODEL.md} §2.1）。 */
public interface AccountSubjectDao extends BaseDao<AccountSubject, Long> {

    /**
     * 按科目编码取科目。
     *
     * @param subjectCode 科目编码
     * @return 科目；不存在返回 {@code null}
     */
    AccountSubject findByCode(String subjectCode);

    /**
     * 取系统预置模板科目（{@code Q-9} 初始化导入用）。
     *
     * @return 列表（{@code is_template_default = 'Y'} 且启用）
     */
    List<AccountSubject> findTemplateDefaults();

    /**
     * 按业务场景取可用科目（生成凭证热路径）。
     *
     * @param bizScene 业务场景
     * @return 列表
     */
    List<AccountSubject> findEnabledByScene(String bizScene);
}
