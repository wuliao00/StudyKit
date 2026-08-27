package com.studykit.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 演示数据播种器。在数据库首次创建（onCreate）时调用，保证可重复执行（幂等）。
 */
object DemoSeeder {

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun seed(db: SupportSQLiteDatabase) {
        // 幂等守卫：若已有单词数据则视为已播种，直接跳过
        db.query("SELECT COUNT(*) FROM words").use { c ->
            if (c.moveToFirst() && c.getLong(0) > 0) return
        }

        val now = System.currentTimeMillis()
        db.execSQL("PRAGMA foreign_keys=ON")
        db.beginTransaction()
        try {
            seedWords(db, now)
            seedQuestions(db)
            seedHabitsAndCheckIns(db, now)
            seedBooks(db, now)
            seedMistakes(db, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun seedWords(db: SupportSQLiteDatabase, now: Long) {
        // Triple: word, meaning, example
        val words = listOf(
            Triple("abandon", "放弃；抛弃", "He had to abandon the plan."),
            Triple("benefit", "利益；好处", "Exercise has many health benefits."),
            Triple("curious", "好奇的", "The child was curious about everything."),
            Triple("dedicate", "奉献；致力于", "She dedicated her life to science."),
            Triple("efficient", "高效的", "This method is more efficient."),
            Triple("flourish", "繁荣；茁壮成长", "Plants flourish in spring."),
            Triple("generous", "慷慨的", "He made a generous donation."),
            Triple("hesitate", "犹豫", "Do not hesitate to ask questions."),
            Triple("inspire", "激励；启发", "Her story inspired many people."),
            Triple("jovial", "愉快的", "He was in a jovial mood."),
            Triple("keen", "敏锐的；渴望的", "She has a keen interest in art."),
            Triple("luminous", "发光的", "The stars were luminous."),
            Triple("meticulous", "一丝不苟的", "He kept meticulous records."),
            Triple("nurture", "培养；养育", "Parents nurture their children."),
            Triple("optimistic", "乐观的", "Stay optimistic about the future."),
            Triple("persevere", "坚持不懈", "You must persevere to succeed."),
            Triple("quaint", "古色古香的", "We stayed in a quaint village."),
            Triple("resilient", "有韧性的", "Children are often resilient."),
            Triple("serene", "宁静的", "The lake looked serene at dawn."),
            Triple("thrive", "茁壮成长", "Plants thrive with enough sunlight."),
        )
        words.forEachIndexed { index, (word, meaning, example) ->
            val status = when {
                index in listOf(2, 7) -> "LEARNING"
                index in listOf(5) -> "MASTERED"
                else -> "NEW"
            }
            val nextReviewAt = if (status == "MASTERED") now + DAY_MS * 3 else now
            db.execSQL(
                """INSERT INTO words
                   (id, uuid, syncStatus, word, meaning, example, status, next_review_at, created_at)
                   VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any>(
                    index + 1L, "seed-word-${index + 1}", word, meaning, example,
                    status, nextReviewAt, now - index * 3600L * 1000L,
                ),
            )
        }
    }

    private fun seedQuestions(db: SupportSQLiteDatabase) {
        // subject, stem, options(List of 4), answerIndex, explanation
        data class Q(val subject: String, val stem: String, val options: List<String>, val answer: Int, val explain: String)
        val questions = listOf(
            Q("数学", "3 + 5 × 2 = ?", listOf("16", "13", "11", "10"), 1, "先算乘法 5×2=10，再加 3 得 13。"),
            Q("数学", "一个正方形边长为 4，面积是多少？", listOf("8", "12", "16", "20"), 2, "面积 = 边长×边长 = 4×4 = 16。"),
            Q("数学", "100 减去 37 等于多少？", listOf("63", "73", "53", "67"), 0, "100 - 37 = 63。"),
            Q("数学", "12 除以 4 等于多少？", listOf("2", "3", "4", "6"), 1, "12 ÷ 4 = 3。"),
            Q("英语", "\"apple\" 的中文意思是？", listOf("香蕉", "苹果", "橘子", "葡萄"), 1, "apple 意为苹果。"),
            Q("英语", "下面哪个是动词？", listOf("happy", "run", "table", "blue"), 1, "run（跑）是动词。"),
            Q("英语", "\"book\" 的复数形式是？", listOf("bookes", "books", "bookies", "book"), 1, "book 的复数是 books。"),
            Q("常识", "地球绕着什么天体公转？", listOf("月亮", "火星", "太阳", "金星"), 2, "地球绕太阳公转。"),
            Q("常识", "水的化学式是什么？", listOf("CO2", "H2O", "O2", "NaCl"), 1, "水的化学式是 H2O。"),
            Q("常识", "一年有多少个月？", listOf("10", "11", "12", "13"), 2, "一年有 12 个月。"),
        )
        questions.forEachIndexed { index, q ->
            val optionsJson = "[" + q.options.joinToString(",") { "\"$it\"" } + "]"
            db.execSQL(
                """INSERT INTO questions
                   (id, uuid, syncStatus, subject, stem, options_json, answer_index, explanation)
                   VALUES (?, ?, 0, ?, ?, ?, ?, ?)""",
                arrayOf<Any>(
                    index + 1L, "seed-question-${index + 1}", q.subject, q.stem,
                    optionsJson, q.answer, q.explain,
                ),
            )
        }
    }

    private fun seedHabitsAndCheckIns(db: SupportSQLiteDatabase, now: Long) {
        // Triple: id, name, icon；start_date 默认 7 天前，目标 21 天（天数型）
        val habits = listOf(
            Triple(1L, "背单词", "book"),
            Triple(2L, "跑步", "run"),
            Triple(3L, "阅读", "read"),
        )
        habits.forEach { (id, name, icon) ->
            val defaultText = if (id == 1L) "今天也坚持背单词啦" else ""
            db.execSQL(
                """INSERT INTO habits
                   (id, uuid, syncStatus, name, icon, target_days, start_date, archived,
                    target_count, unit, default_text)
                   VALUES (?, ?, 0, ?, ?, 21, ?, 0, 0, '', ?)""",
                arrayOf<Any>(id, "seed-habit-$id", name, icon, now - 7 * DAY_MS, defaultText),
            )
        }
        // 数量型习惯：每日喝水目标 500 ml（展示数量型打卡累加）
        db.execSQL(
            """INSERT INTO habits
               (id, uuid, syncStatus, name, icon, target_days, start_date, archived,
                target_count, unit, default_text)
               VALUES (4, 'seed-habit-4', 0, '喝水', '💧', 21, ?, 0, 500, 'ml', '今天也喝足了水')""",
            arrayOf<Any>(now - 3 * DAY_MS),
        )
        // 已达成习惯：早起目标 7 天，10 天前开始且已连续打卡 7 天（进度 100%，金色达成态）
        db.execSQL(
            """INSERT INTO habits
               (id, uuid, syncStatus, name, icon, target_days, start_date, archived,
                target_count, unit, default_text)
               VALUES (5, 'seed-habit-5', 0, '早起', '🌅', 7, ?, 0, 0, '', '早安，新的一天')""",
            arrayOf<Any>(now - 10 * DAY_MS),
        )

        // 为「背单词」插入过去 5 天打卡，为「跑步」插入过去 3 天打卡；
        // 「喝水」按数量累加；「早起」连续 7 天达成目标。
        var checkInId = 0L
        fun insertCheckIn(habitId: Long, daysAgo: Int, amount: Double = 1.0, note: String = "") {
            checkInId += 1
            val date = LocalDate.now().minusDays(daysAgo.toLong()).format(DATE_FMT)
            db.execSQL(
                """INSERT OR IGNORE INTO check_ins
                   (id, uuid, syncStatus, habit_id, date, note, amount)
                   VALUES (?, ?, 0, ?, ?, ?, ?)""",
                arrayOf<Any>(checkInId, "seed-checkin-$checkInId", habitId, date, note, amount),
            )
        }
        insertCheckIn(1L, 0, note = "今天背了 20 个新词")
        for (d in 1L..4L) insertCheckIn(1L, d.toInt())
        for (d in 0L..2L) insertCheckIn(2L, d.toInt())
        insertCheckIn(4L, 0, amount = 300.0, note = "上午就喝完一大半")
        insertCheckIn(4L, 1, amount = 500.0)
        insertCheckIn(4L, 2, amount = 450.0)
        for (d in 3L..9L) insertCheckIn(5L, d.toInt())
    }

    private fun seedBooks(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """INSERT INTO books
               (id, uuid, syncStatus, title, author, total_pages, current_page, status, started_at, finished_at)
               VALUES (1, 'seed-book-1', 0, '小王子', '安托万·德·圣-埃克苏佩里', 97, 60, 'reading', ?, NULL)""",
            arrayOf<Any>(now - 10 * DAY_MS),
        )
        db.execSQL(
            """INSERT INTO books
               (id, uuid, syncStatus, title, author, total_pages, current_page, status, started_at, finished_at)
               VALUES (2, 'seed-book-2', 0, '活着', '余华', 191, 191, 'finished', ?, ?)""",
            arrayOf<Any>(now - 30 * DAY_MS, now - 15 * DAY_MS),
        )

        var excerptId = 0L
        fun insertExcerpt(bookId: Long, content: String, pageNo: Int) {
            excerptId += 1
            db.execSQL(
                """INSERT INTO excerpts
                   (id, uuid, syncStatus, book_id, content, page_no, created_at)
                   VALUES (?, ?, 0, ?, ?, ?, ?)""",
                arrayOf<Any>(excerptId, "seed-excerpt-$excerptId", bookId, content, pageNo, now - excerptId * DAY_MS),
            )
        }
        insertExcerpt(1, "真正重要的东西，用眼睛是看不见的。", 63)
        insertExcerpt(1, "你为你的玫瑰花费的时间，使你的玫瑰变得重要。", 71)
        insertExcerpt(2, "人是为了活着本身而活着，而不是为了活着之外的任何事物而活着。", 5)

        db.execSQL(
            """INSERT INTO book_reviews
               (id, uuid, syncStatus, book_id, rating, content, at)
               VALUES (1, 'seed-review-1', 0, 1, 5, '温暖而忧伤的童话，关于爱与责任。', ?)""",
            arrayOf<Any>(now - 2 * DAY_MS),
        )
        db.execSQL(
            """INSERT INTO book_reviews
               (id, uuid, syncStatus, book_id, rating, content, at)
               VALUES (2, 'seed-review-2', 0, 2, 5, '直面苦难的生命力量，读后久久难忘。', ?)""",
            arrayOf<Any>(now - 14 * DAY_MS),
        )
    }

    private fun seedMistakes(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            """INSERT INTO mistakes
               (id, uuid, syncStatus, source, subject, title, image_path, content, note, review_at, mastered, created_at)
               VALUES (1, 'seed-mistake-1', 0, 'practice', '数学', '分数加减法', NULL,
                       '1/2 + 1/3 = ?（易错：直接分子分母相加）', '先通分再相加：3/6 + 2/6 = 5/6。',
                       ?, 0, ?)""",
            arrayOf<Any>(now + DAY_MS, now - 2 * DAY_MS),
        )
        db.execSQL(
            """INSERT INTO mistakes
               (id, uuid, syncStatus, source, subject, title, image_path, content, note, review_at, mastered, created_at)
               VALUES (2, 'seed-mistake-2', 0, 'photo', '英语', '过去式不规则变化', NULL,
                       'go 的过去式是 went，不是 goed。', '常见不规则动词需要单独记忆。',
                       ?, 0, ?)""",
            arrayOf<Any>(now + DAY_MS, now - 1 * DAY_MS),
        )
        // 第 3 条：已到期待复习，供复习提醒验证使用
        db.execSQL(
            """INSERT INTO mistakes
               (id, uuid, syncStatus, source, subject, title, image_path, content, note, review_at, mastered, created_at)
               VALUES (3, 'seed-mistake-3', 0, 'word', '英语', '形近词辨析 affect/effect', NULL,
                       'affect 是动词（影响），effect 是名词（效果）。', '记忆口诀：A 动 E 名。',
                       ?, 0, ?)""",
            arrayOf<Any>(now - DAY_MS, now - 3 * 3600L * 1000L),
        )
    }
}
