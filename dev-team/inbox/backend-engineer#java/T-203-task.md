---
ticket: T-203
type: task
from: project-manager
to: backend-engineer#java
priority: P2
created: 2026-10-26T17:00+08:00
refs: [docs/TASKS.md#T-203, cr-t025 `R1-002`, java/src/main/java/com/hedgeguard/web/SessionAuthFilter.java:37-48, java/src/main/java/com/hedgeguard/web/ApiRoutes.java, 10006 fail-closed 语义（无越权面——建议级可维护性收口）]
---

## 事由

cr-t025 `R1-002`（用户批准收口）：`SessionAuthFilter` 会话白名单**两形态并存**——`:37-40` `PUBLIC_EXACT` 用 3 对字符串字面量（`"POST /api/v1/auth/login"`、`"GET /api/v1/ping"`、`"HEAD /api/v1/ping"`），而同文件 `:46-48` `FORCE_CHANGE_ALLOWED` 已用 `"POST " + ApiRoutes.full("/auth/password")` 拼装。基路径变更时字面量形态**静默脱钩**（脱钩方向 = 未认证即 10006 **fail-closed 无越权面**，故属建议级可维护性），本单统一为拼装形态。

## 要求

1. **主改**：`PUBLIC_EXACT` 三项改 `ApiRoutes.full` 拼装（`"POST " + ApiRoutes.full("/auth/login")` / `"GET " + ApiRoutes.full("/ping")` / `"HEAD " + ApiRoutes.full("/ping")`）——与 `FORCE_CHANGE_ALLOWED` 同形；运行值逐字不变（改动前后 Set 内容恒等）。
2. **同族穷举（范围界定硬约束）**：同族 = **鉴权白名单域的字面量**（`SessionAuthFilter` 内所有 `"METHOD /api/v1/..."` 形态）——穷举改齐；**handler 的 `PATH_*` 常量族不在本单范围**（其有 `ApiRoutes` 一致性/路由计数测试覆盖，防过度改动——若你实测发现白名单域之外仍有「白名单消费」字面量，列出回报编排者裁，勿自行扩面）。
3. **防脱钩用例**：新增测试锁「`PUBLIC_EXACT` 每项 = `METHOD + ApiRoutes.full(路径)` 派生值」（直接引用拼装表达式或以 `ApiRoutes.byModule()` 派生集做等值断言，形态自择但须**在 ApiRoutes 基路径漂移时必红**）——防「改回字面量」与「白名单与路由表脱钩」两类回归；同族负例 ≥1（构造一个不在白名单的 `METHOD+路径` 断言非成员，证判据非恒真）。
4. **残留 = 0**：改后 `grep -n '"/api/v1/' java/src/main/java/com/hedgeguard/web/SessionAuthFilter.java` = 0 处（javadoc 文字除外）；回报 grep 原文。
5. **门禁**：全量 `mvn -B -o clean test` = **1253/0/0/0 基线**（本单零行为变更、预期 +新用例数；@Test==surefire 口径）；`mvn validate` arch rc0；树锚（`cd <仓库> && find java -type f | sort | xargs md5sum | md5sum`，附命令原文与文件数；勿在跑过 mvn 的树里算）。
6. **边界**：写域 = `SessionAuthFilter.java` + 新/改测试文件；**不碰** ApiRoutes.java（只读引用）/任何 handler/文档（TASKS/BOARD/TEST_RESULT 编排者落盘）。**行为零变更**——运行期白名单值恒等。
7. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核）；完成 `git commit --only <精确路径>`、提交号回报；工单处理记录追加一行。业务口径分歧停手 SendMessage 回报 team-lead。

## 回复要求

回报：提交号、numstat、运行值恒等证据（改前三项字面值 vs 改后拼装值逐字对照）、防脱钩用例名+判据形态、负例说明、残留 grep=0 原文、全量计数、arch rc0、树锚+文件数、边界遵守声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-26 | 编排者 | 用户批准派发（cr-t025 R1-002 收口；实例 be-t203） | OPEN，待 backend-engineer#java 处置 |
