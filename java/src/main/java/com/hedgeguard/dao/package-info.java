/**
 * L2a 数据访问层（{@code ARCHITECTURE.md} §3.9，Java 模块 J3）。
 *
 * <p><b>本包的独有地位</b>：它是<b>唯一允许出现 SQL 的地方</b>（附录 B 红线 2）；
 * CI 脚本 {@code E5}（{@code tools/arch-check/scan_java_sql.sh}）扫描 {@code java/**} 时
 * 排除 {@code **&#47;dao/**}，其余位置出现 SQL 关键字即构建失败。
 *
 * <p>包内结构（{@code §3.9}）：
 * <ul>
 *   <li>{@code <Entity>Dao}（本包）—— L3 依赖的接口；</li>
 *   <li>{@code impl/Dm<Entity>Dao} —— 达梦实现，SQL 只在这里；</li>
 *   <li>{@code tx/TxTemplate} —— 事务模板（连接生命周期、隔离级别、线程内事务传递）；</li>
 *   <li>{@code pool/DataSourcePool} —— 自实现极简连接池（{@code §7.2.2}：不引 HikariCP）；</li>
 *   <li>{@code dialect/DmDialect} —— <b>DM 方言唯一集中点</b>，DM 特有语法均带
 *       {@code // DM-SPECIFIC:} 注释（{@code NFR-COMPAT-5}）。</li>
 * </ul>
 *
 * <p>两条纪律：
 * <ol>
 *   <li><b>DAO 不得写业务规则</b>，也不得跨模块直接改他模块的表（跨模块写只允许
 *       {@code SettleService}/{@code AcctService}/{@code FundService} 三处已声明共享台账的边界，
 *       {@code R-SETTLE-8}/{@code R-FUND-8}）；</li>
 *   <li><b>SQL 一律使用占位符</b>，禁止拼接取值；来自外部的标识符（排序字段等）
 *       须经 {@code AbstractDmDao.requireIdentifier} 校验。</li>
 * </ol>
 */
package com.hedgeguard.dao;
