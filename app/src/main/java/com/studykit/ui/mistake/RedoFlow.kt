package com.studykit.ui.mistake

import com.studykit.data.entity.Mistake

// 错题「重做式复习」的纯逻辑：阶段机、正文切分、以及本轮刻意写死的几句文案。
//
// 为什么值得单独立一个文件：
// 重做优先这条规矩（检索练习优于再读解析：Roediger & Karpicke 2006；Karpicke & Blunt 2011,
// Science）落在页面上就是三件小事：进来先遮住什么、按哪颗钮才展开、展开后追问一句什么。
// 三件事都只依赖 `Mistake.content` / `Mistake.note` 两个字符串，跟 Room、Compose、
// 协程都没关系 —— 留在 Composable 里就只能靠真机点，抽到这里才能钉进
// `RedoFlowTest`。**这里一个 Android API 都不许出现。**
//
// 边界（v2.7 B15 已接上重做历史，这里把话说准）：
// `mistake_redos` 表自 v7 就在，B15 起由 `MistakeViewModel.gradeRedo` 在评完排期后写一行逐次重做轨迹。
// 但**本文件仍是纯逻辑**：阶段机 / 正文切分 / 文案都不碰 Room，也不自己记录什么 ——
// 写历史那一步发生在 VM/DAO，不在这里。`redoHistoryBoundary()` 那句钉子说的是「展开解析这个动作
// 本身一次都不写」，与「判定那次会写历史」并不矛盾：前者是看，后者是评分。
// 阶段只活在详情页的组合里，靠 `rememberSaveable` 活过转屏与换屏，页面离开即结束。

/**
 * 重做式复习的两个阶段。
 *
 * - [REDO]：默认阶段。题干可见，答案与解析（或用户自己写的备注）遮住。
 * - [CHECK]：用户承认「我重做了一遍」之后的对照阶段，遮住的东西连同自我解释提示一起出现。
 */
internal enum class RedoPhase {
    REDO,
    CHECK,
}

/** 推动 [nextRedoPhase] 的事件，与详情页的两颗钮一一对应 */
internal sealed interface RedoAction {
    /** 「我重做了一遍」→ 展开答案与解析 */
    data object ConfirmedRedo : RedoAction

    /** 「重新遮住答案」→ 收回答案与解析，准备再练一遍 */
    data object CoverAnswer : RedoAction
}

/**
 * 阶段推进：逐格写出转移表，没有第三态。
 * 重复投同一件事不跳阶也不回滚，所以按钮连点、或转屏后组合重建时重放一次都无害。
 */
internal fun nextRedoPhase(phase: RedoPhase, action: RedoAction): RedoPhase = when (phase) {
    // 已经在重做：收回按钮无事可做（没有东西可再遮一次），只有「我重做了一遍」往前推
    RedoPhase.REDO -> when (action) {
        RedoAction.ConfirmedRedo -> RedoPhase.CHECK
        RedoAction.CoverAnswer -> RedoPhase.REDO
    }

    // 已经在对照：再按一次「我重做了一遍」仍停在对照；收回才回到遮住那一侧
    RedoPhase.CHECK -> when (action) {
        RedoAction.ConfirmedRedo -> RedoPhase.CHECK
        RedoAction.CoverAnswer -> RedoPhase.REDO
    }
}

/**
 * `rememberSaveable` 存的是**串**而不是布尔量：枚举本身没有默认 `Saver`，
 * 而存阶段名比存 `true/false` 多保住一件事 —— 恢复时走的是 [decodeRedoPhase] 那条
 * 「读不懂就回到遮住」的口径，将来加第三个阶段也不必改存档格式的含义。
 */
internal fun encodeRedoPhase(phase: RedoPhase): String = phase.name

/** 解码存档里的阶段；`null` / 空串 / 没见过的串一律回 [RedoPhase.REDO]（默认遮住答案才是安全侧） */
internal fun decodeRedoPhase(raw: String?): RedoPhase =
    RedoPhase.entries.firstOrNull { it.name == raw } ?: RedoPhase.REDO

/**
 * 答案与解析的**行首**标记。刷题收录写进来的正文由
 * `StudyViewModel.addMistakeIfAbsent` 生成（`正确答案：…` + `解析：…`），
 * 拍照与手写错题则什么形状都有，所以标记集合只认「整行以它开头」。
 *
 * 刻意**不收**「解：」：OCR 出来的题干里那通常是题目自带的解题过程，不是这道题的答案；
 * 也刻意不收「知识点：」「点评：」这类没把握的说法 —— 少遮一行只是多看一眼，
 * 错切一刀会把题干腰斩。
 */
private val ANSWER_KEY_PREFIXES = listOf(
    "正确答案：", "正确答案:",
    "参考答案：", "参考答案:",
    "答案解析：", "答案解析:",
    "答案：", "答案:", "【答案】",
    "解析：", "解析:", "【解析】",
    "详解：", "详解:",
    "解答：", "解答:",
)

/**
 * 错题正文切成的两半：[stem] 是重做阶段就该看见的题面（题干 + 选项），
 * [answerKey] 是要等到对照阶段才露出的答案与解析段。
 *
 * 两半都是原文的**连续块**（只是各自 trim 掉首尾空白），所以「展开后保留原有全部信息」
 * 这句承诺在数据层面成立 —— 不重写、不重排、不补标点。
 */
internal data class RedoSplit(val stem: String, val answerKey: String)

/**
 * 按第一个「行首标记」把正文切两刀。找不到标记就整段算题面（[RedoSplit.answerKey] 为空），
 * 标记落在第一行则题面为空。
 *
 * 只切第一刀：后面的答案、解析、二次作答……全归进同一段，
 * 因为重做阶段要遮的是「这题的结论」，而不是某一行格式。
 */
internal fun splitForRedo(content: String): RedoSplit {
    if (content.isBlank()) return RedoSplit(stem = "", answerKey = "")
    val lines = content.split('\n')
    val cut = lines.indexOfFirst { line ->
        val head = line.trimStart()
        ANSWER_KEY_PREFIXES.any { head.startsWith(it) }
    }
    if (cut < 0) return RedoSplit(stem = content.trim(), answerKey = "")
    return RedoSplit(
        stem = lines.subList(0, cut).joinToString("\n").trim(),
        answerKey = lines.subList(cut, lines.size).joinToString("\n").trim(),
    )
}

/** 刷题收录由程序写进 `note` 的题号标记前缀（`qid:12`），它不是解析，遮了没有意义 */
private const val MACHINE_NOTE_PREFIX = "qid:"

/**
 * 这条备注是不是程序自己写的标记。
 * [isMachineNote] 为 true（或备注为空）时，备注卡里没有任何「答案信息」，
 * 于是重做阶段照旧显示它 —— 页面上那行 `qid:12` 是溯源用的，不是可被剧透的内容。
 */
internal fun isMachineNote(note: String): Boolean =
    note.trim().startsWith(MACHINE_NOTE_PREFIX, ignoreCase = true)

/**
 * 从刷题收录写进 `note` 的 `qid:12` 标记里解析出这道错题对应的**题目 id**。
 *
 * 只有「换一道同考点的」这颗钮用得上：变式选取器要知道拿哪道题的 `concept_tag` 去匹配同考点的题。
 * 拍照 / 手写错题的 `note` 不带这个标记，或数字脏（非数字、溢出）时一律给 null —— 拿不到题目就不假装能换。`Mistake.SOURCE_PHOTO` 是 `const val`，编译期内联，这里不产生运行时耦合。
 */
internal fun linkedQuestionId(note: String): Long? {
    val trimmed = note.trim()
    if (!trimmed.startsWith(MACHINE_NOTE_PREFIX, ignoreCase = true)) return null
    return trimmed.substring(MACHINE_NOTE_PREFIX.length).trim().toLongOrNull()
}

/** 重做阶段被遮住的是哪一段；[NONE] 表示这道题压根没有可遮的结论 */
internal enum class ConcealedSection {
    NONE,

    /** 正文里以行首标记切出来的答案与解析段 */
    ANSWER_KEY,

    /** 没有可识别的答案段时，退而遮用户自己写的备注 */
    NOTE,
}

/**
 * 详情页一次渲染所需的门控结论：把「哪段能看、哪段还不能看」算死在一处，
 * 页面只读字段，不再自己拼条件（拼错一次就等于答案提前露出）。
 */
internal data class RedoGate(
    val phase: RedoPhase,
    /** 题面：重做阶段也可见 */
    val stemText: String,
    /** 答案与解析段，可能为空 */
    val answerKey: String,
    /** 备注原文 */
    val noteText: String,
    val concealed: ConcealedSection,
) {
    /** 这道题有没有「可展开的东西」。false 时页面不该说「已遮住」，也不该给收回按钮。 */
    val hasReveal: Boolean get() = concealed != ConcealedSection.NONE

    /** 此刻是否真有内容没显示 */
    val isCovered: Boolean get() = phase == RedoPhase.REDO && hasReveal

    /** 答案与解析段此刻可见吗 */
    val answerKeyVisible: Boolean
        get() = !(phase == RedoPhase.REDO && concealed == ConcealedSection.ANSWER_KEY)

    /** 备注此刻可见吗 */
    val noteVisible: Boolean
        get() = !(phase == RedoPhase.REDO && concealed == ConcealedSection.NOTE)
}

/**
 * 由一行错题数据算出门控结论。
 *
 * 遮的优先级只有一个答案段：正文里认得出答案与解析就遮它；认不出才退而遮备注
 * （备注往往是用户自己写的错因，对答案时同样会被剧透）。两处都没有 → [ConcealedSection.NONE]，
 * 页面照常显示全部内容，只保留「先重做再对答案」这句行为提示，不假装遮住了什么。
 */
internal fun buildRedoGate(content: String, note: String, phase: RedoPhase): RedoGate {
    val split = splitForRedo(content)
    val concealed = when {
        split.answerKey.isNotBlank() -> ConcealedSection.ANSWER_KEY
        note.isNotBlank() && !isMachineNote(note) -> ConcealedSection.NOTE
        else -> ConcealedSection.NONE
    }
    return RedoGate(
        phase = phase,
        stemText = split.stem,
        answerKey = split.answerKey,
        noteText = note,
        concealed = concealed,
    )
}

/** 遮住时给一句「少了什么」的说明；[ConcealedSection.NONE] 时返回 null —— 没遮就不许说遮了 */
internal fun coveredLabel(section: ConcealedSection): String? = when (section) {
    ConcealedSection.NONE -> null
    ConcealedSection.ANSWER_KEY -> "答案与解析已遮住"
    ConcealedSection.NOTE -> "备注已遮住"
}

// ── 文案 ────────────────────────────────────────────────────────────────
// 收在函数而不是散在 Composable 里，是为了让 `RedoFlowTest` 能断言它们：
// 这几句是本轮全部的行为承诺，被改空、被改成暗示算法排期，都只能靠测试拦下来。
// 数字与效应量一律不写（本轮没有任何度量）。

/** 重做区标题 */
internal fun redoGateTitle(): String = "重做这一题"

/** 折叠态提示：动作在前、对答案在后，顺序就是检索练习的意思 */
internal fun redoCoverHint(): String = "先拿张纸把这道题重做一遍，再对答案。"

/** 展开按钮 */
internal fun redoConfirmLabel(): String = "我重做了一遍"

/** 收回按钮：同一道题可以再看一遍，不必重新进页面 */
internal fun redoCoverAgainLabel(): String = "重新遮住答案"

/**
 * 重述门（v2.7 计划 B Task 15；app.docx 模块3「强制重建」的最小实现）。
 *
 * spec 要的是「OCR 题重做通过前解析区保持折叠」：拍照题是机器识别进来的，用户很可能压根没把题面
 * 过一遍就点开解析，等于对着自己没读过的题「对答案」。所以对**只有拍照来源**多加一道：
 * 按过「我重做了一遍」之后，解析区仍按住，直到用户点「我已重述」——重述本体在上方那段可编辑的备注里做。
 * 刷题收录 / 单词来的题本身带标准答案结构，不再加这道门（它们没「重述」这一步可缺）。
 */
internal fun restateGateRequired(source: String): Boolean = source == Mistake.SOURCE_PHOTO

/** 重述门的说明：先照自己的话把关键步骤说一遍，才给看解析 */
internal fun restatePrompt(): String = "先照自己的话把关键步骤重述一遍，再展开解析。"

/** 重述门的确认钮 */
internal fun restateConfirmLabel(): String = "我已重述"

/** 重做完的自评追问（把「对不对」这一位采下来，才谈得上评分与入历史）*/
internal fun redoVerdictPrompt(): String = "这道题这次自己做出来了吗？"

/** 判对钮 → 评 RECALL（`correct = grade != FORGET`，与排期口径同源）*/
internal fun redoVerdictCorrectLabel(): String = "做出来了"

/** 判错钮 → 评 FORGET */
internal fun redoVerdictWrongLabel(): String = "没做出来"

/** 重做阶段的挤牙膏提示钮（复用 QuizFeedback 的三档，一次只往前挪一档）*/
internal fun redoHintLabel(): String = "给点提示"

/** 变式钮：同考点存在其他题时才出现 */
internal fun variantButtonLabel(): String = "换一道同考点的"

/** 展开后那段内容的卡片标题 */
internal fun answerSectionTitle(): String = "答案与解析"

/**
 * 自我解释提问（Dunlosky et al. 2013, PSPI 把精加工提问评为中等效用）。
 * 三问固定：错在哪一步、当时被哪个条件误导、下次靠什么线索避开。
 */
internal fun selfExplainPrompt(): String =
    "这道题我错在哪一步？当时被哪个条件误导了？下次靠什么线索避开？"

/**
 * 边界说明：本轮没有逐次重做历史，也没有 schema 去存它。
 * 这句存在的唯一目的是**不让人误读** —— 展开解析不等于应用记住了你重做过，
 * 更不会因此替你改动复习时间。
 *
 * 后半句在 v2.7 B14 换了口径：排期交给内核（现行文案见 `MistakeScheduling.kt` 的 [systemSchedulingNote]），
 * 但**这一句仍然成立**：真正改动 `review_at` 的是重做之后的那一次评分（`MistakeScheduling.grade`），
 * 而展开/收起答案本身一次写都不写。T15 接上 `mistake_redos` 之后这句也不用改：
 * 历史表记的是评分，不是“看过解析”。
 */
internal fun redoHistoryBoundary(): String =
    "这里没有记录你每次重做的结果，展开解析也不会改动复习时间。"
