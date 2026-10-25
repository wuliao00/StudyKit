package com.studykit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.studykit.BuildConfig
import com.studykit.data.dao.AppSettingDao
import com.studykit.data.dao.BookDao
import com.studykit.data.dao.ContractDao
import com.studykit.data.dao.DesignPlanDao
import com.studykit.data.dao.HabitDao
import com.studykit.data.dao.MistakeDao
import com.studykit.data.dao.MockSessionDao
import com.studykit.data.dao.ParamSnapshotDao
import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.dao.ReviewLogDao
import com.studykit.data.dao.WordDao
import com.studykit.data.dao.WordListDao
import com.studykit.data.entity.AppSetting
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.ChapterTest
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Contract
import com.studykit.data.entity.DesignPlan
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.Habit
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MistakeRedo
import com.studykit.data.entity.MockSession
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import com.studykit.data.entity.ReviewLog
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordList
import com.studykit.data.entity.WordReview
import com.studykit.optimize.ParamSnapshot

@Database(
    entities = [
        Word::class,
        WordReview::class,
        Question::class,
        PracticeRecord::class,
        Mistake::class,
        Habit::class,
        CheckIn::class,
        Book::class,
        Excerpt::class,
        BookReview::class,
        WordList::class,
        AppSetting::class,
        Contract::class,
        MistakeRedo::class,
        ChapterTest::class,
        ReviewLog::class,
        ParamSnapshot::class,
        MockSession::class,
        DesignPlan::class,
    ],
    // v12：学习设计——新增 design_plans（8 种模板共用，纯加表 + updated_at 索引）
    // v11：v3.3——阶段列 exam_stage + (exam_stage, question_type, subject) 复合索引
    // v10：题型扩展——questions 加 question_type / answer_indices_json / answer_text_json 三列
    // v9：v3.0-B——套卷会话持久化（mock_sessions）+ 错题遮挡两列（kind/extra）
    // v8：v2.9 计划 Task 4+5——新增 append-only 复习事件流 review_log（Task 4）与参数快照表 param_snapshots（Task 5），均纯加表，不动既有表
    // v7：v2.7 spec §3.2——7 实体加列 + mistake_redos/chapter_tests 新表（迁移与接线见 A7）
    version = 12,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordDao(): WordDao
    abstract fun wordListDao(): WordListDao
    abstract fun questionDao(): QuestionDao
    abstract fun practiceDao(): PracticeDao
    abstract fun mistakeDao(): MistakeDao
    abstract fun habitDao(): HabitDao
    abstract fun bookDao(): BookDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun contractDao(): ContractDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun paramSnapshotDao(): ParamSnapshotDao
    abstract fun mockSessionDao(): MockSessionDao
    abstract fun designPlanDao(): DesignPlanDao

    companion object {
        private const val DB_NAME = "studykit.db"

        /**
         * v1 → v2：习惯支持数量型（target_count/unit/default_text），
         * 打卡支持备注与数量（note/amount）。均为带默认值的 ADD COLUMN，原地升级不丢数据。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN target_count REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habits ADD COLUMN default_text TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE check_ins ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE check_ins ADD COLUMN amount REAL NOT NULL DEFAULT 1")
            }
        }

        /**
         * v2 → v3：新增在线词库来源表，并给 `words` 挂上可空的来源列。
         *
         * SQL 必须与 Room 依据实体生成的建表语句逐字一致，否则真机升级时抛
         * `IllegalStateException: Room cannot verify that the schema matches`。
         * 注意覆盖面：MigrationTestHelper 那张网（A-T7 起）目前只在 6→7 上跑通——1..5 没有
         * 旧快照可造，本条 2→3 仍只能靠真机走查兜住。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `word_lists` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`source_id` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`word_num` INTEGER NOT NULL, " +
                        "`imported_count` INTEGER NOT NULL, " +
                        "`imported_at` INTEGER NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_word_lists_source_id` " +
                        "ON `word_lists` (`source_id`)",
                )
                // 可空列没有 DEFAULT；Room 校验列类型时只看类型与可空性
                db.execSQL("ALTER TABLE `words` ADD COLUMN `source_list_id` INTEGER")
            }
        }

        /**
         * v3 → v4：新增「用户本地设置」键值表。纯加表，不动任何既有列，
         * 因此装有 v2.1 数据的机器升级后词库、错题、打卡一条都不会少
         * （这条升级路径的真机存活验证在计划 T14 里，CI 证明不了）。
         *
         * SQL 同样必须与 Room 依实体生成的建表语句逐字一致，理由见 [MIGRATION_2_3]。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_settings` (" +
                        "`key` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`)" +
                        ")",
                )
            }
        }

        /**
         * v4 → v5：把复习从"写死的 1 天 / 3 天阶梯"换成半衰期模型所需的状态列。
         *
         * 全部是**带默认值的 ADD COLUMN**，不删不改任何既有列 —— 用户已有一千多条词数据，
         * 丢不起。写它的时候这条路径在 CI 里测不出来（那时 `exportSchema` 还是 false，
         * Room 只在真机升级时才校验）；如今 `exportSchema` 已开、A-T7 起又有 [Migration6To7Test]
         * 那张 MigrationTestHelper 的网 —— 但它兜的是 6→7，4→5 仍旧只经过真机验证。
         *
         * 老数据不是"重置"而是"折算"：`status` 里其实存着历史信息
         * （MASTERED 说明它至少被答对过两轮），所以按档位给一个起步半衰期，
         * 比一律回到 0.5 天少一次"刚升级就被排到 10 分钟后"的惊吓。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `words` ADD COLUMN `half_life_days` REAL NOT NULL DEFAULT 0.5",
                )
                db.execSQL(
                    "ALTER TABLE `words` ADD COLUMN `difficulty` REAL NOT NULL DEFAULT 1.0",
                )
                db.execSQL("ALTER TABLE `words` ADD COLUMN `last_review_at` INTEGER")
                db.execSQL(
                    "ALTER TABLE `words` ADD COLUMN `total_reviews` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `words` ADD COLUMN `lapses` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE `words` SET `half_life_days` = CASE `status` " +
                        "WHEN 'MASTERED' THEN 30.0 WHEN 'LEARNING' THEN 3.0 ELSE 0.5 END",
                )
                /**
                 * **防洪**：到期判据从「未掌握且到期」改成「有排期且到期」之后，
                 * 老库里那些"答对两次就被永久冻结"的 MASTERED 词会全部同时到期 ——
                 * 用户升级后打开 app 会看到几十上百个待复习，直接劝退。
                 * 这里给它们统一排到 4 天后（h=30 天、target 0.9 时模型自己会算出 ≈4.5 天），
                 * 让它们错峰回流。只动 MASTERED：LEARNING 里过期的那些本来就该今天见。
                 */
                val now = System.currentTimeMillis()
                db.execSQL(
                    "UPDATE `words` SET `next_review_at` = " + (now + 4 * 24 * 3600_000L) +
                        " WHERE `status` = 'MASTERED' AND `next_review_at` > 0",
                )

                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `gap_days` REAL")
                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `p_at_review` REAL")
                db.execSQL(
                    "ALTER TABLE `word_reviews` ADD COLUMN `grade` INTEGER NOT NULL DEFAULT -1",
                )
                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `h_before` REAL")
                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `h_after` REAL")
                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `reaction_ms` INTEGER")
                // 旧行只有布尔：认识→0、忘记→2。"模糊"这一档历史上不存在，不要瞎猜成 1
                db.execSQL(
                    "UPDATE `word_reviews` SET `grade` = CASE WHEN `correct` = 1 THEN 0 ELSE 2 END",
                )
            }
        }

        /**
         * v5 → v6（v2.4，一次做完五批的全部 schema，不让用户为真机升级多冒一次险）：
         * - `check_ins.is_makeup`：补打卡标记（Lally 2010：漏一天不毁习惯，补打卡不断签但单独标识）
         * - `habits.category` / `habits.sort_order`：时段分类与手动排序
         *   （habits.archived 已存在，不用加）
         * - 新表 `contracts`：自我契约（批次五的存储，先建好逻辑后上）
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `check_ins` ADD COLUMN `is_makeup` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `habits` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'ANY'",
                )
                db.execSQL(
                    "ALTER TABLE `habits` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contracts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`syncStatus` INTEGER NOT NULL DEFAULT 0, " +
                        "`habit_id` INTEGER NOT NULL, " +
                        "`deadline_epoch_day` INTEGER NOT NULL, " +
                        "`goal_count` INTEGER NOT NULL, " +
                        "`promise_text` TEXT NOT NULL DEFAULT '', " +
                        "`consequence_text` TEXT NOT NULL DEFAULT '', " +
                        "`signed_by` TEXT NOT NULL DEFAULT '', " +
                        "`signed_at_epoch_day` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL DEFAULT 'ACTIVE', " +
                        "`settled_at` INTEGER" +
                        ")",
                )
            }
        }

        /**
         * v6 → v7（v2.7，spec §3）：双内核与 v2.7 全部新列/新表，一次做完。
         *
         * 只增不删；建表/列定义**逐字**取自 `app/schemas/com.studykit.data.AppDatabase/7.json`
         * （exportSchema=true 的产物，只把 `${TABLE_NAME}` 换成真实表名），
         * 由 [Migration6To7Test] 在 CI 里校验（A-T7 起，schema 漂移不再是真机撞运气）。
         *
         * 为什么不用 autoMigration：自动迁移只会给新列填默认值，等于把每个人已有的复习历史
         * 当成"从没背过"。把 `h` 折算成 `S` 是语义决定（spec §2.4），只能手写。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) words：4 新列（列文本 = 7.json `words` createSql 相对 6.json 的尾段增量）
                db.execSQL("ALTER TABLE `words` ADD COLUMN `fsrs_stability` REAL")
                db.execSQL("ALTER TABLE `words` ADD COLUMN `fsrs_difficulty` REAL")
                db.execSQL("ALTER TABLE `words` ADD COLUMN `fsrs_state` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `words` ADD COLUMN `kernel` TEXT NOT NULL DEFAULT 'FSRS'")
                //    回填（spec §2.4）：**有历史才折算**。`last_review_at` 为 null 的纯新词刻意留 null，
                //    让 FSRS 首评走原生 S0 支（`FsrsKernel.review` 的 firstTime 判据就是 stability==null），
                //    而不是把"没复习过"冒充成"复习过且稳定度=S0"——后者会让首周复习量翻倍（A-T3 教训）。
                //    S = h / 12.7895：幂律族（decay=−0.5）下 h/S = 243/19，即 `FSRS_HALF_OVER_S`；
                //    这里写**字面量**而不是引用那个常量，是因为迁移 SQL 一旦发布就得冻结，
                //    不能跟着以后的标定改动漂移。⚠ "6.57×"只属于指数族
                //    （reverse-ref\竞品\02-Anki-FSRS\evidence\引用复查台账.md §2），不许再流通。
                //    脏 h（≤0）：不抛、也不产出 NULL/负 S —— 退到 NEW 档半衰期 0.5 天折算，
                //    与 `FsrsKernel.seedFromHalfLife` 的脏值降级同口径（A-T7 测试第 4 段钉住）。
                db.execSQL(
                    "UPDATE `words` SET `fsrs_stability` = CASE WHEN `half_life_days` > 0 " +
                        "THEN `half_life_days` / 12.789473684210526 " +
                        "ELSE 0.5 / 12.789473684210526 END, " +
                        "`fsrs_difficulty` = 5.0 + (`difficulty` - 1.0) * 0.5, `fsrs_state` = 2 " +
                        "WHERE `last_review_at` IS NOT NULL",
                )

                // 2) word_reviews：信心与 FSRS 评分档位。两列都可空、无 DEFAULT——
                //    空就是"这条旧记录没记过信心"，不能拿 0 冒充"用户答『肯定不记得』"（spec §2.3 的超纠正会被假数据触发）
                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `confidence` INTEGER")
                db.execSQL("ALTER TABLE `word_reviews` ADD COLUMN `fsrs_rating` INTEGER")

                // 3) practice_records：题库侧信心，同样可空（与 word_reviews 同构，spec §3.2）
                db.execSQL("ALTER TABLE `practice_records` ADD COLUMN `confidence` INTEGER")

                // 4) questions：考点标签（变式与按考点聚合，G7）；空串=未标注，故给 DEFAULT ''
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `concept_tag` TEXT NOT NULL DEFAULT ''")

                // 5) mistakes：FSRS 三件套 + 两个计数 + 优先级。**不做回填**，理由写在这儿：
                //    v6 的错题只有"用户手动定的 `review_at`"，没有任何逐次对错轨迹可折算成 S/D；
                //    而 `fsrs_state` DEFAULT 1（LEARNING）+ `fsrs_stability` NULL 已经自洽——
                //    内核首评时 stability==null 就是"从 S0 起步"的正确语义。
                //    `review_at` 原样留着当"手动覆盖"（spec §3.2 改了它的语义），
                //    把它复制进 fsrs_* 等于把用户手定的时间冒充成算法排期，反而更难纠正。
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `fsrs_stability` REAL")
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `fsrs_difficulty` REAL")
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `fsrs_state` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `correct_streak` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `review_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")

                // 6) excerpts：书摘入复习队列（spec §7.2）。`next_review_at`=0 即"未启用"，
                //    所以给 DEFAULT 0 是安全的；`stability` 可空，同 words 的"没评过就是没评过"
                db.execSQL("ALTER TABLE `excerpts` ADD COLUMN `next_review_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `excerpts` ADD COLUMN `review_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `excerpts` ADD COLUMN `stability` REAL")

                // 7) habits：执行意图整句（Gollwitzer & Sheeran 2006）；when 维度复用既有 `category`
                db.execSQL("ALTER TABLE `habits` ADD COLUMN `if_then` TEXT NOT NULL DEFAULT ''")

                // 8) 新表两张 + 各自索引（下面 4 条语句逐字来自 7.json，含 FOREIGN KEY 子句——
                //    漏掉它 Room 校验会报"表结构不符"，而差异只在不可见的那个尾巴上）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mistake_redos` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`syncStatus` INTEGER NOT NULL DEFAULT 0, " +
                        "`mistake_id` INTEGER NOT NULL, " +
                        "`redone_at` INTEGER NOT NULL, " +
                        "`correct` INTEGER NOT NULL, " +
                        "`hints_used` INTEGER NOT NULL DEFAULT 0, " +
                        "`had_note_rebuild` INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(`mistake_id`) REFERENCES `mistakes`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mistake_redos_mistake_id` " +
                        "ON `mistake_redos` (`mistake_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_tests` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`syncStatus` INTEGER NOT NULL DEFAULT 0, " +
                        "`book_id` INTEGER NOT NULL, " +
                        "`chapter_label` TEXT NOT NULL, " +
                        "`question` TEXT NOT NULL, " +
                        "`expected_answer` TEXT NOT NULL, " +
                        "`passed` INTEGER NOT NULL, " +
                        "`tested_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`book_id`) REFERENCES `books`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_tests_book_id` " +
                        "ON `chapter_tests` (`book_id`)",
                )
            }
        }

        /**
         * v7 → v8（v2.9 计划 Task 4）：端侧 FSRS 优化器的**事件流**表 `review_log`（append-only）。
         *
         * **纯加表，不动任何既有表** —— 老装机的词、题、错题、书摘一条不碰，只多出一张
         * 供优化器只读回放的历史表。建表/建索引语句**逐字**取自
         * `app/schemas/com.studykit.data.AppDatabase/8.json`（exportSchema=true 产物，只把
         * `${TABLE_NAME}` 换成真实表名），由 [Migration7To8Test] 在 CI 里逐字校验（沿用 A-T7 那张网，
         * schema 漂移不再是真机撞运气）。
         *
         * Task 5 把**参数快照表 `param_snapshots`** 一并并进同一份 v8（不再升 v9）：同属"纯加表、
         * 不动既有表"，与 review_log 共用一次迁移；建表/建索引同样逐字取自 8.json。
         *
         * 为什么单开一张表而不在 `word_reviews` 上就地扩展：优化器要跨 word/practice/mistake/excerpt
         * 四类卡统一按 `(card_kind, card_id, ts)` 分组扫描（Task 7），且要能存"卡删了历史还在"的
         * append-only 语义——四类各自的状态表既异构又会被清，兜不住这份完整轨迹。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`card_kind` TEXT NOT NULL, " +
                        "`card_id` INTEGER NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`rating` INTEGER NOT NULL, " +
                        "`elapsed_days` REAL NOT NULL, " +
                        "`scheduled_days` REAL NOT NULL, " +
                        "`state` INTEGER NOT NULL, " +
                        "`duration_ms` INTEGER NOT NULL, " +
                        "`origin` TEXT NOT NULL, " +
                        "`ocr_conf` REAL, " +
                        "`self_conf` INTEGER, " +
                        "`session_id` TEXT NOT NULL, " +
                        "`is_makeup` INTEGER NOT NULL, " +
                        "`log_schema_ver` INTEGER NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_log_card_kind_card_id_ts` " +
                        "ON `review_log` (`card_kind`, `card_id`, `ts`)",
                )
                // Task 5：参数快照表 param_snapshots（同样纯加表）。建表/建索引逐字取自 8.json。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `param_snapshots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`params` TEXT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`n_terms` INTEGER NOT NULL, " +
                        "`logloss` REAL NOT NULL, " +
                        "`engine_ver` TEXT NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_param_snapshots_ts` " +
                        "ON `param_snapshots` (`ts`)",
                )
            }
        }

        /**
         * v8 → v9（v3.0-B 计划 Task 8）：新表 `mock_sessions`（套卷持久化）+ `mistakes` 两列（遮挡卡）。
         *
         * 建表/建索引语句**逐字**取自 `app/schemas/com.studykit.data.AppDatabase/9.json`
         * （exportSchema=true 产物，只把 `${TABLE_NAME}` 换成真实表名），由 [Migration8To9Test]
         * 在 CI 里逐字校验（沿用 A-T7 那张网，schema 漂移不再是真机撞运气）。
         *
         * `mistakes` 两列都是 `NOT NULL DEFAULT`（kind=0、extra='{}'），旧行自动落到
         * 「普通错题、无遮挡」—— 这是计划里的 ⛔ 硬闸：迁移不许改变任何既有列的语义，
         * 也不许丢行（纯加表 + 带默认值的加列，一句 UPDATE/DELETE 都没有）。
         * `deadline_ts`/`result` 可空且无 DEFAULT：NULL 就是"不限时"/"未交卷"，不能拿 0 冒充。
         *
         * **v9 就地改过一次（评审 F1）**：`mock_sessions` 的建表语句里多了 `paused_at`（可空、无
         * DEFAULT，同一口径）。v9 落地那会儿版本号没跟着动：`698a127` 之前最后一个改
         * `app/build.gradle.kts` 版本号的提交是 `dd55e71`（`chore(v2.9): 发版 2.9.0/19 …`），而那个提交上
         * Room 还是 `version = 8`、`app/schemas` 里只有 6/7/8.json（两句都可 `git show` 复验）。
         * v3.0-C 这条 lane 也没把任何东西推出去过：本 shell 拿不到凭据，推送与 `git fetch` 归用户在正常
         * 终端做，远端此刻的真实状态不由这里断言 —— 这句只报本地复验得到的部分。
         * 折进这一版而不是另起 v10：加一轮 `ALTER TABLE` 迁移只会给"从来没有过旧行"的表造一段
         * 没人跑得过的代码路径，而 8→9 那条 CREATE TABLE 本来就要跟着改。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mock_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`syncStatus` INTEGER NOT NULL DEFAULT 0, " +
                        "`subject` TEXT NOT NULL, " +
                        "`exam_name` TEXT NOT NULL, " +
                        "`question_ids` TEXT NOT NULL, " +
                        "`answers` TEXT NOT NULL, " +
                        "`cursor` INTEGER NOT NULL, " +
                        "`start_ts` INTEGER NOT NULL, " +
                        "`deadline_ts` INTEGER, " +
                        "`paused_at` INTEGER, " +
                        "`limit_min` INTEGER NOT NULL, " +
                        "`state` INTEGER NOT NULL, " +
                        "`result` TEXT, " +
                        "`created_at` INTEGER NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mock_sessions_created_at` " +
                        "ON `mock_sessions` (`created_at`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mock_sessions_state` " +
                        "ON `mock_sessions` (`state`)",
                )
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `kind` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `mistakes` ADD COLUMN `extra` TEXT NOT NULL DEFAULT '{}'")
            }
        }

        /**
         * v9 → v10：题库从「只有单选」扩到单选/多选/填空。三条**带默认值的 ADD COLUMN**，
         * 不删不改任何既有列，旧题自动落成 `single` + 空 JSON 数组 —— 旧 UI 只读 `answerIndex`，
         * 因此升级后行为与升级前完全一致。
         *
         * 默认值必须与 `10.json` 的 `defaultValue` 逐字一致（`'single'`、`'[]'`），
         * 否则 [Migration9To10Test] 的 `validate=true` 会当场红。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `question_type` TEXT NOT NULL DEFAULT 'single'")
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `answer_indices_json` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `answer_text_json` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * v10 → v11：题库阶段（空串=未标注，不猜阶段）+ 阶段/题型/学科复合索引。
         * 只加列与索引，不改既有行；`Migration10To11Test` 用 10.json 旧库逐字校验。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `exam_stage` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_questions_exam_stage_question_type_subject` " +
                        "ON `questions` (`exam_stage`, `question_type`, `subject`)",
                )
            }
        }

        /**
         * v11 → v12：新增「学习设计」方案表 `design_plans` 与其 `updated_at` 索引。
         *
         * **纯加表，不动任何既有表** —— 词、题、错题、习惯、套卷一条不碰，升级只多出一张
         * 供 8 种模板共用的方案表。
         *
         * 建表/建索引语句**逐字**取自 `app/schemas/com.studykit.data.AppDatabase/12.json`
         * （exportSchema=true 的产物，只把 `${TABLE_NAME}` 换成真实表名），由
         * `Migration11To12Test` 在 CI 里逐字校验 —— 沿用 A-T7 起那张 MigrationTestHelper
         * 的网，schema 漂移不再靠真机撞运气。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `design_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`fields_json` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_design_plans_updated_at` " +
                        "ON `design_plans` (`updated_at`)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                        MIGRATION_10_11, MIGRATION_11_12,
                    )
                    .addCallback(SeedCallback)
                    .build()
                    .also { instance = it }
            }

        private val SeedCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // 演示数据**默认不播种**（开关见 `app/build.gradle.kts` 的 DEMO_SEED）。
                // 这里原来写的是 `BuildConfig.DEBUG`，而分发的正是 assembleDebug 产物 ——
                // 那个条件对真实使用者恒为 true，于是"仅 Debug 播种"实际等于每次都播种：
                // 全新安装会被灌进 20 个演示单词、3 个演示习惯 + 打卡记录、题目、书、错题。
                // 首启应当是干净的空库，用户看到的是自己的空白而不是别人的假数据。
                if (BuildConfig.DEMO_SEED) DemoSeeder.seed(db)
            }
        }
    }
}
