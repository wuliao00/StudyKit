package com.studykit.modelpack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ModelPackViewModel] 的编排守卫（计划 T7）。
 *
 * 走 Robolectric 只为拿一个真实的 [Application]（AndroidViewModel 的构造参数）；
 * store 用 TemporaryFolder 上的实例、下载器用假实现 —— 全程不碰网络、不碰 Room。
 * 单测直接调 *Now 的 suspend 版本（不用 viewModelScope/Dispatchers.Main，理由同 CalibrateViewModel）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelPackViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var root: File
    private lateinit var store: ModelPackStore

    private val fixedNow = 42L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        root = tmp.newFolder("model-packs")
        store = ModelPackStore(root) { fixedNow }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun manifestOf(
        id: String = "voice-tts",
        files: Map<String, ByteArray> = mapOf("model.bin" to ByteArray(64) { it.toByte() }),
        url: String? = "https://example.invalid/pack.zip",
        bytesOverride: Long? = null,
    ): PackManifest {
        val packFiles = files.map { (path, bytes) ->
            PackFile(path = path, bytes = bytes.size.toLong(), sha256 = sha256(bytes))
        }
        val size = bytesOverride ?: packFiles.sumOf { it.bytes }
        return PackManifest(
            schema = 1,
            id = id,
            kind = PackKind.VOICE_TTS,
            displayName = "语音包（朗读）",
            version = "1.0.0",
            minApp = null,
            sizeBytes = size,
            downloadUrl = url,
            files = packFiles,
        )
    }

    private fun seedOf(manifest: PackManifest): PackSeed = PackSeed(manifest.id, manifest.kind, manifest)

    /** 绕过下载器直接摆一个"已安装"目录（正常小文件）。 */
    private fun writeInstalledDir(manifest: PackManifest, enabled: Boolean = true) {
        val dir = store.dirOf(manifest.id).apply { mkdirs() }
        val payload = File(dir, ModelPackStore.PAYLOAD_DIR).apply { mkdirs() }
        manifest.files.forEach { file ->
            File(payload, file.path).apply { parentFile?.mkdirs() }
                .writeBytes(ByteArray(file.bytes.toInt()) { 1 })
        }
        File(dir, ModelPackStore.MANIFEST_FILE).writeText(manifest.toJson())
        File(dir, ModelPackStore.STATE_FILE).writeText(
            JSONObject().put("enabled", enabled).put("installedAt", fixedNow).toString(),
        )
    }

    private fun newVm(
        registry: List<PackSeed> = PackRegistry.all,
        downloader: PackDownloader = PackDownloader(),
    ): ModelPackViewModel = ModelPackViewModel(
        app = app,
        store = store,
        downloader = downloader,
        registry = registry,
        log = { _, _ -> },
    )

    /** 假下载器：记录被调次数；默认行为是"不该被调用"。 */
    private class RecordingDownloader(
        private val behavior: suspend (PackManifest, File, (Float) -> Unit, () -> Boolean) -> DownloadResult =
            { _, _, _, _ -> error("这个用例不该真的下载") },
    ) : PackDownloader() {
        var calls = 0

        override suspend fun download(
            manifest: PackManifest,
            stagingDir: File,
            onProgress: (Float) -> Unit,
            isCancelled: () -> Boolean,
        ): DownloadResult {
            calls++
            return behavior(manifest, stagingDir, onProgress, isCancelled)
        }
    }

    private fun successDownloader(files: Map<String, ByteArray>): RecordingDownloader =
        RecordingDownloader { _, stagingDir, onProgress, _ ->
            val payload = File(stagingDir, ModelPackStore.PAYLOAD_DIR).apply { mkdirs() }
            files.forEach { (path, bytes) ->
                File(payload, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
            onProgress(1f)
            DownloadResult.Success(stagingDir)
        }

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `首次刷新六个包全是未上架占位且总账为零`() = runBlocking {
        val vm = newVm()
        vm.refreshNow()

        val state = vm.state.value
        assertEquals(6, state.rows.size)
        assertEquals(PackIds.ALL, state.rows.map { it.id })
        state.rows.forEach { row ->
            assertEquals("本轮六个包都还没上架：${row.id}", PackRowStatus.Planned, row.status)
        }
        assertEquals(0L, state.installedBytes)
        assertEquals(ModelPackCopy.budgetSummary(0L, BudgetGuard.DEFAULT_BUDGET_BYTES), state.summary)
    }

    @Test
    fun `未上架包下载被拒且不发网络请求`() = runBlocking {
        val downloader = RecordingDownloader()
        val vm = newVm(downloader = downloader)

        assertFalse(vm.downloadNow(PackIds.VOICE_TTS))

        assertEquals(0, downloader.calls)
        assertEquals(ModelPackCopy.messageNotPublished(), vm.state.value.message)
        assertNull(vm.state.value.budgetReject)
    }

    @Test
    fun `下载成功进已装并刷新总账与提示`() = runBlocking {
        val files = mapOf(
            "model.bin" to ByteArray(64) { it.toByte() },
            "tokens.txt" to "tokens".toByteArray(),
        )
        val manifest = manifestOf(files = files)
        val seed = seedOf(manifest)
        val vm = newVm(registry = listOf(seed), downloader = successDownloader(files))

        assertTrue(vm.downloadNow(seed.id))

        val state = vm.state.value
        assertEquals(PackRowStatus.Installed(enabled = true), state.rows.single().status)
        assertEquals(manifest.sizeBytes, state.installedBytes)
        assertEquals(ModelPackCopy.messageInstalled(seed.displayName), state.message)
        assertEquals("正式目录已落盘", 1, store.list().size)
        assertFalse("commit 后不许留 staging", store.stagingDirOf(seed.id).exists())
    }

    @Test
    fun `预算拒装时弹层挡住下载且不发起下载`() = runBlocking {
        // 无已装包 + 650MB 的包：超过 600MB 预算 → 拒装且"卸哪个都不够"（没有可建议的已装包）
        val incomingManifest = manifestOf(
            id = "voice-asr",
            files = mapOf("model.bin" to ByteArray(16)),
            bytesOverride = 650L * 1024 * 1024,
        )
        val incomingSeed = PackSeed(incomingManifest.id, PackKind.VOICE_ASR, incomingManifest)
        val downloader = RecordingDownloader()
        val vm = newVm(registry = listOf(incomingSeed), downloader = downloader)
        vm.refreshNow()

        assertFalse(vm.downloadNow(incomingSeed.id))

        assertEquals("预算判定在下载之前，一个字节都不该下", 0, downloader.calls)
        val reject = vm.state.value.budgetReject
        assertNotNull("应弹预算拒装", reject)
        assertEquals(incomingSeed.id, reject?.incomingId)
        assertEquals("没有单个已装包可腾，弹层不该给「去卸载」目标", null, reject?.removalId)
        assertTrue(
            "正文要写出还差多少：@{reject?.body}",
            reject?.body.orEmpty().contains("还差 50 MB"),
        )
        assertEquals("被拒后行状态仍是未安装", PackRowStatus.NotInstalled, vm.state.value.rows.single().status)
    }

    @Test
    fun `删除已装包回到未安装并提示释放体积`() = runBlocking {
        val files = mapOf("model.bin" to ByteArray(64) { it.toByte() })
        val manifest = manifestOf(files = files)
        writeInstalledDir(manifest)
        val vm = newVm(registry = listOf(seedOf(manifest)))
        vm.refreshNow()
        assertTrue(vm.state.value.rows.single().status is PackRowStatus.Installed)

        val freed = vm.deleteNow(manifest.id)

        assertEquals(manifest.sizeBytes, freed)
        assertEquals(PackRowStatus.NotInstalled, vm.state.value.rows.single().status)
        assertTrue("提示里应说明释放量：${vm.state.value.message}", vm.state.value.message.orEmpty().contains("已删除"))
        assertTrue(vm.state.value.message.orEmpty().contains("释放"))
        assertTrue(store.list().isEmpty())
        assertEquals(0L, vm.state.value.installedBytes)
    }

    @Test
    fun `payload 缺件的已装目录降级为损坏行`() = runBlocking {
        val manifest = manifestOf()
        // 只写 manifest/state，不写 payload → scan 判 broken
        val dir = store.dirOf(manifest.id).apply { mkdirs() }
        File(dir, ModelPackStore.MANIFEST_FILE).writeText(manifest.toJson())
        File(dir, ModelPackStore.STATE_FILE).writeText(JSONObject().put("enabled", true).toString())

        val vm = newVm(registry = listOf(seedOf(manifest)))
        vm.refreshNow()

        assertEquals(PackRowStatus.Corrupt, vm.state.value.rows.single().status)
        assertEquals(ModelPackCopy.corrupt(), vm.state.value.rows.single().statusLine)
    }

    @Test
    fun `取消下载会清 staging 并回到未安装态`() = runBlocking {
        val files = mapOf("model.bin" to ByteArray(16))
        val manifest = manifestOf(files = files)
        val seed = seedOf(manifest)
        val started = CompletableDeferred<Unit>()
        val fake = object : PackDownloader() {
            override suspend fun download(
                manifest: PackManifest,
                stagingDir: File,
                onProgress: (Float) -> Unit,
                isCancelled: () -> Boolean,
            ): DownloadResult {
                File(stagingDir, ModelPackStore.PAYLOAD_DIR).mkdirs()
                File(stagingDir, "half.tmp").writeText("half")
                started.complete(Unit)
                while (!isCancelled()) delay(5)
                stagingDir.deleteRecursively()
                return DownloadResult.Cancelled
            }
        }
        val vm = newVm(registry = listOf(seed), downloader = fake)

        val job = launch(Dispatchers.Default) { vm.downloadNow(seed.id) }
        started.await()
        vm.cancel(seed.id)
        job.join()

        assertEquals(ModelPackCopy.messageCancelled(), vm.state.value.message)
        assertEquals(PackRowStatus.NotInstalled, vm.state.value.rows.single().status)
        assertFalse("取消后 staging 必须删干净", store.stagingDirOf(seed.id).exists())
        assertFalse(
            "root 下不许残留 .partial",
            root.listFiles().orEmpty().any { it.name.endsWith(ModelPackStore.PARTIAL_SUFFIX) },
        )
    }

    @Test
    fun `下载失败只经 message 呈现不抛给界面`() = runBlocking {
        val files = mapOf("model.bin" to ByteArray(16))
        val manifest = manifestOf(files = files)
        val seed = seedOf(manifest)
        val fake = RecordingDownloader { _, _, _, _ ->
            DownloadResult.Failed(DownloadFailure.NETWORK, "连接被掐")
        }
        val vm = newVm(registry = listOf(seed), downloader = fake)

        assertFalse(vm.downloadNow(seed.id))

        assertEquals(ModelPackCopy.downloadFailed(DownloadFailure.NETWORK), vm.state.value.message)
        assertEquals(PackRowStatus.NotInstalled, vm.state.value.rows.single().status)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `启用开关写回包目录且刷新后读数一致`() = runBlocking {
        val files = mapOf("model.bin" to ByteArray(32) { 7 })
        val manifest = manifestOf(files = files)
        writeInstalledDir(manifest)
        val vm = newVm(registry = listOf(seedOf(manifest)))
        vm.refreshNow()
        assertEquals(PackRowStatus.Installed(enabled = true), vm.state.value.rows.single().status)

        assertTrue(vm.setEnabledNow(manifest.id, enabled = false))

        assertEquals(PackRowStatus.Installed(enabled = false), vm.state.value.rows.single().status)
        assertFalse(store.list().single().enabled)
    }
}
