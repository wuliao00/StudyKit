package com.studykit.modelpack

/**
 * 设置页一行包的状态机（计划 §3.7 的六态）。
 *
 * 用 sealed 而不是"布尔 + 若干可空字段"：六态互斥，界面 when 穷尽时少一个分支编译器就红，
 * "下载中却同时显示删除按钮"这类组合状态在类型上就构造不出来。
 */
sealed interface PackRowStatus {
    /** 未上架占位：本轮六个包全在此态（计划 §3.3）。 */
    data object Planned : PackRowStatus

    /** 已上架、未安装：显示体积 + 下载。 */
    data object NotInstalled : PackRowStatus

    /** 下载中：进度 + 取消。 */
    data class Downloading(val progress: Float) : PackRowStatus

    /** 下载完正在逐文件校验（sha256 已通过才算完）。 */
    data object Verifying : PackRowStatus

    /** 已安装：开关 + 删除。 */
    data class Installed(val enabled: Boolean) : PackRowStatus

    /** manifest 可解析但 payload 缺件/长度不符：文件不完整，重新下载。 */
    data object Corrupt : PackRowStatus
}

/**
 * 一行包的渲染数据。文案全部来自 [ModelPackCopy]（Composable 里不写第二份），
 * 这里只做"哪一行、什么状态、显示什么数字"的拼装。
 */
data class PackRowUi(
    val id: String,
    val title: String,
    /** 说明句（可能带"约 X MB"）。 */
    val hint: String,
    val status: PackRowStatus,
    /** 已装包是 manifest.sizeBytes；已上架未装是清单体积；占位为 0。 */
    val sizeBytes: Long = 0L,
    /** 状态行文案（已装体积 / 开发中徽标 / 损坏提示）；Composable 直接显示，不再拼字。 */
    val statusLine: String? = null,
)

/**
 * 预算拒装弹层的数据（计划 §3.7）。
 *
 * [body] 已经在 ViewModel 里用 [ModelPackCopy] 拼好：弹层本身不认识预算算法，
 * 只负责显示与"去卸载/知道了"两个动作。
 */
data class BudgetRejectUi(
    val incomingId: String,
    /** 建议卸载的已装包 id；null = 卸哪个都不够，弹层没有可跳转的行。 */
    val removalId: String?,
    val title: String,
    val body: String,
)

/**
 * 模型包分区的**唯一**界面状态（计划 §3.8 的 `StateFlow<ModelPackUiState>`）。
 *
 * [summary] 是顶部总账，数字来自 [ModelPackStore] 的已装字节与 [BudgetGuard] 的预算，
 * 界面不许另算一份（计划 §3.7 末条）。
 */
data class ModelPackUiState(
    val rows: List<PackRowUi> = emptyList(),
    val installedBytes: Long = 0L,
    val budgetBytes: Long = BudgetGuard.DEFAULT_BUDGET_BYTES,
    /** 一次性的结果提示（装好/删掉/失败/取消）；界面显示后由 VM 清空或下次操作覆盖。 */
    val message: String? = null,
    /** 非空时显示预算拒装弹层。 */
    val budgetReject: BudgetRejectUi? = null,
) {
    /** 顶部一行总账（计划 §3.7）。 */
    val summary: String get() = ModelPackCopy.budgetSummary(installedBytes, budgetBytes)
}

/** 把某一行换状态，其余行不动 —— VM 的进度刷新与"校验中"都走这一条。 */
fun ModelPackUiState.withRowStatus(id: String, status: PackRowStatus): ModelPackUiState =
    copy(rows = rows.map { row -> if (row.id == id) row.copy(status = status) else row })

/** 只换提示文案（不动行状态）。 */
fun ModelPackUiState.withMessage(text: String?): ModelPackUiState = copy(message = text)

/**
 * 由注册表 + 一次目录扫描拼出六行（纯函数，无 IO）。
 *
 * 顺序 = [registry] 顺序 = [PackIds.ALL]：UI 行序稳定，测试可以按 id 断言。
 * 规则（与 [ScanResult] 的三档对应）：
 * 1. 在 [installed] 里 → [PackRowStatus.Installed]；
 * 2. 在 [brokenIds] 里 → [PackRowStatus.Corrupt]（清单还在，能重新下载修复）；
 * 3. 种子有完整清单 → [PackRowStatus.NotInstalled]；
 * 4. 否则 → [PackRowStatus.Planned]（本轮六个包全在此态）。
 *
 * 已安装但**不在注册表**里的目录不在这里显示：本轮资源全是占位，没有"注册表外的手工包"；
 * 将来真出现时应该另开一条"未知包"通道，而不是让 UI 猜它的文案。
 */
object ModelPackRows {
    fun build(
        registry: List<PackSeed>,
        installed: Map<String, InstalledPack>,
        brokenIds: Set<String>,
    ): List<PackRowUi> = registry.map { seed ->
        val pack = installed[seed.id]
        when {
            pack != null -> PackRowUi(
                id = seed.id,
                title = ModelPackCopy.packTitle(seed.kind),
                hint = ModelPackCopy.packHint(seed.kind, pack.manifest.sizeBytes),
                status = PackRowStatus.Installed(enabled = pack.enabled),
                sizeBytes = pack.manifest.sizeBytes,
                statusLine = ModelPackCopy.installedSize(pack.manifest.sizeBytes),
            )
            seed.id in brokenIds -> PackRowUi(
                id = seed.id,
                title = ModelPackCopy.packTitle(seed.kind),
                hint = ModelPackCopy.packHint(seed.kind, null),
                status = PackRowStatus.Corrupt,
                statusLine = ModelPackCopy.corrupt(),
            )
            seed.downloadable -> PackRowUi(
                id = seed.id,
                title = ModelPackCopy.packTitle(seed.kind),
                hint = ModelPackCopy.packHint(seed.kind, seed.manifest?.sizeBytes),
                status = PackRowStatus.NotInstalled,
                sizeBytes = seed.manifest?.sizeBytes ?: 0L,
            )
            else -> PackRowUi(
                id = seed.id,
                title = ModelPackCopy.packTitle(seed.kind),
                hint = ModelPackCopy.packHint(seed.kind, null),
                status = PackRowStatus.Planned,
                statusLine = ModelPackCopy.plannedBadge(),
            )
        }
    }
}
