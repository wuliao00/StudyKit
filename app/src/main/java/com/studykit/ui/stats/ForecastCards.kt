package com.studykit.ui.stats

import com.studykit.data.dao.ScheduledMemoryRow
import com.studykit.data.entity.Excerpt
import com.studykit.data.entity.Mistake
import com.studykit.data.memory.ForecastCard
import java.util.concurrent.TimeUnit

/**
 * 一张进了预测队列的卡：来自哪张表、稳定度多少、距锚点多久。
 *
 * [kind] 是装配完成后**唯一**还留着的「这张卡来自哪张表」的证据，所以它得是字符串而不是
 * 事后能推出来的东西 —— Task 7 的稳定性全景按来源分组靠的就是它。取值只能是
 * [ForecastCards.KIND_WORD] / [ForecastCards.KIND_MISTAKE] / [ForecastCards.KIND_EXCERPT]
 * 三者之一（有条测试把它钉成恰好这三个，多一种就得先改测试）。
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
 *  - `excerpts`：稳定度列 [Excerpt.stability] **可空**（没评过分的书摘就是 null）；锚点 `created_at`
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

    fun of(
        words: List<ScheduledMemoryRow>,
        mistakes: List<Mistake>,
        excerpts: List<Excerpt>,
        now: Long,
    ): List<ScheduledCard> = buildList {
        for (word in words) {
            val s = word.fsrsStability.usable() ?: continue
            add(ScheduledCard(KIND_WORD, s, elapsed(word.anchorAt, now)))
        }
        for (mistake in mistakes) {
            val s = mistake.fsrsStability.usable() ?: continue
            // 错题侧没有 last_review_at 列，createdAt 是唯一锚点——口径见类 KDoc
            add(ScheduledCard(KIND_MISTAKE, s, elapsed(mistake.createdAt, now)))
        }
        for (excerpt in excerpts) {
            val s = excerpt.stability.usable() ?: continue
            add(ScheduledCard(KIND_EXCERPT, s, elapsed(excerpt.createdAt, now)))
        }
    }

    /**
     * Δt（天）。`coerceAtLeast(0L)` 挡的是「时钟回拨 / 未来的 createdAt」：负的 elapsed 会把反解出的
     * 到期日**往后**推，于是那张已经逾期的卡看起来"还没到期"——错的方向正好和用户需要的那一版相反。
     */
    private fun elapsed(anchorAt: Long, now: Long): Double =
        (now - anchorAt).coerceAtLeast(0L) / ONE_DAY_MS.toDouble()

    /** 可信稳定度：`null` / `<= 0` / 非有限都当「这一行没有 S」，返回 null 让调用方丢弃。 */
    private fun Double?.usable(): Double? = takeIf { it != null && it.isFinite() && it > 0.0 }
}

/**
 * 折成 [ForecastCard]（[com.studykit.data.memory.DueForecast.forecast] 与
 * `ReminderPolicy.decide` 的输入）。
 *
 * 只做字段投影，**不**再过滤一次：脏 S 在 [ForecastCards.of] 里已经丢完，所以这里的条数与 `of`
 * 的条数逐条相等（[com.studykit.ui.stats.ForecastCardsTest] 钉住了这一点）。[ScheduledCard.kind]
 * 在这一步丢掉：负荷带答的是「一天多少项」，不区分来源；要按来源看就直接用 `of` 的结果
 * （Task 7 的稳定性全景干的是那件事）。
 *
 * 写成**顶层扩展**而不是 `ForecastCards` 的成员扩展：成员扩展要求每个调用点都套一层
 * `with(ForecastCards) { … }`，而 Task 4/6 要写的本来就是 `ForecastCards.of(…).toForecastCards()`
 * 这一行管道。同一文件、同一个包，import 成本为零。
 */
fun List<ScheduledCard>.toForecastCards(): List<ForecastCard> =
    map { ForecastCard(it.stabilityDays, it.elapsedDays) }
