package com.studykit.data.repository

import com.studykit.data.dao.DesignPlanDao
import com.studykit.data.entity.DesignPlan
import com.studykit.domain.design.DesignMode
import com.studykit.domain.design.DesignPlanCodec
import kotlinx.coroutines.flow.Flow

/**
 * 「学习设计」仓储（v12）。
 *
 * 大部分成员只是把 [DesignPlanDao] 原样转出去 —— 与 [MockSessionRepository] 同一手法：
 * 这一层存在的意义是把 Room 挡在 ViewModel 外，不是再造一套业务规则。
 *
 * 唯一有逻辑的是 [save]：它要决定「插一行还是改一行」，并把 `fields` 编码成 JSON、
 * 把 `updated_at` 推到当前时刻。拼 Markdown 不在这里（那是
 * [com.studykit.domain.design.DesignGenerator] 的纯函数），这层只落库。
 */
class DesignPlanRepository(private val dao: DesignPlanDao) {

    fun observeAll(): Flow<List<DesignPlan>> = dao.observeAll()

    fun observeById(id: Long): Flow<DesignPlan?> = dao.observeById(id)

    suspend fun getById(id: Long): DesignPlan? = dao.getById(id)

    /**
     * 新建或覆盖保存，返回落库后的行 id（新建时是自增 id，覆盖时就是 [id]）。
     *
     * 语义（逐条都可被测试钉住）：
     *  - `id == null`：插入新行。[DesignPlan.uuid] / `created_at` 走实体默认值
     *    （`UUID.randomUUID()` / 当前时刻），`updated_at` 与 `created_at` 同一次
     *    取时，避免"刚存下来就已经是一分钟前"这种看起来像坏数据的行。
     *  - `id != null`：先读旧行，**保留** `uuid` / `created_at`（它们是这份方案的身份与
     *    出生时间，编辑不该刷新），只改 `title` / `mode` / `fields_json` / `content` /
     *    `updated_at`。
     *  - 旧行读不到（比如另一次点击已经删掉了它）：**退化为插入**，而不是对着一行
     *    不存在的记录做 UPDATE。`update` 命中 0 行不会报错，用户看到的是"保存成功"
     *    但内容其实丢了 —— 这是最糟的一种失败，宁可多出一行也不吞掉他刚写的东西。
     *
     * `title` 只做 trim，不替用户补默认名：空标题怎么显示是 UI 的事（列表里已按
     * 「未命名方案」兜底），存储层不去猜。
     *
     * 字段编码用 [DesignPlanCodec]（键排序稳定）：同一份表单重复保存得到同一串 JSON，
     * 将来做备份比对/去重时不必先规范化。
     */
    suspend fun save(
        id: Long?,
        mode: DesignMode,
        title: String,
        fields: Map<String, String>,
        content: String,
    ): Long {
        val now = System.currentTimeMillis()
        val fieldsJson = DesignPlanCodec.encode(fields)
        val old = id?.let { dao.getById(it) }
        return if (old == null) {
            dao.insert(
                DesignPlan(
                    mode = mode.name,
                    title = title.trim(),
                    fieldsJson = fieldsJson,
                    content = content,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            dao.update(
                old.copy(
                    mode = mode.name,
                    title = title.trim(),
                    fieldsJson = fieldsJson,
                    content = content,
                    updatedAt = now,
                ),
            )
            old.id
        }
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
