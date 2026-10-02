package com.studykit.util.backup

import com.studykit.data.dao.ReviewHistoryRow
import com.studykit.ui.stats.RetentionBuckets

/**
 * 备份包里的 `review_history.csv`（v2.7 spec §9 / D7，格式=spec 附录 A）。
 *
 * ## 它存在的唯一理由
 *
 * D7 把"7/30 天延迟后测保留率"从遥测降级成**本地前后对照**：应用自己不上传任何东西，
 * 但用户/将来的我们要能拿一份原始流水出去自己算。所以这张表就是 `word_reviews` 的
 * 一次性投影，只写不读回（附录 A：「解析端只需容忍性读，不做回导」）。
 *
 * ## 三条口径
 *
 * 1. **列序逐字等于附录 A**：`item_kind,item_id,reviewed_at_epoch_ms,gap_days,confidence,grade,priority,elapsed_bucket`。
 *    列序变了不会报错，只会让旧包算出错数，所以它在 `BackupCsvEntryTest` 里是被逐字钉住的字符串。
 * 2. **可空就留空，不拿 0 冒充**：`gap_days`/`confidence` 为空时写出空字段。
 *    把"没记信心"写成 0，事后拿 CSV 算超纠正率就会凭空多出成堆"用户答『肯定不记得』" ——
 *    与 `AppDatabase.MIGRATION_6_7` 第 2 段拒绝给旧行填 0 是同一条理由。
 *    `grade = -1` 是**有值**的（"迁移前没记档位"这个事实本身），照写 -1，不留空。
 * 3. **分桶不在这儿定义**：`elapsed_bucket` 调 [RetentionBuckets.csvBucket]，与看板那张卡同一判定。
 *    这条 import 的方向（util → ui）是刻意的：宁可让备份模块依赖一个纯 Kotlin object，
 *    也不在两个模块各写一份 7/30 边界。
 *
 * ## 只增不改
 *
 * 恢复端把本条目当 [ZipEntryKind.UNKNOWN] 直接跳过（[BackupCodec.entryKindOf] 没有为它加分支），
 * 所以旧版本打开新包照样能恢复自己认识的那几样。这一条由 `BackupCsvEntryTest` 钉住。
 *
 * TODO(v2.7/B19 后续)：附录 A 的 `item_kind` 还有 `mistake` / `excerpt` 两种取值，本任务**只出 word 行**。
 * 理由不是忘了，是原料不齐：`mistake_redos` 这张表只有 `redone_at/correct/hints_used/had_note_rebuild`，
 * 既没有 `gap_days` 也没有 `p_at_review`，`priority` 反倒只有错题侧有；书摘侧（`excerpts`）
 * 连逐次复习流水表都还没接出来。等 lane-A 把错题重做的读表面与书摘复习流水做出来，
 * 在 [ReviewHistoryCsv] 里加一条 `mistake`/`excerpt` 的行来源即可 —— 列序、可空口径、分桶都不必动，
 * 恢复端也永远不需要认这个文件。
 */
internal object ReviewHistoryCsv {

    /** zip 根下的条目名（附录 A 明写"文件名进 zip 根"） */
    const val ENTRY_NAME = "review_history.csv"

    /** 表头 = 附录 A 的列序，逐字 */
    const val HEADER =
        "item_kind,item_id,reviewed_at_epoch_ms,gap_days,confidence,grade,priority,elapsed_bucket"

    /** 词卡侧的 `item_kind`（附录 A 允许 `word|mistake|excerpt`） */
    const val KIND_WORD = "word"

    /** `elapsed_bucket` 的唯一入口，转给看板那侧，免得两处口径分叉 */
    fun bucketOf(gapDays: Double?): String = RetentionBuckets.csvBucket(gapDays)

    /**
     * 一行词卡复习流水。
     *
     * Double 直接 `toString()`：`3.0` 出来就是 `3.0`（附录 A 的示例行与 `word_reviews.gap_days`
     * 都是 REAL 口径）。整数化的写法会让 CSV 与库里的实际值不等，
     * 而 `0.3333333` 这种原样写出的丑，好过悄悄改掉用户的数。
     */
    fun line(row: ReviewHistoryRow, itemKind: String = KIND_WORD): String = listOf(
        itemKind,
        row.itemId.toString(),
        row.reviewedAt.toString(),
        row.gapDays?.toString().orEmpty(),
        row.confidence?.toString().orEmpty(),
        // grade 的 -1 是"迁移前没记档位"这个事实，照实写出；null 才是"这一列压根没读过"
        row.grade.toString(),
        row.priority.toString(),
        bucketOf(row.gapDays),
    ).joinToString(",")

    /** 表头 + 逐行，行尾只用 `\n`（CSV 里混进 `\r` 会让"行数"这种最朴素的检查在 Windows 上骗人） */
    fun text(rows: List<ReviewHistoryRow>, itemKind: String = KIND_WORD): String =
        (listOf(HEADER) + rows.map { line(it, itemKind) }).joinToString(separator = "\n", postfix = "\n")

    /** UTF-8 字节（附录 A 钉的编码） */
    fun bytes(rows: List<ReviewHistoryRow>, itemKind: String = KIND_WORD): ByteArray =
        text(rows, itemKind).toByteArray(Charsets.UTF_8)
}
