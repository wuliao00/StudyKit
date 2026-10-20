package com.studykit.modelpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelPackCopy] 的文案逐字测试（计划 T5 / 附录 B）。
 *
 * 为什么文案也要测：它是用户读到的产品承诺。改一个字（比如把"开发中 · 暂未上架"改成"可下载"）
 * 就是产品行为变了，测试必须红。附录 B 里标了"待 Lead 批"的两处（无单包可腾时的建议句、
 * 以及状态标签的最终措辞）集中钉在这里，Lead 定稿时改文案 + 改断言同步完成。
 */
class ModelPackCopyTest {

    private val mb = 1024L * 1024L

    @Test
    fun `分区文案逐字`() {
        assertEquals("模型包（可选）", ModelPackCopy.sectionTitle())
        assertEquals(
            "这些包把能力留在本机：下载后自己跑，不上传。不装也能用基础功能。",
            ModelPackCopy.sectionHint(),
        )
        assertEquals("还没有可选的模型包。", ModelPackCopy.emptyState())
    }

    @Test
    fun `六个包标题逐字`() {
        assertEquals("解释包（本机口诀）", ModelPackCopy.packTitle(PackKind.LLM))
        assertEquals("语音包（朗读）", ModelPackCopy.packTitle(PackKind.VOICE_TTS))
        assertEquals("语音包（背诵验收）", ModelPackCopy.packTitle(PackKind.VOICE_ASR))
        assertEquals("高配结构包", ModelPackCopy.packTitle(PackKind.STRUCTURE_V3))
        assertEquals("去手写包", ModelPackCopy.packTitle(PackKind.DE_HANDWRITE))
        assertEquals("语义包", ModelPackCopy.packTitle(PackKind.EMBED))
    }

    @Test
    fun `说明句不带体积时只有正文`() {
        assertEquals("本地朗读卡片，音频不出设备。", ModelPackCopy.packHint(PackKind.VOICE_TTS))
        assertEquals("本地朗读卡片，音频不出设备。", ModelPackCopy.packHint(PackKind.VOICE_TTS, null))
        assertEquals("本地朗读卡片，音频不出设备。", ModelPackCopy.packHint(PackKind.VOICE_TTS, 0L))
    }

    @Test
    fun `说明句按 manifest 体积拼约多少MB`() {
        assertEquals(
            "本地朗读卡片，音频不出设备。约 30 MB。",
            ModelPackCopy.packHint(PackKind.VOICE_TTS, 30L * mb),
        )
        assertEquals(
            "卡绊了你两次时，在本机想一个口诀和错因。约 433 MB。",
            ModelPackCopy.packHint(PackKind.LLM, 433L * mb + 123L),
        )
        // 每个包的正文都要有，且互不相同（防复制粘贴时把 6 句写成同一句）
        val hints = PackKind.entries.map { ModelPackCopy.packHint(it) }
        assertEquals(PackKind.entries.size, hints.toSet().size)
    }

    @Test
    fun `未上架状态与动作标签逐字`() {
        assertEquals("开发中 · 暂未上架（端侧运行时就绪后开放）", ModelPackCopy.plannedBadge())
        assertEquals("这个包还在开发中，当前版本不提供下载。", ModelPackCopy.plannedHint())
        assertEquals("下载", ModelPackCopy.actionDownload())
        assertEquals("取消", ModelPackCopy.actionCancel())
        assertEquals("删除", ModelPackCopy.actionDelete())
        assertEquals("重新下载", ModelPackCopy.actionRedownload())
        assertEquals("去卸载", ModelPackCopy.actionGoUninstall())
        assertEquals("知道了", ModelPackCopy.actionKnown())
        assertEquals("正在校验…", ModelPackCopy.verifying())
        assertEquals("文件不完整，重新下载", ModelPackCopy.corrupt())
    }

    @Test
    fun `删除确认框文案逐字`() {
        assertEquals("删除这个模型包？", ModelPackCopy.deleteConfirmTitle())
        assertEquals(
            "只删除「语音包（朗读）」的模型文件；学习数据、复习记录、错题都不会动，需要时可以再装回来。",
            ModelPackCopy.deleteConfirmBody("语音包（朗读）"),
        )
    }

    @Test
    fun `下载进度百分比向下取整且夹在零到一百`() {
        assertEquals("正在下载… 0%", ModelPackCopy.downloadProgress(0f))
        assertEquals("正在下载… 42%", ModelPackCopy.downloadProgress(0.42f))
        assertEquals("正在下载… 99%", ModelPackCopy.downloadProgress(0.999f))
        assertEquals("正在下载… 100%", ModelPackCopy.downloadProgress(1f))
        assertEquals("正在下载… 0%", ModelPackCopy.downloadProgress(-1f))
        assertEquals("正在下载… 100%", ModelPackCopy.downloadProgress(2f))
    }

    @Test
    fun `已装体积与顶部总账逐字`() {
        assertEquals("已装 30 MB", ModelPackCopy.installedSize(30L * mb))
        assertEquals(
            "已装可选包 30 MB / 预算 600 MB",
            ModelPackCopy.budgetSummary(30L * mb, 600L * mb),
        )
        assertEquals("已装可选包 0 MB / 预算 600 MB", ModelPackCopy.budgetSummary(0L, 600L * mb))
    }

    @Test
    fun `预算拒装带出要卸的包名与二选一策略`() {
        val removal = PackUsage("voice-tts", ModelPackCopy.packTitle(PackKind.VOICE_TTS), 30L * mb)
        val decision = BudgetDecision(
            allowed = false,
            installedBytes = 500L * mb,
            newBytes = 200L * mb,
            budgetBytes = 600L * mb,
            needFreeBytes = 100L * mb,
            suggestRemove = removal,
        )
        val body = ModelPackCopy.budgetReject(decision, ModelPackCopy.packTitle(PackKind.EMBED))
        assertEquals(
            "空间不够：先卸掉 语音包（朗读） 才能装 语义包。（LLM 包与高配结构包建议二选一）",
            body,
        )
        assertEquals("空间不够", ModelPackCopy.budgetDialogTitle())
    }

    @Test
    fun `无单包可腾时还差多少向上取整`() {
        // 只差 1 字节 → 显示 1 MB，不能显示"还差 0 MB"
        val body = ModelPackCopy.budgetRejectNoSingleRemoval(needFreeBytes = 1L)
        assertEquals("空间不够：卸掉任何一个包都还差 1 MB。这个包先别装了，或者先删掉一些别的数据。", body)
        val withAlternative = ModelPackCopy.budgetRejectNoSingleRemoval(
            needFreeBytes = 100L * mb,
            alternativeTitle = ModelPackCopy.packTitle(PackKind.VOICE_TTS),
        )
        assertEquals("空间不够：卸掉任何一个包都还差 100 MB。可以先装 语音包（朗读）。", withAlternative)
    }

    @Test
    fun `预算拒装入口按有无建议分支`() {
        val noSuggestion = BudgetDecision(
            allowed = false,
            installedBytes = 0L,
            newBytes = 700L * mb,
            budgetBytes = 600L * mb,
            needFreeBytes = 100L * mb,
            suggestRemove = null,
        )
        assertTrue(ModelPackCopy.budgetReject(noSuggestion, "X").contains("还差 100 MB"))
    }

    @Test
    fun `下载失败六种话术都非空且互不相同`() {
        val texts = DownloadFailure.entries.map { ModelPackCopy.downloadFailed(it) }
        assertEquals(DownloadFailure.entries.size, texts.size)
        texts.forEach { assertTrue("失败话术不能为空", it.isNotBlank()) }
        assertEquals("六种失败原因不能共用同一句话", texts.size, texts.toSet().size)
        assertEquals(ModelPackCopy.messageNotPublished(), ModelPackCopy.downloadFailed(DownloadFailure.NOT_PUBLISHED))
    }

    @Test
    fun `结果提示与降级文案逐字`() {
        assertEquals("语音包（朗读） 装好了，现在就能用。", ModelPackCopy.messageInstalled("语音包（朗读）"))
        assertEquals("语音包（朗读） 已删除，释放 30 MB。", ModelPackCopy.messageDeleted("语音包（朗读）", 30L * mb))
        assertEquals("已取消下载。", ModelPackCopy.messageCancelled())
        assertEquals("这个包还没上架，当前版本只显示占位。", ModelPackCopy.messageNotPublished())
        assertEquals("读取模型包目录失败，稍后再试。", ModelPackCopy.readFailed())
        assertEquals("操作没成功，稍后再试。", ModelPackCopy.actionFailed())
        assertEquals("这个功能要先装对应的模型包；包只在本机跑，不联网。", ModelPackCopy.missingPack())
        assertEquals("麦克风仅用于背诵验收，波形只在内存里过一遍。", ModelPackCopy.micPermissionRationale())
        assertEquals("背下来了，验收通过。", ModelPackCopy.recitePassed())
        assertEquals("差一点：“勾股定理” 这句没听清，看一面再试。", ModelPackCopy.reciteRetry("勾股定理"))
    }

    @Test
    fun `toMb 向下取整`() {
        assertEquals(600L, ModelPackCopy.toMb(600L * mb))
        assertEquals(0L, ModelPackCopy.toMb(mb - 1))
        assertEquals(1L, ModelPackCopy.toMb(mb + mb - 1))
    }
}
