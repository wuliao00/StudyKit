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
import com.studykit.data.dao.HabitDao
import com.studykit.data.dao.MistakeDao
import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.dao.WordDao
import com.studykit.data.dao.WordListDao
import com.studykit.data.entity.AppSetting
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Contract
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.Habit
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordList
import com.studykit.data.entity.WordReview

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
    ],
    version = 6,
    exportSchema = false,
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
         * SQL 必须与 Room 依据实体生成的建表语句逐字一致（`exportSchema = false` 时无人替你核对），
         * 否则真机升级时抛 `IllegalStateException: Room cannot verify that the schema matches`，
         * 且 CI 全绿也测不出来 —— 因此本迁移的真机存活验证是计划里的硬步骤。
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
         * 丢不起，而且这条路径 CI 测不出来（`exportSchema = false`，Room 只在真机升级时才校验）。
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

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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
