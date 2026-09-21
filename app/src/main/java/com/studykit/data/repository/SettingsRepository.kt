package com.studykit.data.repository

import com.studykit.data.AppSettings
import com.studykit.data.dao.AppSettingDao
import com.studykit.data.entity.AppSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 用户本地设置的唯一读写口。
 *
 * 读侧只有一条 `Flow<AppSettings>`：整表观察 → 键值映射 → 类型化，
 * 坏值在 [AppSettings.fromMap] 里逐项退回默认，所以下游永远拿到一份能直接画的值，不需要判空。
 *
 * 写侧一律整份覆盖（upsert 全部键）。看起来"改一个开关写八行"浪费，但换来的是
 * 任何时刻库里的设置都是一个完整快照，不会读到"主题已改、玻璃没改"的半成品，
 * 而这张表的规模是常数级（不到十行）。
 */
class SettingsRepository(private val dao: AppSettingDao) {

    val settings: Flow<AppSettings> = dao.observeAll()
        .map { rows -> AppSettings.fromMap(rows.associate { it.key to it.value }) }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(next: AppSettings) {
        val now = System.currentTimeMillis()
        dao.upsertAll(next.toMap().map { (key, value) -> AppSetting(key = key, value = value, updatedAt = now) })
    }

    /** 读-改-写。同一帧内连续两次 update 是"后写覆盖前写"，设置页不存在这种并发 */
    suspend fun update(transform: (AppSettings) -> AppSettings) = save(transform(current()))

    /** 只记不判：诊断文本，设置页原样展示 */
    suspend fun recordDictFailure(message: String) = update { it.copy(lastDictFailure = message.take(400)) }

    /** 清空即回到默认 —— [AppSettings.fromMap] 对缺键取默认，所以不需要逐个键写回默认值 */
    suspend fun resetToDefaults() = dao.clearAll()
}
