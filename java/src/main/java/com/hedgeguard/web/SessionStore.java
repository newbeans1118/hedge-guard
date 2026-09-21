package com.hedgeguard.web;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储（接口；{@code AUTH} 模块后续以 {@code t_login_session} 的 DM 实现替换）。
 *
 * <p>此处给出接口 + 内存实现，使 T-003 阶段即可完成鉴权链路自检，
 * 且 L3/L4 只依赖接口（{@code ARCHITECTURE.md} §2.3「经接口调用」）。
 */
public interface SessionStore {

    /**
     * 按会话 ID 查会话。
     *
     * @param sessionId 会话 ID
     * @return 会话；不存在或已过期返回 {@code null}
     */
    Session find(String sessionId);

    /**
     * 写入/覆盖会话。
     *
     * @param session 会话
     */
    void save(Session session);

    /**
     * 失效单个会话。
     *
     * @param sessionId 会话 ID
     */
    void invalidate(String sessionId);

    /**
     * 失效某用户的全部会话（停用用户 / 权限变更时立即生效，{@code FR-AUTH-3} 验收 1）。
     *
     * @param userId 用户 ID
     * @return 实际失效数量
     */
    int invalidateByUser(long userId);

    /** 进程内内存实现（单进程、非持久；进程重启即全部失效——首版可接受，见 {@code TB-AUTH-9} 备注）。 */
    final class InMemory implements SessionStore {

        private final Map<String, Session> sessions = new ConcurrentHashMap<>();

        @Override
        public Session find(String sessionId) {
            if (sessionId == null) {
                return null;
            }
            Session s = sessions.get(sessionId);
            if (s == null) {
                return null;
            }
            if (s.expireTime.isBefore(com.hedgeguard.common.Clocks.now())) {
                sessions.remove(sessionId);
                return null;
            }
            return s;
        }

        @Override
        public void save(Session session) {
            sessions.put(session.sessionId, session);
        }

        @Override
        public void invalidate(String sessionId) {
            if (sessionId != null) {
                sessions.remove(sessionId);
            }
        }

        @Override
        public int invalidateByUser(long userId) {
            List<String> hit = new ArrayList<>();
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                if (e.getValue().userId == userId) {
                    hit.add(e.getKey());
                }
            }
            for (String id : hit) {
                sessions.remove(id);
            }
            return hit.size();
        }

        /**
         * 清理全部过期会话。
         *
         * @return 清理数量
         */
        public int purgeExpired() {
            LocalDateTime now = com.hedgeguard.common.Clocks.now();
            List<String> hit = new ArrayList<>();
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                if (e.getValue().expireTime.isBefore(now)) {
                    hit.add(e.getKey());
                }
            }
            for (String id : hit) {
                sessions.remove(id);
            }
            return hit.size();
        }
    }
}
