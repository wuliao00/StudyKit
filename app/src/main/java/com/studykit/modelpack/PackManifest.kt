package com.studykit.modelpack

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 模型包清单（schema v1）校验失败。
 *
 * 抛异常而不是返回 null：清单坏了是**实现/托管错误**，不是用户能修的状态。
 * 单测逐条钉住每种坏法的报错文案，改规则时先改测试。
 */
class PackManifestException(message: String) : Exception(message)

/** manifest 里 id 的合法形状：与目录名 / `files[].path` 的第一道目录穿越防线，见 [PackIds]。 */
internal val PackIdPattern = Regex("[a-z0-9-]{3,40}")

/** sha256 的字面形状；大小写都收，比较时忽略大小写（我们自己算出来的恒为小写）。 */
private val Sha256Pattern = Regex("[0-9a-fA-F]{64}")

/**
 * 模型包的种类（schema v1 的封闭枚举）。
 *
 * 为什么是枚举而不是自由字符串：种类决定 UI 文案（[ModelPackCopy.packTitle]）与未来运行时接线，
 * 未知种类必须被清单层直接拒绝 —— 一个拼错的 kind 在盘上看起来"装好了"，实际没有任何代码认领它。
 */
enum class PackKind {
    /** 端侧 0.6B 解释包（文档 L2173-2188；计划 §3.3 种子表）。 */
    LLM,
    /** 朗读语音包（文档 L2022-2045）。 */
    VOICE_TTS,
    /** 背诵验收语音包（文档 L2022-2045）。 */
    VOICE_ASR,
    /** PP-StructureV3 高配结构包（文档 L2218-2228）。 */
    STRUCTURE_V3,
    /** 去手写包（文档 L2218-2228）。 */
    DE_HANDWRITE,
    /** 语义包（v3.0 欠账；计划 D8 建议最先做）。 */
    EMBED,
    ;

    companion object {
        /** 反序列化用：只认枚举名，未知即拒。 */
        fun fromWire(value: String): PackKind =
            entries.firstOrNull { it.name == value }
                ?: throw PackManifestException("未知 kind：$value（只认 ${entries.joinToString("/") { it.name }}）")
    }
}

/**
 * 包里的一个文件。
 *
 * [path] 是相对 `payload/` 根的路径（目录布局见 [ModelPackStore] 的 KDoc）；
 * [bytes] 与 [sha256] 是下载校验的唯一依据 —— **文件即真相**：盘上这个文件长度/sha 与这里不符，
 * 这个包就不算装好，不存在"先信 state.json、文件坏了再说"的中间态。
 */
data class PackFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * 一个模型包的完整清单，落地在 `filesDir/model-packs/<id>/manifest.json`。
 *
 * ## 为什么是 JSON 文件而不是 Room 表
 * 计划 D2：不升 Room schema。包状态天然是"目录 + 文件"，放进数据库会多出一次迁移、一套 DAO、
 * 以及"表里说装了、盘上没有"这种第二真相源。文件化之后判定规则只有一条：
 * **目录在 + manifest 可解析 + 每个文件长度与 sha256 都对得上**才算已安装。
 *
 * ## schema v1 的硬规则（每条都有单测）
 * 1. `schema` 只认 [SCHEMA]；
 * 2. `id` 必须匹配 [PackIdPattern]；
 * 3. `kind` 必须是 [PackKind] 之一；
 * 4. `files` 非空；`path` 必须是相对路径（无 `..`、无盘符、无反斜杠、无空段）；
 *    `sha256` 64 位 hex；`bytes > 0`；`path` 不许重复；
 * 5. `sizeBytes == Σ files[].bytes`（不许手填一个和明细对不上的总数）；
 * 6. `downloadUrl` 可空（空 = 未上架），非空必须是 `https://`；
 * 7. 未知键忽略（前向兼容：加字段不打断旧版本解析）；缺 `minApp` 视为不限制。
 *
 * 数字口径出自计划 §3.3 / 附录 C，体积账的矛盾（文档 597MB 对实测 ≈669MB）见计划 §2.6 与 D4。
 */
data class PackManifest(
    val schema: Int,
    val id: String,
    val kind: PackKind,
    val displayName: String,
    val version: String,
    val minApp: String?,
    val sizeBytes: Long,
    val downloadUrl: String?,
    val files: List<PackFile>,
) {

    /** 落盘形态；与 [fromJson] 严格互逆（单测用 round-trip 钉住字段一个不丢）。 */
    fun toJson(): String = JSONObject().apply {
        put("schema", schema)
        put("id", id)
        put("kind", kind.name)
        put("displayName", displayName)
        put("version", version)
        put("minApp", minApp ?: JSONObject.NULL)
        put("sizeBytes", sizeBytes)
        put("downloadUrl", downloadUrl ?: JSONObject.NULL)
        put("files", JSONArray().apply {
            files.forEach { file ->
                put(
                    JSONObject().apply {
                        put("path", file.path)
                        put("bytes", file.bytes)
                        put("sha256", file.sha256)
                    },
                )
            }
        })
    }.toString()

    companion object {
        /** 当前唯一认识的清单版本号。升级 schema 必须同时改解析、写测试、写迁移说明。 */
        const val SCHEMA = 1

        private const val SHA_KEY = "sha256"

        /**
         * 解析并校验清单。任何一条规则不过都抛 [PackManifestException]，不返回半成品。
         *
         * 调用方（[ModelPackStore] 的 list/verify）用 `runCatching` 把坏清单降级为"未安装/损坏"，
         * 绝不让一个坏文件把设置页打崩 —— 计划的红线是"损坏/半包要降级为未安装，不得崩"。
         */
        fun fromJson(json: String): PackManifest {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw PackManifestException("manifest 不是合法 JSON：${e.message}")
            }

            val schema = root.requireInt("schema")
            if (schema != SCHEMA) {
                throw PackManifestException("schema 只认 $SCHEMA，读到 $schema（旧版本不认识新清单，宁可拒绝）")
            }

            val id = root.requireString("id")
            if (!PackIdPattern.matches(id)) {
                throw PackManifestException("id 不合法：$id（要求 [a-z0-9-]{3,40}）")
            }

            val kind = PackKind.fromWire(root.requireString("kind"))

            val displayName = root.requireString("displayName")
            if (displayName.isBlank()) {
                throw PackManifestException("displayName 不能为空")
            }
            val version = root.requireString("version")
            if (version.isBlank()) {
                throw PackManifestException("version 不能为空")
            }

            val minApp = root.optionalString("minApp")?.takeIf { it.isNotBlank() }

            val downloadUrl = root.optionalString("downloadUrl")?.takeIf { it.isNotBlank() }
            if (downloadUrl != null && !downloadUrl.startsWith("https://")) {
                throw PackManifestException("downloadUrl 必须 https://：$downloadUrl（明文下载会被现代网络路径卡住）")
            }

            val filesArray = root.optJSONArray("files")
                ?: throw PackManifestException("缺 files 或不是数组")
            if (filesArray.length() == 0) {
                throw PackManifestException("files 不能为空")
            }
            val files = ArrayList<PackFile>(filesArray.length())
            for (index in 0 until filesArray.length()) {
                val item = filesArray.optJSONObject(index)
                    ?: throw PackManifestException("files[$index] 不是对象")
                val path = item.requireString("path")
                validatePackFilePath(path)?.let { throw PackManifestException("files[$index].$it") }
                if (files.any { it.path == path }) {
                    throw PackManifestException("files 里 path 重复：$path（同一路径两份明细，install 时无法判定信哪份）")
                }
                val bytes = item.requireLong("bytes")
                if (bytes <= 0) {
                    throw PackManifestException("files[$index].bytes 必须 > 0，读到 $bytes")
                }
                val sha = item.requireString(SHA_KEY)
                if (!Sha256Pattern.matches(sha)) {
                    throw PackManifestException("files[$index].$SHA_KEY 不是 64 位十六进制：$sha")
                }
                files += PackFile(path = path, bytes = bytes, sha256 = sha)
            }

            val sizeBytes = root.requireLong("sizeBytes")
            val fileBytesSum = files.sumOf { it.bytes }
            if (sizeBytes != fileBytesSum) {
                throw PackManifestException(
                    "sizeBytes($sizeBytes) 与明细合计($fileBytesSum)不一致 —— 总数必须由代码从 files 汇总，不抄文档估算",
                )
            }

            return PackManifest(
                schema = schema,
                id = id,
                kind = kind,
                displayName = displayName,
                version = version,
                minApp = minApp,
                sizeBytes = sizeBytes,
                downloadUrl = downloadUrl,
                files = files,
            )
        }

        /**
         * 校验一个包内相对路径；返回一句人话原因，合法时返回 null。
         *
         * 这是防目录穿越的唯一入口：manifest 是**下载来的**数据（哪怕今天全是占位），
         * 一个 ${"path": "../../databases/studykit.db"} 就能让安装写穿到别的目录去。
         */
        internal fun validatePackFilePath(path: String): String? {
            if (path.isBlank()) return "path 不能为空"
            if (path.startsWith("/") || path.startsWith("\\")) return "path 不能是绝对路径：$path"
            if (path.contains('\\')) return "path 不能含反斜杠：$path"
            if (path.contains("..")) return "path 不能含 ..：$path"
            if (path.contains(':')) return "path 不能含冒号（盘符 / NTFS 数据流）：$path"
            if (path.endsWith("/")) return "path 不能以斜杠结尾：$path"
            path.split('/').forEach { segment ->
                if (segment.isEmpty()) return "path 不能有空路径段：$path"
                if (segment == ".") return "path 不能含 ./ 段：$path"
            }
            return null
        }

        private fun JSONObject.requireString(key: String): String {
            if (!has(key) || isNull(key)) throw PackManifestException("缺字段 $key")
            return opt(key) as? String ?: throw PackManifestException("字段 $key 不是字符串")
        }

        private fun JSONObject.optionalString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return opt(key) as? String ?: throw PackManifestException("字段 $key 不是字符串")
        }

        private fun JSONObject.requireInt(key: String): Int {
            val value = requireLong(key)
            if (value > Int.MAX_VALUE || value < Int.MIN_VALUE) {
                throw PackManifestException("字段 $key 超出 Int 范围：$value")
            }
            return value.toInt()
        }

        private fun JSONObject.requireLong(key: String): Long {
            if (!has(key) || isNull(key)) throw PackManifestException("缺字段 $key")
            val value = opt(key)
            if (value !is Number) throw PackManifestException("字段 $key 不是数字")
            val asDouble = value.toDouble()
            if (asDouble.isNaN() || asDouble.isInfinite() || asDouble != Math.floor(asDouble)) {
                throw PackManifestException("字段 $key 必须是整数：$value")
            }
            return value.toLong()
        }
    }
}

/**
 * 六个模型包的 id 常量。
 *
 * 命名照计划 §3.3 的种子表：`llm-zh-06b-int4` 与本仓任务名一致（文档 L930 写作 0.5B，
 * 两套命名的不一致见计划 D5，最终由 Lead 拍板；在拍板前**不动 id**，否则盘上目录与代码要一起迁）。
 */
object PackIds {
    const val LLM_ZH_06B_INT4 = "llm-zh-06b-int4"
    const val VOICE_TTS = "voice-tts"
    const val VOICE_ASR = "voice-asr"
    const val OCR_STRUCTURE = "ocr-structure"
    const val DE_HANDWRITE = "de-handwrite"
    const val EMBED_ZH_SMALL_INT8 = "embed-zh-small-int8"

    /** 全部 id，注册表与测试共用（顺序固定，UI 行序就是它）。 */
    val ALL: List<String> = listOf(
        LLM_ZH_06B_INT4,
        VOICE_TTS,
        VOICE_ASR,
        OCR_STRUCTURE,
        DE_HANDWRITE,
        EMBED_ZH_SMALL_INT8,
    )
}

/**
 * 注册表里的一条包种子。
 *
 * ## 为什么 `manifest` 可空
 * 计划 §3.3 要求六条种子**全部"未上架"**：真实下载 URL 与逐文件 sha256 要等托管方案（D3）定了
 * 才允许填。**没有 sha256 的包不允许上架**（计划 §10-D3），所以"能不能下载"这件事的真相是
 * [manifest] 非空 —— 而不是另一个容易和清单脱节的 boolean 开关。
 *
 * ## 为什么没有 displayName 字段
 * 用户文案的单一来源是 [ModelPackCopy]（硬约束），种子里再存一份显示名就是第二个家。
 * [displayName] 这个属性只是转调 Copy，给存储/预算层一个"包叫什么"的口径。
 *
 * [estimatedSizeMb] 是**未上架行的预计体积占位**（计划 §3.3 写的是 null）；真实体积只能来自
 * 下载后的 manifest.sizeBytes，不许抄文档估算（计划 §2.6 末段）。
 */
data class PackSeed(
    val id: String,
    val kind: PackKind,
    val manifest: PackManifest? = null,
    val estimatedSizeMb: Int? = null,
) {
    init {
        require(manifest == null || manifest.id == id) {
            "种子 id=$id 与 manifest.id=${manifest?.id} 不一致：装出来的目录名会和清单对不上"
        }
    }

    /** 有完整清单（含 https URL 与逐文件 sha256）才允许进入下载流程。 */
    val downloadable: Boolean get() = manifest != null

    /** 包显示名；文案唯一来源是 [ModelPackCopy]。 */
    val displayName: String get() = ModelPackCopy.packTitle(kind)
}

/**
 * 六个可选包种子的**唯一**注册表。
 *
 * 本轮全部 `manifest = null` → UI 一律显示"开发中 · 暂未上架"（计划 §3.3 / §3.10）。
 * 真实包上架时只改这里：把对应条的 `manifest` 填成托管方给的清单即可，
 * UI/Store/预算/下载器都不必改。
 */
object PackRegistry {

    val all: List<PackSeed> = listOf(
        PackSeed(id = PackIds.LLM_ZH_06B_INT4, kind = PackKind.LLM),
        PackSeed(id = PackIds.VOICE_TTS, kind = PackKind.VOICE_TTS),
        PackSeed(id = PackIds.VOICE_ASR, kind = PackKind.VOICE_ASR),
        PackSeed(id = PackIds.OCR_STRUCTURE, kind = PackKind.STRUCTURE_V3),
        PackSeed(id = PackIds.DE_HANDWRITE, kind = PackKind.DE_HANDWRITE),
        PackSeed(id = PackIds.EMBED_ZH_SMALL_INT8, kind = PackKind.EMBED),
    )

    init {
        require(all.map { it.id }.distinct().size == all.size) { "注册表 id 必须唯一（目录名即真相，重复就装串了）" }
        require(all.map { it.id }.toSet() == PackIds.ALL.toSet()) { "注册表缺/多了条目：PackIds.ALL 与 all 必须同一组" }
    }

    /** 按 id 查种子；未知 id 返回 null（调用方据此给"未上架"文案，不抛）。 */
    fun byId(id: String): PackSeed? = all.firstOrNull { it.id == id }
}
