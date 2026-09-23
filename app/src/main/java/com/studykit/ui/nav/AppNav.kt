package com.studykit.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.Outlined
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.studykit.ui.book.BookDetailScreen
import com.studykit.ui.book.BookEditScreen
import com.studykit.ui.book.BookShelfScreen
import com.studykit.ui.book.BookViewModel
import com.studykit.ui.book.ExcerptEditScreen
import com.studykit.ui.book.ReviewEditScreen
import com.studykit.ui.bulkimport.BulkPasteScreen
import com.studykit.ui.bulkimport.ImportKind
import com.studykit.ui.bulkimport.ImportPreviewScreen
import com.studykit.ui.bulkimport.ImportResultScreen
import com.studykit.ui.bulkimport.ImportViewModel
import com.studykit.ui.habit.GlobalCalendarScreen
import com.studykit.ui.habit.GlobalCalendarViewModel
import com.studykit.ui.habit.HabitCalendarScreen
import com.studykit.ui.habit.HabitCreateScreen
import com.studykit.ui.habit.HabitListScreen
import com.studykit.ui.habit.HabitViewModel
import com.studykit.ui.material.glassContainerColor
import com.studykit.ui.material.glassSurface
import com.studykit.ui.material.rememberGlassStyle
import com.studykit.ui.mistake.MistakeCaptureScreen
import com.studykit.ui.mistake.MistakeDetailScreen
import com.studykit.ui.mistake.MistakeListScreen
import com.studykit.ui.mistake.MistakeViewModel
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.settings.SettingsScreen
import com.studykit.ui.settings.SettingsViewModel
import com.studykit.ui.stats.StatsScreen
import com.studykit.ui.stats.StatsViewModel
import com.studykit.ui.study.CardStudyScreen
import com.studykit.ui.study.DictStoreScreen
import com.studykit.ui.study.DictStoreViewModel
import com.studykit.ui.study.QuestionCreateScreen
import com.studykit.ui.study.QuizScreen
import com.studykit.ui.study.StudyHomeScreen
import com.studykit.ui.study.StudyViewModel
import com.studykit.ui.study.WordCreateScreen
import com.studykit.ui.study.WordListScreen
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.ImportOutcome
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** 底部 Tab 定义 */
private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Study : Tab("study", "学习", Outlined.Create)
    data object Habit : Tab("habit", "习惯", Outlined.DateRange)
    data object Book : Tab("book", "读书", Outlined.Favorite)
    data object Mistake : Tab("mistake", "错题", Outlined.CheckCircle)
}

private val Tabs = listOf(Tab.Study, Tab.Habit, Tab.Book, Tab.Mistake)

/**
 * 目的地路由是否落在这四个底部 Tab 之内。
 * `NavBackStackEntry.destination.route` 可空，这里显式收口，避免把 `String?` 直接丢进 `Set<String>.contains`。
 */
private fun Set<String>.isTabRoute(route: String?): Boolean = route != null && route in this

object HabitRoutes {
    const val LIST = "habit"
    const val CALENDAR = "habit/calendar/{habitId}"
    const val CALENDAR_GLOBAL = "habit/calendar-global"
    const val CREATE = "habit/create"
    fun calendar(habitId: Long) = "habit/calendar/$habitId"
}

object StudyRoutes {
    const val WORDS = "study/words"
    const val CARDS = "study/cards"
    const val QUIZ = "study/quiz"
    const val WORD_CREATE = "study/word/create"
    const val QUESTION_CREATE = "study/question/create"

    /** 记忆看板：半衰期模型的三块图（未来量 / 持久度分布 / 遗忘曲线） */
    const val STATS = "study/stats"
}

/** 在线词库商店（唯一需要联网的界面） */
object DictRoutes {
    const val STORE = "study/dict/store"
}

/** 设置页。入口只有学习页顶栏那一枚齿轮，所以不做子路由：三段内容同页滚动 */
object SettingsRoutes {
    const val SETTINGS = "settings"
}

object BookRoutes {
    const val DETAIL = "book/{bookId}"
    const val CREATE = "book/create"
    const val EDIT = "book/{bookId}/edit"
    const val EXCERPT_CREATE = "book/{bookId}/excerpt/create"
    const val EXCERPT_EDIT = "excerpt/{excerptId}"
    const val REVIEW_CREATE = "book/{bookId}/review/create"
    const val REVIEW_EDIT = "review/{reviewId}"
    fun detail(bookId: Long) = "book/$bookId"
    fun edit(bookId: Long) = "book/$bookId/edit"
    fun excerptCreate(bookId: Long) = "book/$bookId/excerpt/create"
    fun excerptEdit(excerptId: Long) = "excerpt/$excerptId"
    fun reviewCreate(bookId: Long) = "book/$bookId/review/create"
    fun reviewEdit(reviewId: Long) = "review/$reviewId"
}

object MistakeRoutes {
    const val LIST = "mistake"
    const val DETAIL = "mistake/detail/{mistakeId}"
    // `auto` 只有分享收件会置真：拍照那条路不该被自动识别抢跑（用户往往正要自己敲标题）。
    const val CAPTURE = "mistake/capture?auto={auto}"
    fun capture(fromShare: Boolean = false) = "mistake/capture?auto=$fromShare"
    fun detail(mistakeId: Long) = "mistake/detail/$mistakeId"
}

/**
 * 批量导入三站：粘贴/选文件 → 预览 → 结果。
 * 预览与结果不带参数，是因为它们读的是同一个 [ImportViewModel] 里的 StateFlow ——
 * 把 3000 条塞进导航参数既会超 Binder 事务上限，也没意义。
 */
object ImportRoutes {
    const val PASTE = "import/paste/{kind}"
    const val PREVIEW = "import/preview"
    const val RESULT = "import/result"
    fun paste(kind: ImportKind) = "import/paste/${kind.name}"
}

/** 应用主导航：底部 4 Tab + 习惯/读书模块子路由 */
@Composable
fun AppNav(
    sharedImage: StateFlow<File?>,
    onSharedConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Tabs.any { it.route == currentRoute }
    val tabRoutes = remember { Tabs.map { it.route }.toSet() }
    val habitViewModel: HabitViewModel = viewModel()
    val globalCalendarViewModel: GlobalCalendarViewModel = viewModel()
    val bookViewModel: BookViewModel = viewModel()
    val studyViewModel: StudyViewModel = viewModel()
    val mistakeViewModel: MistakeViewModel = viewModel()
    val importViewModel: ImportViewModel = viewModel()
    val dictStoreViewModel: DictStoreViewModel = viewModel()
    val statsViewModel: StatsViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    // 分享进来的图走错题录入页既有的 pendingCapture 通道（与拍照同一条路，页面零分支）。
    // 先 onSharedConsumed 再 navigate：顺序反了的话，转屏重建组合时会再跳一次录入页。
    val shared by sharedImage.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        if (shared != null) {
            mistakeViewModel.setPendingCapture(shared)
            onSharedConsumed()
            navController.navigate(MistakeRoutes.capture(fromShare = true)) { launchSingleTop = true }
        }
    }

    // ── 边到边与键盘（终审 I11 / 波 4 项 2）────────────────────────────────
    // `MainActivity.enableEdgeToEdge()` + targetSdk 35 ⇒ 内容绘制在系统栏之下，insets 全部
    // 由 Compose 侧消费。改动前全仓碰 insets 的只有下面那一枚 `Modifier.padding(innerPadding)`
    // （`grep imePadding|safeDrawingPadding|windowInsetsPadding|consumeWindowInsets|
    // statusBarsPadding|navigationBarsPadding` 在改动前只命中 AppNav 的 Scaffold 本身），
    // 页面一侧从来没有第二处补偿 —— 所以「谁消费键盘」只需要在这一层说死。
    //
    // 结论：**键盘的唯一消费者就是这里的 `innerPadding`**，理由是 Compose insets 的传播规则：
    // 1. Material3 `Scaffold` 的默认 `contentWindowInsets` 是 `WindowInsets.safeDrawing`，
    //    而 safeDrawing = systemBars ∪ displayCutout ∪ captionBar ∪ **ime** ——
    //    键盘高度本就已经算进 `innerPadding.bottom`（有底栏时是 bottomBarHeight +
    //    max(safeDrawing.bottom − bottomBarHeight, 0)，键盘高出底栏的部分照样落进来）；
    // 2. `Modifier.padding(innerPadding)` 是**普通布局留白**，不 `consumeWindowInsets`，
    //    所以下游读到的 `LocalWindowInsets` 仍是满的 —— 在这之上再叠一枚 `.imePadding()`
    //    等于把同一段键盘高度补**两次**（本波真按这个写法提过：NavHost 可用高度被啃掉约两个
    //    键盘，表单页直接塌成一条），brief 的「保留 padding(innerPadding) 再叠 imePadding」
    //    按字面执行是个回归，不是修复；
    // 3. 曾想把 Scaffold 口径收窄成「不含 ime」再交给 `.imePadding()` 独占，本版
    //    Compose Foundation 没有 `WindowInsets.padding` 这个成员（CI 两次编译报错：
    //    先 "Function invocation 'padding(...)' expected"，改成调用后又只剩 `Modifier.padding`
    //    的候选），而 `safeDrawing` 这条路径既编译确定、又额外带住 captionBar（桌面/自由窗口）。
    //
    // 于是这里把默认值**显式写出来**：它不再是一句 Material3 的隐式默认，而是本仓的一条约定 ——
    // 七张表单页（单词/题目/习惯/书/摘录/评述录入 + 错题拍照）的键盘补偿只有这一处，
    // 页面内不得再补 `.imePadding()` / `.safeDrawingPadding()`。各页根 `Column` 本来就是
    // `verticalScroll`，NavHost 一缩就能滚到保存按钮。
    Scaffold(
        containerColor = AppTheme.colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onTabSelected = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Study.route,
            // 系统栏 / 刘海 / 底栏 / 键盘全部由上面 Scaffold 的 `innerPadding` 一处补偿，
            // 这里只做那一层留白 —— 再补一枚 `.imePadding()` 就是双份键盘高度（波 4 踩过）。
            modifier = Modifier.padding(innerPadding),
            // Tab 之间只做淡入淡出（无方向语义），且成对走 MotionSpec.fadeEnter/fadeExit，
            // 不再在这里裸写 `fadeIn(tween(...))`；进入子页 = 新页自右侧推入、当前页向左让位，
            // 返回 = 当前页向右滑出、上一页自左侧滑入（见 MotionSpec.navPopExit / navPopEnter）。
            // 判定入/退场两端都取自同一个 `Tabs` 列表，新增 Tab 时不必再改转场。
            enterTransition = {
                if (tabRoutes.isTabRoute(targetState.destination.route)) {
                    MotionSpec.fadeEnter()
                } else {
                    MotionSpec.navEnter()
                }
            },
            exitTransition = {
                if (tabRoutes.isTabRoute(initialState.destination.route)) {
                    MotionSpec.fadeExit()
                } else {
                    MotionSpec.navExit()
                }
            },
            popEnterTransition = {
                if (tabRoutes.isTabRoute(targetState.destination.route)) {
                    MotionSpec.fadeEnter()
                } else {
                    MotionSpec.navPopEnter()
                }
            },
            popExitTransition = {
                if (tabRoutes.isTabRoute(initialState.destination.route)) {
                    MotionSpec.fadeExit()
                } else {
                    MotionSpec.navPopExit()
                }
            },
        ) {
            // 子页 navigate 一律带 `launchSingleTop = true`（终审波 4）：这些入口都是
            // 「按钮 → 推一层」的手势，连点两下在没有该 flag 时会把同一个 route 压两份，
            // 用户按第一次返回只弹掉重复的那层，看起来就像「返回键没反应」。
            // Tab 切换那两处（下方的 onOpenMistakes 与 AppBottomBar 的 onTabSelected）本来就有，
            // 这里照同一写法补齐；带参数的子页（书/错题/习惯详情）同样只与**同参数**的栈顶合并，
            // 先 A 后 B 仍各占一层，不影响波 2 修的「写错目标行」。
            composable(Tab.Study.route) {
                StudyHomeScreen(
                    viewModel = studyViewModel,
                    onOpenWords = {
                        navController.navigate(StudyRoutes.WORDS) { launchSingleTop = true }
                    },
                    onStartQuiz = {
                        studyViewModel.resetQuiz()
                        navController.navigate(StudyRoutes.QUIZ) { launchSingleTop = true }
                    },
                    onOpenMistakes = {
                        navController.navigate(Tab.Mistake.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onAddWord = {
                        navController.navigate(StudyRoutes.WORD_CREATE) { launchSingleTop = true }
                    },
                    onAddQuestion = {
                        navController.navigate(StudyRoutes.QUESTION_CREATE) { launchSingleTop = true }
                    },
                    onBulkImportQuestions = {
                        navController.navigate(ImportRoutes.paste(ImportKind.QUESTION)) { launchSingleTop = true }
                    },
                    onOpenStats = {
                        navController.navigate(StudyRoutes.STATS) { launchSingleTop = true }
                    },
                    onOpenSettings = {
                        navController.navigate(SettingsRoutes.SETTINGS) { launchSingleTop = true }
                    },
                )
            }
            composable(Tab.Habit.route) {
                HabitListScreen(
                    viewModel = habitViewModel,
                    onOpenHabit = { habitId ->
                        navController.navigate(HabitRoutes.calendar(habitId)) { launchSingleTop = true }
                    },
                    onAddClick = {
                        navController.navigate(HabitRoutes.CREATE) { launchSingleTop = true }
                    },
                    onOpenCalendar = {
                        navController.navigate(HabitRoutes.CALENDAR_GLOBAL) { launchSingleTop = true }
                    },
                )
            }
            composable(Tab.Book.route) {
                BookShelfScreen(
                    viewModel = bookViewModel,
                    onOpenBook = { bookId ->
                        navController.navigate(BookRoutes.detail(bookId)) { launchSingleTop = true }
                    },
                    onAddClick = {
                        navController.navigate(BookRoutes.CREATE) { launchSingleTop = true }
                    },
                )
            }
            composable(Tab.Mistake.route) {
                MistakeListScreen(
                    viewModel = mistakeViewModel,
                    onOpenDetail = { id ->
                        navController.navigate(MistakeRoutes.detail(id)) { launchSingleTop = true }
                    },
                    onOpenCapture = {
                        navController.navigate(MistakeRoutes.capture()) { launchSingleTop = true }
                    },
                )
            }

            composable(
                route = HabitRoutes.CALENDAR,
                arguments = listOf(navArgument("habitId") { type = NavType.LongType }),
            ) { entry ->
                HabitCalendarScreen(
                    habitId = entry.arguments?.getLong("habitId") ?: 0L,
                    viewModel = habitViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HabitRoutes.CALENDAR_GLOBAL) {
                GlobalCalendarScreen(
                    viewModel = globalCalendarViewModel,
                    habitViewModel = habitViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HabitRoutes.CREATE) {
                HabitCreateScreen(
                    viewModel = habitViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            // ── 学习模块子路由 ──────────────────────────────────────
            composable(StudyRoutes.WORDS) {
                WordListScreen(
                    viewModel = studyViewModel,
                    importViewModel = importViewModel,
                    onBack = { navController.popBackStack() },
                    onStartStudy = {
                        studyViewModel.startCardSession()
                        navController.navigate(StudyRoutes.CARDS) { launchSingleTop = true }
                    },
                    onBulkImport = {
                        navController.navigate(ImportRoutes.paste(ImportKind.WORD)) { launchSingleTop = true }
                    },
                    // 截图取词跳过粘贴页：OCR 出来的文本已经在 plan 里，直接进预览
                    onPreviewImport = {
                        navController.navigate(ImportRoutes.PREVIEW) { launchSingleTop = true }
                    },
                    onOpenDict = {
                        navController.navigate(DictRoutes.STORE) { launchSingleTop = true }
                    },
                )
            }
            composable(StudyRoutes.CARDS) {
                CardStudyScreen(
                    viewModel = studyViewModel,
                    onBack = { navController.popBackStack() },
                    onAddWord = {
                        navController.navigate(StudyRoutes.WORD_CREATE) { launchSingleTop = true }
                    },
                )
            }
            composable(StudyRoutes.QUIZ) {
                QuizScreen(
                    viewModel = studyViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(StudyRoutes.WORD_CREATE) {
                WordCreateScreen(
                    viewModel = studyViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(StudyRoutes.QUESTION_CREATE) {
                QuestionCreateScreen(
                    viewModel = studyViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(DictRoutes.STORE) {
                DictStoreScreen(
                    viewModel = dictStoreViewModel,
                    importViewModel = importViewModel,
                    onBack = { navController.popBackStack() },
                    // 下载完直接进预览：商店不碰 words 表，判重与坏行处理只有一套实现
                    onPreview = { navController.navigate(ImportRoutes.PREVIEW) { launchSingleTop = true } },
                )
            }

            composable(StudyRoutes.STATS) {
                StatsScreen(
                    viewModel = statsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(SettingsRoutes.SETTINGS) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            // ── 批量导入（粘贴 / 选文件 → 预览 → 结果）──────────────────
            composable(
                route = ImportRoutes.PASTE,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) { entry ->
                // 认不出的 kind 退回 WORD 而不是抛：路由串可能来自深链，崩在这里不值当
                val kind = entry.arguments?.getString("kind")
                    ?.let { ImportKind.fromRoute(it) }
                    ?: ImportKind.WORD
                BulkPasteScreen(
                    viewModel = importViewModel,
                    kind = kind,
                    onBack = { navController.popBackStack() },
                    onPreview = { navController.navigate(ImportRoutes.PREVIEW) { launchSingleTop = true } },
                )
            }
            composable(ImportRoutes.PREVIEW) {
                ImportPreviewScreen(
                    viewModel = importViewModel,
                    kindLabel = "结果",
                    onBack = { navController.popBackStack() },
                    // onSubmit 是「写成功之后」的回调，不是点击即跳：写失败时停在预览页并留下原因
                    onSubmit = { navController.navigate(ImportRoutes.RESULT) { launchSingleTop = true } },
                )
            }
            composable(ImportRoutes.RESULT) {
                val importState by importViewModel.state.collectAsStateWithLifecycle()
                ImportResultScreen(
                    outcome = importState.outcome ?: ImportOutcome(0, emptyList(), emptyList()),
                    onDone = {
                        importViewModel.reset()
                        // 传的是路由模板而不是填好的路径：hasRoute 比的就是 destination.route 本身
                        navController.popBackStack(ImportRoutes.PASTE, inclusive = true)
                    },
                    onReviewRejected = {
                        importViewModel.reset()
                        // inclusive = false ⇒ 停在粘贴页，`raw` 由 rememberSaveable 留着，用户就地改那几行
                        navController.popBackStack(ImportRoutes.PASTE, inclusive = false)
                    },
                )
            }

            // ── 错题模块子路由 ──────────────────────────────────
            composable(
                route = MistakeRoutes.DETAIL,
                arguments = listOf(navArgument("mistakeId") { type = NavType.LongType }),
            ) { entry ->
                val mistakeId = entry.arguments?.getLong("mistakeId") ?: 0L
                // `openDetail` 不在这里发：发在 entry 里时本页首帧读到的仍是上一道错题的 detail，
                // 用户却在 B 的 route 上，此时任何写动作都会落到 A（终审 C1）。
                // 现在只把 route 上的 id 交下去，页面自己用它当渲染与动作的准绳。
                MistakeDetailScreen(
                    viewModel = mistakeViewModel,
                    mistakeId = mistakeId,
                    // 仅当本 entry 仍是栈顶时才 pop：详情页「标记掌握」的弹跳会在 popExit 的 280ms 里
                    // 继续留在组合中，若期间用户已用系统返回/手势返回，无脑 popBackStack 会连列表页
                    // 一起弹掉（多弹一层，落到「学习」）。页面侧另有一道一次性门（leaveOnce），
                    // 这条兜底管的是不走页面 lambda 的那类返回。
                    onBack = {
                        if (navController.currentBackStackEntry == entry) navController.popBackStack()
                    },
                )
            }
            composable(
                route = MistakeRoutes.CAPTURE,
                arguments = listOf(navArgument("auto") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                MistakeCaptureScreen(
                    viewModel = mistakeViewModel,
                    autoExtract = entry.arguments?.getBoolean("auto") ?: false,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }

            // ── 读书模块子路由 ──────────────────────────────────────
            composable(
                route = BookRoutes.DETAIL,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                BookDetailScreen(
                    bookId = entry.arguments?.getLong("bookId") ?: 0L,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                    onEditBook = { bookId ->
                        navController.navigate(BookRoutes.edit(bookId)) { launchSingleTop = true }
                    },
                    onAddExcerpt = { bookId ->
                        navController.navigate(BookRoutes.excerptCreate(bookId)) { launchSingleTop = true }
                    },
                    onEditExcerpt = { excerptId ->
                        navController.navigate(BookRoutes.excerptEdit(excerptId)) { launchSingleTop = true }
                    },
                    onAddReview = { bookId ->
                        navController.navigate(BookRoutes.reviewCreate(bookId)) { launchSingleTop = true }
                    },
                    onEditReview = { reviewId ->
                        navController.navigate(BookRoutes.reviewEdit(reviewId)) { launchSingleTop = true }
                    },
                )
            }
            composable(BookRoutes.CREATE) {
                BookEditScreen(
                    bookId = null,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = BookRoutes.EDIT,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                BookEditScreen(
                    bookId = entry.arguments?.getLong("bookId") ?: 0L,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = BookRoutes.EXCERPT_CREATE,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                ExcerptEditScreen(
                    excerptId = null,
                    bookId = entry.arguments?.getLong("bookId") ?: 0L,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = BookRoutes.EXCERPT_EDIT,
                arguments = listOf(navArgument("excerptId") { type = NavType.LongType }),
            ) { entry ->
                ExcerptEditScreen(
                    excerptId = entry.arguments?.getLong("excerptId") ?: 0L,
                    bookId = null,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = BookRoutes.REVIEW_CREATE,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                ReviewEditScreen(
                    reviewId = null,
                    bookId = entry.arguments?.getLong("bookId") ?: 0L,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = BookRoutes.REVIEW_EDIT,
                arguments = listOf(navArgument("reviewId") { type = NavType.LongType }),
            ) { entry ->
                ReviewEditScreen(
                    reviewId = entry.arguments?.getLong("reviewId") ?: 0L,
                    bookId = null,
                    viewModel = bookViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    onTabSelected: (Tab) -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    val glass = rememberGlassStyle()
    val shape = remember {
        RoundedCornerShape(
            topStart = AppTheme.radius.lg,
            topEnd = AppTheme.radius.lg,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
    }
    // 亮带只在**切 Tab** 时扫过一次就停（理由见 MotionSpec.SweepMs 的 KDoc）。
    // 减弱动效或玻璃关掉时根本不跑这次动画 —— 不是"跑了但看不见"，是一次重绘都不产生。
    // `AppTheme.settings` 是 @Composable getter，只能在组合层读一次再带进协程里用。
    val reduceMotion = AppTheme.settings.reduceMotion
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(currentRoute, glass.enabled) {
        if (!glass.enabled || reduceMotion) return@LaunchedEffect
        sweep.snapTo(0f)
        sweep.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = MotionSpec.SweepMs, easing = MotionSpec.Easing),
        )
    }
    // tonalElevation = 0：Material3 会按容器色做色调叠加，置 0 才能让 card 原色呈现。
    // 玻璃开着时 containerColor 退成透明，那层底由 glassSurface 自己画（见 glassContainerColor）。
    NavigationBar(
        modifier = Modifier.glassSurface(style = glass, shape = shape, scrollPhase = { sweep.value }),
        containerColor = glassContainerColor(),
        tonalElevation = 0.dp,
    ) {
        Tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            val interaction = remember(tab) { MutableInteractionSource() }
            // pressScale 是 @Composable，只能在 item 组合层取值；graphicsLayer 块非组合，故在里面只读 State
            val scale by rememberPressScale(interaction)
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                interactionSource = interaction,
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        // `contentDescription = null`：这一项**同时**有图标和 `label = Text(tab.label)`，
                        // 二者被合并进同一个语义节点。这里再给 tab.label 就等于念两遍「学习 学习」
                        // （终审 I9；T5 起就挂着这条 carry，本波落地）。图标纯装饰，文字那份即等价朗读。
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                },
                label = { Text(text = tab.label, style = texts.caption) },
                colors = NavigationBarItemDefaults.colors(
                    // 图标是非文本元素（3:1 达标）；文案按 T1 裁定走 accentInk，
                    // accent 在 card 上只有 3.04:1，撑不起 13sp 文本 AA。
                    selectedIconColor = colors.accent,
                    selectedTextColor = colors.accentInk,
                    unselectedIconColor = colors.secondaryText,
                    unselectedTextColor = colors.secondaryText,
                    indicatorColor = colors.accentSoft,
                ),
            )
        }
    }
}
