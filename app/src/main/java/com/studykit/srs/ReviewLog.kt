package com.studykit.srs

import com.studykit.data.entity.WordReview
import java.util.UUID

/**
 * 复习日志构造器。
 *
 * 把 FSRS 评分（Again/Hard/Good/Easy）、作答前的自评信心、以及本次复习后的稳定性快照
 * 一并落成一条 `word_reviews`。将来做个体化参数拟合（把 FSRS 默认参数校准成用户自己的记忆
 * 曲线）靠的就是这些历史；只记「对/错」会把数据白白丢掉。
 */
object ReviewLog {

    /** 未自评时的哨兵值，与实体列默认值一致 */
    const val NO_CONFIDENCE = -1

    fun wordReview(
        wordId: Long,
        rating: Rating,
        confidence: Confidence?,
        stabilityAfter: Double,
        reviewedAt: Long,
    ): WordReview = WordReview(
        uuid = UUID.randomUUID().toString(),
        wordId = wordId,
        correct = rating != Rating.AGAIN,
        reviewedAt = reviewedAt,
        rating = rating.grade,
        confidence = confidence?.level ?: NO_CONFIDENCE,
        stabilityAfter = stabilityAfter,
    )
}
