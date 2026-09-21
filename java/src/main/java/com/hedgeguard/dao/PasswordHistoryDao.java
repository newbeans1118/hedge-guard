package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PasswordHistory;

import java.util.List;

/** {@code TB-AUTH-11 t_password_history} 数据访问（{@code DATA_MODEL.md} §2.2）。 */
public interface PasswordHistoryDao extends BaseDao<PasswordHistory, Long> {

    /**
     * 取最近 N 次口令哈希（改口令时"不得与最近 5 次相同"的判据）。
     *
     * @param userId 用户 ID
     * @param limit  条数（{@code HG.AUTH.PASSWORD_HISTORY_SIZE}，默认 5）
     * @return 列表（{@code seq_no} 升序，1 = 最近）
     */
    List<PasswordHistory> findRecent(long userId, int limit);

    /**
     * 序号整体后移（写入新口令前调用，保证 {@code seq_no = 1} 为最新）。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int shiftSeqNo(long userId);

    /**
     * 清理超出保留条数的历史记录（物理删除留痕表之外的冗余行，非业务记录）。
     *
     * @param userId 用户 ID
     * @param keep   保留条数
     * @return 删除行数
     */
    int deleteBeyond(long userId, int keep);
}
