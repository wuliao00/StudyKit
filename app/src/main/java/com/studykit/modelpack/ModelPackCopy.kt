package com.studykit.modelpack

/**
 * 模型包相关的**全部**用户可见文案（计划 T5 / 附录 B）。
 *
 * ## 为什么文案要收成一个文件
 * 1. 单一来源：Composable 里写第二份字面量，改一处忘一处时没有任何测试看得见
 *    （这正是仓库里 `check-copied-strings.sh` 那道闸门存在的理由）；
 * 2. 可机检：纯函数返回 String，JVM 单测逐字钉住 —— 文案是产品承诺，改它应该让测试变红；
 * 3. 好定稿：附录 B 里标了"待 Lead 批"的句子集中在这里，Lead 改一处即可，不用翻 UI。
 *
 * ## 数字口径
 * 所有"约 X MB"都从 `manifest.sizeBytes` / 已装字节算出（[toMb]），**不写死文档估算**。
 * 计划 §2.6 明确：文档 L930 的 +400/+70/+50/+150 是修正前体积，不能直接进 UI 文案。
 *
 * ## 语气
 * 沿用设置页既有口径：第二人称、说清"装了什么、数据在哪、不装会怎样"；不承诺未验证的收益。
 */
object ModelPackCopy {

    private const val MB = 1024L * 1024L

    // ── 分区 ────────────────────────────────────────────────────────────

    /** 设置页分区标题（计划附录 B）。 */
    fun sectionTitle(): String = "模型包（可选）"

    /** 分区说明：把"本机跑、不上传、不装也能用"三件事說全（文档口径）。 */
    fun sectionHint(): String = "这些包把能力留在本机：下载后自己跑，不上传。不装也能用基础功能。"

    /** 六个包一条都没有时的空态（注册表被改空时界面不至于只剩标题）。 */
    fun emptyState(): String = "还没有可选的模型包。"

    // ── 六个包 → 标题 / 说明 ───────────────────────────────────────────

    /**
     * 包标题（计划附录 B / §3.3 种子表）。
     *
     * 按 [PackKind] 而不是 id 分派：同一类包换 id（如 D5 的 0.5B/0.6B 命名之争）时文案不跟着改名。
     */
    fun packTitle(kind: PackKind): String = when (kind) {
        PackKind.LLM -> "解释包（本机口诀）"
        PackKind.VOICE_TTS -> "语音包（朗读）"
        PackKind.VOICE_ASR -> "语音包（背诵验收）"
        PackKind.STRUCTURE_V3 -> "高配结构包"
        PackKind.DE_HANDWRITE -> "去手写包"
        PackKind.EMBED -> "语义包"
    }

    /**
     * 包说明：先一句"装了这个包你会得到什么"，体积已知时再补"约 X MB。"。
     *
     * [sizeBytes] 为空 / 0 表示"还没有真实体积"（未上架占位），此时**不许**编一个数字。
     */
    fun packHint(kind: PackKind, sizeBytes: Long? = null): String {
        val base = when (kind) {
            PackKind.LLM -> "卡绊了你两次时，在本机想一个口诀和错因。"
            PackKind.VOICE_TTS -> "本地朗读卡片，音频不出设备。"
            PackKind.VOICE_ASR -> "本地听你背诵并打分，波形只在内存里过一遍。"
            PackKind.STRUCTURE_V3 -> "把整页读成块：正文、表格、公式各归其位，本机跑。"
            PackKind.DE_HANDWRITE -> "尝试擦掉照片上的笔迹，原图永远留着。"
            PackKind.EMBED -> "给每张卡在本机算一个「意思坐标」，语义交错、相似错题检索靠它。"
        }
        val size = sizeBytes?.takeIf { it > 0L }?.let { "约 ${toMb(it)} MB。" }.orEmpty()
        return base + size
    }

    // ── 行状态 ─────────────────────────────────────────────────────────

    /** 未上架占位（计划 §3.3 / §3.10：本轮五个包全在此态）。 */
    fun plannedBadge(): String = "开发中 · 暂未上架（端侧运行时就绪后开放）"

    /** 未上架行里不该出现体积承诺：这里单独给一句"别装"的说明。 */
    fun plannedHint(): String = "这个包还在开发中，当前版本不提供下载。"

    fun actionDownload(): String = "下载"

    fun actionCancel(): String = "取消"

    fun actionDelete(): String = "删除"

    fun actionRedownload(): String = "重新下载"

    /**
     * 删除确认框的标题/正文（计划 §3.7：不可逆动作必须二次确认）。
     *
     * 正文必须说清「只删模型文件、不碰学习数据」：用户对「删除」的默认恐惧是丢学习记录，
     * 而这里删的只是可选包的文件，库里的单词 / 复习记录 / 错题一行都不动。
     */
    fun deleteConfirmTitle(): String = "删除这个模型包？"

    fun deleteConfirmBody(title: String): String =
        "只删除「$title」的模型文件；学习数据、复习记录、错题都不会动，需要时可以再装回来。"

    /** 预算拒装弹层的确认按钮：跳到要卸的那一行。 */
    fun actionGoUninstall(): String = "去卸载"

    /** 无单包可腾时弹层的确认按钮（没有可跳的行，只关窗）。 */
    fun actionKnown(): String = "知道了"

    fun verifying(): String = "正在校验…"

    /** 行内状态：文件不完整（[PackRowStatus.Corrupt]）。 */
    fun corrupt(): String = "文件不完整，重新下载"

    /** 下载进度行；百分比向下取整（99.9% 不显示 100%，避免"下完了却还在转"的错觉）。 */
    fun downloadProgress(progress: Float): String {
        val percent = (progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
        return "正在下载… $percent%"
    }

    /** 已装行右侧的体量（附录 B 的 `已装 %1$s MB`）。 */
    fun installedSize(sizeBytes: Long): String = "已装 ${toMb(sizeBytes)} MB"

    /** 分区顶部总账（计划 §3.7）；预算数字来自 [BudgetGuard.DEFAULT_BUDGET_BYTES]，不写死。 */
    fun budgetSummary(installedBytes: Long, budgetBytes: Long): String =
        "已装可选包 ${toMb(installedBytes)} MB / 预算 ${toMb(budgetBytes)} MB"

    // ── 预算拒装（文档 L2220 原文 + 一处待定稿分支）─────────────────────

    fun budgetDialogTitle(): String = "空间不够"

    /**
     * 拒装正文：有"删一个就够"的候选时，把**包名**写进去（用户要能一眼找到卸哪个）。
     *
     * 文案口径是两个分支的入口，具体句子见下面两个函数 —— 界面只调这个。
     */
    fun budgetReject(decision: BudgetDecision, incomingTitle: String): String =
        decision.suggestRemove?.let { removal ->
            budgetRejectWithRemoval(removal.displayName, incomingTitle)
        } ?: budgetRejectNoSingleRemoval(decision.needFreeBytes)

    /**
     * 删掉一个包就够的正文（附录 B 文档 L2220 原文，保留"LLM 包与高配结构包建议二选一"这句策略）。
     *
     * 注意这里的包名来自已装包的显示名，而不是写死"某个包"：用户看到的名字必须与列表里一致。
     */
    fun budgetRejectWithRemoval(removalTitle: String, incomingTitle: String): String =
        "空间不够：先卸掉 $removalTitle 才能装 $incomingTitle。（LLM 包与高配结构包建议二选一）"

    /**
     * 无单包可腾时的正文。**这半句是计划附录 B 标"新文案待 Lead 批"的那条**，
     * 目前按"还差多少 + 建议先装更小的包"写；Lead 定稿后只改这里与对应断言。
     */
    fun budgetRejectNoSingleRemoval(needFreeBytes: Long, alternativeTitle: String? = null): String {
        // "还差 X MB"向上取整：只差 1 字节时写 1，不能显示"还差 0 MB"
        val shortfallMb = (needFreeBytes + MB - 1) / MB
        val advice = alternativeTitle?.let { "可以先装 $it。" } ?: "这个包先别装了，或者先删掉一些别的数据。"
        return "空间不够：卸掉任何一个包都还差 $shortfallMb MB。$advice"
    }

    // ── 下载/删除/降级 的结果文案 ───────────────────────────────────────

    fun messageInstalled(title: String): String = "$title 装好了，现在就能用。"

    fun messageDeleted(title: String, freedBytes: Long): String =
        "$title 已删除，释放 ${toMb(freedBytes)} MB。"

    fun messageCancelled(): String = "已取消下载。"

    fun messageNotPublished(): String = "这个包还没上架，当前版本只显示占位。"

    /** 读取包目录/预算判定这类"底层失败"的用户话术（技术细节进 logcat，不进界面）。 */
    fun readFailed(): String = "读取模型包目录失败，稍后再试。"

    /** 删除/改启用状态等操作失败时的通用话术（失败原因进 logcat）。 */
    fun actionFailed(): String = "操作没成功，稍后再试。"

    /**
     * 下载失败的**用户话术**（按 [DownloadFailure] 分支）。
     *
     * 技术细节（HTTP 码、缺哪个文件）不进这句：它们是排查用的，由 ViewModel 落到 logcat；
     * 用户只需要知道"再试一次有没有用"。
     */
    fun downloadFailed(reason: DownloadFailure): String = when (reason) {
        DownloadFailure.NOT_PUBLISHED -> messageNotPublished()
        DownloadFailure.SPACE -> "空间不够，先腾出一些空间再下载。"
        DownloadFailure.NETWORK -> "网络不稳，这次没下完。稍后再试一次。"
        DownloadFailure.HTTP -> "下载源暂时没有给出可用的文件，稍后再试。"
        DownloadFailure.ARCHIVE -> "下载到的文件不完整（解不开），重新下载试试。"
        DownloadFailure.VERIFY -> "文件校验没通过，这次下载已清掉，重新下载试试。"
    }

    // ── 功能侧（留给 T8 之后接线的降级文案）─────────────────────────────

    /** 包缺失时功能入口的降级句（计划附录 B）："要装包" + "只在本机跑"两件事。 */
    fun missingPack(): String = "这个功能要先装对应的模型包；包只在本机跑，不联网。"

    /** 麦克风权限理据（文档 L2045）。 */
    fun micPermissionRationale(): String = "麦克风仅用于背诵验收，波形只在内存里过一遍。"

    /** 背诵验收通过（文档 L2045）。 */
    fun recitePassed(): String = "背下来了，验收通过。"

    /** 背诵验收差一点：把没听清的那句原文回显（文档 L2045 的改写）。 */
    fun reciteRetry(unclear: String): String = "差一点：“$unclear” 这句没听清，看一面再试。"

    // ── 数字工具 ───────────────────────────────────────────────────────

    /** 字节 → MB（向下取整），所有 MB 文案的唯一换算口径。 */
    fun toMb(bytes: Long): Long = bytes / MB
}
