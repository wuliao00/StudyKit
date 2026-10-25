package com.studykit.domain.design

/**
 * 模板里的一个输入字段。
 *
 * 这里**只有形状，没有默认值**：`hint` 是输入框的占位提示（"该写什么"），不是替用户预填的内容。
 * 生成器与表单都按这份清单走，所以加字段只需要改本文件 + `DesignGenerator` 的对应分支，
 * 表单（`DesignEditorScreen`）会自动长出来。
 *
 * @property key 存库用的稳定键，会进 `fields_json`，**不要跟着界面文案改** —— 改了旧方案里
 *   这一项就读不回来了。
 * @property label 界面上显示的中文标签。
 * @property hint 输入框占位提示，写"该填什么"，不写"示例答案"。
 * @property multiline 单行还是多行。多行字段在 Markdown 里逐行拆成列表项。
 * @property required 是否必填。必填项缺失时生成器写「（待补充）」占位，可选项缺失时整段省略。
 */
data class DesignField(
    val key: String,
    val label: String,
    val hint: String,
    val multiline: Boolean = true,
    val required: Boolean = true,
)

/**
 * 8 种教学/学习设计模板。
 *
 * 枚举顺序就是界面上的展示顺序（也是 `DesignMode.entries` 的顺序）：先"讲清楚"（费曼），
 * 再"练"（习题带练），再"调方法"（学会学习），再"带别人"（工作坊），最后是课程级设计
 * （系列课、逆向、核心素养、螺旋）。不是按字母序，也不是按热度 —— 它是一条从"我用"
 * 到"我教"的线。
 *
 * [basis] 只写方法论的名字与出处，不写"研究表明"这种没有落点的空话：用户看到"依据"
 * 两个字时，应该能顺藤查证，而不是被告知一个结论。
 *
 * 本文件是纯 Kotlin（不 import 任何 Android 类），所以 `test/` 里的 JVM 用例可以直接
 * 跑它，不需要 Robolectric。
 */
enum class DesignMode(
    val label: String,
    val tagline: String,
    val basis: String,
    val fields: List<DesignField>,
) {

    /** 费曼技巧：把概念讲给外行，讲不下去的地方就是没懂的地方；配自测问题做检索练习。 */
    FEYNMAN(
        label = "费曼学习",
        tagline = "讲给外行听，暴露理解缺口",
        basis = "费曼技巧 + 自测效应",
        fields = listOf(
            DesignField(
                key = "concept",
                label = "概念或主题",
                hint = "例：机会成本（只写一个，越小越好）",
                multiline = false,
            ),
            DesignField(
                key = "audience",
                label = "讲给谁听",
                hint = "一个没学过的人",
                multiline = false,
            ),
            DesignField(
                key = "explanation",
                label = "用大白话解释",
                hint = "不查资料一口气写完；不许用行话，必须出现「因为……所以……」",
            ),
            DesignField(
                key = "gaps",
                label = "卡住 / 说不清的点",
                hint = "写不下去、只能背原话、要靠「反正就是这样」带过的地方",
            ),
            DesignField(
                key = "analogy",
                label = "类比或生活例子",
                hint = "能对上解释里最抽象的那一步，不只是「大概像」",
            ),
            DesignField(
                key = "selfTest",
                label = "自测问题",
                hint = "每行一个，答案要能在这份解释里找到",
            ),
        ),
    ),

    /** 波利亚四阶段（理解题目—拟定方案—执行—回顾）+ 例题与变式练习。 */
    GUIDED_PRACTICE(
        label = "习题带练",
        tagline = "从例题到变式，梳理解题思路",
        basis = "波利亚《怎样解题》四阶段 + 例子学习与变式练习",
        fields = listOf(
            DesignField(
                key = "topic",
                label = "知识点",
                hint = "例：一元二次方程根的判别式",
                multiline = false,
            ),
            DesignField(
                key = "questionType",
                label = "题型",
                hint = "单选/多选/判断/填空/计算/简答/主观/材料",
                multiline = false,
            ),
            DesignField(
                key = "sample",
                label = "例题",
                hint = "完整题干与选项/设问；这一步先不写答案",
            ),
            DesignField(
                key = "steps",
                label = "解题步骤",
                hint = "每行一步，写清「为什么走这一步」，不是只写算式",
            ),
            DesignField(
                key = "traps",
                label = "易错点",
                hint = "例：漏判增根、符号写反、把「至少」读成「恰好」",
            ),
            DesignField(
                key = "variant",
                label = "变式题",
                hint = "改条件 / 改问法 / 换情境，至少一道",
            ),
            DesignField(
                key = "transfer",
                label = "下次遇到同类题先问什么",
                hint = "写下那句能启动思路的自问，例：未知量是谁？限制条件有几个？",
            ),
        ),
    ),

    /** 学习技术综述：检索练习、间隔、交错比重读/划线有效；用证据调方法，而不是加时长。 */
    LEARNING_TO_LEARN(
        label = "学会学习",
        tagline = "用证据调整方法，而不是更用力",
        basis = "Dunlosky et al. 2013 学习技术综述：检索练习、间隔、交错优于重读与划线",
        fields = listOf(
            DesignField(
                key = "goal",
                label = "当前目标",
                hint = "例：两个月内把四级核心词过一轮（要有期限与可验收的结果）",
                multiline = false,
            ),
            DesignField(
                key = "current",
                label = "现在怎么做",
                hint = "如实写：每天多久、什么时间、用什么材料、怎么算「学过」",
            ),
            DesignField(
                key = "bottleneck",
                label = "最大卡点",
                hint = "只写一个最要命的：背了就忘 / 一做题就超时 / 一遇难就停",
            ),
            DesignField(
                key = "evidence",
                label = "一周内的证据 / 数据",
                hint = "能数出来的东西：次数、正确率、时长、卡在第几步；不写「感觉还行」",
            ),
            DesignField(
                key = "experiment",
                label = "接下来两周要试的方法",
                hint = "每条只改一件事，并写明怎么判断它有没有用",
            ),
            DesignField(
                key = "keepDrop",
                label = "继续 / 停止什么",
                hint = "例：继续交错练习与睡前检索；停止反复重读笔记",
            ),
            DesignField(
                key = "reviewAt",
                label = "复盘时间",
                hint = "例：两周后的周日晚上（写具体到哪一天）",
                multiline = false,
            ),
        ),
    ),

    /** Kolb 体验式学习循环（具体经验—反思观察—抽象概括—主动实验）+ 工作坊三段时间盒。 */
    WORKSHOP(
        label = "互动工作坊",
        tagline = "用活动让参与者自己得出答案",
        basis = "Kolb 体验式学习循环 + 工作坊三段时间盒（破冰—活动—复盘）",
        fields = listOf(
            DesignField(
                key = "theme",
                label = "主题",
                hint = "例：把教研会开成工作坊",
                multiline = false,
            ),
            DesignField(
                key = "audience",
                label = "参与者与人数",
                hint = "例：初二数学组 12 人（4 人一组）",
                multiline = false,
            ),
            DesignField(
                key = "duration",
                label = "总时长",
                hint = "例：90 分钟（2 × 45），含 10 分钟休息",
                multiline = false,
            ),
            DesignField(
                key = "goal",
                label = "学习目标",
                hint = "结束时参与者能做什么（可观察，不写「了解 / 感受」）",
            ),
            DesignField(
                key = "warmup",
                label = "破冰 / 激活",
                hint = "10 分钟内把每个人拉进问题：一个提问、一次投票、一张便签",
            ),
            DesignField(
                key = "activity",
                label = "核心活动",
                hint = "每行一步：做什么、几人一组、产出什么；活动要逼出分歧",
            ),
            DesignField(
                key = "debrief",
                label = "复盘问题",
                hint = "按「发生了什么 / 为什么 / 下次怎么做」排，问题要具体到刚才的活动",
            ),
            DesignField(
                key = "materials",
                label = "材料与分工",
                hint = "清单 + 谁负责哪一段（含计时与记录）",
                required = false,
            ),
        ),
    ),

    /** 课程地图 + 目标—活动—评价对齐：多课时排成一条递进线，而不是八个独立讲座。 */
    SERIES(
        label = "系列课规划",
        tagline = "多课时排成一条递进线",
        basis = "课程地图 + 目标—活动—评价对齐",
        fields = listOf(
            DesignField(
                key = "course",
                label = "课程名",
                hint = "例：高一函数入门（8 课时）",
                multiline = false,
            ),
            DesignField(
                key = "audience",
                label = "对象",
                hint = "例：高一平行班，零基础",
                multiline = false,
            ),
            DesignField(
                key = "outcomes",
                label = "课程目标",
                hint = "每行一条，用「能……」开头；3-5 条即可",
            ),
            DesignField(
                key = "arc",
                label = "主线 / 递进逻辑",
                hint = "从哪到哪：每一课给下一课铺什么，断了会怎样",
            ),
            DesignField(
                key = "sessionPlan",
                label = "课时安排",
                hint = "每行：课时主题 | 核心目标 | 关键活动",
            ),
            DesignField(
                key = "assessment",
                label = "评价方式",
                hint = "每行一种：形式 + 评价什么（作业/小测/作品/口头答辩）",
            ),
            DesignField(
                key = "resources",
                label = "资源与作业",
                hint = "每行一项：材料、阅读、练习",
                required = false,
            ),
        ),
    ),

    /** Wiggins & McTighe 逆向设计：先定目标与证据，再排活动（而不是先想活动再贴目标）。 */
    BACKWARD(
        label = "逆向教学设计",
        tagline = "先定目标与证据，再排活动",
        basis = "Wiggins & McTighe 逆向设计三阶段",
        fields = listOf(
            DesignField(
                key = "standard",
                label = "课标 / 目标来源",
                hint = "例：义务教育数学课程标准（2022）第三学段「函数」",
                multiline = false,
            ),
            DesignField(
                key = "enduring",
                label = "持久理解 / 大概念",
                hint = "离开课堂两年后仍该记住的那句话；不是知识点清单",
            ),
            DesignField(
                key = "questions",
                label = "基本问题",
                hint = "每行一个，能引发持续追问，没有唯一标准答案",
            ),
            DesignField(
                key = "evidence",
                label = "可接受的证据 / 表现性任务",
                hint = "学生做出什么，就说明他真的懂了（作品、解释、迁移题）",
            ),
            DesignField(
                key = "activities",
                label = "学习活动",
                hint = "每条活动都要能对上一条证据；对不上的先别写",
            ),
            DesignField(
                key = "transfer",
                label = "迁移情境",
                hint = "新情境 / 新题型 / 真实问题：在哪里还能用上这个理解",
            ),
        ),
    ),

    /** 中国学生发展核心素养 + 学科课标的目标—任务—评价一致性：把素养翻译成可观察的任务。 */
    COMPETENCY(
        label = "核心素养备课",
        tagline = "把素养目标翻译成可观察的任务",
        basis = "中国学生发展核心素养 + 学科课程标准的目标—任务—评价一致性",
        fields = listOf(
            DesignField(
                key = "subject",
                label = "学科与学段",
                hint = "例：初中物理 八年级",
                multiline = false,
            ),
            DesignField(
                key = "unit",
                label = "课题",
                hint = "例：浮力（第 1 课时）",
                multiline = false,
            ),
            DesignField(
                key = "competency",
                label = "素养目标",
                hint = "每行：素养 | 具体表现（表现要能被看见，不写「培养……意识」）",
            ),
            DesignField(
                key = "task",
                label = "核心任务",
                hint = "每行一个任务，写清产出物与完成条件",
            ),
            DesignField(
                key = "assessment",
                label = "评价量规",
                hint = "每行：维度 | 水平描述（水平描述要能区分「做到」与「没做到」）",
            ),
            DesignField(
                key = "support",
                label = "支架与资源",
                hint = "每行一项：工具、范例、提问单、分层材料",
                required = false,
            ),
            DesignField(
                key = "reflection",
                label = "课后反思点",
                hint = "每行一个，下次备课时先看",
                required = false,
            ),
        ),
    ),

    /** Bruner《教育过程》：同一概念多轮回来，每轮换一种表征、加深一层抽象。 */
    SPIRAL(
        label = "螺旋式课程设计",
        tagline = "同一概念多轮回来，每次更深",
        basis = "Bruner《教育过程》螺旋课程",
        fields = listOf(
            DesignField(
                key = "concept",
                label = "核心概念",
                hint = "例：函数 / 能量守恒 / 供需（要能贯穿多个学段）",
                multiline = false,
            ),
            DesignField(
                key = "levels",
                label = "学段 / 轮次",
                hint = "例：小学直观 → 初中变量 → 高中模型",
                multiline = false,
            ),
            DesignField(
                key = "round1",
                label = "第一轮：直观经验",
                hint = "先动手、先观察，不急着下定义",
            ),
            DesignField(
                key = "round2",
                label = "第二轮：结构化解释",
                hint = "给出定义、符号与推理链，把第一轮的直觉接上",
            ),
            DesignField(
                key = "round3",
                label = "第三轮：迁移与创造",
                hint = "用这个概念解决新问题，或做出一个作品",
            ),
            DesignField(
                key = "check",
                label = "每轮如何判断「真的更深了」",
                hint = "每行一条可观察的判据：能说出什么、能做出什么",
            ),
        ),
    ),
    ;

    companion object {
        /**
         * 从 `design_plans.mode` 读回枚举。认不出（旧版本写的名字、手改过的库）就返回 null，
         * 让调用方显示「未知模板」而不是在列表页崩溃。
         *
         * 允许前后空白：这一列是我们自己写的，宽容一点不会掩盖任何真实错误。
         */
        fun fromStorage(raw: String?): DesignMode? {
            val key = raw?.trim().orEmpty()
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.name == key }
        }
    }
}
