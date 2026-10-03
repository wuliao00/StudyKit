package com.studykit.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.studykit.data.AppDatabase
import com.studykit.data.entity.Word
import com.studykit.data.entity.WordList
import com.studykit.data.repository.WordListRepository
import com.studykit.data.repository.WordRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 词库导入「写回源」守卫（v2.7.0.1 Bug A 取证的另一半）。
 *
 * 真机上「单词库录入了单词显示 0 个」—— 拉来的 studykit.db 里 `imported_count=1162` 与
 * `COUNT(words WHERE source_list_id=3)=1162` 完全相等，证明**写回是对的**，屏幕上的 0 出在显示源
 * （见 `ImportExitTest`）。这条测试把「写回源正确」这个结论钉进 CI：它按 `ImportViewModel.submitWords`
 * 的真实写序（add 整本 → insertAll 词 → markImportedCount(实际插入数)）跑一遍，断言三本账必须齐平。
 *
 * 走 in-memory Room 而非假 DAO：这条性质长在真实的 `imported_count` 写回 SQL 上，且能顺带证明
 * 「去重后写回的是真实插入数、不是数据源声称的 word_num」——那正是「已导入 x / 3000」的口径来源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictImportWriteBackTest {

    private lateinit var db: AppDatabase
    private lateinit var wordRepository: WordRepository
    private lateinit var wordListRepository: WordListRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        wordRepository = WordRepository(db.wordDao())
        wordListRepository = WordListRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun words(count: Int, listId: Long?) = (1..count).map {
        Word(
            uuid = UUID.randomUUID().toString(),
            word = "w$it",
            meaning = "m$it",
            example = "",
            sourceListId = listId,
        )
    }

    @Test
    fun `整本导入后 imported_count 必须等于实际插入数与按库计数`() = runBlocking {
        // 复现真机那本：source_id='CET4luan_1'，数据源声称 1162 词
        val listId = wordListRepository.add(
            WordList(sourceId = "CET4luan_1", title = "四级真题核心单词", wordNum = 1162),
        )
        val entities = words(1162, listId)
        val inserted = wordRepository.addAll(entities)
        if (listId != 0L) wordListRepository.markImportedCount(listId, inserted)

        val stored = wordListRepository.getBySourceId("CET4luan_1")!!
        assertEquals(1162, inserted)
        // 三本账必须齐平：屏幕显示的「已导入」读的就是 imported_count 这一列
        assertEquals(inserted, stored.importedCount)
        assertEquals(inserted, db.wordDao().countByList(listId))
    }

    @Test
    fun `去重后写回的是真实插入数而不是数据源声称的词数`() = runBlocking {
        val listId = wordListRepository.add(
            WordList(sourceId = "CET4luan_2", title = "会有重复的一本", wordNum = 1162),
        )
        // 库内先有两个词面（模拟之前已学过），导入这 1162 里有 w1/w2 会撞车
        wordRepository.addAll(
            listOf(
                Word(uuid = UUID.randomUUID().toString(), word = "w1", meaning = "旧义1", example = ""),
                Word(uuid = UUID.randomUUID().toString(), word = "w2", meaning = "旧义2", example = ""),
            ),
        )
        // 与 ImportViewModel.dedupeWords 同口径：拿全量词面判重后再写
        val existing = wordRepository.wordTexts().toSet()
        val kept = words(1162, listId).filterNot { it.word in existing }
        val inserted = wordRepository.addAll(kept)
        wordListRepository.markImportedCount(listId, inserted)

        val stored = wordListRepository.getBySourceId("CET4luan_2")!!
        assertEquals(1160, inserted)
        assertEquals(inserted, stored.importedCount)
        assertEquals(inserted, db.wordDao().countByList(listId))
    }
}
