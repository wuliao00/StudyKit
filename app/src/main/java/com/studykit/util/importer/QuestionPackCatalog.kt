package com.studykit.util.importer

import com.studykit.data.question.ExamStage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个可下载的题库包。`path` 是相对 `question-banks/` 的文件路径，`sha256` 用于下载后校验。
 *
 * 题包元数据把**来源与许可**放在字段里，而不是只写在 README：用户点下载前就能看见
 * 这是原创/开放许可还是自备授权内容，避免把“能下”误当成“能随便再分发”。
 */
data class QuestionPackInfo(
    val id: String,
    val title: String,
    val stage: ExamStage,
    val subjects: List<String>,
    val count: Int,
    val description: String,
    val license: String,
    val source: String,
    val path: String,
    val sha256: String,
    /**
     * v3.5：题型分布（题型中文名 → 题量），manifest 里的 `types` 字段。
     * 空列表 = 老目录没写，商店卡片就只显示总题数，不留空位。
     */
    val typeCounts: List<PackTypeCount> = emptyList(),
    /**
     * v3.5+：在线地址（manifest 的 `url`），null = 只能读内置副本。
     *
     * 有它就先联网下最新的，失败再落到本机缓存与内置副本（见 [QuestionPackStore]）；
     * 只允许 http(s)，`javascript:`、`file:` 这类地址在解析阶段就丢掉。
     */
    val remoteUrl: String? = null,
) {
    /** 内置资产路径；没有内置副本时这个路径打不开，由 [QuestionPackStore] 兜住 */
    val assetPath: String get() = "question_banks/$path"

    /** 是否可以从网上下载（商店卡片据此标「在线」） */
    val isRemote: Boolean get() = !remoteUrl.isNullOrBlank()
}

/** 一个题包里的题型分布，用于商店卡片的「单选 30 · 判断 9」那一行。 */
data class PackTypeCount(val label: String, val count: Int)

data class QuestionPackCatalog(
    val packs: List<QuestionPackInfo>,
    val notice: String = "",
)

/**
 * 题库商店目录解析。纯函数，JVM 单测直接跑；对远程/手改 JSON 的坏条目采取“跳过而不是整页崩”：
 * 一个包写错不该让其余五个包都下不了。路径只允许 `packs/` 下的相对文件，挡住 `../` 穿越。
 */
object QuestionPackCatalogParser {

    fun parse(raw: String): QuestionPackCatalog {
        val root = JSONObject(raw)
        val notice = root.optString("notice", "")
        val array = root.optJSONArray("packs") ?: JSONArray()
        val packs = ArrayList<QuestionPackInfo>(array.length())
        val seen = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val pack = parsePack(item) ?: continue
            if (!seen.add(pack.id)) continue
            packs.add(pack)
        }
        return QuestionPackCatalog(packs = packs, notice = notice)
    }

    private fun parsePack(item: JSONObject): QuestionPackInfo? {
        val id = item.optString("id").trim()
        val title = item.optString("title").trim()
        val stageRaw = item.optString("stage")
        val stage = ExamStage.fromAlias(stageRaw)?.takeIf { it != ExamStage.UNSPECIFIED }
            ?: ExamStage.fromStorage(stageRaw).takeIf { it != ExamStage.UNSPECIFIED }
            ?: return null
        val path = item.optString("path").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        if (!path.startsWith("packs/") || path.contains("..") || !path.endsWith(".txt")) return null
        val count = item.optInt("count", 0)
        if (count <= 0) return null
        val subjects = item.optJSONArray("subjects")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.orEmpty()
        if (subjects.isEmpty()) return null
        return QuestionPackInfo(
            id = id,
            title = title,
            stage = stage,
            subjects = subjects,
            count = count,
            description = item.optString("description", "").trim(),
            license = item.optString("license", "未标注许可").trim(),
            source = item.optString("source", "未标注来源").trim(),
            path = path,
            sha256 = item.optString("sha256", "").trim(),
            typeCounts = parseTypeCounts(item.optJSONObject("types")),
            remoteUrl = parseRemoteUrl(item.optString("url", "")),
        )
    }

    /**
     * v3.5+：题包在线地址。只收 http(s)，空串与其它协议一律当「没有在线地址」。
     *
     * 不在这里做可达性判断（离线目录也要能列出来），也不允许 `..`：地址是数据，
     * 不能让它被当成路径去拼。真正的请求在 [QuestionPackRemote] 里。
     */
    private fun parseRemoteUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains("..")) return null
        val lower = trimmed.lowercase()
        return trimmed.takeIf { lower.startsWith("https://") || lower.startsWith("http://") }
    }

    /**
     * v3.5：`"types": {"单选": 30, "判断": 9}` → 有序列表。
     *
     * 只做展示，缺失/写错不该让整包下不了：非正数直接跳过，键为空跳过，
     * 总数与 `count` 对不对由单测兜（见 `QuestionPackCatalogTest`）。
     */
    private fun parseTypeCounts(obj: JSONObject?): List<PackTypeCount> {
        if (obj == null) return emptyList()
        val keys = obj.keys()
        val out = ArrayList<PackTypeCount>(obj.length())
        while (keys.hasNext()) {
            val label = keys.next().trim()
            val value = obj.optInt(label, 0)
            if (label.isNotEmpty() && value > 0) out.add(PackTypeCount(label = label, count = value))
        }
        return out
    }
}
