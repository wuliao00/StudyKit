package com.studykit.ui.study

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.AppContainer
import com.studykit.StudyKitApp
import com.studykit.data.AppDatabase
import com.studykit.data.dao.QuestionDao
import com.studykit.data.dao.StageQuestionCount
import com.studykit.data.dao.SubjectStageFormatCount
import com.studykit.data.dao.SubjectTypeCount
import com.studykit.data.entity.Question
import com.studykit.data.repository.QuestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 假 DAO：只实现组轮入口用到的那三条读查询，语义照抄 `QuestionDao` 的 `@Query`
 * （`WHERE subject = :subject ORDER BY id LIMIT :limit OFFSET :offset`、
 * `SELECT DISTINCT subject FROM questions ORDER BY subject`），并把每次 `getBySubject`
 * 的入参记进 [queries]。
 *
 * 其余方法按 [com.studykit.ui.habit.HabitTargetDaysTest] 的先例直接报错 ——
 * 入口要是多走了一条没写在这里的路，测试就该炸给我看，而不是静默通过。
 */
private class RecordingQuestionDao : QuestionDao {
    val rows = mutableListOf<Question>()
    val queries = mutableListOf<Triple<String, Int, Int>>()
    var subjectsReads = 0

    override suspend fun getBySubject(subject: String, limit: Int, offset: Int): List<Question> {
        queries += Triple(subject, limit, offset)
        return rows.filter { it.subject == subject }
            .sortedBy { it.id }
            .drop(offset)
            .take(limit)
    }

    override suspend fun getBySubjectAndType(
        subject: String,
        type: String,
        limit: Int,
        offset: Int,
    ): List<Question> {
        queries += Triple(subject, limit, offset)
        return rows.filter { it.subject == subject && it.questionType == type }
            .sortedBy { it.id }
            .drop(offset)
            .take(limit)
    }

    override suspend fun getBySubjectFiltered(
        subject: String,
        stage: String?,
        type: String?,
        limit: Int,
        offset: Int,
    ): List<Question> {
        queries += Triple(subject, limit, offset)
        return rows.filter {
            it.subject == subject &&
                (stage == null || it.examStage == stage) &&
                (type == null || it.questionType == type)
        }.sortedBy { it.id }.drop(offset).take(limit)
    }

    override suspend fun getBySubjectAndTypes(
        subject: String,
        stage: String?,
        types: List<String>,
        limit: Int,
        offset: Int,
    ): List<Question> {
        queries += Triple(subject, limit, offset)
        return rows.filter {
            it.subject == subject &&
                (stage == null || it.examStage == stage) &&
                it.questionType in types
        }.sortedBy { it.id }.drop(offset).take(limit)
    }

    override fun observeSubjectStageFormatCounts(): Flow<List<SubjectStageFormatCount>> = flow {
        emit(
            rows.groupBy { Triple(it.subject, it.examStage, it.questionType) }
                .map { (key, items) ->
                    SubjectStageFormatCount(key.first, key.second, key.third, items.size)
                }
                .sortedWith(compareBy({ it.subject }, { it.stage }, { it.type })),
        )
    }

    override fun observeSubjectTypeCounts(): Flow<List<SubjectTypeCount>> = flow {
        emit(
            rows.groupBy { it.subject to it.questionType }
                .map { (key, items) -> SubjectTypeCount(key.first, key.second, items.size) }
                .sortedWith(compareBy({ it.subject }, { it.type })),
        )
    }

    /**
     * v3.5：题库商店的「已入库 N 题」用。组轮入口不读它，这里按 `exam_stage` 分组照抄 SQL 语义即可 ——
     * 真排序（最久没练优先）在 [com.studykit.data.dao.QuestionRotationOrderTest] 里对真 SQL 测。
     */
    override fun observeCountsByStage(): Flow<List<StageQuestionCount>> = flow {
        emit(rows.groupBy { it.examStage }.map { (stage, items) -> StageQuestionCount(stage, items.size) })
    }

    override suspend fun countBySubjectAndType(subject: String, type: String): Int =
        rows.count { it.subject == subject && it.questionType == type }

    override fun observeSubjects(): Flow<List<String>> = flow {
        subjectsReads++
        emit(rows.map { it.subject }.distinct().sorted())
    }

    override suspend fun getById(id: Long): Question? = unused("getById")
    override fun observeAll(): Flow<List<Question>> = unused("observeAll")
    override suspend fun countBySubject(subject: String): Int = unused("countBySubject")
    override suspend fun getByConceptTag(tag: String): List<Question> = unused("getByConceptTag")
    override suspend fun insert(question: Question): Long = unused("insert")
    override suspend fun insertAll(questions: List<Question>): List<Long> = unused("insertAll")
    override suspend fun deleteAll(): Unit = unused("deleteAll")

    private fun unused(name: String): Nothing =
        error("组轮入口不该走 QuestionDao.$name —— 走到了就是接线变了，先看这条再改期望值")
}

/**
 * 假 DAO 的转接口：[QuizQueueWiringTestApp] 在**构造**容器时把挂在 [RecordingQuestionDao] 上的仓储
 * 交给 [AppContainer] 的 `questionRepository` 参数，用例再从这里取回那只假 DAO 数查询次数。
 *
 * ## 为什么要假 DAO，而不是仿仓储
 * [StudyViewModel] 拿仓储只有一条路：`(application as StudyKitApp).container.questionRepository`，
 * 而 [AppContainer] 是 final 类、`questionRepository` 是 final val、[QuestionRepository] 本身也是 final 类
 * （`data/repository/QuestionRepository.kt`）—— 于是**仿不了仓储类**，只能换它底下那只 DAO。
 * 仓内既有先例是替 DAO 接口（`ui/habit/HabitTargetDaysTest.kt:129-158`），那一手在仓储测试里够用，
 * 因为那里的仓储是测试自己 `new` 的；这里仓储必须住在容器里，所以走 [AppContainer] 的那一个注入点：
 * 默认值就是生产原来那句构造表达式，被换掉的只是测试进程里这一份容器实例用哪只仓储。
 *
 * ## 这一档钉得住什么、钉不住什么
 * 钉得住：取数次数（"关掉就真的关掉"里的那一次 IO）、取数参数（limit/offset 的档位）、
 * 以及"另一科的题没被读"。钉不住：Room 那条 SQL 的真实语义（列映射、排序稳定性），
 * 那一份由 `QuestionDaoTest` 一类仓储级用例继续钉。
 */
private object WiringHarness {
    @Volatile
    var dao: RecordingQuestionDao? = null

    fun requireDao(): RecordingQuestionDao =
        dao ?: error("假 DAO 没装上：@Config(application = QuizQueueWiringTestApp::class) 用错了吗")
}

/**
 * 本文件唯一的容器覆写点：真库换成一次性内存库（姿势同 [MockExamResumeTestApp]），
 * 构造这一只容器时就把挂在 [RecordingQuestionDao] 上的那**一个**仓储交进去。
 * 其余仓储（settings / mockSession / practice / reviewLog）仍走真 Room —— 模考那两条用例要真落一行卷。
 * Robolectric 每个用例重建 Application ⇒ 每个用例一只干净的假 DAO。
 */
class QuizQueueWiringTestApp : StudyKitApp() {
    override fun createContainer(): AppContainer {
        val db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val fake = RecordingQuestionDao()
        WiringHarness.dao = fake
        return AppContainer(db, QuestionRepository(fake, db.practiceDao()))
    }
}

/**
 * 组轮入口的接线守卫（v3.0-C Task 7）：`settings.semanticInterleaveEnabled` 的第一批生产读点。
 *
 * 前面几层（T3 打分器 / T4 队列算法 / T5 装配层 / T6 设置行）都是**没有生产调用者**的纯层，
 * 这一份是第一个把真题交给 `planQuestionQueue` 的地方，所以每条用例都从 ViewModel 起手、
 * 断言的是 `vm.quiz.value`（用户看得见的那一份），不是内部返回值。
 *
 * ## 夹具的期望值怎么来的
 * 每份夹具的题干都先交给 `TextSimilarity` 的同一套算术（字符二元组 Dice + 同考点地板 0.65f）逐对算过，
 * 再对着 `LexicalInterleave.DEFAULT`（tauHi 0.60 / tauLo 0.15 / 混淆带 0.40..0.75 / gap 3..5 /
 * lookahead 12 / maxRepairRounds 4）手推末序，逐条写在用例注释里。
 * `QUIZ_SIZE` / `MOCK_EXAM_SIZE` 是 `private const`，所以下面的 10 与 20 全是**字面量**
 * （⛔ 不许读生产常量当期望值）。
 * 每份夹具里除注释点名的那几对外，**任意两题的分值都够不着 `drillLo = 0.40`，更够不着 `tauHi = 0.60`** ——
 * 但别把它读成「分值都是 0f」，那句是假话（修复轮 I-1 改的就是它）：逐对重算下来有一批对子落在
 * **0.07..0.30** 这一档（`mixedBank` 最高的是 (9,12) = 0.2963、英语簇 (6,8) = 0.2857 / (5,8) = 0.2593；
 * `confusableMathBank` 缓冲池（ids 11..14）内部两两沾了字的那几对：(11,13) = 0.1667、(11,14) = 0.1905、
 * (13,14) = 0.1739（池里那张 id 12 与谁都 0f）；`dominantBank` 的 (12,13) = 0.1538）。
 * 于是"没有相似题时不许动手"这一档在夹具上仍然是可证的 —— 靠的是**上限在 drillLo / tauHi 之下**，
 * 不是靠"彼此一个字都不沾"。另外 id 不是跨夹具的身份：同为 (6,8) 那一对，在 [confusableMathBank] 里是
 * 点名的同文对（1.0f），在 [mixedBank] 里只是两句英语题干（0.2857）。
 * 重算用的替身与全矩阵输出留在
 * `.superpowers/sdd/2026-10-09-studykit-v3.0-c-semantic-interleave/t7fix-probe.py` / `t7fix-probe-out.txt`
 * （题面直接从本文件解析，不手抄）。
 *
 * ## 不测什么
 * `planQuestionQueue` / `QueueBuilder` 的内部性质由 `QuestionQueuePlannerTest`、`QueueBuilder*Test` 继续钉；
 * 这里只钉**调用点**：谁读了哪一个设置、传了哪一档、取了几次库、`drillIds` 有没有真走到 UI 状态。
 * 会话内那一行的渲染由 [QuizDrillNoteRenderTest] 钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = QuizQueueWiringTestApp::class)
class StudyQueueWiringTest {

    private val app get() = ApplicationProvider.getApplicationContext<StudyKitApp>()
    private val dao get() = WiringHarness.requireDao()

    // ── 夹具：题干两两之间最高 0.30（都在 drillLo/tauHi 之下），点名的那几对除外 ──

    private fun q(id: Long, subject: String, stem: String, tag: String = "") = Question(
        id = id,
        uuid = "wire-$id",
        subject = subject,
        stem = stem,
        optionsJson = JSONArray().apply { put("甲"); put("乙"); put("丙"); put("丁") }.toString(),
        answerIndex = 1,
        explanation = "解析",
        conceptTag = tag,
    )

    /** 14 张同科（数学）的题面：ids 1..10 落进会话，ids 11..14 只能从 offset 10 那一次查询进来当缓冲池 */
    private val mathStems = listOf(
        "求二次函数y等于x方加2x的最小值",
        "已知三角形三边长求它的面积",
        "圆柱的底面半径扩大两倍体积怎么变",
        "等差数列首项为三公差为二求第十项",
        "抛两枚均匀硬币都正面朝上的概率",
        "计算下列各式的值甲",
        "抛物线开口方向由哪个系数决定",
        "计算下列各式的值，甲（　）",     // 与第 6 句去掉标点后**完全同文** ⇒ Dice = 1.0
        "直角坐标系中点P到原点的距离",
        "一组数据的平均数与中位数相等吗",
        "牛顿第三定律的内容是什么",
        "水的沸点在标准大气压下是多少",
        "氧气在工业上的制取方法是什么",
        "细胞核的主要功能是什么",
    )

    /**
     * 会话 1..10 + 缓冲池 11..14，其中 1 与 7 **共用考点标签**（题干互不相像 ⇒ 分值走地板 0.65f：
     * 既 > tauHi 0.60 会被隔开，又落在混淆带 0.40..0.75 内 ⇒ 一个夹具同时钉"隔开"与"挂提示"两件事）。
     * 另有一对 6/8 是同文重复题（1.0f，在带**外**）。
     */
    private fun confusableMathBank() = mathStems.mapIndexed { i, stem ->
        q(id = (i + 1).toLong(), subject = "数学", stem = stem, tag = if (i == 0 || i == 6) "二次函数最值" else "")
    }

    /** 同一批题面、标签全部留空，且第 8 句换成另一道题（于是**没有任何一对**落进混淆带或越过 tauHi） */
    private fun quietMathBank(): List<Question> =
        confusableMathBank().map { question ->
            if (question.id == 8L) q(8L, "数学", "解一元一次方程移项要变号吗") else question.copy(conceptTag = "")
        }

    /** 只有 10 张会话卡（没有第 11 张 ⇒ 开着语义层时缓冲池是空的）；第 1、2 张是同文重复题 */
    private fun duplicateAdjacentBank(): List<Question> = listOf(
        q(1L, "数学", "计算下列各式的值甲"),
        q(2L, "数学", "计算下列各式的值，甲（　）"),
        q(3L, "数学", "已知三角形三边长求它的面积"),
        q(4L, "数学", "圆柱的底面半径扩大两倍体积怎么变"),
        q(5L, "数学", "等差数列首项为三公差为二求第十项"),
        q(6L, "数学", "抛两枚均匀硬币都正面朝上的概率"),
        q(7L, "数学", "抛物线开口方向由哪个系数决定"),
        q(8L, "数学", "直角坐标系中点P到原点的距离"),
        q(9L, "数学", "一组数据的平均数与中位数相等吗"),
        q(10L, "数学", "牛顿第三定律的内容是什么"),
    )

    /**
     * 三科各 4 张（数学 1..4 / 英语 5..8 / 语文 9..12），1 与 4 共用考点标签。
     * `observeSubjects()` 按 subject 排序 ⇒ 合并序列恒是 [1..4, 5..8, 9..12]（**按学科分组**，
     * 正是"先截断后打散会退化成单科"的那个形状）。
     */
    private fun mixedBank(): List<Question> = listOf(
        q(1L, "数学", "求二次函数y等于x方加2x的最小值", "二次函数最值"),
        q(2L, "数学", "已知三角形三边长求它的面积"),
        q(3L, "数学", "圆柱的底面半径扩大两倍体积怎么变"),
        q(4L, "数学", "抛物线开口方向由哪个系数决定", "二次函数最值"),
        q(5L, "英语", "Choose the correct past tense form"),
        q(6L, "英语", "Fill in the blank with a preposition"),
        q(7L, "英语", "Which word means to reduce in price"),
        q(8L, "英语", "Read the passage and answer the question"),
        q(9L, "语文", "下列句子中没有语病的一项是"),
        q(10L, "语文", "选出加点字注音完全正确的一项"),
        q(11L, "语文", "仿写句子要求句式一致修辞相同"),
        q(12L, "语文", "古诗文默写补写出下列句子中的空缺"),
    )

    /**
     * 一科独大的**库存**形状：数学 10 张（题面取自 [quietMathBank]，标签全空）+ 英语 2 张 = 12 张。
     * 只有这种形状才测得出"截断发生在打散之前还是之后"：先截 10 条就是**清一色数学**。
     *
     * 数学那一份写的是 `take(10)` 而不是 `take(11)` —— 这是修复轮 2 对 I-2 点名的那具死数据的**处置**
     * （上一轮只标不删，裁定是 comment-only）：入口的合并序列是 `subjects.flatMap { getBySubject(it, QUIZ_SIZE, 0) }`，
     * 每科取数上限就是 `QUIZ_SIZE` ⇒ 数学的第 11 张压根到不了这一轮 —— 它取不回、断言碰不到、
     * 连"一科独大"这个形状都不靠它（数学 10 张本身就够让「先截 10 条」截出清一色数学）。
     * 删掉它之后送进学科层的还是同样那 12 条、同样的末序，所以下面那一条的每个断言一字未改。
     */
    private fun dominantBank(): List<Question> =
        quietMathBank().take(10) + listOf(
            q(12L, "英语", "Choose the correct past tense form"),
            q(13L, "英语", "Fill in the blank with a preposition"),
        )

    /**
     * F8 的夹具：**会话十张的最后两张是同文重复题**（ids 9 与 10，去掉标点后逐字相同 ⇒ 1.0f），
     * 库里再留一张 id 11（`offset = QUIZ_SIZE` 那一次才取得到它 ⇒ 它只能当缓冲卡）。
     * 这一份的形状是"修不动的相邻违规"里最小的一个：换位窗口是 `[i+2, i+1+lookahead]` 且右端截到队尾，
     * 违规踩在下标 8 与 9 上时窗口是 `[10, 9]` = **空** ⇒ 换不了位 ⇒ 只剩拉缓冲卡这一招。
     * 标签全空、其余两两分值最高 0.1739（(7,8)，`drillLo = 0.40` 之下）⇒ 题面层第二遍一对都够不着 ⇒
     * `relocated` 0、不追加复扫、`drillIds` 空集（本条要钉的是**轮次长度**，不是提示）。
     */
    private fun pulledBufferBank(): List<Question> = listOf(
        q(1L, "数学", "直角坐标系中点P到原点的距离"),
        q(2L, "数学", "一组数据的平均数与中位数相等吗"),
        q(3L, "数学", "抛两枚均匀硬币都正面朝上的概率"),
        q(4L, "数学", "圆柱的底面半径扩大两倍体积怎么变"),
        q(5L, "数学", "已知三角形三边长求它的面积"),
        q(6L, "数学", "水的沸点在标准大气压下是多少"),
        q(7L, "数学", "细胞核的主要功能是什么"),
        q(8L, "数学", "氧气在工业上的制取方法是什么"),
        q(9L, "数学", "已知等比数列求前n项和的公式"),
        q(10L, "数学", "已知等比数列，求前n项和的公式（　）"),  // 与第 9 句去掉标点后**完全同文** ⇒ 1.0f
        q(11L, "数学", "下列图形中是轴对称图形的是哪一个"),      // 缓冲卡：与 9 / 10 两侧都是 0f < tauLo
    )

    // ── 起跑姿势 ────────────────────────────────────────────────

    private suspend fun applySettings(interleaving: Boolean, semantic: Boolean) {
        app.container.settingsRepository.update {
            it.copy(interleavingEnabled = interleaving, semanticInterleaveEnabled = semantic)
        }
    }

    /** 同 [MockSessionResumeWriterTest] 的 awaitTrue：VM 里是 viewModelScope + Room suspend，主线程反复 idle 等它跑完 */
    private suspend fun idleUntil(what: String, read: () -> Boolean) {
        val stopAt = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < stopAt) {
            shadowOf(Looper.getMainLooper()).idle()
            if (read()) return
            Thread.sleep(2L)
        }
        throw AssertionError("等不到$what")
    }

    private suspend fun startQuizWith(bank: List<Question>, subject: String, interleaving: Boolean, semantic: Boolean): QuizUiState {
        dao.rows.clear()
        dao.rows.addAll(bank)
        dao.queries.clear()
        dao.subjectsReads = 0
        applySettings(interleaving, semantic)
        val vm = StudyViewModel(app)
        vm.startQuiz(subject)
        idleUntil("startQuiz 组好这一轮") { vm.quiz.value.started }
        return vm.quiz.value
    }

    private suspend fun startMixedWith(bank: List<Question>, interleaving: Boolean, semantic: Boolean): QuizUiState {
        dao.rows.clear()
        dao.rows.addAll(bank)
        dao.queries.clear()
        dao.subjectsReads = 0
        applySettings(interleaving, semantic)
        val vm = StudyViewModel(app)
        vm.startMixedQuiz()
        idleUntil("startMixedQuiz 组好这一轮") { vm.quiz.value.started }
        return vm.quiz.value
    }

    private fun ids(state: QuizUiState) = state.questions.map { it.id }

    // ── ① 回退链：关掉语义层 ⇒ 队列 == 学科交错那一档，且一个提示都不挂 ──────

    /**
     * ⛔ 简报 Step 1 第 1 条。夹具是 [confusableMathBank]（1 与 7 同考点、6 与 8 同文）——
     * **开着就会动**的那一份，所以"关着不许动"才是有牙齿的断言（拿一份永不动的夹具测回退链是空转）。
     *
     * 杀的变异（实跑标记见报告）：
     *  - MU-A：入口把 `semantic` 传成死值 true（= 压根不读开关）⇒ 第一句与第二句红（7 挪到第 3 格）；
     *  - 本条**不**钉"drillIds 没接线"那一刀（它在这里恒为真，关着本来就该是空集）；
     *    开着那一侧由「生产路径上drill真的把卡挪到新位置并且跑过复扫」钉（实跑 MU-B：把
     *    `drillIds = plan.drillIds` 换成 `emptySet()` ⇒ 那条红）。
     */
    @Test
    fun `关掉语义层时队列与学科交错一致且没有提示 id`() = runBlocking {
        val state = startQuizWith(confusableMathBank(), "数学", interleaving = true, semantic = false)
        val session = confusableMathBank().take(10)
        assertEquals(
            "关着语义层时队列必须逐元素等于学科层那一档（回退链不许跳格）",
            interleaveBySubject(session, enabled = true).map { it.id },
            ids(state),
        )
        // ⛔ 字面量第二道闸：上面那句拿的是同一个纯函数，这里把末序本身钉死
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), ids(state))
        assertTrue("关着语义层不许有任何 drill id: ${state.drillIds}", state.drillIds.isEmpty())
        assertFalse("当前这题（第 1 张）不该被认成混淆对里靠后的那张", state.currentIsDrill)
    }

    // ── ② 开着语义层：同考点的一对被隔开，提示挂在靠后那张 ──────────────────

    /**
     * ⛔ 简报 Step 1 第 2 条（走综合练习入口）。手推（[mixedBank]，两个开关都开）：
     *  1. 合并序列 = [1,2,3,4,5,6,7,8,9,10,11,12]（按学科分组）；
     *  2. 学科层在**整份**序列上贪心（三桶各 4、平手取先出现的）⇒ [1,5,9,2,6,10,3,7,11,4,8,12]；
     *  3. 截断：会话 = 前 10 = [1,5,9,2,6,10,3,7,11,4]，缓冲池 = [8,12]；
     *  4. 题面层：会话内九对相邻全 0f ⇒ 第一遍没有违规 ⇒ `pulled` 0；混淆映射只有 {1:[4], 4:[1]}
     *     （同科同考点 0.65f），1 在下标 0、4 在下标 9 ⇒ 距离 9 在 3..5 之外 ⇒ 挪 4 到 anchor(0)+gapLo(3)=3
     *     （左右邻 9 与 2 都 0f ⇒ 落点合格）⇒ 末序 [1,5,9,4,2,6,10,3,7,11]，复扫没有新违规。
     * 杀的变异（三条都实跑过）：
     *  - MU-C：入口把学科层再跑一遍（`interleaving = settings.interleavingEnabled`）⇒ 第一句红，
     *    实际末序 [1, 5, 2, 4, 9, 3, 6, 10, 7, 11]；
     *  - MU-D：先截断后打散（`session = pool.take(QUIZ_SIZE)`）⇒ 第一句红，实际 [1, 5, 2, 4, 6, 3, 7, 9, 8, 10]；
     *  - MU-N：`drillNoteIds` 把提示挂到靠前那张 ⇒ 第四句红（expected [4] but was [1]）。
     */
    @Test
    fun `打开语义层时两道同考点题被隔开并给后者挂提示`() = runBlocking {
        val state = startMixedWith(mixedBank(), interleaving = true, semantic = true)
        assertEquals(listOf(1L, 5L, 9L, 4L, 2L, 6L, 10L, 3L, 7L, 11L), ids(state))
        val pos1 = state.questions.indexOfFirst { it.id == 1L }
        val pos4 = state.questions.indexOfFirst { it.id == 4L }
        assertTrue(
            "同考点的一对至少隔 2 格（这里推出来是 gapLo=3）: pos1=$pos1 pos4=$pos4",
            pos4 - pos1 >= 2,
        )
        assertEquals("提示挂在靠后那张（4），不是靠前那张（1）", setOf(4L), state.drillIds)
    }

    /**
     * ⛔ 简报 Step 1 第 3 条：开着语义层但题面互不相像 ⇒ 末序与关着一格不差。
     * 这是回退链**最上**一档的 VM 侧证据（`QuestionQueuePlannerTest` 在纯层钉过同一性质，
     * 这里钉的是"入口真的把那条性质交给了用户"）。夹具 [quietMathBank] 的**会话那十张（ids 1..10）两两 0f**
     * ⇒ 第一遍没有违规、第二遍映射为空；缓冲池 11..14 那一侧**不是** 0f（逐对重算：(11,13) = 0.1667、
     * (11,14) = 0.1905、(13,14) = 0.1739，外加跨会话/池的 (5,13) = 0.0741）—— 修好的是这句话，不是结论：
     * 这四对全在 `drillLo = 0.40` 之下 ⇒ 进不了混淆映射；而拉池那一步要求"池卡与左右两邻都 `< tauLo`"，
     * 本轮没有违规可修 ⇒ 压根走不到那里（"会话内互不相像"与"池内部有点沾字"这两件事互不干扰）。
     * 杀的变异：M5（入口传错 session/pool —— 比如把缓冲池并进 session ⇒ 会话多出 11..14，
     * 两句同时红；实跑见报告的 MU-K2）—— 本条在入口这一头钉的就是这一刀。
     * 「没有相似题时装配层不许动手」那一半（`urgency` 编码）住在纯层，由
     * `QuestionQueuePlannerTest.语义遍开但题互不相像时输出与不开完全相同` 钉，本条不重复声称钉得住它。
     */
    @Test
    fun `语义层开着但题互不相像时顺序与关着一样`() = runBlocking {
        val off = startQuizWith(quietMathBank(), "数学", interleaving = true, semantic = false)
        val on = startQuizWith(quietMathBank(), "数学", interleaving = true, semantic = true)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), ids(off))
        assertEquals("没有相似题时开与关必须逐格相同", ids(off), ids(on))
        assertTrue("没有成对就没有提示: ${on.drillIds}", on.drillIds.isEmpty())
    }

    // ── ③ 模考不受这一层影响（偏差 D8）──────────────────────────────

    /**
     * ⛔ 简报 Step 1 第 4 条：开关一开一关各起一卷，题序必须一致，而且**取数一次都没多**。
     * 夹具用 [confusableMathBank]（1 与 7 同考点）—— 哪天有人把模考也接进 `planQuestionQueue`，
     * 7 会从第 7 格挪到第 3 格，第一句当场红；他把缓冲池也接上的话，第三句（每次开卷恒一次取数）红。
     * 模考整卷 20 题（`MOCK_EXAM_SIZE`）而库里只有 14 道 ⇒ 有多少出多少，14 道全在卷上（⛔ 字面量）。
     */
    @Test
    fun `模考不受开关影响`() = runBlocking {
        dao.rows.clear()
        dao.rows.addAll(confusableMathBank())
        dao.queries.clear()
        applySettings(interleaving = true, semantic = false)
        val off = StudyViewModel(app)
        off.startMockExam("数学")
        idleUntil("关着开模考") { off.mockExam.value.started }

        applySettings(interleaving = true, semantic = true)
        val on = StudyViewModel(app)
        on.startMockExam("数学")
        idleUntil("开着开模考") { on.mockExam.value.started }

        val offIds = off.mockExam.value.questions.map { it.id }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L), offIds)
        assertEquals("语义开关不许改变模考题序（D8）", offIds, on.mockExam.value.questions.map { it.id })
        assertEquals(
            "两次开卷各只取一次库（模考既不跑装配层、也不取缓冲池）: ${dao.queries}",
            listOf(Triple("数学", 20, 0), Triple("数学", 20, 0)),
            dao.queries,
        )
    }

    // ── ④ 取数次数：关掉连那次 IO 都不做 ────────────────────────────

    /**
     * ⛔ 简报 Step 1 第 5 条。计数打在**假 DAO** 上（`QuestionRepository` 是 final 类，仿不动，
     * 理由见 [WiringHarness]）。关着 1 次 `(数学,10,0)`；开着 2 次，第二条的 offset 必须是 10
     * （排在会话之后那一段）——写成 0 就是在跟本轮抢同一批题，装配层按 id 滤重之后池子恒空。
     * 杀的变异（两条都实跑过）：MU-F（`semanticPool` 无视开关 —— 少一次 IO 不是性能优化，
     * 是"关掉就真的关掉"的兑现 ⇒ 第一句红）；MU-G（池的 offset 写 0 ⇒ 第二句红）。
     */
    @Test
    fun `开语义层时才会多取一次候选池`() = runBlocking {
        startQuizWith(confusableMathBank(), "数学", interleaving = false, semantic = false)
        assertEquals(
            "关着语义层不许查第二次库: ${dao.queries}",
            listOf(Triple("数学", 10, 0)),
            dao.queries,
        )

        startQuizWith(confusableMathBank(), "数学", interleaving = false, semantic = true)
        assertEquals(
            "开着语义层要取缓冲池（同一科、排在第 10 题之后那段）: ${dao.queries}",
            listOf(Triple("数学", 10, 0), Triple("数学", 10, 10)),
            dao.queries,
        )
    }

    /**
     * 综合练习**不调** `semanticPool`：它的缓冲池是从已经取回的合并序列里切的（`ordered.drop(10)`）。
     * 开着语义层时三科各取一次、offset 恒 0、学科清单只读一次，就是这一句的全部含义。
     * 杀的变异：MU-H（实跑 —— "顺手统一"成两边都查库 ⇒ 综合练习每轮多读 N 次，N = 学科数）。
     */
    @Test
    fun `综合练习开着语义层也不额外查库`() = runBlocking {
        startMixedWith(mixedBank(), interleaving = true, semantic = true)
        assertEquals(
            "每科一次、offset 恒 0：${dao.queries}",
            listOf(Triple("数学", 10, 0), Triple("英语", 10, 0), Triple("语文", 10, 0)),
            dao.queries,
        )
        assertEquals("学科清单只读一次", 1, dao.subjectsReads)
    }

    // ── ⑤ 学科层只跑一次，而且跑在截断之前（简报的两道机检）───────────────

    /**
     * 机检 1/2：综合练习这一轮 == 「先在**整份**合并序列上跑学科层 → 前 10 条交给
     * `planQuestionQueue(interleaving = false)`」。
     *
     * 这里 `interleaving = false` 就是**被测的那件东西**：把它改回 `settings.interleavingEnabled`
     *（= 学科层跑第二遍）这一条就红 —— 第二遍在本夹具上不幂等：会话里数学 4 / 英语 3 / 语文 3，
     * 重排贪心给出 [1,5,2,9,3,6,10,4,7,11]，与正确那一档喂进装配层的 [1,5,9,2,6,10,3,7,11,4] 不同，
     * drill 落点跟着一起漂。
     * 期望值走的是同一条**纯函数**链，所以本条钉的是**调用点形状**，不重复证明装配算法；
     * 末序本身另有下面那条 ⛔ 字面量当独立证人。
     */
    @Test
    fun `综合练习的学科层只在整份合并序列上跑一次`() = runBlocking {
        val state = startMixedWith(mixedBank(), interleaving = true, semantic = true)
        val ordered = interleaveBySubject(mixedBank(), enabled = true)
        val expected = planQuestionQueue(
            session = ordered.take(10),
            pool = ordered.drop(10),
            interleaving = false,
            semantic = true,
        )
        assertEquals(expected.questions.map { it.id }, ids(state))
        assertEquals("⛔ 手推字面量", listOf(1L, 5L, 9L, 4L, 2L, 6L, 10L, 3L, 7L, 11L), ids(state))
    }

    /**
     * 机检 2/2：库里 ≥ 2 科时，这一轮里出现的**不同学科数 ≥ 2**。
     * 上面那条挡不住"把外层学科层删掉、同时把 interleaving 也传下去"那一刀（那条照旧绿），
     * 也挡不住"先截断后打散"—— [dominantBank] 是**一科独大**（库里数学 10 / 英语 2）的夹具：
     * 先截 10 条就是清一色数学，这一条才看得见。
     * 手推（贪心拿的就是夹具那 12 条 —— 合并序列 = `subjects.flatMap { getBySubject(it, 10, 0) }`，每科上限 10；
     * 修复轮 I-2 改的是旧稿这里写的「数学 11 + 英语 2 的贪心：[1,12,2,13,3,4,5,6,7,8,9,10,11]」那 13 个元素，
     * 修复轮 2 接着把夹具里那具到不了本轮的第 11 张数学删掉了，见 [dominantBank] 的注释）：
     * 合并 [1,2,3,4,5,6,7,8,9,10,12,13] ⇒ 贪心 12 条 [1,12,2,13,3,4,5,6,7,8,9,10] ⇒ 会话取前 10 =
     * [1,12,2,13,3,4,5,6,7,8]（数学 8 + 英语 2）、缓冲池 = drop(10) = [9,10]；
     * 题面层一对都够不着（标签全空；会话那 10 张逐对重算**只有一个非零分值** —— 英语那一对 (12,13) = 0.1538，
     * 其余两两 0f。旧稿在这里补的「其次 (5,13) = 0.0741」是假话，修复轮 2 删掉了它：那个 0.0741 属于
     * [quietMathBank] / [confusableMathBank]，那里的 id 13 是「氧气在工业上的制取方法是什么」，而本夹具的 id 13
     * 是英语的「Fill in the blank with a preposition」，与夹具里数学那 10 张（ids 1..10）逐对都是 0f —— 正是本文件类 KDoc
     * 那句「id 不是跨夹具的身份」点名的错法。唯一的非零对 0.1538 也在 `drillLo = 0.40` 之下，而 12 与 13
     * 那一轮里中间还隔着一张 id 2，连相邻都算不上）⇒ 不挂提示也不动手 ⇒ 末序不变。
     * 杀的变异（实跑 MU-D = 先 `take(QUIZ_SIZE)` 再打散 ⇒ 会话是清一色数学，本条第一句红，
     * 失败信息就是「只出现 1 个学科就是退化成了单科练习: [数学]」）。
     * 「学科层压根没跑」（enabled 写死 false）那一刀看得见的是**同一句**（末序同样是分组序、同样一科），
     * 本轮没有单独跑它 —— 记在这里，不当已验。
     */
    @Test
    fun `综合练习这一轮里至少出现两个学科`() = runBlocking {
        val state = startMixedWith(dominantBank(), interleaving = true, semantic = true)
        // 学科数那句**先判**：这条用例的失败只应该是「退化成单科」这一件事显形。
        // 把字面量放前面，JUnit 只会报顺序那句，读起来像"期望值推错了"而不是"综合练习成了一科"。
        assertTrue(
            "只出现 1 个学科就是退化成了单科练习: ${state.questions.map { it.subject }.distinct()}",
            state.questions.map { it.subject }.distinct().size >= 2,
        )
        assertEquals(listOf(1L, 12L, 2L, 13L, 3L, 4L, 5L, 6L, 7L, 8L), ids(state))
    }

    // ── ⑥ drill 在生产路径上真的把一张卡挪到了新位置 ──────────────────

    /**
     * ⛔ Task 4 复审记的那笔账：drill 出带的路线**清单只住在 `QueueBuilder.applyDrills` 的注释里**，钉这些形状的用例住在
     * `QueueBuilderDrillTest` 自己那份文件里（这里从前数过路线的条数、还替那份文件许过「各钉过一条」式的覆盖账 —— 条数会随
     * 清单改动变、覆盖到哪条只有那份文件自己说得清，两边的账都在那边记，
     * 这里不重述也不数），但全仓**没有任何用例**
     * 在装配层/入口这一头造过那些形状。本条补最实在的一件：
     * 一次真正的挪位（不是"本来就在带内、只记不动"），而且复扫那一轮真的跑过。
     *
     * 为什么**这一份夹具**走不到 `coerceIn` 夹到队尾那一路（旧稿在这里写成"那一档必然被落点检查拒"，
     * 那是说过头了 —— 修复轮 F3 改的就是它）：夹到队尾在一般情形**真的搬得成**。`gapLo = 3` 时如果
     * anchor 落在 `size - 2`（size = 把移动卡抽走之后那一份的长度），`(anchor + gapLo)` 越界被夹到队尾，
     * 夹完的落点左邻是 **anchor 之后的那一张**
     * 而不是 anchor 自己；移动卡与它得 0f 时 `placeBeside` 就接受 ⇒ 挪成了，实际距离短于 gapLo ——
     * 那正是 `QueueBuilder.applyDrills` 注释里「`coerceIn` 够不着 `gapLo` ⇒ 夹到队尾」那一路（`QueueBuilderDrillTest.够不着 gapLo 时夹到队尾也如实报真实距离` 报 1）。
     * 同考点地板 0.65f 挡不住它：落点检查问的是「移动卡 vs 左右邻居」，从来不是「移动卡 vs anchor」。
     * 所以这里只能声称"本轮没走到那一档"，理由是几何：唯一那一对在下标 0 与 6 的 10 张会话里 ⇒
     * `removeAt(6)` 后 `size = 9`、anchor = `ia` = 0 ⇒ `target = (0 + 3).coerceIn(0, 9) = 3`，
     * 离天花板 9 还差 6 格 ⇒ `coerceIn` 不动手。这一条因此走**够得着的带内挪位**：距离 6（> gapHi 5）⇒ 挪到第 3 格。
     *
     * 手推（[confusableMathBank]，单科 startQuiz、两个开关都开）：
     *  1. 学科层：单科 ⇒ 原序 [1..10]（缓冲池 11..14 不参与学科层）；
     *  2. 第一遍相邻修复：会话内相邻九对全 0f（1 与 7 在带内但**不相邻**；6 与 8 同文但中间隔着 7）
     *     ⇒ 没有违规 ⇒ 不拉缓冲池；
     *  3. 第二遍 drill：映射 {1:[7], 7:[1]}，距离 6 不在 3..5 ⇒ 抽走 7、放到第 3 格（左右邻 3 与 4 都 0f）
     *     ⇒ [1,2,3,7,4,5,6,8,9,10]、`relocated` 1；
     *  4. **复扫**（只因为真挪过卡才追加）：7 被从 6 与 8 中间抽走 ⇒ 同文那一对成了相邻（1.0f > tauHi）
     *     ⇒ 窗口里第一张 `sim < tauLo` 的是 9 ⇒ 换位 ⇒ 末序 [1,2,3,7,4,5,6,9,8,10]。
     *
     * 杀的变异（三条都实跑过）：
     *  - MU-M：`placeBeside` 无条件把落点判成冲突 ⇒ drill 一次都没挪成 ⇒ 第一句红（7 停在第 6 格）；
     *  - **MU-L：复扫那一轮被摘掉（`relocated > 0` 永假）⇒ 末序停在 [1,2,3,7,4,5,6,8,9,10]，
     *    同一句字面量就是"复扫在生产路径上跑过"的证据**；
     *  - MU-B：`drillIds` 没接线 ⇒ 第二句红。
     */
    @Test
    fun `生产路径上drill真的把卡挪到新位置并且跑过复扫`() = runBlocking {
        val state = startQuizWith(confusableMathBank(), "数学", interleaving = true, semantic = true)
        assertEquals(listOf(1L, 2L, 3L, 7L, 4L, 5L, 6L, 9L, 8L, 10L), ids(state))
        assertEquals("挪位之后靠后的那张（7）才挂提示", setOf(7L), state.drillIds)
        assertEquals("缓冲池一张都没拉进来（本轮没有修不动的相邻违规）", 10, state.questions.size)
    }

    // ── ⑦ 同文重复题：只该分开，不该成对 ────────────────────────────

    /**
     * 简报"派发前手推"第 1 条点名的那一条：题干去掉标点后**完全同文**（Dice = 1.0）的一对落在
     * 混淆带 [0.40, 0.75] **之外** ⇒ `confusablePairs` 不收它 ⇒ 谁都不许挂提示；
     * 但 1.0 > tauHi ⇒ 相邻修复必须把它们分开。
     * 手推：初始 [1,2,3,…] 在 i=0 就看见 (1,2) 违规 ⇒ 窗口里第一张 `sim(1,·) < tauLo` 的是第 3 张 ⇒ 换位
     * ⇒ [1,3,2,4,5,6,7,8,9,10]；drill 映射为空 ⇒ 不追加复扫、不改序。
     * 杀的变异：MU-J（实跑 —— 混淆带上界 `drillHi` 形同虚设 ⇒ 1.0f 那对也进映射 ⇒ 2 被再挪一次、
     * drillIds 还多出 2L，两句同时红）。
     */
    @Test
    fun `同文重复题只被分开不当混淆对`() = runBlocking {
        val state = startQuizWith(duplicateAdjacentBank(), "数学", interleaving = true, semantic = true)
        assertEquals(listOf(1L, 3L, 2L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), ids(state))
        assertTrue("重复题不是易混题，不许挂提示: ${state.drillIds}", state.drillIds.isEmpty())
    }

    // ── ⑧ 一轮可以是 11 题：QUIZ_SIZE 是**取数**条数，不是轮次长度 ──────────

    /**
     * **F8（复审把这条从 out-of-scope 里提上来当产品缺口）**：接线之后一轮**合法地可以是 11 题** ——
     * 会话按 `QUIZ_SIZE = 10` 取，而缓冲拉前上限是 `due.size × 10 / 100 = 10 × 10 / 100 = 1`（整数除法），
     * 所以被拉进来的那张缓冲卡**就活在这一轮里**：`state.questions.size` 因此是 10 + pulled，而不是恒 10。
     * 裁定是「11 是预期」：缓冲卡存在的意义就是被插进那一处修不动的相邻违规中间，把它剪掉等于把这层功能
     * 自己剪掉。而 `state.total`、屏幕上那句「第 x / N 题」（`QuizScreen` 里那个用 `state.index` 与 `state.total`
     * 拼出来的 `Text`）、`isLast`（`QuizQuestionBranch` 传给 `QuestionView` 的那句 `state.index == state.total - 1`）、
     * `accuracyPercent` 的分母全都从 `questions.size` 走 —— 11 这一档此前**没有用例在入口 / UI 状态这一侧钉过**：
     * 纯层那一头早就断过 11 条（`QuestionQueuePlannerTest.缓冲卡来自池且是同一个对象` 与
     * `QueueBuilderAdjacentTest.窗口内没有可换的才从池里插一张缓冲`、`QueueBuilderAdjacentTest.拉前条数受上限约束等于条数的一成`
     * 字面断 `size == 11`，而 `QuestionQueuePlannerTest.池里的条数受一成上限` 断的是 `10 + pulled`、pulled 由紧挨着
     * 上面那一句钉成 1），可那些断的是 `plan` / `queue` 的长度，不是 `vm.quiz.value` —— 用户看得见的那一份当时没人钉。
     *
     * 手推（[pulledBufferBank]，单科 `startQuiz`、两个开关都开；逐对分值见 `t7fix-probe-out.txt`）：
     *  1. 学科层：单科 ⇒ 原序 [1..10]；装配层拿到的池 = `getBySubject(数学, 10, 10)` = [11]；
     *  2. 第一遍前扫：(1,2)…(8,9) 都不违规（唯一非 0 的相邻对是 (7,8) = 0.1739 < tauHi），
     *     i=8 上 (9,10) = **1.0f > tauHi** ⇒ 违规；换位窗口 `[i+2, i+1+lookahead]` 右端截到队尾 = `[10, 9]`
     *     ⇒ **空窗口** ⇒ 换不了位 ⇒ 走 `insertBuffer`：pullCap = 1 还有额度，id 11 与左右两邻（9 与 10）都 0f
     *     `< tauLo` ⇒ 插到 i+1 ⇒ 队列 [1,2,3,4,5,6,7,8,9,11,10]、`pulled` = 1；
     *  3. 同一轮继续前扫：(9,11) 与 (11,10) 都 0f ⇒ 无新违规；第二轮整扫 `changed` 为假 ⇒ 早停，
     *     拉池的额度也只有一份，不会再长出第 12 张；
     *  4. 第二遍 drill：会话里落进混淆带 `[0.40, 0.75]` 的对子为**零**（同文那一对 1.0f 在带**外**）
     *     ⇒ `relocated` 0 ⇒ 不追加复扫、`drillIds` 空集 ⇒ 末序就停在第 2 步那一串（11 格）。
     *
     * 与 ⑥ 那条互为对照：同一份装配层，⑥ 的夹具"违规修得动"⇒ 轮次就是 10 题，⑧ 的夹具"修不动"⇒ 11 题。
     * 杀的变异（**实跑** MU-F8a：把 `startQuiz` 那一行改成 `questions = plan.questions.take(10)` ——
     * 也就是「轮次长度 = 取数上限」那一读法）⇒ 本条第一句红，控制台逐字：
     * `expected:<[1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 10]> but was:<[1, 2, 3, 4, 5, 6, 7, 8, 9, 11]>` ——
     * 被截掉的不是缓冲卡，而是**本来就在卷里的 id 10**（`take` 从队尾砍）。红之后 JUnit 中止本方法，
     * 后面那些断言（total / 下标边界 / 正确率分母 / 提示空集）没有机会各自显形 —— 记在这里，不当已验。
     * 变异跑完即还原（提交前全仓不留临时变异标记），stdout 与 XML 的留档路径见 `task-7-report.md` 的 §R-8。
     * ⚠️ 本条**没有传统的 RED 一步**：它钉的是已经存在的生产行为（11 题那一档本来就出得来），
     * 首跑即绿，所以牙齿只能靠上面那一刀变异给 —— 这也是 F9 那条账要留 stdout 的原因。
     * ⚠️ 这里没有走 `vm.selectOption`：假 DAO 对 `QuestionDao.getById` 是直接报错的（答题链路不归本文件钉），
     * 所以 `index` / `correctCount` 两个入参用 data class 的 `copy` 打进去 —— 轮次本身是生产路径产的真货，
     * 派生读数（`total` / `current` / `finished` / `accuracyPercent`）是它自己的纯函数。
     */
    @Test
    fun `拉进缓冲卡时这一轮就是十一题而取数上限只是取数`() = runBlocking {
        val state = startQuizWith(pulledBufferBank(), "数学", interleaving = true, semantic = true)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 11L, 10L), ids(state))
        assertEquals("会话取 10 张 + 缓冲池拉 1 张 = 这一轮 11 题（QUIZ_SIZE 不是轮次长度）", 11, state.total)

        // 最后一格的边界：index 10 仍在轮内（屏幕上那句「第 11 / 11 题」与 isLast 都从这里走）
        val last = state.copy(index = 10)
        assertEquals(10L, last.current?.id)
        assertFalse("第 11 张还在轮里，不许提前 finished", last.finished)
        assertTrue("第 11 格就是这一轮的最后一格（isLast 的算式）", last.index == last.total - 1)
        val past = state.copy(index = 11)
        assertTrue("越出 11 格才算完卷", past.finished)
        assertNull(past.current)

        // 正确率的分母是这一轮的真实题数：1 题对 = 1×100/11 = 9（拿 10 当分母会报成 10）
        assertEquals(9, state.copy(correctCount = 1).accuracyPercent)
        assertEquals(100, state.copy(correctCount = 11).accuracyPercent)
        assertTrue("本夹具一对都够不着混淆带，不该挂提示: ${state.drillIds}", state.drillIds.isEmpty())
    }
}
