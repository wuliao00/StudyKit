package com.studykit.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 首次建库的「第一眼价值」播种器（task-7 A）。
 *
 * 和 [DemoSeeder] 刻意分开：那个是本地演示/截图用的整包数据（20 词 + 习惯 + 书 + 题目），
 * 默认不跑；这里只预置 3 道示例错题，一条带公式、一条英语、一条形近词，且 review_at 已到期，
 * 用户第一次打开就有一条真实的待复习可点，60 秒内能完成一次重做。
 *
 * 三条都只写 mistakes 表，不碰 words / habits / books / questions：
 * 新用户看到的「我的东西」里只有 3 道错题（内容本身就是真实知识点），
 * 不是 20 个陌生单词和别人的打卡记录。三条都可以正常编辑、标记掌握、删除。
 *
 * 幂等：表里已有任何错题就跳过（用户自己的数据永远优先）。只在 Room onCreate
 * （= 该安装第一次建库）调用，老用户升级不会补种。
 */
object FirstRunSeeder {

    private const val HOUR_MS = 60L * 60L * 1000L

    fun seedSampleMistakes(db: SupportSQLiteDatabase) {
        runCatching {
            db.query("SELECT COUNT(*) FROM mistakes").use { cursor ->
                if (cursor.moveToFirst() && cursor.getLong(0) > 0) return
            }
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                insert(
                    db = db,
                    uuid = "first-run-mistake-formula",
                    source = "practice",
                    subject = "数学",
                    title = "完全平方公式",
                    content = "(a + b)² 展开时容易漏掉中间项。",
                    note = "(a + b)² = a² + 2ab + b²。记不住就先把 (a + b)(a + b) 逐项乘一遍，中间那 2ab 就是两条对角线。",
                    reviewAt = now - 2 * HOUR_MS,
                    createdAt = now - 6 * HOUR_MS,
                )
                insert(
                    db = db,
                    uuid = "first-run-mistake-english",
                    source = "practice",
                    subject = "英语",
                    title = "现在完成时 vs 一般过去时",
                    content = "I have finished my homework. / I finished my homework yesterday.",
                    note = "有明确过去时间（yesterday、last week）用一般过去时；强调对现在的影响用现在完成时。",
                    reviewAt = now - HOUR_MS,
                    createdAt = now - 5 * HOUR_MS,
                )
                insert(
                    db = db,
                    uuid = "first-run-mistake-confusable",
                    source = "word",
                    subject = "英语",
                    title = "形近词辨析：affect / effect",
                    content = "affect 是动词（影响）；effect 是名词（效果）。",
                    note = "口诀：A 动 E 名 —— affect = action（影响），effect = end result（结果）。",
                    reviewAt = now - 3 * HOUR_MS,
                    createdAt = now - 4 * HOUR_MS,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun insert(
        db: SupportSQLiteDatabase,
        uuid: String,
        source: String,
        subject: String,
        title: String,
        content: String,
        note: String,
        reviewAt: Long,
        createdAt: Long,
    ) {
        db.execSQL(
            "INSERT INTO mistakes " +
                "(uuid, syncStatus, source, subject, title, image_path, content, note, " +
                "review_at, mastered, created_at) " +
                "VALUES (?, 0, ?, ?, ?, NULL, ?, ?, ?, 0, ?)",
            arrayOf<Any>(uuid, source, subject, title, content, note, reviewAt, createdAt),
        )
    }
}
