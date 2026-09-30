package com.studykit.ui.mistake

// 错题「重做式复习」的纯逻辑：阶段机、正文切分、以及本轮刻意写死的几句文案。
//
// 为什么值得单独立一个文件：
// 重做优先这条规矩（检索练习优于再读解析：Roediger & Karpicke 2006；Karpicke & Blunt 2011,
// Science）落在页面上就是三件小事：进来先遮住什么、按哪颗钮才展开、展开后追问一句什么。
// 三件事都只依赖 `Mistake.content` / `Mistake.note` 两个字符串，跟 Room、Compose、
// 协程都没关系 —— 留在 Composable 里就只能靠真机点，抽到这里才能钉进
// `RedoFlowTest`。**这里一个 Android API 都不许出现。**
//
// 本轮的边界（写清楚，免得被误读成已有能力）：
// 没有 schema 就没有逐次重做历史：`Mistake` 没有 `cause` 列，也没有重做记录表，
// 所以这里**不记录**「这道题重做过几次、每次都错在哪一步」，更不拿它去排复习时间
// （`redoHistoryBoundary()` 就是把这条边界写成用户看得见的一句话）。
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
 * 更不会因此替你改动复习时间（时间的口径见详情页那句「复习时间由你自己定，这里没有算法排期。」）。
 */
internal fun redoHistoryBoundary(): String =
    "这里没有记录你每次重做的结果，展开解析也不会改动复习时间。"
