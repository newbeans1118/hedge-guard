# hedge-guard 项目指南

Java 11 Maven 业务端（`java/`）+ C++11 引擎（`cpp/`，CTP/stub 双轨）+ 原生 ES modules 前端（`web/`，零构建）。全仓文档为简体中文。

## 常用命令

```bash
# Java 门禁（在 java/ 下执行；离线，无需 DM 环境）
mvn -B -o clean test              # 全量单测（标准门禁）
mvn -B -o -Punit clean test       # unit profile（无 DM 模式变体）
mvn validate                      # E1–E8 架构门禁（tools/arch-check/，退出码 0/1/2）

# C++ 双轨
cmake -S cpp -B cpp/build && cmake --build cpp/build    # 真实 CTP（需 sdk/）
cmake -S cpp -B cpp/build_stub                          # stub 轨
ctest --test-dir cpp                                    # 运行（arch_check/ctp_found_parity 为 fixture）

# 前端探针（headless DOM 桩 + 断言）
node tools/probes/<probe>.mjs
```

- Java 编译 `-Xlint:all`，警告必须为零；测试计数以 surefire 为准（勿用 `grep -c @Test`，注释吞注解会虚高）。
- 覆盖率用 `mvn -Pcoverage`（jacoco）。
- DM JDBC 驱动不随仓库分发：达梦未发布到 Maven Central，由部署方提供 jar 后走 `mvn -Pdm compile`（system scope，路径 `java/lib/DmJdbcDriver18.jar`，见 pom.xml profile `dm`）。

## 权威文档链

改任何东西前先确认读写口：

- `PROJECT.md` — 唯一事实源；`docs/ARCHITECTURE.md` — 架构与 E1–E8 门禁；`docs/DATA_MODEL.md` — schema 唯一来源
- `docs/TASKS.md` §1 合计行 — 任务数/人时唯一权威；`docs/BOARD.md` — 状态流转（TODO→IN_PROGRESS→DONE）
- `docs/REQUIREMENTS/` — 14 模块 FR；`docs/TEST_PLAN/` — 用例；`java/CONTRACTS.md` — 接口冻结（HGC-1.6）
- `docs/DECISIONS/`、`docs/BUGS/`、`docs/DEPLOY/`

纪律：
- 引用文档版本号/md5/行号前必须实测（会漂移），引用时附"时点 + 以实时为准"。
- 勘误不回改历史：TEST_RESULT/任务台账只追加或显式登记，原文保留。
- 派发任务时**优先使用 virtual team 插件的 agent**（manager/architect/backend/frontend/reviewer/tester/test-leader）；完整"需求→架构→开发→测试→审查→交付"流程用 `lifecycle` 技能编排。角色边界见 `dev-team/agents/*.md`。

## 必须知道的约定

- 新端点必须登记 `web/ApiRoutes.java`，并同步 `web/js/core/routes.js`（前端只读镜像）。
- wire 字段名是 **camelCase**（以已实现 handler 为准），≠ DATA_MODEL 的 snake_case 列名。
- `PageQuery` 缺省排序恒 **ASC**；要降序须显式 `"field,desc"`。
- C++ 禁 SQL 拼接与 DB 直连（arch-check 会拦）；SQL 只在 Java dao 层。

## 测试基建陷阱（InMemoryDmEngine）

测试跑在 `InMemoryDmEngine`（`java/src/test/.../dao/testsupport/`，内存语义求值假 JDBC，无 DM8 实例）。已知陷阱：

1. `DELETE ... WHERE` 带绑定参数 affected=0 静默不删；构造"行不生效"用例改用 `UPDATE` 改行。
2. DDL `DEFAULT` 不生效——insert 显式给全列；显式 NULL 撞 NOT NULL。
3. `row_version` 须 ≥1；时间戳传 `LocalDateTime`（`Timestamp` 会 ClassCastException）。
4. `engine.table(name)` 表名区分大小写。
5. 引擎强制表级 UNIQUE（uk 冲突抛 SQLState=null）。
6. 引擎 rollback 是 no-op——事务类回归测试先核回滚语义，用 `TransactionAwareInMemory` 包装。

## 红线

- 勿新增 Maven 依赖：compile 白名单仅 JDK11 / protobuf-java 3.21.12 / gson 2.10.1（DM JDBC 仅经 `dm` profile 由部署方提供）。
- 勿把口令/密钥写入代码、文档、测试（零落盘）。
- 勿改写 `docs/TEST_RESULT.md` 既有内容（只追加）。
- 勿为措辞统一而改定版制品——先算锚点连锁代价；措辞冲突优先语义消解。
- 改共享文档（TASKS/BOARD/TEST_RESULT）单写者、逐条留痕、可回退。
