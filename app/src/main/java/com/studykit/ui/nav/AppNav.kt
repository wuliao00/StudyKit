package com.studykit.ui.nav

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import com.studykit.ui.habit.GlobalCalendarScreen
import com.studykit.ui.habit.GlobalCalendarViewModel
import com.studykit.ui.habit.HabitCalendarScreen
import com.studykit.ui.habit.HabitCreateScreen
import com.studykit.ui.habit.HabitListScreen
import com.studykit.ui.habit.HabitViewModel
import com.studykit.ui.mistake.MistakeCaptureScreen
import com.studykit.ui.mistake.MistakeDetailScreen
import com.studykit.ui.mistake.MistakeListScreen
import com.studykit.ui.mistake.MistakeViewModel
import com.studykit.ui.motion.MotionSpec
import com.studykit.ui.motion.rememberPressScale
import com.studykit.ui.study.CardStudyScreen
import com.studykit.ui.study.QuestionCreateScreen
import com.studykit.ui.study.QuizScreen
import com.studykit.ui.study.StudyHomeScreen
import com.studykit.ui.study.StudyViewModel
import com.studykit.ui.study.WordCreateScreen
import com.studykit.ui.study.WordListScreen
import com.studykit.ui.theme.AppTheme

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
    const val CAPTURE = "mistake/capture"
    fun detail(mistakeId: Long) = "mistake/detail/$mistakeId"
}

/** 应用主导航：底部 4 Tab + 习惯/读书模块子路由 */
@Composable
fun AppNav() {
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

    Scaffold(
        containerColor = AppTheme.colors.background,
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
                        navController.navigate(MistakeRoutes.CAPTURE) { launchSingleTop = true }
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
                    onBack = { navController.popBackStack() },
                    onStartStudy = {
                        studyViewModel.startCardSession()
                        navController.navigate(StudyRoutes.CARDS) { launchSingleTop = true }
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
            composable(MistakeRoutes.CAPTURE) {
                MistakeCaptureScreen(
                    viewModel = mistakeViewModel,
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
    // tonalElevation = 0：Material3 会按容器色做色调叠加，置 0 才能让 card 原色呈现
    NavigationBar(
        containerColor = colors.card,
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
                        contentDescription = tab.label,
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
