package com.studykit.modelpack

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PackManifest] 的解析/校验/往返 —— 计划 T1 的验收：每种坏法都有明确异常与用例。
 *
 * 这些规则不是"洁癖"：manifest 是**下载来的**数据，schema 决定 ① 盘上目录能不能被认领
 * （id/kind），② 安装时文件该写到哪（path），③ 下载有没有被掉包（sha256），
 * ④ 预算算得对不对（sizeBytes 必须等于明细合计，否则 600MB 守卫会被一个手填数字绕过）。
 */
class PackManifestTest {

    private val sha = "a".repeat(64)

    /** 一份最小的合法清单；各用例通过 [mutable] 只改坏一个字段。 */
    private fun baseJson(mutable: (JSONObject) -> Unit = {}): String {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("id", "voice-tts")
        root.put("kind", "VOICE_TTS")
        root.put("displayName", "语音包（朗读）")
        root.put("version", "1.0.0")
        root.put("minApp", "3.0.1")
        root.put("sizeBytes", 1024L)
        root.put("downloadUrl", "https://example.invalid/voice-tts.zip")
        root.put("files", JSONArray().put(fileJson("model.bin", 1024L, sha)))
        mutable(root)
        return root.toString()
    }

    private fun fileJson(path: String, bytes: Long, sha256: String): JSONObject = JSONObject().apply {
        put("path", path)
        put("bytes", bytes)
        put("sha256", sha256)
    }

    private fun rejectedBy(json: String, expectedFragment: String) {
        val error = assertThrows(PackManifestException::class.java) { PackManifest.fromJson(json) }
        assertTrue(
            "报错应含「$expectedFragment」，实际：${error.message}",
            error.message.orEmpty().contains(expectedFragment),
        )
    }

    @Test
    fun `往返序列化保留全部字段`() {
        val manifest = PackManifest.fromJson(baseJson())
        assertEquals(1, manifest.schema)
        assertEquals("voice-tts", manifest.id)
        assertEquals(PackKind.VOICE_TTS, manifest.kind)
        assertEquals("语音包（朗读）", manifest.displayName)
        assertEquals("1.0.0", manifest.version)
        assertEquals("3.0.1", manifest.minApp)
        assertEquals(1024L, manifest.sizeBytes)
        assertEquals("https://example.invalid/voice-tts.zip", manifest.downloadUrl)
        assertEquals(listOf(PackFile("model.bin", 1024L, sha)), manifest.files)

        val roundTrip = PackManifest.fromJson(manifest.toJson())
        assertEquals("toJson → fromJson 必须一个字段不丢", manifest, roundTrip)
    }

    @Test
    fun `缺 minApp 视为不限制`() {
        val root = JSONObject(baseJson())
        root.remove("minApp")
        val manifest = PackManifest.fromJson(root.toString())
        assertNull(manifest.minApp)
    }

    @Test
    fun `downloadUrl 为空表示未上架`() {
        val manifest = PackManifest.fromJson(baseJson { it.put("downloadUrl", JSONObject.NULL) })
        assertNull(manifest.downloadUrl)
    }

    @Test
    fun `未知键忽略前向兼容`() {
        val manifest = PackManifest.fromJson(
            baseJson {
                it.put("futureField", "ignored")
                it.put("futureObject", JSONObject().put("a", 1))
                it.put("unknownArray", JSONArray().put(1).put(2))
            },
        )
        assertEquals("voice-tts", manifest.id)
    }

    @Test
    fun `schema 不是一就拒`() {
        rejectedBy(baseJson { it.put("schema", 2) }, "schema 只认 1")
        rejectedBy(baseJson { it.remove("schema") }, "缺字段 schema")
    }

    @Test
    fun `id 不合法就拒`() {
        rejectedBy(baseJson { it.put("id", "Voice-TTS") }, "id 不合法")
        rejectedBy(baseJson { it.put("id", "../evil") }, "id 不合法")
        rejectedBy(baseJson { it.put("id", "ab") }, "id 不合法")
    }

    @Test
    fun `未知 kind 就拒`() {
        rejectedBy(baseJson { it.put("kind", "VOICE_UNKNOWN") }, "未知 kind")
    }

    @Test
    fun `sha256 不是六十四位十六进制就拒`() {
        rejectedBy(
            baseJson { root ->
                root.put("files", JSONArray().put(fileJson("model.bin", 1024L, "abc")))
            },
            "不是 64 位十六进制",
        )
    }

    @Test
    fun `path 含点点就拒`() {
        rejectedBy(
            baseJson { root ->
                root.put("files", JSONArray().put(fileJson("../databases/studykit.db", 1024L, sha)))
            },
            "不能含 ..",
        )
    }

    @Test
    fun `path 是绝对路径或含盘符就拒`() {
        rejectedBy(
            baseJson { root ->
                root.put("files", JSONArray().put(fileJson("/etc/passwd", 1024L, sha)))
            },
            "不能是绝对路径",
        )
        rejectedBy(
            baseJson { root ->
                root.put("files", JSONArray().put(fileJson("C:/windows/x.bin", 1024L, sha)))
            },
            "不能含冒号",
        )
        rejectedBy(
            baseJson { root ->
                root.put("files", JSONArray().put(fileJson("a\\b.bin", 1024L, sha)))
            },
            "不能含反斜杠",
        )
    }

    @Test
    fun `files 为空就拒`() {
        rejectedBy(baseJson { it.put("files", JSONArray()) }, "files 不能为空")
        rejectedBy(baseJson { it.remove("files") }, "缺 files")
    }

    @Test
    fun `bytes 不大于零就拒`() {
        rejectedBy(
            baseJson { root ->
                root.put("files", JSONArray().put(fileJson("model.bin", 0L, sha)))
            },
            "bytes 必须 > 0",
        )
    }

    @Test
    fun `path 重复就拒`() {
        rejectedBy(
            baseJson { root ->
                root.put(
                    "files",
                    JSONArray()
                        .put(fileJson("model.bin", 512L, sha))
                        .put(fileJson("model.bin", 512L, sha)),
                )
            },
            "path 重复",
        )
    }

    @Test
    fun `sizeBytes 与明细合计不一致就拒`() {
        rejectedBy(baseJson { it.put("sizeBytes", 999L) }, "与明细合计")
    }

    @Test
    fun `downloadUrl 不是 https 就拒`() {
        rejectedBy(baseJson { it.put("downloadUrl", "http://example.invalid/x.zip") }, "必须 https://")
    }

    @Test
    fun `缺必填字段就拒`() {
        rejectedBy(baseJson { it.remove("kind") }, "缺字段 kind")
        rejectedBy(baseJson { it.remove("displayName") }, "缺字段 displayName")
        rejectedBy(baseJson { it.remove("version") }, "缺字段 version")
        rejectedBy(baseJson { it.remove("sizeBytes") }, "缺字段 sizeBytes")
    }

    @Test
    fun `不是合法 JSON 就拒`() {
        rejectedBy("{not json", "不是合法 JSON")
    }

    @Test
    fun `注册表六条 id 与 PackIds 一致且全部未上架`() {
        assertEquals(6, PackRegistry.all.size)
        assertEquals(PackIds.ALL, PackRegistry.all.map { it.id })
        assertEquals(6, PackIds.ALL.distinct().size)
        PackRegistry.all.forEach { seed ->
            assertEquals("本轮六个包都必须未上架", false, seed.downloadable)
            assertNull("未上架种子不许带 manifest", seed.manifest)
            assertTrue("显示名不能为空：${seed.id}", seed.displayName.isNotBlank())
            assertEquals(seed.id, PackRegistry.byId(seed.id)?.id)
        }
        assertNull(PackRegistry.byId("not-a-pack"))
    }

    @Test
    fun `种子 id 与 manifest id 不一致时构造失败`() {
        val manifest = PackManifest.fromJson(baseJson())
        assertThrows(IllegalArgumentException::class.java) {
            PackSeed(id = "voice-asr", kind = PackKind.VOICE_ASR, manifest = manifest)
        }
    }
}
