package com.studykit.data.repository

import com.studykit.data.dao.MistakeDao
import com.studykit.data.entity.Mistake
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class MistakeRepository(private val mistakeDao: MistakeDao) {

    fun observeAll(): Flow<List<Mistake>> = mistakeDao.observeAll()

    fun observeBySource(source: String): Flow<List<Mistake>> = mistakeDao.observeBySource(source)

    fun observeBySubject(subject: String): Flow<List<Mistake>> = mistakeDao.observeBySubject(subject)

    fun observeUnmastered(): Flow<List<Mistake>> = mistakeDao.observeUnmastered()

    fun observeById(id: Long): Flow<Mistake?> = mistakeDao.observeById(id)

    /** 到期且未掌握的错题（复习提醒用） */
    suspend fun getDueForReview(now: Long): List<Mistake> = mistakeDao.getDueForReview(now)

    fun observeUnmasteredCount(): Flow<Int> = mistakeDao.observeUnmasteredCount()

    suspend fun add(
        source: String,
        subject: String,
        title: String,
        content: String,
        imagePath: String? = null,
        note: String = "",
    ): Long =
        mistakeDao.insert(
            Mistake(
                uuid = UUID.randomUUID().toString(),
                source = source,
                subject = subject,
                title = title,
                imagePath = imagePath,
                content = content,
                note = note,
            ),
        )

    suspend fun update(mistake: Mistake) = mistakeDao.update(mistake)

    suspend fun delete(id: Long) = mistakeDao.deleteById(id)

    /** 标记错题为已掌握 */
    suspend fun markMastered(id: Long) = mistakeDao.markMastered(id)
}
