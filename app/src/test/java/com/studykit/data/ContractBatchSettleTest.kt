package com.studykit.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.entity.Contract
import com.studykit.data.repository.ContractRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 一批契约**一次写完**之后，`observeAll()` 的订阅者看到的是什么。
 *
 * ## 它防的是什么
 *
 * 2026-09 真机走查：三张真达成占了五个仪式槽（「第 1 / 共 5 张」），收下时同一张放了两遍。
 * 成因不在判定规则，在落库的时机 —— [ContractRepository.update] 一张一次事务，Room 每提交一张
 * 就失效一次 `contracts` 表，于是 `ContractsViewModel` 的 collect 收到**半新半旧**的快照，
 * 里面还没提交的那几张照旧写着 ACTIVE 且已过期，就被再判一次、再报一次。
 * 修法是 [ContractRepository.updateAll]：整批一桩事务，中间态根本不存在。
 *
 * ## 为什么这一条要走 Robolectric 而不是纯 JVM
 *
 * 要钉的是"中间状态会不会被看见"，那是 Room 失效通知 + SQLite 事务的联合行为，
 * 假 DAO 复现不出来（纯 JVM 单测里没有"提交"这回事，写与读都在同一根线程上瞬间完成）。
 * 本仓已有的 Robolectric 用例（`CheckInSheetRenderTest`）立过同样的规矩：
 * 引模拟器不在 CI 的执行器里，能 JVM 跑的一律 JVM 跑。
 *
 * ## 等快照要按**内容**等，不能按"第几趟"等（这一版重写的原因）
 *
 * 上一版两条测试都用 `awaitAtLeast(seen, seen.size + 1)` 数通知趟数，而
 * **一次提交对应几趟通知、通知会不会被合并，都不是 Room 对外承诺的东西**。
 * 全量跑到第三遍就红在对照组上（"只收到 4 趟，等不到第 5 趟"），
 * 那时被测代码是对的 —— 是量具在赌一个它不该赌的东西。
 * 现在一律等内容：`awaitSnapshot { 这一张已经是 ACHIEVED }`。
 *
 * 改完对照组反而**从"看运气"变成"必然成立"**：逐张写时，等第 1 张可见的那一刻，
 * 第 2、3 张**根本还没被写**，所以那份快照必然是半新半旧的。
 * 中间态不再依赖时序，也就不会再有"机器忙 ⇒ 假红"。
 *
 * ## 对照组为什么必须一起留着
 *
 * 「没观察到中间快照」有两种可能：真的原子，或者**量具坏了**（订阅者压根没收到任何一趟）。
 * [perRowWritesDoDeliverPartiallyVisibleSnapshots] 就是把同一套量具对着"确定会漏"的写法跑一遍：
 * 它红了说明收不到通知，那么批量那条绿着也不作数。判别力靠这一对，不靠任何单独一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContractBatchSettleTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ContractRepository

    private val today: LocalDate = LocalDate.of(2026, 10, 1)

    /** 与 `settle` 同一个口径：对账日 0 点 —— 结算后的副本带着它 */
    private val settleStamp: Long =
        today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ContractRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun dueContract(id: Long): Contract = Contract(
        id = id,
        uuid = "u-$id",
        habitId = id,
        deadlineEpochDay = today.minusDays(1).toEpochDay(),
        goalCount = 5,
        promiseText = "如果到了【睡前】，我就完成当日打卡。",
        consequenceText = "当天不许刷剧",
        signedBy = "小考",
        signedAtEpochDay = today.minusDays(30).toEpochDay(),
        status = Contract.STATUS_ACTIVE,
    )

    private fun statuses(rows: List<Contract>): List<String> = rows.map { it.status }

    /** 一份"半新半旧"的快照 = 同一批里既有判完的又有没判完的 */
    private fun partiallyVisible(rows: List<Contract>): Boolean =
        statuses(rows).distinct().size > 1

    private fun allActive(rows: List<Contract>): Boolean =
        rows.size == 3 && rows.all { it.status == Contract.STATUS_ACTIVE }

    private fun allAchieved(rows: List<Contract>): Boolean =
        rows.size == 3 && rows.all { it.status == Contract.STATUS_ACHIEVED }

    /**
     * 等到**满足条件的那一份快照**出现。按内容等，不数趟数 —— 理由见类文档。
     * 超时即判"量具坏了"，并把话说清楚：那不是被测代码的错。
     */
    private suspend fun awaitSnapshot(
        seen: List<List<Contract>>,
        what: String,
        predicate: (List<Contract>) -> Boolean,
    ): List<Contract> {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            seen.lastOrNull()?.let { if (predicate(it)) return it }
            if (System.currentTimeMillis() >= deadline) {
                fail("等不到「$what」—— 订阅者一共收到 ${seen.size} 趟，最后一份是 " +
                    "${seen.lastOrNull()?.let { statuses(it) }}。" +
                    "这是量具坏了（收不到通知），不是批量写坏了")
            }
            delay(POLL_MS)
        }
    }

    /** 订阅 `observeAll()`：返回"按到达顺序记下的快照"与"退订用的 Job" */
    private fun CoroutineScope.watchEmissions(): Pair<CopyOnWriteArrayList<List<Contract>>, Job> {
        val seen = CopyOnWriteArrayList<List<Contract>>()
        val watcher = launch(Dispatchers.IO) {
            db.contractDao().observeAll().collect { seen.add(it) }
        }
        return seen to watcher
    }

    /** 三张到期契约入库（写完才订，第一趟就是干净的三张 ACTIVE） */
    private suspend fun seedThreeDueContracts() {
        listOf(dueContract(1L), dueContract(2L), dueContract(3L))
            .forEach { repository.insert(it) }
    }

    private fun settledCopiesFrom(initial: List<Contract>): List<Contract> =
        initial.map { it.copy(status = Contract.STATUS_ACHIEVED, settledAt = settleStamp) }

    /**
     * 整批一次写完：全程**不许**出现任何一份半新半旧的快照，
     * 而订阅者最终必须看到三张一起 ACHIEVED 的全貌。
     *
     * 上一版这里还断言"只该被惊动一次"，那是数通知趟数 —— 趟数不是 Room 的承诺，
     * 同内容的快照重发一次也算合法。要紧的性质是**到达的每一份快照都是全貌**，
     * 所以只留这一条，它也更接近真机上那个缺陷的形状（中间态被看见）。
     */
    @Test
    fun batchSettleReachesObserversWholeWithNoPartialSnapshot() {
        runBlocking {
            seedThreeDueContracts()
            val (seen, watcher) = watchEmissions()
            try {
                val initial = awaitSnapshot(seen, "三张进行中的到期契约", ::allActive)
                repository.updateAll(settledCopiesFrom(initial))
                awaitSnapshot(seen, "整批三张一起判成 ACHIEVED", ::allAchieved)
                // 再多等一会儿：多出来的那一趟（若有）也该在这里到齐
                delay(SILENCE_MS)

                val partials = seen.filter { partiallyVisible(it) }
                assertTrue(
                    "中途出现过半新半旧的快照：${seen.map { statuses(it) }} —— " +
                        "整批一桩事务不该有中间态，而仪式队列正是被这种快照喂出重复入列的",
                    partials.isEmpty(),
                )
            } finally {
                watcher.cancelAndJoin()
            }
        }
    }

    /**
     * 对照组：逐张提交**确实**会把中间快照送到订阅者手里 —— 这就是真机上那个 bug 的形状。
     *
     * 这一条红了 = 上面的量具收不到任何通知，那条"没观察到中间快照"就什么都不证明。
     * 它同时也是这条注释所描述事实的唯一守卫：谁哪天把批量写退回成 `forEach { update(it) }`，
     * 这里先红，而且红得看得懂。
     *
     * 中间态在这里是**必然**而不是碰运气：等第 1 张可见时，第 2、3 张还没被写，
     * 所以那一份快照必然是"一张判完、两张还写着进行中"。
     */
    @Test
    fun perRowWritesDoDeliverPartiallyVisibleSnapshots() {
        runBlocking {
            seedThreeDueContracts()
            val (seen, watcher) = watchEmissions()
            try {
                val initial = awaitSnapshot(seen, "三张进行中的到期契约", ::allActive)
                val settled = settledCopiesFrom(initial)

                settled.forEach { row ->
                    repository.update(row)
                    awaitSnapshot(
                        seen,
                        "第 ${row.id} 张判成 ACHIEVED",
                    ) { snap ->
                        snap.first { it.id == row.id }.status == Contract.STATUS_ACHIEVED
                    }
                }
                delay(SILENCE_MS)

                assertTrue(
                    "逐张提交居然没被观察到中间快照：整套量具收不到通知，" +
                        "批量那条绿着也不算数。实际收到：${seen.map { statuses(it) }}",
                    seen.any { partiallyVisible(it) },
                )
                assertEquals(
                    "最后一趟仍然是全貌",
                    List(3) { Contract.STATUS_ACHIEVED },
                    statuses(seen.last()),
                )
            } finally {
                watcher.cancelAndJoin()
            }
        }
    }

    private companion object {
        /** 静默窗口：多出来的那一趟通知如果会来，这段时间里就到齐了 */
        const val SILENCE_MS = 500L

        const val WAIT_MS = 10_000L

        const val POLL_MS = 20L
    }
}
