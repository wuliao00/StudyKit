package com.studykit.modelpack

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ModelPackStore] 的文件行为（计划 T2）：装 / 验 / 删 / 启停 / 字节统计 / 坏包降级 / 目录穿越。
 *
 * 全部是纯 JVM（TemporaryFolder，不碰 Android）—— store 是"文件即真相"的那一层，
 * 它不需要 Context，单测也就不该为它起 Robolectric。
 */
class ModelPackStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: ModelPackStore

    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = tmp.newFolder("model-packs")
        store = ModelPackStore(root) { fixedNow }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 直接构造 manifest（store 测试不重复走 fromJson 校验；那条由 PackManifestTest 管）。 */
    private fun manifestOf(id: String, files: Map<String, ByteArray>): PackManifest {
        val packFiles = files.map { (path, bytes) ->
            PackFile(path = path, bytes = bytes.size.toLong(), sha256 = sha256(bytes))
        }
        return PackManifest(
            schema = 1,
            id = id,
            kind = PackKind.VOICE_TTS,
            displayName = "语音包（朗读）",
            version = "1.0.0",
            minApp = null,
            sizeBytes = packFiles.sumOf { it.bytes },
            downloadUrl = "https://example.invalid/pack.zip",
            files = packFiles,
        )
    }

    /** 模拟下载器：把文件写进 staging/payload，返回清单；**不** commit。 */
    private fun writeStaging(id: String, files: Map<String, ByteArray>): PackManifest {
        val staging = store.stagingDirOf(id)
        val payload = File(staging, ModelPackStore.PAYLOAD_DIR).apply { mkdirs() }
        files.forEach { (path, bytes) ->
            File(payload, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        return manifestOf(id, files)
    }

    private fun install(id: String, files: Map<String, ByteArray>): InstalledPack = runBlocking {
        store.commit(id, writeStaging(id, files))
    }

    private val twoFiles = mapOf(
        "model.bin" to ByteArray(256) { it.toByte() },
        "tokens.txt" to "hello-tokens".toByteArray(),
    )

    @Test
    fun `空根目录list为空且不抛`() = runBlocking {
        val scan = store.scan()
        assertTrue(scan.packs.isEmpty())
        assertTrue(scan.skippedIds.isEmpty())
        assertTrue(scan.brokenIds.isEmpty())
        assertEquals(0L, store.installedBytes())
        assertTrue(store.usages().isEmpty())
    }

    @Test
    fun `commit 之后 list 与 installedBytes 正确`() = runBlocking {
        val expected = install("voice-tts", twoFiles)

        val packs = store.list()
        assertEquals(1, packs.size)
        val pack = packs.single()
        assertEquals(expected.manifest, pack.manifest)
        assertTrue("装上默认启用", pack.enabled)
        assertEquals(fixedNow, pack.installedAt)
        assertEquals(expected.manifest.sizeBytes, store.installedBytes())

        val usage = store.usages().single()
        assertEquals("voice-tts", usage.id)
        assertEquals(ModelPackCopy.packTitle(PackKind.VOICE_TTS), usage.displayName)
        assertEquals(expected.manifest.sizeBytes, usage.bytes)

        // 正式目录里 manifest/state/payload 三件都在
        val dir = store.dirOf("voice-tts")
        assertTrue(File(dir, ModelPackStore.MANIFEST_FILE).isFile)
        assertTrue(File(dir, ModelPackStore.STATE_FILE).isFile)
        assertEquals("hello-tokens", File(File(dir, ModelPackStore.PAYLOAD_DIR), "tokens.txt").readText())
        assertFalse("commit 后 staging 不该留下", store.stagingDirOf("voice-tts").exists())
    }

    @Test
    fun `staging 校验失败时 commit 抛错且不落正式目录`() = runBlocking {
        val manifest = writeStaging("voice-tts", twoFiles)
        // 篡改 staging 里一个文件（长度变、sha 也不对）
        File(File(store.stagingDirOf("voice-tts"), ModelPackStore.PAYLOAD_DIR), "model.bin")
            .writeBytes(ByteArray(257))
        assertThrows(ModelPackStoreException::class.java) { runBlocking { store.commit("voice-tts", manifest) } }

        assertFalse("校验失败不许出现正式目录", store.dirOf("voice-tts").exists())
        assertFalse("失败的 staging 要清掉（半包留着只会让下次读到垃圾）", store.stagingDirOf("voice-tts").exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `commit 的 id 与 manifest 不一致时拒绝`() = runBlocking {
        val manifest = writeStaging("voice-tts", twoFiles)
        assertThrows(ModelPackStoreException::class.java) {
            runBlocking { store.commit("voice-asr", manifest) }
        }
        assertFalse(store.dirOf("voice-asr").exists())
    }

    @Test
    fun `verify 全对时 Ok`() = runBlocking {
        install("voice-tts", twoFiles)
        assertEquals(VerifyResult.Ok, store.verify("voice-tts"))
    }

    @Test
    fun `verify 篡改一字节报 mismatched`() = runBlocking {
        install("voice-tts", twoFiles)
        val target = File(File(store.dirOf("voice-tts"), ModelPackStore.PAYLOAD_DIR), "model.bin")
        val bytes = target.readBytes()
        bytes[0] = (bytes[0] + 1).toByte()
        target.writeBytes(bytes)

        val result = store.verify("voice-tts") as VerifyResult.Broken
        assertEquals(listOf("model.bin"), result.mismatched)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun `verify 缺文件报 missing`() = runBlocking {
        install("voice-tts", twoFiles)
        assertTrue(File(File(store.dirOf("voice-tts"), ModelPackStore.PAYLOAD_DIR), "tokens.txt").delete())
        val result = store.verify("voice-tts") as VerifyResult.Broken
        assertEquals(listOf("tokens.txt"), result.missing)
    }

    @Test
    fun `delete 递归删目录并返回释放字节`() = runBlocking {
        val installed = install("voice-tts", twoFiles)
        val freed = store.delete("voice-tts")
        assertEquals(installed.manifest.sizeBytes, freed)
        assertFalse(store.dirOf("voice-tts").exists())
        assertTrue(store.list().isEmpty())
        assertEquals(0L, store.delete("voice-tts"))
    }

    @Test
    fun `setEnabled 持久化到 state_json`() = runBlocking {
        install("voice-tts", twoFiles)
        store.setEnabled("voice-tts", enabled = false)
        assertFalse(store.list().single().enabled)

        // 换一个 store 实例从盘上读：证明写的是文件而不是内存
        val fresh = ModelPackStore(root) { fixedNow + 1 }
        assertFalse(fresh.list().single().enabled)

        store.setEnabled("voice-tts", enabled = true)
        assertTrue(ModelPackStore(root).list().single().enabled)
    }

    @Test
    fun `state_json 缺失时默认启用`() = runBlocking {
        install("voice-tts", twoFiles)
        assertTrue(File(store.dirOf("voice-tts"), ModelPackStore.STATE_FILE).delete())
        assertTrue(store.list().single().enabled)
    }

    @Test
    fun `setEnabled 对未安装包抛错`() {
        assertThrows(ModelPackStoreException::class.java) {
            runBlocking { store.setEnabled("voice-tts", enabled = true) }
        }
    }

    @Test
    fun `坏 manifest 目录被跳过不抛`() = runBlocking {
        val broken = File(root, "voice-asr").apply { mkdirs() }
        File(broken, ModelPackStore.MANIFEST_FILE).writeText("{broken json")
        File(root, "not-a-pack").mkdirs() // 目录名也不是合法 id
        File(root, "README.txt").writeText("普通文件，不是包目录")

        val scan = store.scan()
        assertTrue(scan.packs.isEmpty())
        assertEquals(listOf("not-a-pack", "voice-asr"), scan.skippedIds.sorted())
        assertTrue("坏清单不许把界面打崩", store.list().isEmpty())
    }

    @Test
    fun `payload 缺件的目录计入 broken 而不是已安装`() = runBlocking {
        install("voice-tts", twoFiles)
        assertTrue(File(File(store.dirOf("voice-tts"), ModelPackStore.PAYLOAD_DIR), "tokens.txt").delete())

        val scan = store.scan()
        assertTrue(scan.packs.isEmpty())
        assertEquals(listOf("voice-tts"), scan.brokenIds)
        assertEquals("损坏的包不许进预算口径", 0L, store.installedBytes())
    }

    @Test
    fun `只有 staging 的半包不算已安装且可被清理`() = runBlocking {
        writeStaging("voice-tts", twoFiles)
        assertTrue("staging 不是正式安装", store.list().isEmpty())
        assertEquals(1, store.cleanupPartials())
        assertFalse(store.stagingDirOf("voice-tts").exists())
    }

    @Test
    fun `cleanupPartials 只清 partial 后缀目录`() = runBlocking {
        File(root, "voice-tts.partial").mkdirs()
        File(root, "voice-asr.partial").mkdirs()
        File(root, "embed-zh-small-int8").mkdirs()

        assertEquals(2, store.cleanupPartials())
        assertFalse(File(root, "voice-tts.partial").exists())
        assertFalse(File(root, "voice-asr.partial").exists())
        assertTrue(File(root, "embed-zh-small-int8").exists())
        assertEquals(0, store.cleanupPartials())
    }

    @Test
    fun `非法 id 直接抛目录穿越防护`() {
        // dirOf/stagingDirOf 是纯函数（不 suspend），不需要 runBlocking
        assertThrows(ModelPackStoreException::class.java) { store.dirOf("..") }
        assertThrows(ModelPackStoreException::class.java) { store.dirOf("../voice-tts") }
        assertThrows(ModelPackStoreException::class.java) { store.dirOf("/tmp/x") }
        assertThrows(ModelPackStoreException::class.java) { store.dirOf("Voice-TTS") }
        assertThrows(ModelPackStoreException::class.java) { store.stagingDirOf("a/b") }
    }

    @Test
    fun `重装替换旧目录且不留 old 回滚位`() = runBlocking {
        install("voice-tts", mapOf("model.bin" to ByteArray(16) { 1 }))
        install("voice-tts", mapOf("model.bin" to ByteArray(32) { 2 }, "tokens.txt" to "t".toByteArray()))

        val packs = store.list()
        assertEquals(1, packs.size)
        assertEquals(32L + 1L, packs.single().manifest.sizeBytes)
        assertFalse("替换完成后旧目录回滚位必须删掉", File(root, "voice-tts.old").exists())
        assertTrue(File(File(store.dirOf("voice-tts"), ModelPackStore.PAYLOAD_DIR), "tokens.txt").isFile)
    }

    @Test
    fun `直接构造的 manifest 里 path 越界时 commit 拒绝`() = runBlocking {
        // 绕过 fromJson 的路径校验：store 自己必须有第二道防线
        val staging = store.stagingDirOf("voice-tts")
        val payload = File(staging, ModelPackStore.PAYLOAD_DIR).apply { mkdirs() }
        val manifest = PackManifest(
            schema = 1,
            id = "voice-tts",
            kind = PackKind.VOICE_TTS,
            displayName = "x",
            version = "1.0.0",
            minApp = null,
            sizeBytes = 4L,
            downloadUrl = null,
            files = listOf(PackFile("../escape.bin", 4L, sha256(ByteArray(4)))),
        )
        File(staging, "escape.bin").writeBytes(ByteArray(4))
        assertThrows(ModelPackStoreException::class.java) {
            runBlocking { store.commit("voice-tts", manifest) }
        }
        assertFalse(store.dirOf("voice-tts").exists())
        assertNull("越界文件不许出现在 root 下一层", root.listFiles()?.firstOrNull { it.name == "escape.bin" })
    }

    @Test
    fun `installedAt 取注入的时间源`() = runBlocking {
        install("voice-tts", twoFiles)
        val state = JSONObject(File(store.dirOf("voice-tts"), ModelPackStore.STATE_FILE).readText())
        assertEquals(fixedNow, state.getLong("installedAt"))
        assertEquals(true, state.getBoolean("enabled"))
    }
}
