package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.studykit.data.entity.AppSetting
import kotlinx.coroutines.flow.Flow

/**
 * 设置的读写。只有三个动作：整表观察、批量 upsert、整表清空（清除数据用）。
 *
 * 刻意**没有**按 key 单读的接口 —— 设置项之间要一起生效（主题 + 玻璃 + 减弱动效同时变），
 * 单键读会在页面里长出十几个 flow，重组时机还各不一样。
 */
@Dao
interface AppSettingDao {

    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<AppSetting>>

    @Upsert
    suspend fun upsertAll(rows: List<AppSetting>)

    @Query("DELETE FROM app_settings")
    suspend fun clearAll()
}
