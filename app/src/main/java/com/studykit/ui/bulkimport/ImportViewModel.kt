package com.studykit.ui.bulkimport

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.util.OneShotGate
import com.studykit.util.importer.ImportItem
import com.studykit.util.importer.ImportOutcome
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.dedupeBy
import com.studykit.util.importer.dedupeWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

enum class ImportPhase { PREVIEW, COMMITTING, DONE }

/**
 * 预览态：解析计划 + 被用户手动剔除的行号 + 阶段 + 结果。
 * `plan` 可空是必要的：刚进页面与 reset() 之后都没有计划，
 * 此时整页空着、按钮不可点，比拿空计划显示「可导入 0」更诚实。
 */
data class ImportUiState(
    val plan: ImportPlan? = null,
    val excluded: Set<Int> = emptySet(),
    val phase: ImportPhase = ImportPhase.PREVIEW,
    val outcome: ImportOutcome? = null,
    val error: String? = null,
) {
    val visibleItems: List<ImportItem>
        get() = plan?.items?.filterNot { it.sourceLine in excluded } ?: emptyList()
    val canSubmit: Boolean get() = phase == ImportPhase.PREVIEW && visibleItems.isNotEmpty()
}

/**
 * 四个来源（批量粘贴 / SAF 文件 / 在线词库 / OCR 取词）共用的预览与入库。
 *
 * 去重放在这里而不是解析器里：解析器不认识数据库，也不该知道「重复」是相对谁的。
 * 实体构造（uuid / optionsJson）同样收在这里，让 `util/importer/` 保持零 Android 依赖。
 */
class ImportViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val wordRepository = container.wordRepository
    private val questionRepository = container.questionRepository

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val committing = OneShotGate()

    fun loadPlan(plan: ImportPlan) {
        _state.value = ImportUiState(plan = plan)
    }

    fun toggleExcluded(sourceLine: Int) {
        val current = _state.value
        if (current.phase != ImportPhase.PREVIEW) return
        val next = if (sourceLine in current.excluded) current.excluded - sourceLine
        else current.excluded + sourceLine
        _state.value = current.copy(excluded = next)
    }

    fun reset() {
        _state.value = ImportUiState()
    }

    /**
     * 预览页只认这一个入口：按条目的实际类型决定写哪张表。
     * 预览路由不带 kind，这里若写死 submitWords，题目那一批会被当成单词灌进 words 表。
     */
    fun submit(onDone: () -> Unit) {
        when (_state.value.visibleItems.firstOrNull()) {
            is ImportItem.Word -> submitWords(onDone = onDone)
            is ImportItem.Question -> submitQuestions(onDone = onDone)
            else -> Unit
        }
    }

    /** 批量入词：先与库内全量词面去重，再一次性写入；[sourceListId] 只在整本词库导入时给 */
    fun submitWords(sourceListId: Long? = null, onDone: () -> Unit) = commit(onDone) {
        val items = _state.value.visibleItems.filterIsInstance<ImportItem.Word>()
        val deduped = dedupeWords(items, wordRepository.wordTexts().toSet())
        val entities = deduped.kept.map { item ->
            Word(
                uuid = UUID.randomUUID().toString(),
                word = item.word,
                meaning = item.meaning,
                example = item.example,
                sourceListId = sourceListId,
            )
        }
        ImportOutcome(
            inserted = wordRepository.addAll(entities),
            skippedDuplicates = deduped.skipped,
            rejected = _state.value.plan?.rejected.orEmpty(),
        )
    }

    /** 批量入题：题目没有天然唯一键，退化为「题干 + 全部选项」的内容判重 */
    fun submitQuestions(onDone: () -> Unit) = commit(onDone) {
        val items = _state.value.visibleItems.filterIsInstance<ImportItem.Question>()
        val deduped = dedupeBy(
            items = items,
            keyOf = { (listOf(it.stem) + it.options).joinToString(QUESTION_KEY_SEP) },
            displayOf = { it.stem },
        )
        val entities = deduped.kept.map { item ->
            Question(
                uuid = UUID.randomUUID().toString(),
                subject = UNGROUPED_SUBJECT,
                stem = item.stem,
                optionsJson = JSONArray(item.options).toString(),
                answerIndex = item.answerIndex,
                explanation = item.explanation,
            )
        }
        ImportOutcome(
            inserted = questionRepository.addAll(entities),
            skippedDuplicates = deduped.skipped,
            rejected = _state.value.plan?.rejected.orEmpty(),
        )
    }

    /**
     * 三条入库路共用的闸门：阶段检查 + 双击防重 + 失败回退。
     * 失败必须把 phase 退回 PREVIEW 并留下一句话，否则按钮永久停在灰态、
     * 用户分不清是没点着还是写坏了。
     */
    private fun commit(onDone: () -> Unit, work: suspend () -> ImportOutcome) {
        val current = _state.value
        if (current.phase != ImportPhase.PREVIEW) return
        if (!committing.tryEnter()) return
        _state.value = current.copy(phase = ImportPhase.COMMITTING, error = null)
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { work() }
                _state.value = _state.value.copy(phase = ImportPhase.DONE, outcome = outcome)
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    phase = ImportPhase.PREVIEW,
                    error = "没能写入，可以再试一次：${e.localizedMessage ?: e.javaClass.simpleName}",
                )
            } finally {
                committing.leave()
            }
        }
    }
}

/** 与 QuestionCreateScreen 的默认学科一致，用户之后可在题目列表里改 */
private const val UNGROUPED_SUBJECT = "未分类"

/** 题干与每个选项用控制字符拼键：正文里不会出现，不会把两条不同题误判成同一条 */
private const val QUESTION_KEY_SEP = "\u0000"
