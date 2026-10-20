package com.studykit.modelpack

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 文件化 store 的失败：路径越界、staging 不完整、包不存在……都收成这一种，界面只展示可读原因。 */
class ModelPackStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一个已安装包在磁盘上的真相三元组。
 *
 * [enabled] 与 [installedAt] 来自包目录里的 `state.json`（不塞进 app_setting 键值表：计划 D2）；
 * [manifest] 来自 `manifest.json`，是校验与 UI 的口径来源。
 */
data class InstalledPack(
    val manifest: PackManifest,
    val enabled: Boolean,
    val installedAt: Long,
)

/** 逐个文件 sha256 校验的结果。 */
sealed interface VerifyResult {
    /** 全部文件都在、长度与 sha256 都对。 */
    data object Ok : VerifyResult

    /** [missing] 缺文件；[mismatched] 在但内容对不上（长度或 sha256）。 */
    data class Broken(val missing: List<String>, val mismatched: List<String>) : VerifyResult
}

/**
 * 一次目录扫描的结果，三个集合互斥：
 * - [packs]：manifest 合法且 payload 齐全 —— 才算"已安装"；
 * - [brokenIds]：manifest 合法但 payload 缺件/长度不符 —— 降级为"文件不完整"，可重新下载；
 * - [skippedIds]：目录名不是合法 id，或 manifest 缺失/损坏 —— 直接当未安装，绝不抛异常。
 *
 * 为什么要把"坏"分两档：manifest 坏掉时连"这个目录属于哪个包"都不确定，只能忽略；
 * payload 坏掉时清单还在，用户能看见"重新下载"这条修复路径。
 */
data class ScanResult(
    val packs: List<InstalledPack>,
    val skippedIds: List<String>,
    val brokenIds: List<String>,
)

/** `state.json` 的读回形态（字段缺失时给安全默认值，见 [ModelPackStore.readState]）。 */
internal data class PackPersistedState(
    val enabled: Boolean,
    val installedAt: Long,
)

/**
 * 模型包的文件化存储（计划 §3.4）。
 *
 * ## 目录布局（`filesDir/model-packs/`）
 * ```
 * model-packs/
 * ├─ voice-tts/                 # 已安装
 * │  ├─ manifest.json           # 只读、参与校验
 * │  ├─ state.json              # {"enabled":true,"installedAt":<ms>}
 * │  └─ payload/                # ← manifest.files[].path 的相对根
 * │     ├─ model.onnx
 * │     └─ tokens.txt
 * └─ voice-asr.partial/         # 下载中（staging，崩溃/取消后可被下次启动清理）
 * ```
 *
 * ## 文件即真相
 * "已安装"的判定是 **目录在 + manifest 可解析 + 每个 payload 文件长度与 sha256 对得上**。
 * 不存在索引文件、没有缓存表：包数 ≤ 6，每次打开设置页扫一遍目录是 O(packs) 的；
 * 反过来，"表里说装了、盘上没有"这种第二真相源一旦出现，就要写对账逻辑与对账测试。
 *
 * ## 原子安装
 * 下载器只写 `<id>.partial/`；[commit] 校验通过后把 manifest/state 写进 staging，
 * 再 `File.renameTo` 到正式目录（同分区 rename 是原子的）。目标已存在（升级/重装）时先把旧目录
 * 改名成 `<id>.old` 当回滚位，换好新目录再删 —— 中途失败要把旧目录改回去，不留半安装态。
 *
 * ## 防目录穿越
 * id 与 `files[].path` 都先过白名单/相对路径校验（[PackIdPattern]、[PackManifest.validatePackFilePath]），
 * 再规范化并断言落在 root 之内。manifest 是**下载来的**数据，不能信。
 *
 * ## 不做什么
 * 不统计图片/数据库（那是 [com.studykit.util.backup.StorageStats] 的活）；不自动删包
 * （删除必须用户确认）；不做断点续传（下载器的事）。
 *
 * @param root 包根目录；生产是 `File(app.filesDir, "model-packs")`，单测给 TemporaryFolder。
 * @param now 安装时间源；单测固定它才能逐字断言 state.json。
 */
class ModelPackStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 包根目录（下载器要在它下面准备 staging，VM 要把它交给 store；只读暴露，不做缓存）。 */
    val rootDir: File get() = root

    /**
     * 某包的正式目录。id 非法或规范化后越界时抛 [ModelPackStoreException]。
     *
     * 注意：**本方法不创建目录**。"目录存在"本身就是安装状态的一部分。
     */
    fun dirOf(id: String): File {
        requireSafeId(id)
        return File(root, id).also(::assertUnderRoot)
    }

    /**
     * 某包的下载暂存目录（`<id>.partial`）。
     *
     * 下载器只许往这里写；[commit] 成功后该目录整体改名为正式目录。
     */
    fun stagingDirOf(id: String): File {
        requireSafeId(id)
        return File(root, "$id$PARTIAL_SUFFIX").also(::assertUnderRoot)
    }

    /**
     * 扫描根目录。坏包按两档降级（见 [ScanResult]），**本方法不抛**（除 IO 层被杀的极端情况）。
     *
     * 这里只做廉价校验（文件存在 + 长度），不读 433MB 的 sha256 —— 设置页每次打开都全量哈希
     * 会把中端机卡出可感知的延迟。完整 sha256 在 [verify] 里按需做。
     */
    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        val packs = ArrayList<InstalledPack>(dirs.size)
        val skipped = ArrayList<String>()
        val broken = ArrayList<String>()
        for (dir in dirs) {
            if (dir.name.endsWith(PARTIAL_SUFFIX)) continue // 下载中的半包不算安装
            val manifest = readManifest(dir)
            if (manifest == null || manifest.id != dir.name) {
                skipped += dir.name
                continue
            }
            if (!payloadComplete(dir, manifest)) {
                broken += dir.name
                continue
            }
            val state = readState(dir)
            packs += InstalledPack(manifest = manifest, enabled = state.enabled, installedAt = state.installedAt)
        }
        ScanResult(packs = packs, skippedIds = skipped, brokenIds = broken)
    }

    /** [scan] 的已安装子集（大多数调用方只关心这个）。 */
    suspend fun list(): List<InstalledPack> = scan().packs

    /**
     * 已安装包的占位列表：BudgetGuard 的唯一输入口径（计划 §3.4 要求 installedBytes 与预算同源）。
     *
     * 只统计 manifest 合法的已安装包；[ScanResult.brokenIds] 里那些"文件不完整"的包不算 ——
     * 它们不可用，UI 会提示重新下载，重新下载成功后自然回到这份列表里。
     */
    suspend fun usages(): List<PackUsage> = scan().packs.map { pack ->
        PackUsage(
            id = pack.manifest.id,
            displayName = ModelPackCopy.packTitle(pack.manifest.kind),
            bytes = pack.manifest.sizeBytes,
        )
    }

    /**
     * 已安装包总字节（Σ manifest.sizeBytes，与 [usages] 同一份 scan）。
     *
     * 与界面"已装可选包 X MB"同口径；不要拿 [com.studykit.util.backup.StorageStats] 的数字来比，
     * 那是按目录实际字节递归统计的，两套算法放一起必然对不上（计划 §3.4 末条）。
     */
    suspend fun installedBytes(): Long = scan().packs.sumOf { it.manifest.sizeBytes }

    /**
     * 把下载好的 staging 提交成正式安装。
     *
     * 步骤：id 一致性 → staging 存在 → 逐文件 sha256 复核（下载器已经验过一次，这里是**安装前**的
     * 最后一道，防止下载与 commit 之间被篡改）→ 写 manifest/state → 原子 rename。
     *
     * 任何一步失败都不会留下正式目录；staging 会被删掉（半包留着只会让下次"重试"读到旧垃圾）。
     *
     * @return 安装后的 [InstalledPack]（enabled 默认 true：装上就是启用）。
     */
    suspend fun commit(id: String, manifest: PackManifest): InstalledPack = withContext(Dispatchers.IO) {
        if (id != manifest.id) {
            throw ModelPackStoreException("commit id=$id 与 manifest.id=${manifest.id} 不一致，拒绝安装（目录名必须来自清单）")
        }
        val staging = stagingDirOf(id)
        val target = dirOf(id)
        if (!staging.isDirectory) {
            throw ModelPackStoreException("staging 目录不存在：${staging.path}（先下载再 commit）")
        }
        verifyPayload(staging, manifest)?.let { reason ->
            staging.deleteRecursively()
            throw ModelPackStoreException("安装前校验失败，已删 staging：$reason")
        }

        val installedAt = now()
        File(staging, MANIFEST_FILE).writeText(manifest.toJson(), Charsets.UTF_8)
        File(staging, STATE_FILE).writeText(
            JSONObject().put("enabled", true).put("installedAt", installedAt).toString(),
            Charsets.UTF_8,
        )

        val backup = File(root, "$id$BACKUP_SUFFIX")
        backup.deleteRecursively()
        val hadOld = target.exists()
        if (hadOld && !target.renameTo(backup)) {
            staging.deleteRecursively()
            throw ModelPackStoreException("替换旧目录失败（rename），已删除 staging：${target.path}")
        }
        if (!staging.renameTo(target)) {
            if (hadOld) backup.renameTo(target) // 尽量把旧安装改回去
            staging.deleteRecursively()
            throw ModelPackStoreException("staging rename 到正式目录失败，已回滚：${target.path}")
        }
        if (hadOld) backup.deleteRecursively()

        InstalledPack(manifest = manifest, enabled = true, installedAt = installedAt)
    }

    /**
     * 逐文件 sha256 校验一个已安装包（比 [scan] 贵得多，只在用户要求"校验"或排障时调）。
     *
     * manifest 本身读不出来时返回 [VerifyResult.Broken]（missing 里给 manifest.json），
     * 而不是抛 —— 坏包是数据状态，调用方只负责显示。
     */
    suspend fun verify(id: String): VerifyResult = withContext(Dispatchers.IO) {
        val dir = dirOf(id)
        val manifest = readManifest(dir)
        if (manifest == null || manifest.id != id) {
            return@withContext VerifyResult.Broken(missing = listOf(MANIFEST_FILE), mismatched = emptyList())
        }
        val payload = File(dir, PAYLOAD_DIR)
        val missing = ArrayList<String>()
        val mismatched = ArrayList<String>()
        for (file in manifest.files) {
            val target = resolvePayloadFile(payload, file.path)
            when {
                target == null || !target.isFile -> missing += file.path
                target.length() != file.bytes -> mismatched += file.path
                !sha256Of(target).equals(file.sha256, ignoreCase = true) -> mismatched += file.path
            }
        }
        if (missing.isEmpty() && mismatched.isEmpty()) {
            VerifyResult.Ok
        } else {
            VerifyResult.Broken(missing = missing, mismatched = mismatched)
        }
    }

    /**
     * 启用/停用（写 `state.json`）。
     *
     * 包没装时抛 [ModelPackStoreException] 而不是静默 no-op：静默成功会让界面以为写进去了，
     * 下一帧又从盘上读回旧值 —— 这种"点了没反应"比报错难查得多。
     */
    suspend fun setEnabled(id: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val dir = dirOf(id)
        val manifest = readManifest(dir)
        if (manifest == null || manifest.id != id) {
            throw ModelPackStoreException("包未安装，不能改启用状态：$id")
        }
        val state = readState(dir)
        File(dir, STATE_FILE).writeText(
            JSONObject().put("enabled", enabled).put("installedAt", state.installedAt).toString(),
            Charsets.UTF_8,
        )
    }

    /**
     * 删除一个包（递归删正式目录 + 顺手清掉同名 staging），返回释放的 payload 字节。
     *
     * 只删包目录，不碰 base/数据库/错题图片；返回的字节只统计 payload（manifest/state 那几百字节
     * 没有展示价值，且会让"已装可选包"和删除后释放量对不上）。
     */
    suspend fun delete(id: String): Long = withContext(Dispatchers.IO) {
        val dir = dirOf(id)
        val staging = stagingDirOf(id)
        val freed = payloadBytes(dir)
        dir.deleteRecursively()
        staging.deleteRecursively()
        freed
    }

    /**
     * 清理崩溃/取消残留的 `*.partial` 目录，返回清理个数。
     *
     * 下载 v1 是前台下载（切后台即取消，计划 §3.10），残留只可能来自进程被杀；
     * 在打开包管理界面时清一次即可，不值得为它引入 WorkManager。
     */
    suspend fun cleanupPartials(): Int = withContext(Dispatchers.IO) {
        val dirs = root.listFiles()?.filter { it.isDirectory && it.name.endsWith(PARTIAL_SUFFIX) }.orEmpty()
        var cleaned = 0
        for (dir in dirs) {
            if (dir.deleteRecursively()) cleaned++
        }
        cleaned
    }

    // ── 内部：扫描/校验的公共件 ─────────────────────────────────────────

    private fun readManifest(dir: File): PackManifest? {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_MANIFEST_BYTES) return null
        return runCatching { PackManifest.fromJson(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun readState(dir: File): PackPersistedState {
        val raw = runCatching {
            File(dir, STATE_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()
        val json = raw?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
            ?: return PackPersistedState(enabled = true, installedAt = dir.lastModified())
        return PackPersistedState(
            enabled = json.optBoolean("enabled", true),
            installedAt = if (json.has("installedAt") && !json.isNull("installedAt")) {
                json.optLong("installedAt", dir.lastModified())
            } else {
                dir.lastModified()
            },
        )
    }

    /** 只查存在与长度（廉价）；完整 sha256 见 [verify]。 */
    private fun payloadComplete(dir: File, manifest: PackManifest): Boolean {
        val payload = File(dir, PAYLOAD_DIR)
        return manifest.files.all { file ->
            val target = resolvePayloadFile(payload, file.path)
            target != null && target.isFile && target.length() == file.bytes
        }
    }

    /** 安装前/下载后的完整校验；返回一句人话原因，通过时返回 null（实现与下载器共用同一份口径）。 */
    private fun verifyPayload(installDir: File, manifest: PackManifest): String? =
        verifyPayloadFiles(installDir, manifest)

    /**
     * 把 manifest 里的相对路径解析成 payload 下的真实文件；路径不合法或越界时返回 null。
     *
     * 这里是第二道目录穿越防线（第一道在 manifest 解析）：即便有人绕过 fromJson 直接构造
     * [PackManifest]，也不能让 [commit]/[verify] 写到 payload 之外去。
     */
    private fun resolvePayloadFile(payload: File, relativePath: String): File? =
        resolvePayloadFileUnder(payload, relativePath)

    private fun payloadBytes(installDir: File): Long {
        val payload = File(installDir, PAYLOAD_DIR)
        if (!payload.isDirectory) return 0L
        return payload.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun requireSafeId(id: String) {
        if (!PackIdPattern.matches(id)) {
            throw ModelPackStoreException("非法包 id：$id（要求 [a-z0-9-]{3,40}，不允许 /、..、盘符）")
        }
    }

    private fun assertUnderRoot(file: File) {
        val rootPath = root.absoluteFile.normalize().path
        val targetPath = file.absoluteFile.normalize().path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw ModelPackStoreException("路径越界：$targetPath 不在 $rootPath 下")
        }
    }

    companion object {
        /** 正式目录里的清单文件名。 */
        const val MANIFEST_FILE = "manifest.json"

        /** 正式目录里的启用状态文件名。 */
        const val STATE_FILE = "state.json"

        /** payload 子目录名：manifest.files[].path 的相对根。 */
        const val PAYLOAD_DIR = "payload"

        /** staging 后缀：`<id>.partial`。 */
        const val PARTIAL_SUFFIX = ".partial"

        /** 旧目录回滚位的后缀：`<id>.old`（只在 commit 的替换窗口里短暂存在）。 */
        private const val BACKUP_SUFFIX = ".old"

        /** manifest 体积上限（1MB）：正常的清单是几 KB，超大文件只可能是损坏或恶意数据。 */
        private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    }
}

/**
 * 把 manifest 里的相对路径解析成 payload 根下的真实文件；路径不合法或越界时返回 null。
 *
 * 下载器与 store 共用这一份：目录穿越的判定只能有一个口径，两份拷贝迟早会分叉。
 * 第一道防线在 [PackManifest.fromJson]（解析时拒），这里是第二道（写盘前再规范化断言一次）。
 */
internal fun resolvePayloadFileUnder(payloadDir: File, relativePath: String): File? {
    if (PackManifest.validatePackFilePath(relativePath) != null) return null
    val base = payloadDir.absoluteFile.normalize()
    val target = File(base, relativePath).absoluteFile.normalize()
    val basePath = base.path
    return if (target.path == basePath || target.path.startsWith(basePath + File.separator)) target else null
}

/**
 * 逐文件校验 payload：存在、长度、sha256 三项全对才算通过；返回一句人话原因，通过时 null。
 *
 * 下载器（下载后）与 store（安装前）各调一次，**同一份实现**：否则下载侧用 A 算法、安装侧用
 * B 算法时，会出现"下载说通过、安装说失败"的假阳性。
 */
internal fun verifyPayloadFiles(installDir: File, manifest: PackManifest): String? {
    val payload = File(installDir, ModelPackStore.PAYLOAD_DIR)
    val missing = ArrayList<String>()
    val mismatched = ArrayList<String>()
    for (file in manifest.files) {
        val target = resolvePayloadFileUnder(payload, file.path)
        when {
            target == null || !target.isFile -> missing += file.path
            target.length() != file.bytes -> mismatched += file.path
            !sha256Of(target).equals(file.sha256, ignoreCase = true) -> mismatched += file.path
        }
    }
    return when {
        missing.isNotEmpty() -> "缺文件：${missing.joinToString("、")}"
        mismatched.isNotEmpty() -> "sha256/长度不符：${mismatched.joinToString("、")}"
        else -> null
    }
}

/** sha256 十六进制字符表（避免逐字节 String.format，433MB 的包会差出几秒）。 */
private val HexDigits = "0123456789abcdef".toCharArray()

/**
 * 流式算 sha256（8KB buffer），返回小写 64 位十六进制。
 *
 * 与 [PackManifest] 的 sha256 字段同口径；下载器与 store 共用这一份实现，
 * 避免"下载时用 A 算法、安装时用 B 算法"的假阳性。
 */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    val bytes = digest.digest()
    val hex = CharArray(bytes.size * 2)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xFF
        hex[index * 2] = HexDigits[value ushr 4]
        hex[index * 2 + 1] = HexDigits[value and 0x0F]
    }
    return String(hex)
}
