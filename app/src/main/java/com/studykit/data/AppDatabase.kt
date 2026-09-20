package com.studykit.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.studykit.BuildConfig
import com.studykit.data.dao.BookDao
import com.studykit.data.dao.HabitDao
import com.studykit.data.dao.MistakeDao
import com.studykit.data.dao.PracticeDao
import com.studykit.data.dao.QuestionDao
import com.studykit.data.dao.WordDao
import com.studykit.data.dao.WordListDao
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.CheckIn
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
    ],
    version = 3,
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
        private val MIGRATION_2_3 = object : Migration(2, 3) {
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

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(SeedCallback)
                    .build()
                    .also { instance = it }
            }

        private val SeedCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // 仅 Debug 构建播种演示数据，Release 首装保持空库
                if (BuildConfig.DEBUG) DemoSeeder.seed(db)
            }
        }
    }
}
