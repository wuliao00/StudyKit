package com.studykit.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.studykit.ui.components.EmptyState
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
import com.studykit.ui.study.CardStudyScreen
import com.studykit.ui.study.QuestionCreateScreen
import com.studykit.ui.study.StudyHomeScreen
import com.studykit.ui.study.StudyViewModel
import com.studykit.ui.study.WordCreateScreen
import com.studykit.ui.study.WordListScreen
import com.studykit.ui.study.QuizScreen
import com.studykit.ui.theme.DesignTokens

/** 底部 Tab 定义 */
private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Study : Tab("study", "学习", Outlined.Create)
    data object Habit : Tab("habit", "习惯", Outlined.DateRange)
    data object Book : Tab("book", "读书", Outlined.Favorite)
    data object Mistake : Tab("mistake", "错题", Outlined.CheckCircle)
}

private val Tabs = listOf(Tab.Study, Tab.Habit, Tab.Book, Tab.Mistake)

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
    val habitViewModel: HabitViewModel = viewModel()
    val globalCalendarViewModel: GlobalCalendarViewModel = viewModel()
    val bookViewModel: BookViewModel = viewModel()
    val studyViewModel: StudyViewModel = viewModel()
    val mistakeViewModel: MistakeViewModel = viewModel()

    Scaffold(
        containerColor = DesignTokens.Background,
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
        ) {
            composable(Tab.Study.route) {
                StudyHomeScreen(
                    viewModel = studyViewModel,
                    onOpenWords = { navController.navigate(StudyRoutes.WORDS) },
                    onStartQuiz = {
                        studyViewModel.resetQuiz()
                        navController.navigate(StudyRoutes.QUIZ)
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
                    onAddWord = { navController.navigate(StudyRoutes.WORD_CREATE) },
                    onAddQuestion = { navController.navigate(StudyRoutes.QUESTION_CREATE) },
                )
            }
            composable(Tab.Habit.route) {
                HabitListScreen(
                    viewModel = habitViewModel,
                    onOpenHabit = { habitId -> navController.navigate(HabitRoutes.calendar(habitId)) },
                    onAddClick = { navController.navigate(HabitRoutes.CREATE) },
                    onOpenCalendar = { navController.navigate(HabitRoutes.CALENDAR_GLOBAL) },
                )
            }
            composable(Tab.Book.route) {
                BookShelfScreen(
                    viewModel = bookViewModel,
                    onOpenBook = { bookId -> navController.navigate(BookRoutes.detail(bookId)) },
                    onAddClick = { navController.navigate(BookRoutes.CREATE) },
                )
            }
            composable(Tab.Mistake.route) {
                MistakeListScreen(
                    viewModel = mistakeViewModel,
                    onOpenDetail = { id -> navController.navigate(MistakeRoutes.detail(id)) },
                    onOpenCapture = { navController.navigate(MistakeRoutes.CAPTURE) },
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
                        navController.navigate(StudyRoutes.CARDS)
                    },
                )
            }
            composable(StudyRoutes.CARDS) {
                CardStudyScreen(
                    viewModel = studyViewModel,
                    onBack = { navController.popBackStack() },
                    onAddWord = { navController.navigate(StudyRoutes.WORD_CREATE) },
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
                LaunchedEffect(mistakeId) { mistakeViewModel.openDetail(mistakeId) }
                MistakeDetailScreen(
                    viewModel = mistakeViewModel,
                    onBack = { navController.popBackStack() },
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
                    onEditBook = { bookId -> navController.navigate(BookRoutes.edit(bookId)) },
                    onAddExcerpt = { bookId -> navController.navigate(BookRoutes.excerptCreate(bookId)) },
                    onEditExcerpt = { excerptId -> navController.navigate(BookRoutes.excerptEdit(excerptId)) },
                    onAddReview = { bookId -> navController.navigate(BookRoutes.reviewCreate(bookId)) },
                    onEditReview = { reviewId -> navController.navigate(BookRoutes.reviewEdit(reviewId)) },
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
    NavigationBar(containerColor = DesignTokens.Card) {
        Tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(text = tab.label, style = DesignTokens.Caption) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = DesignTokens.Accent,
                    selectedTextColor = DesignTokens.Accent,
                    unselectedIconColor = DesignTokens.SecondaryText,
                    unselectedTextColor = DesignTokens.SecondaryText,
                    indicatorColor = DesignTokens.Accent.copy(alpha = 0.10f),
                ),
            )
        }
    }
}

/** 其余三个模块的占位页 */
@Composable
private fun PlaceholderTab(title: String, caption: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DesignTokens.Background)
            .padding(horizontal = DesignTokens.PageHorizontalPadding),
    ) {
        Spacer(Modifier.height(DesignTokens.SpacingSm))
        Text(text = title, style = DesignTokens.LargeTitle)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(title = "${title}模块", caption = caption)
        }
    }
}
