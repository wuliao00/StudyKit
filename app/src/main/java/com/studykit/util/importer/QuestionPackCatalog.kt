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
) {
    /** 内置资产路径；未来接远程源时，网络地址也从 [path] 拼，不另存一份。 */
    val assetPath: String get() = "question_banks/$path"
}

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
        )
    }
}
