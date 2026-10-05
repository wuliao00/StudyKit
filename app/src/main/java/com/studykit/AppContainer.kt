package com.studykit

import android.content.Context
import com.studykit.data.AppDatabase
import com.studykit.data.repository.BookRepository
import com.studykit.data.repository.ContractRepository
import com.studykit.data.repository.HabitRepository
import com.studykit.data.repository.MistakeRepository
import com.studykit.data.repository.QuestionRepository
import com.studykit.data.repository.ReviewLogRepository
import com.studykit.data.repository.SettingsRepository
import com.studykit.data.repository.WordListRepository
import com.studykit.data.repository.WordRepository
import com.studykit.optimize.FsrsTrainer
import com.studykit.optimize.NativeFsrsTrainer

/**
 * 手动依赖注入容器：持有数据库与全部仓储实例。
 * 由 [StudyKitApp] 在启动时初始化。
 */
class AppContainer(context: Context) {

    val database: AppDatabase = AppDatabase.getInstance(context)

    val wordRepository: WordRepository = WordRepository(database.wordDao())

    val wordListRepository: WordListRepository = WordListRepository(database)

    val questionRepository: QuestionRepository =
        QuestionRepository(database.questionDao(), database.practiceDao())

    val habitRepository: HabitRepository = HabitRepository(database.habitDao())

    /**
     * 自我契约（v2.4 批次五）：计数取自 habitDao（check_ins），存取取自 contractDao。
     * 整个 db 传进去只为批量对账那一桩事务（见 `ContractRepository.updateAll`）。
     */
    val contractRepository: ContractRepository = ContractRepository(database)

    val bookRepository: BookRepository = BookRepository(database.bookDao())

    val mistakeRepository: MistakeRepository = MistakeRepository(database.mistakeDao())

    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingDao())

    /**
     * `review_log` 事件流写入侧（v2.9 Task 6）：四处评分点在各自状态写之后追加一行。
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
