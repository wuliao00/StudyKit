package com.studykit.ui.bulkimport

import com.studykit.ui.nav.DictRoutes
import com.studykit.ui.nav.ImportRoutes
import com.studykit.ui.nav.StudyRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键录入」菜单的**决策表**（`BulkEntry.kt`）。
 *
 * 为什么单独钉这一层：菜单本身是 Compose，而"某一屏该给哪几项、点了去哪"是可以写成纯函数的判断。
 * 把它抽出来有三个好处 ——
 *  1. 路由字符串只有一个真源（`StudyRoutes` / `ImportRoutes` / `DictRoutes`），这张表**不许**自己拼路径，
 *     于是"入口接错屏"这种错会被测试抓住，而不是等真机上点出错屏；
 *  2. 「拍照录入题目」是唯一的相机动作，这条约束（其余动作一律不启动相机）靠表检查，不靠 UI 截图；
 *  3. 「已有入口的屏不再摆菜单」是本仓反复强调的一条审美纪律（同一屏不许有两条通往同一个地方的路，
 *     见 `StudyHomeScreen` 里删掉「背单词」入口卡那处注释），把它写成断言，将来手滑加菜单会被拦下。
 */
class BulkEntryTest {

    // ── 各屏该给哪些项 ────────────────────────────────────────────────
    @Test
    fun 学习首页菜单是四项按现状顺序() {
        assertEquals(
            listOf(
                BulkAction.WORD_CREATE,
                BulkAction.QUESTION_CAPTURE,
                BulkAction.QUESTION_BULK,
                BulkAction.DICT_STORE,
            ),
            BulkEntries.menuFor(BulkScreen.STUDY_HOME),
        )
    }

    @Test
    fun 题库屏菜单给拍照批量与单题() {
        assertEquals(
            listOf(
                BulkAction.QUESTION_CAPTURE,
                BulkAction.QUESTION_BULK,
                BulkAction.QUESTION_CREATE,
            ),
            BulkEntries.menuFor(BulkScreen.QUESTION_BANK),
        )
    }

    @Test
    fun 菜单文案与规格一字不差() {
        assertEquals(
            listOf("录入单词", "拍照录入题目", "批量录入题目", "从词库导入"),
            BulkEntries.menuFor(BulkScreen.STUDY_HOME).map { it.label },
        )
        assertEquals(
            listOf("拍照录入题目", "批量录入题目", "单题录入题目"),
            BulkEntries.menuFor(BulkScreen.QUESTION_BANK).map { it.label },
        )
    }

    @Test
    fun 入口已齐的屏不再摆菜单() {
        // 单词库：右上已有「批量导入」「词库」+ 页内「截图取词」；
        // 书架：唯一入口「新建书」已在，摘录批量导入落不下库（见 ImportKind 的 KDoc）；
        // 错题：页内已有「拍照录入」hero 动作。三处都不该再长出第二把门。
        for (screen in listOf(BulkScreen.WORD_LIST, BulkScreen.BOOK_SHELF, BulkScreen.MISTAKE_LIST)) {
            assertTrue("${screen.name} 不该有录入菜单", BulkEntries.menuFor(screen).isEmpty())
        }
    }

    // ── 点了去哪：只复用既有路由 ──────────────────────────────────────
    @Test
    fun 每条动作都落在既有目的地上() {
        assertEquals(BulkTarget.Route(StudyRoutes.WORD_CREATE), BulkAction.WORD_CREATE.target())
        assertEquals(BulkTarget.Route(StudyRoutes.QUESTION_CREATE), BulkAction.QUESTION_CREATE.target())
        assertEquals(
            BulkTarget.Route(ImportRoutes.paste(ImportKind.WORD)),
            BulkAction.WORD_BULK.target(),
        )
        assertEquals(
            BulkTarget.Route(ImportRoutes.paste(ImportKind.QUESTION)),
            BulkAction.QUESTION_BULK.target(),
        )
        assertEquals(BulkTarget.Route(DictRoutes.STORE), BulkAction.DICT_STORE.target())
        assertEquals(BulkTarget.Camera, BulkAction.QUESTION_CAPTURE.target())
    }

    @Test
    fun 批量录题目走的就是既有粘贴选文件那一条管线() {
        // 这一条钉的是"不复用管线就失败"：题目批量的目的地必须与首页那颗批量图标同一个串。
        assertEquals(ImportRoutes.paste(ImportKind.QUESTION), QUESTION_BULK_PATH())
    }

    @Test
    fun 只有拍照录入题目启动相机() {
        assertEquals(
            listOf(BulkAction.QUESTION_CAPTURE),
            BulkAction.entries.filter { it.target() == BulkTarget.Camera },
        )
    }

    // ── 分发口径：路由型只导航，相机型只拉相机 ─────────────────────────
    @Test
    fun 路由型动作导航而不去碰相机() {
        for (action in BulkAction.entries.filter { it.target() is BulkTarget.Route }) {
            var navigated: String? = null
            var cameraCalls = 0
            dispatchEntryAction(action, navigate = { navigated = it }, openCamera = { cameraCalls++ })
            assertEquals("${action.name} 不该拉起相机", 0, cameraCalls)
            assertEquals(action.routeOrNull(), navigated)
        }
    }

    @Test
    fun 相机型动作拉起相机而不是空导航() {
        var navigated: String? = null
        var cameraCalls = 0
        dispatchEntryAction(
            BulkAction.QUESTION_CAPTURE,
            navigate = { navigated = it },
            openCamera = { cameraCalls++ },
        )
        assertEquals(1, cameraCalls)
        assertNull(navigated)
    }

    @Test
    fun 任何一屏的菜单都不出现重复项() {
        for (screen in BulkScreen.entries) {
            val menu = BulkEntries.menuFor(screen)
            assertEquals("${screen.name} 菜单有重复项", menu.size, menu.distinct().size)
        }
    }

    private fun QUESTION_BULK_PATH(): String =
        (BulkAction.QUESTION_BULK.target() as BulkTarget.Route).path
}
