package com.studykit.ui.bulkimport

import com.studykit.ui.nav.DictRoutes
import com.studykit.ui.nav.ImportRoutes
import com.studykit.ui.nav.StudyRoutes

/**
 * 哪些屏可能有「一键录入」菜单。
 * 列全五个（含【不该有】菜单的三个），是为了让「不加」这个决定也留在表里、可被测试钉住 ——
 * 空白处最容易被顺手补一枚按钮，补完就成了同一屏两条通往同一个地方的路。
 */
enum class BulkScreen {
    STUDY_HOME,
    QUESTION_BANK,

    /** 右上已有「批量导入」「词库」，页内另有「截图取词」 */
    WORD_LIST,

    /** 唯一入口「新建书」已在；摘录批量导入落不下库，原因见 [ImportKind] 的 KDoc */
    BOOK_SHELF,

    /** 页内已有「拍照录入」hero 动作 */
    MISTAKE_LIST,
}

/**
 * 一条录入动作。文案与目的地都在枚举里，菜单就只有一份口径；
 * 新增一项时必须同时给出 [target]，否则编译不过 —— 防的是"菜单里多出一行点了没反应"。
 */
enum class BulkAction(val label: String) {
    WORD_CREATE("录入单词"),
    WORD_BULK("批量导入单词"),
    DICT_STORE("从词库导入"),
    QUESTION_CREATE("单题录入题目"),
    QUESTION_BULK("批量录入题目"),
    QUESTION_CAPTURE("拍照录入题目"),
    ;

    /**
     * 点了去哪儿。**只允许**是导航层已有的目的地（[StudyRoutes] / [ImportRoutes] / [DictRoutes]），
     * 或者拉起相机。这里不拼任何路由字符串 —— 表自己造串就等于第二个真源。
     *
     * 批量那两项的目的地是既有的粘贴/选文件页 [ImportRoutes.PASTE]：那条页里本来就有
     * 「选文件」那枚 SAF 按钮，所以"从文件导入"不必新开通道，也不必新解析器（一条红线）。
     */
    fun target(): BulkTarget = when (this) {
        WORD_CREATE -> BulkTarget.Route(StudyRoutes.WORD_CREATE)
        QUESTION_CREATE -> BulkTarget.Route(StudyRoutes.QUESTION_CREATE)
        WORD_BULK -> BulkTarget.Route(ImportRoutes.paste(ImportKind.WORD))
        QUESTION_BULK -> BulkTarget.Route(ImportRoutes.paste(ImportKind.QUESTION))
        DICT_STORE -> BulkTarget.Route(DictRoutes.STORE)
        QUESTION_CAPTURE -> BulkTarget.Camera
    }
}

/** 录入动作的两类落点：一条既有路由，或者相机（由页面自己按既有相机范式拉起）。 */
sealed interface BulkTarget {
    data class Route(val path: String) : BulkTarget
    data object Camera : BulkTarget
}

/** 非路由动作（相机）返回 null，由调用方去拉相机 —— 不在导航层抛异常。 */
fun BulkAction.routeOrNull(): String? = (target() as? BulkTarget.Route)?.path

/**
 * 菜单一击的分发口径。两个屏（学习首页 / 题库）共用这一份，免得各写一个 `when` 写歪。
 * 路由型动作走 [navigate]，唯一的相机型动作走 [openCamera]。
 */
fun dispatchEntryAction(
    action: BulkAction,
    navigate: (String) -> Unit,
    openCamera: () -> Unit,
) {
    val path = action.routeOrNull()
    if (path != null) navigate(path) else openCamera()
}

/**
 * 「一键录入」决策表：每屏该列哪几项、按什么顺序。
 *
 * 顺序口径 = 这屏最常用的事前面：学习首页先补单词（现状那颗 `＋录入` 的原职责），再给新来的拍照；
 * 题库屏正相反，拍照录题就是这一屏的新增理由，所以它在最前。
 */
object BulkEntries {

    fun menuFor(screen: BulkScreen): List<BulkAction> = when (screen) {
        BulkScreen.STUDY_HOME -> listOf(
            BulkAction.WORD_CREATE,
            BulkAction.QUESTION_CAPTURE,
            BulkAction.QUESTION_BULK,
            BulkAction.DICT_STORE,
        )

        BulkScreen.QUESTION_BANK -> listOf(
            BulkAction.QUESTION_CAPTURE,
            BulkAction.QUESTION_BULK,
            BulkAction.QUESTION_CREATE,
        )

        // 下面三屏【不给】菜单，各自的理由见 [BulkScreen] 上那几行注释；测试钉住这条决定。
        BulkScreen.WORD_LIST,
        BulkScreen.BOOK_SHELF,
        BulkScreen.MISTAKE_LIST,
        -> emptyList()
    }
}
