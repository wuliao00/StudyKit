package com.studykit.util.backup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 学习数据占用统计：设置页「数据管理」顶部那几行数字的唯一来源。
 *
 * 字段顺序即界面展示顺序（词条 → 题目 → 错题 → 习惯 → 书 → 图片 → 数据库）。
 * 数字只是计数与字节数，不做任何格式化 —— 格式化是界面的事，这里格式化一次，
 * 界面想换单位（MB/GB）就得回头改数据层。
 */
data class StorageStats(
    val words: Int,
    val questions: Int,
    val mistakes: Int,
    val habits: Int,
    val books: Int,
    val imageBytes: Long,
    val dbBytes: Long,
)

/**
 * 只读统计，不写任何东西。
 *
 * ## 为什么不走 DAO 而直接发 COUNT
 *
 * 本模块只新增文件，不动 `AppDatabase` 与各 DAO；而五个计数里 DAO 只提供了一个（`words`），
 * 且那个还是 `Flow<Int>` 不是 suspend。给五个计数各加一个 DAO 方法，等于为了读几个数字
 * 去改数据层 —— 这里直接对 Room 已打开的那个连接发 `COUNT(*)`。
 *
 * **代价**（改表名时必须一起改，编译器不会提醒）：SQL 里的表名要和 `@Entity` 的 `tableName` 对上 ——
 * `words` / `questions` / `mistakes` / `habits` / `books`。对不上的表现是该条查询抛
 * `SQLiteException: no such table`，被包成 [BackupException] 抛出，界面上能看到根因，
 * 不会静悄悄显示 0。
 *
 * 计的是**整表行数**，含软删/归档的行（`habits.archived = 1` 仍计入）：统计面板回答的是
 * "这些数据占了多少地方"，而不是"列表页能看到几条"，两者故意不同。
 */
object StorageStatsReader {

    private const val SQL_COUNT_WORDS = "SELECT COUNT(*) FROM words"
    private const val SQL_COUNT_QUESTIONS = "SELECT COUNT(*) FROM questions"
    private const val SQL_COUNT_MISTAKES = "SELECT COUNT(*) FROM mistakes"
    private const val SQL_COUNT_HABITS = "SELECT COUNT(*) FROM habits"
    private const val SQL_COUNT_BOOKS = "SELECT COUNT(*) FROM books"

    /** 数据库伴生文件后缀；与 SQLite 的约定同名，别改大小写（Android 侧文件系统大小写敏感） */
    private val DB_SIDECAR_SUFFIXES = listOf("-wal", "-shm")

    /**
     * 取一次快照。首次调用会把 Room 的库打开（含跑迁移），所以整段在 `Dispatchers.IO` 里。
     *
     * @throws BackupException 打不开库或查询失败，message 带最深一层根因
     */
    suspend fun of(context: Context): StorageStats = withContext(Dispatchers.IO) {
        try {
            val db = writableDatabaseOf(context)
            StorageStats(
                words = countOf(db, SQL_COUNT_WORDS),
                questions = countOf(db, SQL_COUNT_QUESTIONS),
                mistakes = countOf(db, SQL_COUNT_MISTAKES),
                habits = countOf(db, SQL_COUNT_HABITS),
                books = countOf(db, SQL_COUNT_BOOKS),
                imageBytes = imageBytesOf(context),
                dbBytes = dbBytesOf(context),
            )
        } catch (error: Exception) {
            if (error is BackupException) throw error
            throw BackupException("统计占用失败：${error.rootCauseText()}", error)
        }
    }

    /** COUNT(*) 必返一行；没有行只可能是 SQL 写错了，抛出来比报 0 好查 */
    private fun countOf(db: SupportSQLiteDatabase, sql: String): Int {
        val cursor = try {
            db.query(sql)
        } catch (error: Exception) {
            throw BackupException("查询计数失败：${error.rootCauseText()}（SQL：$sql）", error)
        }
        try {
            if (!cursor.moveToFirst()) {
                throw BackupException("计数查询没返回结果行（SQL：$sql）")
            }
            return cursor.getInt(0)
        } finally {
            cursor.close()
        }
    }

    /**
     * filesDir/mistake_images 下**全部**文件的字节数（含 `thumb/` 里的缩略图 —— 它们是真占地方的）。
     *
     * 与 [BackupArchive] 的导出清单同一套遍历，两边报出来的数才对得上；
     * 但这里读不到某个子目录时**当 0 处理**（统计宁可少报，也别让设置页因为一次 stat 失败整体报错），
     * 导出那边则是宁可中止也不能悄悄少文件 —— 故意不同。
     */
    private fun imageBytesOf(context: Context): Long = sizeOf(File(context.filesDir, BackupCodec.IMAGE_ROOT))

    private fun sizeOf(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            total += if (child.isDirectory) sizeOf(child) else child.length()
        }
        return total
    }

    /**
     * 主库 + `-wal` + `-shm` 的字节数。
     *
     * 只算主库会低估：WAL 在检查点之前可以顶到好几 MB，而设置页上"数据库 12 MB"要和系统
     * 「应用信息」里的数字大致对得上，否则用户以为被坑了。
     */
    private fun dbBytesOf(context: Context): Long {
        val db = context.getDatabasePath(DB_FILE_NAME)
        var total = if (db.isFile) db.length() else 0L
        for (suffix in DB_SIDECAR_SUFFIXES) {
            val sidecar = File("${db.absolutePath}$suffix")
            if (sidecar.isFile) total += sidecar.length()
        }
        return total
    }
}
