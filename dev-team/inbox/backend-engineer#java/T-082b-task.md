---
ticket: T-082b
type: task
from: project-manager
to: backend-engineer#java
priority: P0
created: 2026-10-26T22:00+08:00
refs: [docs/TASKS.md#T-082, INV-081082 盘点报告 /root/inv-081082.md §2.2（md5 92a0c9ed…/121）, NFR-SEC-1~8, te-verify-t082 丢失线记忆（仅供判据形态参考）, T-075 旁路=0 四合一方学先例（M3 合规审查）, T-199 §12 权限清单（在树承载）]
---

## 事由（T-082 重做单·缺口=验证战役+词形判据+证据章，承载代码近零增量）

T-082 完成断言指向已丢失 git 线（账实背离 2026-10-26 实锤·用户裁定 A 重做）。INV-081082 盘点结论：**承载面厚实（1 在树/5 异形在树）**——`Config.rejectSecrets` 装载守卫、`HG_DB_PASSWORD`/`HG_HGCP_AUTH_TOKEN` 环境变量注入、`SensitiveLog`+CFG-19、`requireLoopbackBind`、`TcpHgcpTransport` 鉴权、CFG-22、PBKDF2、T-199 §12 权限清单均**在树**。**缺口仅 4 项**（报告 §2.2）：R-1 四合一审计战役落痕 / R-4 哑口令词形清单判据 / R-5 词形包含关系逐字符重测 / TEST_RESULT §T082 证据章。**重做 = 验证战役 + 判据补齐，预估 8~10h（原 20h）**。

## 要求

1. **R-1 四合一审计战役**（复用 T-075 先例方法学，M3 合规审查 `M3AuditSchemaTest`/审查报告形态同型）：对**敏感字段零落盘**做旁路=0 四合一实证——①全库 grep（口令/密钥/令牌词形清单逐词）②日志面 grep（`log.*password|secret|token` 形态+SensitiveLog 覆盖面实测）③配置面审计（`data-init.sql`/`hg.properties.template`/CFG 种子零明文凭证·逐文件列证）④重启重输路径核实（环境变量注入后不持久化的机制取证）。产出战役清单（逐项命令+结果），**在树承载面复核引用、不重复施工**（盘点报告 §2.1#1 证据可直接引用）。
2. **R-4 哑口令词形清单判据**：新建测试类（如 `DummySecretLexiconTest`）——词形清单（`DUMMY_BANK_PASSWORD`、`DUMMY_PASSWORD`、`dummy_secret` 等你自定枚举 ≥5 形，**判据词须冻结域字面量**）在 `java/src` 全仓 **0 命中**断言 + 负例 ≥1（临时注入一命中形态→红→复位，证判据有牙）；cpp 侧 `fake_gateway.h:141` 夹具例外（`5e1bb87` T-033 线·早于本判据）以**显式豁免清单**载入测试注记（防未来 Java 侧误引）。
3. **R-5 词形包含关系逐字符重测**：`DUMMY_PASSWORD` 是否 `DUMMY_BANK_PASSWORD` 子串的逐字符断言（丢失线 te-verify O-1 判例：非子串——以测试钉死该关系，防清单词形演进时静默互吞）；与 R-4 清单同类或同文件。
4. **证据章 §T082**：`docs/TEST_RESULT.md` **纯追加** §T082 段——门禁基线（开工时实测）+ R-1 四合一战役全文 + R-4/R-5 判据与变异 + 在树承载面复核引用清单（盘点报告 §2.1/§2.3）+ 丢失线背离与本单重做承接链一句话；**只追加不回改既有内容**；追加前测尾部 md5 记录。
5. **门禁**：全量 `mvn -B -o clean test` = 开工基线+新增（基线 1258，@Test==surefire 口径）；`mvn validate` arch rc0；树锚（`cd <仓库> && find java -type f | sort | xargs md5sum | md5sum`，附命令原文与文件数；勿在跑过 mvn 的树里算）。
6. **边界**：写域 = 新测试类（R-4/R-5）+ `docs/TEST_RESULT.md`（仅 §T082 纯追加）+ 工单处理记录；**不碰**任何 main 源（承载面零增量——若你实测发现确需代码增量，停手 SendMessage 回报编排者裁，勿自行扩面）；TASKS/BOARD 编排者落盘。
7. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核）；`git commit --only <精确路径>`、提交号回报；工单处理记录追加一行。业务口径分歧停手回报。

## 回复要求

回报：提交号、numstat、R-1 战役四合一结果清单、R-4/R-5 判据与变异证据（击杀器+复位 md5）、§T082 追加前后锚（尾 md5/行数）、全量计数、arch rc0、树锚+文件数、边界声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-26 | 编排者 | 用户裁定 A 案重做承接派发（INV-081082 盘点 §2.2 缺口 4 项·判据直采报告；实例 be-t082b） | OPEN，待 backend-engineer#java 处置 |
