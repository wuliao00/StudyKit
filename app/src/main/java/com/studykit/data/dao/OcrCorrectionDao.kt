package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.OcrCorrection
import kotlinx.coroutines.flow.Flow

/** v3.9：OCR 纠错记录。只增不改（与 review_log 同一条纪律）。 */
@Dao
interface OcrCorrectionDao {

    @Insert
    suspend fun insert(correction: OcrCorrection): Long

    @Query("SELECT COUNT(*) FROM ocr_corrections")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM ocr_corrections ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<OcrCorrection>

    @Query("SELECT * FROM ocr_corrections ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OcrCorrection>>

    @Query("DELETE FROM ocr_corrections")
    suspend fun deleteAll()
}
