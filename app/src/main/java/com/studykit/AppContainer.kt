package com.studykit

import android.content.Context
import com.studykit.data.AppDatabase
import com.studykit.data.repository.BookRepository
import com.studykit.data.repository.ContractRepository
import com.studykit.data.repository.HabitRepository
import com.studykit.data.repository.MistakeRepository
import com.studykit.data.repository.MockSessionRepository
import com.studykit.data.repository.QuestionRepository
import com.studykit.data.repository.ReviewLogRepository
import com.studykit.data.repository.SettingsRepository
import com.studykit.data.repository.WordListRepository
import com.studykit.data.repository.WordRepository
import com.studykit.optimize.FsrsTrainer
import com.studykit.optimize.NativeFsrsTrainer

/**
 * 手动依赖注入容器：持有数据库与全部仓储实例。
 *
 * 由 [StudyKitApp] 在启动时初始化（生产走 [Context] 那一个构造子，拿进程单例的 [AppDatabase]）。
 *
 * 两个构造子参数各自对应一条测试目的：
 *
 *  - [database]：单测（Robolectric）能把整只容器换成一次性内存库。不加这层的话，`AppDatabase.instance`
 *    那个进程单例会跨用例存活，而 Robolectric 每个用例都会拆掉 SQLite 影子连接 —— 第二个用例起就会撞
 *    `Illegal connection pointer`（Room 缓存的编译语句指着已经死掉的连接）。
 *  - [questionRepository]：组轮那批接线用例只换这一个仓储（挂一只假 DAO），好让生产容器一行代码都不必
 *    为了测试改形状。这是**注入点**，不是给生产代码开的口子：默认值就是原来 body 里那一句构造表达式，
 *    类型、`val`、初始化表达式全部一字未改。有了它，`StudyQueueWiringTest` 那一处不再需要反射 ——
 *    反射改写 `val` 的写法，字段哪天改名不会编译失败，只会在运行期抛 `NoSuchFieldException`。
 *
 * 本仓无 Hilt，注入就停在容器这一层（同 [fsrsTrainer] 那条评论）。
 */
class AppContainer(
    val database: AppDatabase,
    val questionRepository: QuestionRepository =
        QuestionRepository(database.questionDao(), database.practiceDao()),
) {

    constructor(context: Context) : this(AppDatabase.getInstance(context))

    val wordRepository: WordRepository = WordRepository(database.wordDao())

    val wordListRepository: WordListRepository = WordListRepository(database)

    val habitRepository: HabitRepository = HabitRepository(database.habitDao())

    /**
     * 自我契约（v2.4 批次五）：计数取自 habitDao（check_ins），存取取自 contractDao。
     * 整个 db 传进去只为批量对账那一桩事务（见 `ContractRepository.updateAll`）。
     */
    val contractRepository: ContractRepository = ContractRepository(database)

    val bookRepository: BookRepository = BookRepository(database.bookDao())

    val mistakeRepository: MistakeRepository = MistakeRepository(database.mistakeDao())

    /** 套卷会话持久化（v3.0-B）：薄包装，逻辑全在 ViewModel/机器侧，这层只挡 Room */
    val mockSessionRepository: MockSessionRepository = MockSessionRepository(database.mockSessionDao())

    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingDao())

    /**
     * `review_log` 事件流写入侧（v2.9 Task 6）：五处评分点（v2.9 那四处 + v3.0-B 的套卷交卷）
     * 在各自状态写之后追加一行。
     * 只持有 append-only 的 [ReviewLogDao]，没有改历史的路径（DAO 那侧由 `ReviewLogDaoTest` 反射钉死）。
     */
    val reviewLogRepository: ReviewLogRepository = ReviewLogRepository(database.reviewLogDao())

    /**
     * 端侧优化器的**训练接缝**（v2.9 Task 11）：`FsrsNative` 那两个 `external fun` 的唯一入口。
     *
     * 无状态，所以整个进程一份就够；[com.studykit.ui.optimize.CalibrateViewModel] 只认
     * [FsrsTrainer] 接口，单测里换成假实现就能把护栏逐条钉住（本仓无 Hilt，注入就停在这一层）。
     */
    val fsrsTrainer: FsrsTrainer = NativeFsrsTrainer()
}
