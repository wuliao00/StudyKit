package com.studykit

import android.content.Context
import com.studykit.data.AppDatabase
import com.studykit.data.repository.BookRepository
import com.studykit.data.repository.ContractRepository
import com.studykit.data.repository.HabitRepository
import com.studykit.data.repository.MistakeRepository
import com.studykit.data.repository.QuestionRepository
import com.studykit.data.repository.SettingsRepository
import com.studykit.data.repository.WordListRepository
import com.studykit.data.repository.WordRepository

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

    /** 自我契约（v2.4 批次五）：计数取自 habitDao（check_ins），存取取自 contractDao */
    val contractRepository: ContractRepository =
        ContractRepository(database.contractDao(), database.habitDao())

    val bookRepository: BookRepository = BookRepository(database.bookDao())

    val mistakeRepository: MistakeRepository = MistakeRepository(database.mistakeDao())

    val settingsRepository: SettingsRepository = SettingsRepository(database.appSettingDao())
}
