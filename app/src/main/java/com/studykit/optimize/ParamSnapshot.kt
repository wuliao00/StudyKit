package com.studykit.optimize

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 端侧优化器采纳新参数前留下的**参数快照**（v2.9 计划 Task 5，Room v8 新表）。
 *
 * 每一次"采纳"（Task 11 的门禁点头之后）都先落一行：把当时的 21 参向量、训练规模与 loss、
 * 引擎版本一起存下来。它存在的意义是**可回滚**——新参数把排期搞坏了，能退回上一份，
 * 而不是只能重置成出厂默认。DAO 只保留最近 [MAX_KEEP] 份（见 [com.studykit.data.dao.ParamSnapshotDao]）。
 *
 * [params] 用逗号拼接的可读文本存（而不是 BLOB），一个 21 项数组本就几百字节，导出、排查、
 * 手挑回滚某一份都比二进制好读；口径与 [com.studykit.data.AppSettings.fsrsActiveParams] 的存储串一致。
 *
 * 索引建在 `ts`：回滚列表与"最近一份"的查询都按时间倒序取前几条（[MAX_KEEP]），一次索引扫即可。
 */
@Entity(
    tableName = "param_snapshots",
    indices = [
        Index(
            name = "index_param_snapshots_ts",
            value = ["ts"],
        ),
    ],
)
data class ParamSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 21 参 FSRS v6 向量，逗号拼接的可读文本（与设置里的活跃参数串同一口径，便于导出/回滚） */
    val params: String,
    /** 采纳发生的 epoch 毫秒 */
    val ts: Long,
    /** 训练用到的事件条数（nTerms）——快照里留档"这份参数吃了多少数据训出来的" */
    @ColumnInfo(name = "n_terms") val nTerms: Int,
    /** 采纳时的验证集 logloss，与旧参数同表对照，门禁的改善口径在此留痕 */
    val logloss: Double,
    /** 训练/推理引擎版本标识（如 "v6.21"），日后公式演进时据此判别老快照能否重放 */
    @ColumnInfo(name = "engine_ver") val engineVer: String,
) {
    companion object {
        /** 只保留最近这么多份快照：够回滚几步，又不让表无限膨胀（Task 11 护栏）。 */
        const val MAX_KEEP = 5

        /**
         * 写快照时盖上的**训练/推理引擎版本**（Task 11）。
         *
         * 口径：`v` + FSRS 主版本 + `.` + 参数个数，于是 "v6.21" 读作「21 参的 v6 引擎」。
         * 日后公式演进（改 `FsrsModel` 里任何一个式子或参数个数）必须同步改这一串，
         * 否则旧快照会被当成可以拿新内核重放的东西——而 [params] 里那 21 个数对新公式毫无意义。
         * 测试侧（`ParamSnapshotDaoTest` / `CalibrateGuardrailTest`）引用同一个常量，不许两处各自写字面量。
         */
        const val ENGINE_VER = "v6.21"
    }
}
