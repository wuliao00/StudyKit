package com.studykit.ui.stats

import com.studykit.data.AppSettings
import com.studykit.data.dao.ScheduledExcerptRow
import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.Mistake
import com.studykit.data.memory.DayBand
import com.studykit.data.memory.DueForecast
import com.studykit.data.memory.ExamEntry
import com.studykit.data.memory.ForecastCard
import com.studykit.data.memory.ForecastCfg
import com.studykit.data.memory.Scheduling
import com.studykit.data.memory.SprintSurge
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 一张进了预测队列的卡：来自哪张表、稳定度多少、距锚点多久。
 *
 * [kind] 记的是「这张卡来自哪张表」这份来路：[ForecastCards.of] 把三张表折平之后，认得出来源的地方
 * 就只剩它了——事后从 `stabilityDays` / `elapsedDays` 推不出这张卡是词、错题还是书摘。所以它是字符串
 * 而不是能推导的东西。取值只能是 [ForecastCards.KIND_WORD] / [ForecastCards.KIND_MISTAKE] /
 * [ForecastCards.KIND_EXCERPT] 三者之一（有条测试把它钉成恰好这三个，多一种就得先改测试）。
 *
 * **直说：目前没有任何生产代码读它。** 负荷带与提醒都不区分来源，Task 7 的稳定性全景收的是
 * `List<Double>`（只有稳定度，没有来源维度），同样不读。留着它的理由就两条：装配层出错时能问出
 * 「这批卡是哪张表来的」，以及上面那条测试对「第四种来源被顺手接进来」的拦截（brief 的接口也点名要这一字段）。
 * 将来真要按来源出图时不必改动装配层——但那是**将来**，不是现在任何一个任务的依赖。
 *
 * ## 刻意没有 `dueAt` 与 `id`（计划的接口里写了这两个字段，落地时删掉）
 * - `dueAt`：全计划无人读它，而负荷带按设计**不吃**落库的到期时刻（见
 *   [com.studykit.data.memory.DueForecast] 的 KDoc：到期日由 `S` 反解，否则「今天多评一轮」
 *   不会如实变成「明天堆积」）。更要紧的是错题侧**给不出真值**：`mistakes.review_at` 自 v2.7 起
 *   语义是「手动覆盖，可空」（null=由算法排期，见 [Mistake.reviewAt]），把它当落库到期时刻
 *   只会让每张没被手动排过的错题写进一个假的 0。Task 1 已经为同一件事删掉过
 *   [ForecastCard] 的 `dueAt`（计划「执行订正」第 6 条），这里照同一条口径走。
 * - `id`：词卡那侧的 [ScheduledMemoryRow] 投影 SQL 压根没取 id 列（首页那一格不需要它），
 *   为了填满一个人不读的字段去动那条 v2.5 起钉死的投影，是净负债。将来真要做
 *   「落库 dueAt vs 反解」的对照评测，那时连同 id 一起加回来。
 */
data class ScheduledCard(
    val kind: String,
    val stabilityDays: Double,
    val elapsedDays: Double,
    /**
     * 学科，取自 `mistakes.subject`；词与书摘**没有这一列**，那两条分支留的是默认空串。
     * v3.0-B Task 7 加它不是为了画图，是因为涌量预警要在这批已装配好的卡上按科筛子集
     * （见 [sprintSurgeFor]）——另查一次 DAO 就会违反下面那条「求值一次」契约。
     */
    val subject: String = "",
)

/**
 * 三张带记忆态的表折成统一的预测输入。
 *
 * 提醒路径（Task 4 的 `ReminderWorker`）与记忆看板（Task 6 的 `StatsViewModel`）共用这一份定义。
 * 两边各写一遍的话，「哪些行算排过期」「锚点取哪一列」「脏 S 丢不丢」这三个决定会分叉成两套账，
 * 而分叉不报错——它只让用户看到「通知说 3 张、看板画 2 张」。
 *
 * ## 口径差异（照实记录，别"顺手统一"）
 * 三张表给不出同一种锚点，这不是可以抹平的实现细节，是数据本身的形状：
 *  - `words`：锚点 `last_review_at ?: createdAt`（就是 [ScheduledMemoryRow.anchorAt]，与
 *    `StudyViewModel.gradeCard`、[com.studykit.ui.study.TomorrowForecast] 同一个口径）；
 *    稳定度取 `fsrs_stability`。这是三张表里**唯一**能给出真「距上次复习」的一张。
 *  - `mistakes`：**没有 `last_review_at` 列**（`entity/Mistake.kt` 里压根没这一列），锚点只能用
 *    `created_at`，于是错题的 elapsed 是「**建卡至今**」而非「上次复习至今」——**偏大**，会让错题
 *    显得比实际更该复习。可接受（错题本来就倾向催），但别对外说这是精确值。
 *    稳定度取 `fsrs_stability`（v2.7「排期接管」那批列）。
 *  - `excerpts`：稳定度取 [ScheduledExcerptRow.stability]，投影自列 [Excerpt.stability]，**可空**
 *    （没评过分的书摘就是 null）；锚点取同一条投影的 [ScheduledExcerptRow.createdAt]（列 `created_at`）
 *    —— [com.studykit.data.memory.ExcerptReview] 自己算 gapDays 用的也是 `createdAt`（那一侧同样没有
 *    last-review 时间戳），同源不另造口径。
 *  - `questions`：**不参与**。题目表没有任何记忆态列（`entity/Question.kt` 无 stability、无排期列），
 *    练习流水不排期，所以 [of] 的签名里没有它 —— 这是设计，不是漏了。
 *
 * ## 稳定度不可信就整条丢弃（三张表同一个动作，但含义要分开说）
 * `null` / `<= 0` / 非有限一律丢：反解式 `intervalDays(w, S, R)` 在 S 不存在时**没有定义**。
 * `words.half_life_days` 那侧不是「第二个 S」而是另一条曲线（双内核下它是镜像列），混进同一个队列
 * 等于把两种模型算出的天数相加，所以不拿它兜底（[ScheduledMemoryRow.fsrsStability] 的 KDoc 写过
 * 分核这件事）。宁缺不假。代价写明：**从没被 FSRS 写过的行进不了负荷带**，所以新库、或刚切内核
 * 还没回填的那些天，负荷带是**低估**而不是算错。
 *
 * 丢弃发生在这一层、而不是留给 [com.studykit.data.memory.DueForecast] 丢，是因为看板上
 * 「共 N 张卡」与负荷带必须数同一批卡（本仓为「百分比与词数不同批」写过
 * [com.studykit.ui.study.TomorrowLoad.sampledCount] 整条防线，同一件事不留两处判断）。
 *
 * ## 「哪些行算排过期」不在这里判
 * 那是 SQL 的事：词卡走 `WordDao.observeScheduledMemoryRows`（`next_review_at > 0`）、
 * 书摘走 [com.studykit.data.dao.BookDao.observeScheduledExcerpts]（同一条判据）、
 * 错题走 `MistakeDao.observeUnmastered`（`mastered = 0`；错题没有排期列可筛，`review_at` 只是手动覆盖）。
 * [of] 假定传进来的就是这三条查询的结果，不再自己加一遍筛子——两处各判一次「算不算排过期」
 * 迟早分叉，与上面「哪些卡数进 N」是同一条纪律。
 *
 * 纯 JVM：不 import Android / Room，所以它能被无 runner 的单测直接覆盖（与
 * [com.studykit.ui.study.TomorrowForecast] 同一层的理由）。
 */
object ForecastCards {

    private val ONE_DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

    const val KIND_WORD = "word"
    const val KIND_MISTAKE = "mistake"
    const val KIND_EXCERPT = "excerpt"

    /**
     * 三份已排期的行折成统一投影：逐条映射，不排序、不去重、不再判「哪些行算排过期」（口径见类 KDoc）。
     *
     * ## 求值一次：结果绑到一个局部变量，把**同一个列表**同时喂给负荷带与提醒
     * 即 [com.studykit.data.memory.DueForecast.forecast] 与 [com.studykit.data.memory.ReminderPolicy.decide]
     * 共用一次 `of` 的结果，不许各调一次。
     *
     * 三个入参是调用方从三条 Flow `.first()` 出来的**快照**，本函数自己不读库——所以「多调一次」的代价
     * 不是多算一遍，而是**取两次快照**：两次之间只要有一次评分落库（复习页每答一张就写一次），
     * 两个消费者数到的就是**不同的卡集**——通知说 3 张、看板画 2 张，而且不报错。本仓在这同一件事上
     * 踩过一次（`WordDao.observeScheduledMemoryRows` 的 KDoc：分两次查 ⇒ 观察者拿到半新半旧的快照，
     * N 是对的、百分比数的是另一批卡），所以这是**契约**而不是风格建议。
     *
     * 正确形状（Task 4 计划里那段过渡形态正是调了两次，接线时改成一次）：
     * ```
     * val cards = ForecastCards.of(words, mistakes, excerpts, now)   // 只此一次
     * val bands = DueForecast.forecast(cards.toForecastCards(), cfg, w)
     * val decision = ReminderPolicy.decide(cards.toForecastCards(), bands, retention, todayReviewed,
     *     maxIntervalDays, w)
     * ```
     * `toForecastCards()` 是对同一个列表的纯投影，多调一次无妨；**`of` 一次都不许多调**。
     */
    fun of(
        words: List<ScheduledMemoryRow>,
        mistakes: List<Mistake>,
        excerpts: List<ScheduledExcerptRow>,
        now: Long,
    ): List<ScheduledCard> = buildList {
        for (word in words) {
            val s = word.fsrsStability.usable() ?: continue
            // subject 走默认的空串：`words` 没有学科列（只有 source_list_id），不是漏了一个字段。
            // 空串在涌量预警那一侧读作「不属于任何一科」，只会被 subjects 为空的那场考试抬（计划 D5）。
            add(ScheduledCard(KIND_WORD, s, elapsed(word.anchorAt, now)))
        }
        for (mistake in mistakes) {
            val s = mistake.fsrsStability.usable() ?: continue
            // 错题侧没有 last_review_at 列，createdAt 是唯一锚点——口径见类 KDoc
            // subject 是三张表里唯一非空的那一列：v3.0-B 的 per-subject ramp 就是靠它筛到卡的。
            add(ScheduledCard(KIND_MISTAKE, s, elapsed(mistake.createdAt, now), mistake.subject))
        }
        for (excerpt in excerpts) {
            val s = excerpt.stability.usable() ?: continue
            // 同词卡：`excerpts` 归属的是书，没有学科列，留空串
            add(ScheduledCard(KIND_EXCERPT, s, elapsed(excerpt.createdAt, now)))
        }
    }

    /**
     * Δt（天）。`coerceAtLeast(0L)` 挡的是「时钟回拨 / 未来的 createdAt」：负的 elapsed 会把反解出的
     * 到期日**往后**推，于是那张已经逾期的卡看起来"还没到期"——错的方向正好和用户需要的那一版相反。
     */
    private fun elapsed(anchorAt: Long, now: Long): Double =
        (now - anchorAt).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()

    /**
     * 可信稳定度：`null` / `<= 0` / 非有限都当「这一行没有 S」，返回 null 让调用方丢弃。
     *
     * `isFinite()` 不是保险丝，删不得：NaN 比不过 `> 0.0`，但 **±Infinity 过得了 `> 0.0`**——
     * 只有这一支挡得住它。漏下去时 [com.studykit.data.memory.DueForecast] 与
     * [com.studykit.data.memory.ReminderPolicy] 会各自再丢一次（它们自己也判非有限），于是看板的
     * 「共 N 张卡」和负荷带数的又成了两批卡——正是上面那条纪律要拦的分叉。所以钉这条守卫的测试
     * **必须喂正无穷**：只喂 NaN 的话，把 `isFinite()` 删掉也能全绿。
     */
    private fun Double?.usable(): Double? = takeIf { it != null && it.isFinite() && it > 0.0 }
}

/**
 * 折成 [ForecastCard]（[com.studykit.data.memory.DueForecast.forecast] 与
 * `ReminderPolicy.decide` 的输入）。
 *
 * 只做字段投影，**不**再过滤一次：脏 S 在 [ForecastCards.of] 里已经丢完，所以这里的条数与 `of`
 * 的条数逐条相等（[com.studykit.ui.stats.ForecastCardsTest] 钉住了这一点）。[ScheduledCard.kind]
 * 在这一步丢掉：负荷带答的是「一天多少项」，不区分来源；要看来源就得自己回到 `of` 的结果，
 * 但**目前无人这么做**（Task 7 的分箱收的是 `List<Double>`，没有来源维度，见 [ScheduledCard.kind] 的说明）。
 *
 * `subject` **不许**在这一步丢掉（与 kind 相反）：[sprintSurgeFor] 筛子集用的就是 `ForecastCard.subject`，
 * 漏掉它不报错，症状是「涌量预警从此只认全学科那一场」。这条由
 * `投影到预测输入时学科字段一起走，不在这里丢掉` 钉着。
 *
 * 写成**顶层扩展**而不是 `ForecastCards` 的成员扩展：成员扩展要求每个调用点都套一层
 * `with(ForecastCards) { … }`，而 Task 4/6 要写的本来就是 `ForecastCards.of(…).toForecastCards()`
 * 这一行管道。同一文件、同一个包，import 成本为零。
 */
fun List<ScheduledCard>.toForecastCards(): List<ForecastCard> =
    map { ForecastCard(it.stabilityDays, it.elapsedDays, it.subject) }

/** 一场考试对它关联学科造成的涌量读数（v3.0-B T7）。 */
data class SprintSurgeState(
    /** 报警归给哪一科；全学科那一场报的是 [ExamEntry.DEFAULT_NAME]，没有读数时是第一个带学科的卡 */
    val subject: String,
    /** 峰值/中位；null = **没有读数**（格子不够或中位为 0），不等于「没有涌量」 */
    val ratio: Double? = null,
) {
    /** 判据只住在 [SprintSurge.shouldWarn] 一处（严格大于 2.0），这里不重述一遍阈值 */
    val warn: Boolean get() = SprintSurge.shouldWarn(ratio)
}

/**
 * 拿 ramp 之后的目标保留率把那批卡的负荷带重跑一遍，读峰值与中位（文档 L1891）。
 *
 * 为什么是重跑而不是解析式估：`DueForecast` 里每张卡的到期日是 `nextIntervalDays`
 * **反解**出来的，retention 一变整条分布就平移 —— 想手估峰值就得把那次反解重抄一遍，
 * 而「重抄一遍调度算术」正是本仓反复踩的那个坑（v3.0-A T2.6）。文档点名的 `WorkloadSimulator`
 * 本仓没有，[DueForecast.forecast] 就是它的等价物（计划偏差 D4），所以这里复用、不另造第二个预测器。
 *
 * 跑预测用的 `ForecastCfg` 从调用方那份 `cfgBase` copy 出来，只换 `retention` 与 `maxIntervalDays`：
 * 流入参数（`newPerDay`/`learningPerNew`/`inflowW`/`horizonDays`）一旦与统计页那张图用的
 * 不一致，读出来的涌就有一部分是新词造成的、而不是 ramp 造成的。这条不变式由测试里
 * `horizonDays/newPerDay/learningPerNew 相等 + inflowW 逐位相等` 那四行钉住；被换掉的那个上限
 * 则钉成手算的 3.0（horizon=1 → `max(3, 1×0.2)`），因为上限**必须**跟着冲刺排期走——
 * 漏了它，考前被调度器压到 3 天的卡会按自然间隔排到更晚甚至出窗，涌量于是**少报**，
 * 与 [ForecastCfg.maxIntervalDays] 里写的是同一条纪律。
 *
 * `scheduleOf` 与 `forecast` 都是注入的：前者让本函数零 ViewModel，后者让测试能钉住
 * 「只跑一遍、跑的是冲刺保留率、流入参数照抄页面那份」这三件事（用真预测器写断言会得到一条
 * 依赖默认参数的脆测试）。
 *
 * 一场考试只管它那几门：`subjects` 为空 = 全学科那一场，取全部卡；有关联学科时逐科算，
 * **报警取最凶的那一科**（首页只会看到一句提醒）。
 *
 * ## 「ramp 真的抬上去了才重跑」这句怎么判
 * 比较的两边都必须问**同一道缝**，只是交不同的清单：
 *  - `ramped` = 把这一场单独当唯一考试算一份；清单里若还有别的考试在窗内，
 *    `ExamPolicy.effectiveRetention` 的 max 会把两场的效果掺在一起，读数就不再是「这一场会涌」。
 *  - `base` = 把清单**清空**再算一份，拿到的就是没有被任何 ramp 碰过的基线。
 *
 * 原样拿整份清单算出来的那份**不能**当 base：它已经含这一场的 ramp，单场考试的用户两边恒等，
 * 于是闸门永远关着——症状是「涌量预警对绝大多数用户（清单里只有一场）静默不响」。
 * 也不在这里自己 `baseTargetRecall(strictness, 剩余天数)`：那要先把考试日换算成 horizon，
 * 等于在 UI 层抄第二份排期算术（`SubjectScheduling` 那道缝存在的理由就是不许可）。
 *
 * 零 Android、零 Room、零时钟（`today` 由调用方给）。`cards` 是 supplier 而不是列表：考试清单为空时
 * 本函数一次都不读它，将来挂在首页那一类「没有卡集在手」的调用点因此能整段跳过装配。统计页这一路
 * 卡集本来就要给那张图装配，所以那一侧省下来的只是「再查一次 DAO」——那正是求值一次契约禁止的。
 */
internal fun sprintSurgeFor(
    settings: AppSettings,
    cards: () -> List<ForecastCard>,
    cfgBase: ForecastCfg,
    today: LocalDate,
    scheduleOf: (AppSettings, String?, LocalDate) -> Scheduling,
    forecast: (List<ForecastCard>, ForecastCfg) -> List<DayBand> = DueForecast::forecast,
): SprintSurgeState {
    val exams = settings.examList
    if (exams.isEmpty()) return SprintSurgeState(subject = "")
    val all = cards()
    if (all.isEmpty()) return SprintSurgeState(subject = "")
    // 「这一场都没存在过」那份设置：同一道缝问第二次，拿到的就是 ramp 之前的基线
    val noExamSettings = settings.onlyExams(emptyList())
    var worst: SprintSurgeState? = null
    for (exam in exams) {
        // 与 ExamPolicy.linksSubject 同一条「空白 = 全学科」口径（v3.0-B 评审 F3）：
        // listOf("") 不能当成一门真实学科，否则那一场永远筛不到卡、预警静默失效。
        val subjects = exam.subjects.map { it.trim() }.filter { it.isNotEmpty() }
        val linked: List<String?> = if (subjects.isEmpty()) listOf(null) else subjects
        for (subject in linked) {
            // 逐科筛，不是「这场关联的所有卡」：一门绑两科的考试要给两科各读一次数。
            // 掐首尾空白 + 忽略大小写与 linksSubject 同口径（那边两边都掐）。
            val subset = if (subject == null) {
                all
            } else {
                all.filter { it.subject.trim().equals(subject, ignoreCase = true) }
            }
            if (subset.size < 2) continue   // 一张卡谈不上「堆成山」，白跑一遍预测
            val ramped = scheduleOf(settings.onlyExams(listOf(exam)), subject, today)
            val base = scheduleOf(noExamSettings, subject, today)
            if (ramped.targetRecall <= base.targetRecall) continue   // ramp 没真的抬上去就别跑预测
            val cfgRamp = cfgBase.copy(retention = ramped.targetRecall, maxIntervalDays = ramped.maxIntervalDays)
            val ratio = SprintSurge.peakMedianRatio(forecast(subset, cfgRamp))
            val cur = worst
            if (ratio != null && (cur == null || ratio > (cur.ratio ?: 0.0))) {
                worst = SprintSurgeState(subject = subject ?: ExamEntry.DEFAULT_NAME, ratio = ratio)
            }
        }
    }
    return worst ?: SprintSurgeState(subject = all.firstOrNull { it.subject.isNotEmpty() }?.subject ?: "")
}

/**
 * 把「参与排期的考试」换成 [list]，其余设置原样。
 *
 * 两个字段都得写：[AppSettings.examList] 在 `exams` 为空时还会把旧键 [AppSettings.examEpochDay]
 * 合成一场全学科考试，只清 `exams` 的话旧键会偷偷把闸门顶开（base 与 ramped 又变成同一份数）。
 * 住在这里是因为 [sprintSurgeFor] 的两个读数都靠它，而它属于「摊设置」这层口径。
 */
private fun AppSettings.onlyExams(list: List<ExamEntry>): AppSettings = copy(exams = list, examEpochDay = 0L)
