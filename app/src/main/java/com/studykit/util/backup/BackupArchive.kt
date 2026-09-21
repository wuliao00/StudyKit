package com.studykit.util.backup

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.studykit.BuildConfig
import com.studykit.data.AppDatabase
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Room 建库用的文件名（`getDatabasePath` 的入参），与 [com.studykit.data.AppDatabase] 里的私有常量一致。
 * 与 [BackupCodec.DB_ENTRY] 同为 `studykit.db` 是巧合不是同一件事：前者是真身在磁盘上的名字，
 * 后者是 zip 条目名，改了其中一个，另一个的语义并不跟着变。
 */
internal const val DB_FILE_NAME = "studykit.db"

/**
 * 备份/恢复的失败统一成一种异常。
 *
 * message **必须自带最深一层根因**（见 [rootCauseText]）：本模块的失败点至少有四类完全不同的处置方式
 * —— 没存储权限 / 目标文件被别的应用占着 / zip 校验不过 / 内部存储写满。
 * 若统一成"失败可重试"（词库导入上真栽过一次），用户和排查的人都只能瞎猜。
 */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 取 cause 链上**最深**那条非空消息，并附上它属于哪个异常类。
 * 外层包装只会说"写备份包失败"，能判断的永远是 `ENOSPC` / `FileNotFoundException` 这类底层话。
 */
internal fun Throwable.rootCauseText(): String {
    var current: Throwable = this
    var best = javaClass.simpleName
    while (true) {
        current.message?.takeIf { it.isNotBlank() }?.let { best = "$it · ${current.javaClass.simpleName}" }
        current = current.cause ?: break
    }
    return best
}

/**
 * Room 已经打开的那个连接。
 *
 * 本模块只新增文件，不给 [AppDatabase] 加 DAO 方法，所以 PRAGMA 与计数 SQL 都直接对同一个连接发；
 * 另开一条连接会和 Room 的连接池抢 WAL 的读者位，检查点反而永远 busy。
 */
internal fun writableDatabaseOf(context: Context): SupportSQLiteDatabase =
    try {
        AppDatabase.getInstance(context).openHelper.writableDatabase
    } catch (error: Exception) {
        throw BackupException(
            "打不开数据库：${error.rootCauseText()}" +
                "（可能是迁移没跑完、库文件损坏，或 databases 目录首次创建时被存储策略挡住）",
            error,
        )
    }

/**
 * 备份包自述：恢复前靠它判断这份 zip 完不完整、是谁在什么版本打的。
 *
 * [imageCount] 数的是 `mistake_images/` 下的**文件数**，含 `thumb/` 里的缩略图
 * （见 `MistakeImageStore`：一张原图配一张缩略图）。界面要显示"多少道题有图"就别拿它当分母 ——
 * 它是"包里应该解出多少个文件"的完整性断言，改成只数原图就没法校验缩略图有没有丢。
 */
data class BackupManifest(
    val appVersion: String,
    val createdAt: Long,
    val dbBytes: Long,
    val dbChecksum: Long,
    val imageCount: Int,
)

/**
 * 恢复结果，给界面写"恢复了 12.3 MB 数据库与 48 张图片"这类回显。
 * [imageCount] 的口径同 [BackupManifest.imageCount]（含缩略图）。
 */
data class RestoreSummary(val dbBytes: Long, val imageCount: Int)

/** zip 里本模块认领的条目种类；[ZipEntryKind.UNKNOWN] 的条目在恢复时跳过，见 [BackupArchive.restoreFrom] */
internal enum class ZipEntryKind { MANIFEST, DB, IMAGE, UNKNOWN }

/**
 * manifest 的 JSON 编解码、zip 条目名规范化、CRC32 —— 三件与 Android 无关的纯逻辑。
 *
 * ## 为什么要单独抽出来
 *
 * 恢复流程整体绑在 `Context` / `ContentResolver` 上，本机没有 Android SDK，只有 CI 会真正执行一次，
 * 而最容易做错、做错最致命的恰好是这三件事：
 * - 放行一个 `../` 条目名，就能把文件写到应用私有目录**之外**（zip 解包的经典漏洞）；
 * - 缺字段的 manifest 若按默认值解释，等于拿一份没有校验和的库去覆盖真库；
 * - 校验和算错，"完整性校验"就退化成摆设。
 * 收在这里它们才测得到。剩下的 IO 壳子坏了，最坏也只坏在"报不出根因"上。
 */
internal object BackupCodec {

    /** 与 `MistakeImageStore` 的私有 DIR_NAME 对齐；那边是 private，本任务不许改既有文件，故本地声明 */
    const val IMAGE_ROOT = "mistake_images"

    const val MANIFEST_ENTRY = "manifest.json"

    const val DB_ENTRY = "studykit.db"

    private const val FIELD_VERSION = "appVersion"
    private const val FIELD_CREATED_AT = "createdAt"
    private const val FIELD_DB_BYTES = "dbBytes"
    private const val FIELD_DB_CHECKSUM = "dbChecksum"
    private const val FIELD_IMAGE_COUNT = "imageCount"

    /** 少任何一个都没法判断包好不好，所以是"必需字段"而不是"可缺省字段" */
    private val REQUIRED_FIELDS = listOf(
        FIELD_VERSION,
        FIELD_CREATED_AT,
        FIELD_DB_BYTES,
        FIELD_DB_CHECKSUM,
        FIELD_IMAGE_COUNT,
    )

    /** CRC32 分块喂的大小。导出走整数组、恢复走文件，两条路必须得同一个值 */
    private const val CHECKSUM_CHUNK = 8 * 1024

    /**
     * 编成写进 zip 的 JSON 文本。
     *
     * 用 `JSONObject` 而不是手拼字符串：`appVersion` 今天是 `2.1.0`，明天可能是带引号的东西，
     * 手拼一次就写出一个自己读不回来的 manifest。
     */
    fun manifestJson(manifest: BackupManifest): String = JSONObject().apply {
        put(FIELD_VERSION, manifest.appVersion)
        put(FIELD_CREATED_AT, manifest.createdAt)
        put(FIELD_DB_BYTES, manifest.dbBytes)
        put(FIELD_DB_CHECKSUM, manifest.dbChecksum)
        put(FIELD_IMAGE_COUNT, manifest.imageCount)
    }.toString()

    /**
     * 解 manifest；**任何不合格式都返回 null，不抛**。
     *
     * 不抛是为了"预览一张备份卡"这种动作不把设置页崩掉。缺字段也不能退回默认值：
     * 备份包可能被用户手工改过，也可能来自别的打包器（`AppSettings` 的 KDoc 里同一条纪律），
     * 少一个 `dbChecksum` 就按 0 解释，等于宣布"这份没有校验和的库是好的"。
     * 多出来的字段忽略，让新版本的包仍能被旧版本认出来。
     */
    fun manifestFrom(text: String): BackupManifest? = try {
        val json = JSONObject(text)
        if (REQUIRED_FIELDS.any { !json.has(it) || json.isNull(it) }) {
            null
        } else {
            val decoded = BackupManifest(
                appVersion = json.getString(FIELD_VERSION).trim(),
                createdAt = json.getLong(FIELD_CREATED_AT),
                dbBytes = json.getLong(FIELD_DB_BYTES),
                dbChecksum = json.getLong(FIELD_DB_CHECKSUM),
                imageCount = json.getInt(FIELD_IMAGE_COUNT),
            )
            decoded.takeIf {
                it.appVersion.isNotEmpty() && it.createdAt > 0 && it.dbBytes >= 0 &&
                    it.dbChecksum >= 0 && it.imageCount >= 0
            }
        }
    } catch (error: Exception) {
        null // 不是 JSON、字段类型不对、数字溢出，一律当"读不出这份包"处理
    }

    /**
     * zip 条目名规范化；返回 null 表示这个名字**不可信**，调用方必须整包拒绝。
     *
     * - `\` → `/`：zip 规范（APPNOTE 4.4.17）要求条目名用 `/`，但 Windows 上的打包工具会写 `\`，
     *   不规范化就解出一堆名字里带反斜杠的怪文件，或干脆落不到该落的子目录；
     * - 绝对路径、盘符、`..` 段一律拒绝：解包目标永远是应用私有目录，出现这些段就说明包被改过，
     *   此时**最不该做**的是"我帮你把路径算对继续解"；
     * - `.` 段与空段（`a//b`）按噪声吃掉，它们改变不了归属；
     * - 目录条目（`mistake_images/`）返回去尾斜杠的名字，是不是目录由调用方看
     *   `ZipEntry.isDirectory` 判定 —— 这里不返回 null，免得合法的目录条目被当成攻击。
     */
    fun canonicalEntryName(raw: String): String? {
        val unified = raw.replace('\\', '/')
        if (unified.isEmpty() || unified.startsWith("/") || unified.contains(':')) return null
        val kept = ArrayList<String>()
        for (segment in unified.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> return null
                else -> kept += segment
            }
        }
        if (kept.isEmpty()) return null
        return kept.joinToString("/")
    }

    /** 条目归类。不认的条目恢复时跳过：前向兼容比"看见没见过的就报错"更有用 */
    fun entryKindOf(name: String): ZipEntryKind = when {
        name == MANIFEST_ENTRY -> ZipEntryKind.MANIFEST
        name == DB_ENTRY -> ZipEntryKind.DB
        name.startsWith("$IMAGE_ROOT/") -> ZipEntryKind.IMAGE
        else -> ZipEntryKind.UNKNOWN
    }

    /**
     * 把 `/` 分隔的绝对路径对折成条目名（相对 [rootPath]）；不在该根下返回 null。
     *
     * 条目名一律 `/` 分隔、且与 Room 存在 `mistakes.image_path` 里的那个相对路径**同形**，
     * 这样解包时 `File(filesDir, entryName)` 就是它原来的位置，不需要第二份映射表。
     * 调用方传 `File.invariantSeparatorsPath`，别传 `absolutePath`（后者在 Windows 上是 `\`）。
     */
    fun relativeEntryPath(rootPath: String, path: String): String? {
        val root = rootPath.replace('\\', '/').trimEnd('/')
        val target = path.replace('\\', '/')
        if (!target.startsWith("$root/")) return null
        return target.removePrefix("$root/").takeIf { it.isNotEmpty() }
    }

    /** 整份字节的 CRC32（值域 0..2^32-1，用 Long 装，避免签名数被解释成负数） */
    fun checksum(bytes: ByteArray): Long {
        val crc = CRC32()
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(CHECKSUM_CHUNK, bytes.size - offset)
            crc.update(bytes, offset, length)
            offset += length
        }
        return crc.value
    }

    /** 流式 CRC32：库是几十 MB，为算校验和再整份读进内存是白多一份峰值 */
    fun checksumOf(file: File): Long = file.inputStream().use { input ->
        val crc = CRC32()
        val buffer = ByteArray(CHECKSUM_CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            crc.update(buffer, 0, read)
        }
        crc.value
    }
}

/**
 * 学习数据的一键导出 / 恢复：zip = `manifest.json` + `studykit.db` + `mistake_images/**`。
 *
 * ## 为什么非做不可
 *
 * `res/xml/backup_rules.xml` 把 `domain="database"` 整个排除在系统备份之外，也就是说用户的词库、
 * 错题与照片目前**没有任何退路**。本类就是那条退路，格式与 `MistakeImageStore` 的落盘布局对齐。
 *
 * ## 三条不能妥协的地方
 *
 * 1. 导出前先做 WAL 检查点（见 [checkpointWal]），否则拷走的库缺最近若干事务；
 * 2. 恢复先把整包读进内存、解到临时目录、验完大小/校验和/图片数**才**碰真数据；
 * 3. 阻塞 IO 全在 `Dispatchers.IO`，失败只以 [BackupException] 形式抛出并带根因。
 */
object BackupArchive {

    /** SAF 写入模式：显式截断，见 [exportTo] */
    private const val WRITE_MODE = "w"

    /** 归档整体读进内存的上限。正常包几十 MB 量级，超了就是坏包或 zip 炸弹，不陪它玩 */
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L

    /** 单张图片解包上限：解出来的大小由包自己声明，必须不信 */
    private const val MAX_IMAGE_ENTRY_BYTES = 32L * 1024L * 1024L

    private const val COPY_BUFFER_BYTES = 64 * 1024

    private const val CHECKPOINT_ATTEMPTS = 3
    private const val CHECKPOINT_RETRY_MILLIS = 80L

    private const val PRAGMA_WAL_CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"

    /** cacheDir 下的解包暂存目录，与 filesDir 同构，便于按条目名原样落位 */
    private const val FILES_STAGING_DIR = "files"

    /**
     * 导出 zip 到 SAF [target]，返回写出的字节数（界面拿去显示"已导出 18.4 MB"）。
     *
     * 条目顺序是 `manifest.json` → `studykit.db` → `mistake_images/**`：manifest 排第一，
     * 用户把这个包丢给别的工具时，第一个条目就说清了后面是什么。
     *
     * 目标流以 `"w"` 模式打开而不是用无 mode 的重载：语义要**截断**。
     * 万一某个 DocumentsProvider 按追加处理，第二次导出会在同一个文件后面接出第二个 zip，
     * 恢复时读到的是新旧混合的条目，校验还不一定拦得住。
     *
     * @throws BackupException 任何一步失败，message 带最深一层根因
     */
    suspend fun exportTo(context: Context, target: Uri): Long = withContext(Dispatchers.IO) {
        try {
            exportInternal(context, target)
        } catch (error: Exception) {
            if (error is BackupException) throw error
            throw BackupException("导出失败：${error.rootCauseText()}", error)
        }
    }

    /**
     * 从 SAF [source] 指向的 zip 恢复。全程分三段：整包读进内存 → 解到 `cacheDir` 临时目录并校验 →
     * 校验通过才换名落位。任何校验不过都在第三段之前抛出，**一个字节都不动**。
     *
     * 图片是增量覆盖：包里没提到的旧图片文件保留。恢复一个旧备份不该把用户刚拍、
     * 还没进任何备份的照片一起删掉；多出来的孤儿文件只是不被引用，只占空间。
     *
     * 全程不打开现有数据库（只用 `getDatabasePath` 拿路径）：用户来恢复，往往正是因为库已经坏了，
     * 这里若先开一次库，救数据的入口自己就先抛了。
     *
     * **调用方限制（务必读）**：恢复成功后 Room 那批连接还开着旧库的页缓存，本模块不碰进程、
     * 不起 Activity，也不调 `exitProcess`。让调用方（设置页）自己重启进程 ——
     * 不重启就继续用当前进程，界面显示的还是恢复前的数据，用户会以为恢复没生效。
     *
     * 已知未处理的边界：把**更高 schema 版本**的备份恢复到本版本，换上的库本版本打不开
     * （Room 找不到向上的迁移路径，开库即抛）。manifest 里的 `appVersion` 只是给人看的，
     * 这里不拿它当版本闸门 —— 字符串版本号不能可靠地推出 schema 版本，误判的代价是"拒绝救数据"。
     *
     * @throws BackupException 任何一步失败，message 带最深一层根因
     */
    suspend fun restoreFrom(context: Context, source: Uri): RestoreSummary = withContext(Dispatchers.IO) {
        try {
            restoreInternal(context, source)
        } catch (error: Exception) {
            if (error is BackupException) throw error
            throw BackupException("恢复失败：${error.rootCauseText()}", error)
        }
    }

    /**
     * 只读 zip 里的 `manifest.json` 并解码；读不出就返回 null，**不抛**。
     *
     * 界面要在"恢复"之前先知道这份包是什么来路（哪天导的、多大、多少张图）。
     * 那种预览动作抛异常没有意义：包坏了本来就该显示成"不可恢复"，而不是崩。
     */
    fun readManifest(zipBytes: ByteArray): BackupManifest? {
        val text = try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var found: String? = null
                // nextEntry 只能向前推进一次，包成序列才不用手写推进样板（见 DictRemote 同类处理）
                for (entry in generateSequence { zip.nextEntry }) {
                    if (entry.isDirectory) continue
                    val name = BackupCodec.canonicalEntryName(entry.name) ?: continue
                    if (BackupCodec.entryKindOf(name) == ZipEntryKind.MANIFEST) {
                        found = zip.reader(Charsets.UTF_8).readText()
                        break
                    }
                }
                found
            }
        } catch (error: Exception) {
            null // 连 zip 都打不开，就是"读不出这份包"，抛出去只会把预览页搞崩
        }
        if (text == null) return null
        return BackupCodec.manifestFrom(text)
    }

    // region 导出

    private suspend fun exportInternal(context: Context, target: Uri): Long {
        // 先检查点：这一步顺带把库开起来（新装机器上库文件是首次访问才建的，
        // 顺序反过来会对着一个还不存在的文件报"没有可导出的数据库"）
        checkpointWal(context)
        val dbFile = context.getDatabasePath(DB_FILE_NAME)
        if (!dbFile.isFile) {
            throw BackupException("没有可导出的数据库（$DB_FILE_NAME 不存在：${dbFile.absolutePath}）")
        }
        val dbBytes = try {
            dbFile.readBytes()
        } catch (error: Exception) {
            throw BackupException("读数据库文件失败：${error.rootCauseText()}", error)
        }
        val images = imageEntries(context)
        val manifest = BackupManifest(
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(),
            dbBytes = dbBytes.size.toLong(),
            dbChecksum = BackupCodec.checksum(dbBytes),
            imageCount = images.size,
        )
        val counting = CountingSink(openTargetStream(context, target))
        try {
            val manifestBytes = BackupCodec.manifestJson(manifest).toByteArray(Charsets.UTF_8)
            ZipOutputStream(BufferedOutputStream(counting)).use { zip ->
                putBytes(zip, BackupCodec.MANIFEST_ENTRY, manifestBytes)
                putBytes(zip, BackupCodec.DB_ENTRY, dbBytes)
                for ((name, file) in images) putFile(zip, name, file)
            }
        } catch (error: Exception) {
            if (error is BackupException) throw error
            throw BackupException(
                "写备份包失败：${error.rootCauseText()}" +
                    "（常见原因是目标存储已满、云盘 provider 中途关流，或用户在授权弹窗上反悔了）",
                error,
            )
        }
        // use 已经把中央目录写完并关流，此刻的计数才是文件真实大小
        return counting.written
    }

    /**
     * `PRAGMA wal_checkpoint(TRUNCATE)`：把 WAL 里已提交的页刷回主库文件并截断 WAL。
     *
     * 不 checkpoint 就拷，导出的 `studykit.db` 会缺最近若干事务 —— 现象是"恢复后少了最后一次导入"，
     * 而 zip 完好、校验和自洽，**根因永远查不出来**。
     * 该 PRAGMA 返回一行 `(busy, log, checkpointed)`：busy=1 表示仍有读者占着、这次没清空，
     * 必须重试并在重试仍失败时中止，而不是把它当成成功。
     */
    private suspend fun checkpointWal(context: Context) {
        val db = writableDatabaseOf(context)
        var busy = true
        var attempts = 0
        while (busy && attempts < CHECKPOINT_ATTEMPTS) {
            attempts++
            val cursor = try {
                db.query(PRAGMA_WAL_CHECKPOINT)
            } catch (error: Exception) {
                throw BackupException("执行 WAL 检查点失败：${error.rootCauseText()}", error)
            }
            try {
                // 没返回行也按"没清成"处理：正常情况下这个 PRAGMA 必返一行，拿不到结果就是我们不知道状态，
                // 而"不知道"时导出的库可能缺最近数据 —— 宁可不导。
                val hasRow = cursor.moveToFirst()
                busy = !hasRow || cursor.getInt(0) == 1
            } finally {
                cursor.close()
            }
            // 只是给还没走完的读事务让路：用 delay 挂起而不是 Thread.sleep，别占着一条 IO 线程干等
            if (busy) delay(CHECKPOINT_RETRY_MILLIS)
        }
        if (busy) {
            throw BackupException(
                "WAL 检查点连续 $attempts 次没清成（返回 busy=1，或压根没返回结果行）：" +
                    "还有未结束的读事务，此刻导出的库会缺最近的数据，已中止导出",
            )
        }
    }

    /**
     * filesDir/mistake_images 下所有文件 → (条目名, 文件)，按条目名排序。
     *
     * 排序不是洁癖：同一份数据两次导出的条目顺序一致，比对文件大小、或对着两个包查差异时不需要
     * 先解释"为什么顺序变了"。
     */
    private fun imageEntries(context: Context): List<Pair<String, File>> {
        val root = File(context.filesDir, BackupCodec.IMAGE_ROOT)
        if (!root.isDirectory) return emptyList()
        val files = ArrayList<File>()
        collectFiles(root, files)
        val filesDirPath = context.filesDir.invariantSeparatorsPath
        return files.mapNotNull { file ->
            BackupCodec.relativeEntryPath(filesDirPath, file.invariantSeparatorsPath)?.let { it to file }
        }.sortedBy { it.first }
    }

    /**
     * 递归收文件，某层 `listFiles()` 返回 null 就抛。
     *
     * 不用 `walkTopDown()`：它读不到子目录时是**静默跳过**的，那种"导出报成功、包里少了三张照片"
     * 是这个模块最不能出现的错法。
     */
    private fun collectFiles(dir: File, into: MutableList<File>) {
        val children = dir.listFiles()
            ?: throw BackupException("列不出 ${dir.invariantSeparatorsPath} 的内容（目录被外部清理、权限异常或磁盘故障），已中止")
        for (child in children) {
            if (child.isDirectory) collectFiles(child, into) else if (child.isFile) into += child
        }
    }

    private fun openTargetStream(context: Context, target: Uri): OutputStream {
        val stream = try {
            context.contentResolver.openOutputStream(target, WRITE_MODE)
        } catch (error: Exception) {
            throw BackupException(
                "打不开导出目标 $target：${error.rootCauseText()}" +
                    "（没写权限、文件被别的应用占着，或该 provider 不支持写）",
                error,
            )
        }
        if (stream == null) {
            throw BackupException("导出目标 $target 返回了空流且没抛异常：ContentResolver 那一侧的 provider 没实现写入")
        }
        return stream
    }

    /** 写一个内存里的条目。条目名一律 `/` 分隔，与 [BackupCodec.relativeEntryPath] 同一套约定 */
    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** 流式写图片条目：几十张原图不该在内存里堆成一份副本 */
    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        try {
            file.inputStream().use { input -> input.copyTo(zip, COPY_BUFFER_BYTES) }
        } catch (error: Exception) {
            throw BackupException("读错题图片 $name 失败：${error.rootCauseText()}", error)
        }
        zip.closeEntry()
    }

    /** 计字节的输出流包装：拿"到底写了多少字节"，又不用把整包先在内存里过一遍 */
    private class CountingSink(sink: OutputStream) : FilterOutputStream(sink) {

        var written: Long = 0L
            private set

        override fun write(oneByte: Int) {
            out.write(oneByte)
            written++
        }

        /** 必须自己覆盖：父类的默认实现是逐字节 write(int)，几十 MB 会慢到看不下去 */
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            written += length
        }
    }

    // endregion

    // region 恢复

    /** 解到临时目录的结果：真数据一个都没动 */
    private class Unpacked(val dbTemp: File, val filesTemp: File, val imageCount: Int)

    private fun restoreInternal(context: Context, source: Uri): RestoreSummary {
        val archive = readArchive(context, source)
        val manifest = readManifest(archive)
            ?: throw BackupException(
                "备份包里的 manifest.json 缺失、字段不全或不是合法 JSON：没有校验和就无法确认这份库完不完整，" +
                    "已中止，未改动任何数据",
            )
        val staging = File(context.cacheDir, "restore-" + UUID.randomUUID())
        return try {
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw BackupException("建不了解包临时目录 ${staging.absolutePath}：缓存目录不可写（存储已满或权限异常）")
            }
            val unpacked = unpack(archive, manifest, staging)
            verifyIntegrity(manifest, unpacked)
            commit(context, unpacked, manifest)
        } finally {
            // 临时目录里是用户数据的副本，无论成败都不留在 cacheDir
            staging.deleteRecursively()
        }
    }

    /** 整包读进内存（带上限），绝不"边读边覆盖" */
    private fun readArchive(context: Context, source: Uri): ByteArray {
        val stream = try {
            context.contentResolver.openInputStream(source)
        } catch (error: Exception) {
            throw BackupException(
                "读不了所选备份 $source：${error.rootCauseText()}" +
                    "（SAF 授予的读权限是一次性的、可能已失效，或该云盘 provider 不给流）",
                error,
            )
        }
        if (stream == null) {
            throw BackupException("所选备份 $source 打不开：ContentResolver 返回空流且没抛异常（provider 没实现读取）")
        }
        val sink = ByteArrayOutputStream(COPY_BUFFER_BYTES)
        try {
            stream.use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ARCHIVE_BYTES) {
                        throw BackupException(
                            "备份包超过 ${MAX_ARCHIVE_BYTES / (1024 * 1024)}MB 上限，拒绝整体读入内存" +
                                "（正常包是几十 MB 量级，超出的多半是坏包或 zip 炸弹）",
                        )
                    }
                    sink.write(buffer, 0, read)
                }
            }
        } catch (error: Exception) {
            if (error is BackupException) throw error
            throw BackupException("读备份包失败：${error.rootCauseText()}", error)
        }
        return sink.toByteArray()
    }

    /** 解到临时目录。条目名先过 [BackupCodec.canonicalEntryName]，不安全就整包拒绝 */
    private fun unpack(archive: ByteArray, manifest: BackupManifest, staging: File): Unpacked {
        val dbTemp = File(staging, BackupCodec.DB_ENTRY)
        val filesTemp = File(staging, FILES_STAGING_DIR)
        if (!filesTemp.mkdirs() && !filesTemp.isDirectory) {
            throw BackupException("建不了图片暂存目录 ${filesTemp.absolutePath}：存储已满或权限异常")
        }
        var imageCount = 0
        var sawDb = false
        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                for (entry in generateSequence { zip.nextEntry }) {
                    if (entry.isDirectory) continue
                    val name = BackupCodec.canonicalEntryName(entry.name)
                        ?: throw BackupException(
                            "备份包条目名「${entry.name}」不安全（绝对路径、盘符或 ../ 穿越）：整包拒绝，未写任何真实数据",
                        )
                    when (BackupCodec.entryKindOf(name)) {
                        ZipEntryKind.MANIFEST -> Unit // 已经解过并校验过了
                        ZipEntryKind.DB -> {
                            sawDb = true
                            copyOut(zip, dbTemp, manifest.dbBytes, name)
                        }
                        // 大小上限取 manifest 声明的值：多一个字节都说明包与自述不符
                        ZipEntryKind.IMAGE -> {
                            imageCount++
                            copyOut(zip, File(filesTemp, name), MAX_IMAGE_ENTRY_BYTES, name)
                        }
                        // 前向兼容：新版本往包里加了东西，旧版本照样能恢复自己认领的那两样，
                        // 为"看不懂的条目"整体拒绝，等于让用户丢掉唯一的退路
                        ZipEntryKind.UNKNOWN -> Unit
                    }
                }
            }
        } catch (error: Exception) {
            if (error is BackupException) throw error
            // ZipInputStream 在条目 CRC 不符时抛 ZipException，这是"包存坏了"最直接的证据
            throw BackupException("读备份包内容失败：${error.rootCauseText()}（zip 结构或条目校验不过），未写任何真实数据", error)
        }
        if (!sawDb) {
            throw BackupException("备份包里没有 ${BackupCodec.DB_ENTRY} 条目，不像本应用导出的包，已中止，未写任何真实数据")
        }
        if (imageCount != manifest.imageCount) {
            throw BackupException(
                "图片条目 $imageCount 个，manifest 记的却是 ${manifest.imageCount} 个：包被截断或改过，未写任何真实数据",
            )
        }
        return Unpacked(dbTemp, filesTemp, imageCount)
    }

    /** 解一个条目到暂存文件，边解边卡上限（解压后大小由包自己声明，不能信） */
    private fun copyOut(zip: ZipInputStream, into: File, maxBytes: Long, entryName: String) {
        into.parentFile?.mkdirs()
        into.outputStream().use { sink ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw BackupException(
                        "条目 $entryName 解压后超过 ${maxBytes / (1024 * 1024)}MB 上限：坏包或 zip 炸弹，未写任何真实数据",
                    )
                }
                sink.write(buffer, 0, read)
            }
        }
    }

    /** 落位前的最后一道闸：大小与 CRC32 都要与 manifest 逐字相符 */
    private fun verifyIntegrity(manifest: BackupManifest, unpacked: Unpacked) {
        val actualBytes = unpacked.dbTemp.length()
        if (actualBytes != manifest.dbBytes) {
            throw BackupException(
                "解出的数据库 $actualBytes 字节，manifest 记的是 ${manifest.dbBytes} 字节：包不完整，" +
                    "已中止，未写任何真实数据",
            )
        }
        val actualChecksum = try {
            BackupCodec.checksumOf(unpacked.dbTemp)
        } catch (error: Exception) {
            throw BackupException("算不出临时数据库的校验和：${error.rootCauseText()}，未写任何真实数据", error)
        }
        if (actualChecksum != manifest.dbChecksum) {
            throw BackupException(
                "数据库校验和不符（manifest 记 ${manifest.dbChecksum}，实际 $actualChecksum）：" +
                    "包被改过或存储损坏，已中止，未写任何真实数据",
            )
        }
    }

    /** 唯一会动真数据的步骤，放在全部校验之后 */
    private fun commit(context: Context, unpacked: Unpacked, manifest: BackupManifest): RestoreSummary {
        val target = context.getDatabasePath(DB_FILE_NAME)
        try {
            target.parentFile?.mkdirs()
            moveInto(unpacked.dbTemp, target)
            // 老库的 -wal / -shm / -journal 必须删干净：它们记着**上一份**数据的页镜像，
            // 留着的话 SQLite 开库时会把旧事务重新灌进刚换上的新库，现象是"恢复没生效"，
            // 更坏的是页号对不上时直接抛 SQLITE_CORRUPT。
            File("${target.absolutePath}-wal").delete()
            File("${target.absolutePath}-shm").delete()
            File("${target.absolutePath}-journal").delete()
            val stagedFiles = ArrayList<File>()
            collectFiles(unpacked.filesTemp, stagedFiles)
            val stagingRoot = unpacked.filesTemp.invariantSeparatorsPath
            for (file in stagedFiles) {
                val relative = BackupCodec.relativeEntryPath(stagingRoot, file.invariantSeparatorsPath) ?: continue
                // 暂存目录与 filesDir 同构，条目名（mistake_images/…）直接就是目标相对路径
                val dest = File(context.filesDir, relative)
                dest.parentFile?.mkdirs()
                moveInto(file, dest)
            }
        } catch (error: Exception) {
            if (error is BackupException) throw error
            throw BackupException(
                "覆盖真实数据失败：${error.rootCauseText()}（多半是内部存储已满）。" +
                    "注意数据库文件可能已经换上新的了，重启进程后先核对数据再决定要不要重试",
                error,
            )
        }
        return RestoreSummary(dbBytes = manifest.dbBytes, imageCount = unpacked.imageCount)
    }

    /**
     * 换名（rename）优先，失败退化成"同目录临时文件 + 换名"。
     *
     * 直接就地覆盖真身会留下"写到一半的库"这个窗口（断电或存储满就摊上事了）；
     * 而 `cacheDir` 与 `databases` 不在同一挂载点时 `renameTo` 只返回 false、不抛异常，
     * 所以要自己兜底 —— 兜底路径仍然保证目标要么旧的要么新的，不会是半份。
     */
    private fun moveInto(from: File, to: File) {
        if (from.renameTo(to)) return
        val sibling = File(to.parentFile, "${to.name}.pending")
        try {
            sibling.outputStream().use { output -> from.inputStream().use { it.copyTo(output) } }
        } catch (error: Exception) {
            sibling.delete()
            throw BackupException(
                "复制 ${from.absolutePath} → ${to.absolutePath} 失败：${error.rootCauseText()}（内部存储满？），" +
                    "源临时文件仍在 ${from.absolutePath}",
                error,
            )
        }
        if (!sibling.renameTo(to)) {
            sibling.delete()
            throw BackupException(
                "已复制到 ${sibling.absolutePath} 但换名失败：目标 ${to.absolutePath} 可能被别的过程锁住或只读",
            )
        }
        from.delete()
    }

    // endregion
}
