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
import com.studykit.data.entity.Book
import com.studykit.data.entity.BookRecall
import com.studykit.data.entity.BookReview
import com.studykit.data.entity.CheckIn
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.Habit
import com.studykit.data.entity.Mistake
import com.studykit.data.entity.MistakeReview
import com.studykit.data.entity.PracticeRecord
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordReview

@Database(
    entities = [
        Word::class,
        WordReview::class,
        Question::class,
        PracticeRecord::class,
        Mistake::class,
        MistakeReview::class,
        Habit::class,
        CheckIn::class,
        Book::class,
        Excerpt::class,
        BookReview::class,
        BookRecall::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wordDao(): WordDao
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
         * v2 → v3：学习科学改造。
         * - 单词/错题/书摘获得 FSRS 记忆状态列（stability/difficulty/reps/lapses/last_review_at）
         * - 复习与练习记录获得信心自评列（元认知校准、超纠正排期）
         * - 错题获得错因、跨间隔连续答对与手动钉选列
         * - 习惯获得执行意图线索、周达标天数与断签保护卡
         * - 新增 mistake_reviews 与 book_recalls 两张记录表
         * 全部为带默认值的 ADD COLUMN 或新建表，原地升级不丢数据。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN stability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN difficulty REAL NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE words ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN last_review_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE words ADD COLUMN last_confidence INTEGER NOT NULL DEFAULT -1")

                db.execSQL("ALTER TABLE word_reviews ADD COLUMN rating INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN confidence INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE word_reviews ADD COLUMN stability_after REAL NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE mistakes ADD COLUMN cause TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN correct_streak INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN last_gap_days INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN stability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN difficulty REAL NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN last_review_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE mistakes ADD COLUMN high_confidence_error INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mistake_reviews (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "uuid TEXT NOT NULL, syncStatus INTEGER NOT NULL DEFAULT 0, " +
                        "mistake_id INTEGER NOT NULL, correct INTEGER NOT NULL, " +
                        "confidence INTEGER NOT NULL DEFAULT -1, hint_level INTEGER NOT NULL DEFAULT 0, " +
                        "gap_days INTEGER NOT NULL DEFAULT 0, reviewed_at INTEGER NOT NULL, " +
                        "FOREIGN KEY(mistake_id) REFERENCES mistakes(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mistake_reviews_mistake_id ON mistake_reviews(mistake_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mistake_reviews_reviewed_at ON mistake_reviews(reviewed_at)")

                db.execSQL("ALTER TABLE habits ADD COLUMN cue_time TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habits ADD COLUMN cue_place TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habits ADD COLUMN weekly_target_days INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE habits ADD COLUMN protection_cards INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE habits ADD COLUMN protection_used INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN protection_period TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE check_ins ADD COLUMN is_makeup INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE excerpts ADD COLUMN next_review_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE excerpts ADD COLUMN recall_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE excerpts ADD COLUMN stability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE excerpts ADD COLUMN difficulty REAL NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE excerpts ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE excerpts ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE excerpts ADD COLUMN last_review_at INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE practice_records ADD COLUMN confidence INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE practice_records ADD COLUMN hint_level INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS book_recalls (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "uuid TEXT NOT NULL, syncStatus INTEGER NOT NULL DEFAULT 0, " +
                        "book_id INTEGER NOT NULL, page_no INTEGER, question TEXT NOT NULL, " +
                        "answer TEXT NOT NULL, self_score INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL, next_review_at INTEGER NOT NULL DEFAULT 0, " +
                        "FOREIGN KEY(book_id) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_recalls_book_id ON book_recalls(book_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_book_recalls_next_review_at ON book_recalls(next_review_at)")
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
