package com.studykit.data.repository

import com.studykit.data.dao.OcrCorrectionDao
import com.studykit.data.entity.OcrCorrection

/** v3.9：校对纠错记录的薄包装；隐私上只在本机，不导出。 */
class OcrCorrectionRepository(private val dao: OcrCorrectionDao) {

    suspend fun record(correction: OcrCorrection): Long = dao.insert(correction)

    fun observeCount() = dao.observeCount()

    suspend fun recent(limit: Int = 200): List<OcrCorrection> = dao.recent(limit)

    fun observeRecent(limit: Int = 20) = dao.observeRecent(limit)

    suspend fun clearAll() = dao.deleteAll()
}
