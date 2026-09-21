package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.UserAccount;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-1 t_user} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>纪律：本接口<b>不含</b>任何返回明文口令的方法；口令相关操作只接受"加盐哈希"
 * （{@code NFR-SEC-2}）。哈希计算在服务层（JDK 内置 {@code PBKDF2WithHmacSHA256}），DAO 只做存取。
 */
public interface UserAccountDao extends BaseDao<UserAccount, Long> {

    /**
     * 按登录名取用户（登录热路径）。
     *
     * @param username 登录名
     * @return 用户；不存在返回 {@code null}
     */
    UserAccount findByUsername(String username);

    /**
     * 按自然人唯一键取用户（"一个自然人一个账号"校验）。
     *
     * @param naturalPersonKey 自然人唯一键
     * @return 用户；不存在返回 {@code null}
     */
    UserAccount findByNaturalPersonKey(String naturalPersonKey);

    /**
     * 口令错一次：失败计数 +1（{@code FR-AUTH-17}）。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int incrementFailCount(long userId);

    /**
     * 锁定至指定时刻。
     *
     * @param userId    用户 ID
     * @param lockedUntil 锁定截止
     * @return 影响行数
     */
    int lockUntil(long userId, LocalDateTime lockedUntil);

    /**
     * 解锁：失败计数清零、锁定时间置空（{@code FR-AUTH-17}）。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int unlock(long userId);

    /**
     * 更新口令哈希（<b>只接受哈希串</b>，不接受明文）。
     *
     * @param userId           用户 ID
     * @param passwordHash     PBKDF2 哈希串
     * @param passwordUpdatedTime 修改时间
     * @return 影响行数
     */
    int updatePasswordHash(long userId, String passwordHash, LocalDateTime passwordUpdatedTime);

    /**
     * 记录最后登录时间。
     *
     * @param userId        用户 ID
     * @param lastLoginTime 登录时间
     * @return 影响行数
     */
    int touchLastLogin(long userId, LocalDateTime lastLoginTime);
}
