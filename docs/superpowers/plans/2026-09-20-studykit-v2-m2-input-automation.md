# StudyKit v2.0 M2 录入自动化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 StudyKit 加「录入自动化五件套」——批量粘贴、SAF 文件导入、在线词库商店、截图分享进错题、ML Kit bundled 中文 OCR，让录入单词/题目/笔记从「一条一条手打」变成「成批进来 + 一次校对」。

**Architecture:** 三层。①**纯 Kotlin 解析层**（`util/importer/`）：把任意来源（粘贴文本、CSV/TXT 文件、OCR 文本行、在线词表 NDJSON）统一解析成 `ImportItem` / `RejectedLine`，零 Android 依赖，JVM 单测覆盖；②**统一预览-入库层**（`ui/bulkimport/`）：一个 `ImportPreviewScreen` + 一个 `ImportResultScreen`，四个来源全部汇入这里，去重、逐行剔除、批量事务入库都在 `ImportViewModel` 一处实现；③**来源适配层**：SAF 读取、`HttpURLConnection` + `ZipInputStream` 词库下载、`SEND` intent 收件、ML Kit OCR —— 每个只做「拿到原始字节/图片 → 交给解析层」。数据层为 M2 增一张 `word_lists` 表与 `words.source_list_id` 列，支持整表删除。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose（BOM 2024.12.01 / Material3 1.3.1）、Room 2.6.1（KSP）、OkHttp **不用**（`HttpURLConnection` + 内置 `org.json`）、`java.util.zip.ZipInputStream`、ML Kit `text-recognition-chinese:16.0.1`（bundled）、WorkManager 2.10.0（已存在，M2 不新增后台作业）、JUnit 4.13.2。

**Spec:** `docs/superpowers/specs/2026-09-18-studykit-v2-design.md`（§5 录入自动化、§6 数据变更、§7 构建与验证、§8 测试策略）

---

## Global Constraints

以下每一条对**所有任务**隐含生效，值一律逐字照抄，不得自行"顺手改"：

- 分支：`feat/v2-visual-motion`（M1 已在此分支，M2 继续）。**禁止**合并 main、打 tag、发 Release、force push —— 这些需要先问用户。
- **本机没有 JDK 也没有 Android SDK**：任何任务都不得在本地跑 `gradlew`/`java`/`adb install` 之外的构建命令。编译与单测的唯一执行器是 GitHub Actions（`.github/workflows/ci.yml`，两个 job：`Lint / Unit Test / Debug Build` 与 `Release Build (unsigned) / Baseline Profile Check`）。TDD 的 RED/GREEN 用「先推测试提交看红、再推实现看绿」的双推证据，run id 记进报告。
- 真机验证可用 adb 全自动完成：`/e/tools/adb/adb.exe`（先 `export PATH="/e/tools/adb:$PATH"`），设备序列号 `35152127910030J`（vivo V2156A / Android 11 / 1080×2408 / 480dpi）。装包用桌面 `StudyKit-v2.0.0/auto-install.sh`。**注意**：CI 每次 runner 的 debug keystore 不同，覆盖安装必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先 `adb uninstall com.studykit`。
- 颜色/文字/间距/圆角/阴影/尺寸只能取 `AppTheme.colors` / `AppTheme.texts` / `AppTheme.space` / `AppTheme.radius` / `AppTheme.size` / `AppTheme.elevation`；动效参数只能取 `MotionSpec`。禁止 `MaterialTheme.colorScheme`、`Color(0x…)` 字面量（唯一例外：`ui/theme/Palette.kt` 与 `ui/book/BookSpine.kt` 的 `SpinePalette`）。
- 品牌色（`accent`/`success`/`gold`/`warning`）只用于填充、描边、色点、粒子；**文字与图标位**一律用同族 `*Ink`，「实底色 + 白字」只保留 `accentInk` + `onAccent` 一处（见 `AppButton` 填充态）。
- 边到边：`AppNav` 根 `Scaffold` 的 `innerPadding` 是**全仓唯一**的 insets 消费者。任何新页面**禁止**再加 `.imePadding()` / `.safeDrawingPadding()` / `.windowInsetsPadding()`（叠加即双份键盘高度，M1 真机踩过）。页面根用 `Column(...).verticalScroll(rememberScrollState()).padding(horizontal = AppTheme.space.pageH)`。
- 导航：所有子页 `navController.navigate(...) { launchSingleTop = true }`；路由常量集中在 `ui/nav/AppNav.kt` 的 `object *Routes`。
- 动画/组合卫生：`TextStyle` / `animateFloatAsState` / `animateColorAsState` / `Animatable` / `tween` / `spring` 构造**全部命名参数**（位置参会命中废弃重载直接编不过）。**唯一例外**：`@JvmInline value class CornerRadius(radiusX = ...)` 必须位置参；`RoundedCornerShape(Dp)` 沿用位置参。
- 本仓 lint 禁 `produceState`（M1 踩过：`ProduceStateDoesNotAssignValue`）。异步取值用 `remember + LaunchedEffect + withContext(Dispatchers.IO)` 既有写法。
- 一次性提交门一律用 `util/OneShotGate`（`tryEnter()` / `finally leave()`），不要在页面另写 `enabled` 标志。
- 文案：全部中文字面量直接写在 Compose 里（本仓 `strings.xml` 只有 `app_name`，不要引入资源字符串）。
- 提交信息：中文 conventional commits（`feat(dict): ...`、`fix(import): ...`、`test(parser): ...`）。
- 不新增**运行时**依赖，唯一例外是 spec §5.4 明确批准的 `com.google.mlkit:text-recognition-chinese:16.0.1`；测试依赖允许新增 `org.json:json`（见 Task 8 的裁定）。
- `DemoSeeder` 只在 DEBUG 首次建库时播种 20 条演示词（id 1..20），因此任何"空态引导"在 debug 包上不会自动出现，导入相关测试用例不得依赖空库。

---

## 文件结构（M2 新增/改动）

**数据层**
- `app/src/main/java/com/studykit/data/entity/WordList.kt`（新建）—— 在线词库的入库来源记录，支持整表删除。
- `app/src/main/java/com/studykit/data/entity/Word.kt`（改）—— 增可空列 `sourceListId`。
- `app/src/main/java/com/studykit/data/AppDatabase.kt`（改）—— `version 2 → 3`、注册 `WordList::class`、手写 `MIGRATION_2_3`。
- `app/src/main/java/com/studykit/data/dao/WordListDao.kt`（新建）—— 词库表 CRUD。
- `app/src/main/java/com/studykit/data/dao/WordDao.kt`（改）—— 批量 `insertAll`、按词库删除、全量词面用于去重。
- `app/src/main/java/com/studykit/data/repository/WordListRepository.kt`（新建）。
- `app/src/main/java/com/studykit/AppContainer.kt`（改）—— 挂 `wordListRepository`。

**解析层（纯 Kotlin，零 Android import）**
- `app/src/main/java/com/studykit/util/importer/ImportModels.kt`（新建）—— `ImportItem` / `RejectedLine` / `ImportPlan` / `ImportOutcome`。
- `app/src/main/java/com/studykit/util/importer/WordLineParser.kt`（新建）—— 单词行（Tab / 逗号 / 连续空格自适应分列）。
- `app/src/main/java/com/studykit/util/importer/QuestionLineParser.kt`（新建）—— 题目行（`题干 | 选项… | 答案`）。
- `app/src/main/java/com/studykit/util/importer/NoteLineParser.kt`（新建）—— 笔记行（`书名 | 摘录 | 感想`）。
- `app/src/main/java/com/studykit/util/importer/TextCleaner.kt`（新建）—— BOM 剥离、CRLF、法语字母归一、空行折叠。
- `app/src/main/java/com/studykit/util/importer/DictBookParser.kt`（新建）—— 词库 NDJSON 单行 → `ImportItem.Word`（纯字符串入参，可单测）。

**来源适配层**
- `app/src/main/java/com/studykit/data/remote/DictRemote.kt`（新建）—— `HttpURLConnection` 拉目录 / 下 zip / `ZipInputStream` 解出 NDJSON。
- `app/src/main/assets/dict/booklists.json`（新建）—— 离线目录快照（随 APK 发布，在线可刷新）。
- `app/src/main/java/com/studykit/util/OcrTextExtractor.kt`（新建）—— ML Kit 封装，返回识别文本。
- `app/src/main/java/com/studykit/util/ShareIntake.kt`（新建）—— `SEND`/`SEND_MULTIPLE` intent → 本地图片文件。
- `app/src/main/AndroidManifest.xml`（改）—— `INTERNET` 权限、分享 `intent-filter`、`launchMode="singleTop"`。
- `app/src/main/java/com/studykit/MainActivity.kt`（改）—— 读 intent（含 `onNewIntent`）喂给 `ShareIntake`。

**界面层**
- `app/src/main/java/com/studykit/ui/bulkimport/ImportViewModel.kt`（新建）—— 预览态、去重、批量入库、结果统计。
- `app/src/main/java/com/studykit/ui/bulkimport/ImportPreviewScreen.kt`（新建）—— 逐行预览 / 剔除 / 待修正区。
- `app/src/main/java/com/studykit/ui/bulkimport/ImportResultScreen.kt`（新建）—— 成功 N / 跳过 M / 待修正 K + 逐行原因。
- `app/src/main/java/com/studykit/ui/bulkimport/BulkPasteScreen.kt`（新建）—— 大输入框 + 实时预览 + 文件导入入口。
- `app/src/main/java/com/studykit/ui/study/DictStoreScreen.kt`（新建）—— 词库商店（分类 / 搜索 / 预览 / 导入 / 已导入管理）。
- `app/src/main/java/com/studykit/ui/study/WordListScreen.kt`（改）—— 工具栏加「批量导入」「词库商店」。
- `app/src/main/java/com/studykit/ui/mistake/MistakeCaptureScreen.kt`（改）—— 「从图片提取文字」按钮 + 分享来的图片自动预识别。
- `app/src/main/java/com/studykit/ui/mistake/MistakeDetailScreen.kt`（改）—— 长按图片「识别文字」补录。
- `app/src/main/java/com/studykit/ui/nav/AppNav.kt`（改）—— 新路由与转场接入。

---

### Task 1: 数据层——`word_lists` 表、`words.source_list_id` 列、批量入库 DAO

Room 当前 `version = 2`、`exportSchema = false`、只有一条手写 `MIGRATION_1_2`。M2 要加一张表和一列，因此**必须**手写 `MIGRATION_2_3`（没有 schema 导出就没有 AutoMigration），并且 SQL 的类型与默认值要和 Room 生成的建表语句逐字一致，否则启动时 `IllegalStateException: Room cannot verify...`（真机才炸，CI 测不出来 —— 所以本任务含一步真机验证）。

**Files:**
- Create: `app/src/main/java/com/studykit/data/entity/WordList.kt`
- Create: `app/src/main/java/com/studykit/data/dao/WordListDao.kt`
- Create: `app/src/main/java/com/studykit/data/repository/WordListRepository.kt`
- Modify: `app/src/main/java/com/studykit/data/entity/Word.kt`
- Modify: `app/src/main/java/com/studykit/data/AppDatabase.kt`
- Modify: `app/src/main/java/com/studykit/data/dao/WordDao.kt`
- Modify: `app/src/main/java/com/studykit/AppContainer.kt`

**Interfaces:**
- Consumes: 既有 `Word`（`id/uuid/syncStatus/word/meaning/example/status/nextReviewAt/createdAt`，`STATUS_NEW="NEW"`）、`AppDatabase.getInstance(context)`、`AppContainer` 的 eager `val xxxRepository = XxxRepository(database.xxxDao())` 风格。
- Produces:
  - `data class WordList(val id: Long = 0L, val sourceId: String, val title: String, val wordNum: Int = 0, val importedCount: Int = 0, val importedAt: Long = System.currentTimeMillis())`，表名 `word_lists`，`sourceId` 唯一索引。
  - `WordDao.insertAll(words: List<Word>): List<Long>`、`WordDao.getWordTexts(): List<String>`（suspend）、`WordDao.deleteByList(sourceListId: Long): Int`（suspend）、`WordDao.countByList(sourceListId: Long): Int`（suspend）。
  - `WordListRepository(db: AppDatabase)`（构造只接数据库，DAO 在仓储内部自取），方法：`add(wordList: WordList): Long`、`observeAll(): Flow<List<WordList>>`、`getBySourceId(sourceId: String): WordList?`、`markImportedCount(id: Long, count: Int)`、`deleteAlongWithWords(wordList: WordList): Int`。
  - `Word.sourceListId: Long?`（默认 `null`）。

- [ ] **Step 1: 新建 `WordList` 实体**

```kotlin
package com.studykit.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 在线词库的导入来源：一行 = 一次「整本导入」。
 * `words.source_list_id` 指回这里，因此支持「删掉这本词库带进来的所有单词」——
 * 用户最怕的就是「导入 3000 个词之后没法反悔」。
 */
@Entity(
    tableName = "word_lists",
    indices = [Index(value = ["source_id"], unique = true)],
)
data class WordList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 词库在数据源里的标识（如 kajweb/dict 的 `CET4luan_1`），用于去重与整表删除 */
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    /** 数据源声称的词数，仅用于展示「已导入 x / 3000」 */
    @ColumnInfo(name = "word_num") val wordNum: Int = 0,
    /** 实际入库条数（去重后可能少于 wordNum） */
    @ColumnInfo(name = "imported_count") val importedCount: Int = 0,
    @ColumnInfo(name = "imported_at") val importedAt: Long = System.currentTimeMillis(),
)
```

- [ ] **Step 2: `Word` 增列**

在 `Word.kt` 的 `createdAt` 之前插入（保持既有字段顺序不动，避免 `MIGRATION_1_2` 的语义受影响）：

```kotlin
    /** 来自哪本在线词库（`word_lists.id`）；手工/粘贴/文件导入为 null，不参与整表删除 */
    @ColumnInfo(name = "source_list_id") val sourceListId: Long? = null,
```

- [ ] **Step 3: `WordDao` 增批量与按库操作**

```kotlin
    /** 批量入库；返回自增 id 列表，实践上与入参同序，但 Room 未承诺 —— 勿依赖顺序，只当入库计数用 */
    @Insert
    suspend fun insertAll(words: List<Word>): List<Long>

    /** 全量词面，仅用于导入前去重（词表万级以内可接受；M2 不做索引优化） */
    @Query("SELECT word FROM words")
    suspend fun getWordTexts(): List<String>

    @Query("DELETE FROM words WHERE source_list_id = :sourceListId")
    suspend fun deleteByList(sourceListId: Long): Int

    @Query("SELECT COUNT(*) FROM words WHERE source_list_id = :sourceListId")
    suspend fun countByList(sourceListId: Long): Int
```

同时把文件顶部 import 补成 `androidx.room.Insert`（已有）与 `androidx.room.Query`（已有）。

- [ ] **Step 4: 新建 `WordListDao`**

```kotlin
package com.studykit.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.studykit.data.entity.WordList
import kotlinx.coroutines.flow.Flow

@Dao
interface WordListDao {

    /**
     * `source_id` 上有 UNIQUE 索引，默认 ABORT 策略：重复导入同一本词库会抛
     * `SQLiteConstraintException`。这是刻意保留的报错信号 —— 调用方必须先 [getBySourceId] 判重，
     * 而不是把冲突改成 IGNORE/REPLACE 把问题吞掉。
     */
    @Insert
    suspend fun insert(wordList: WordList): Long

    @Query("SELECT * FROM word_lists ORDER BY imported_at DESC")
    fun observeAll(): Flow<List<WordList>>

    @Query("SELECT * FROM word_lists WHERE source_id = :sourceId LIMIT 1")
    suspend fun getBySourceId(sourceId: String): WordList?

    @Query("UPDATE word_lists SET imported_count = :count WHERE id = :id")
    suspend fun updateImportedCount(id: Long, count: Int)

    @Delete
    suspend fun delete(wordList: WordList)
}
```

- [ ] **Step 5: 新建 `WordListRepository`**

```kotlin
package com.studykit.data.repository

import androidx.room.withTransaction
import com.studykit.data.AppDatabase
import com.studykit.data.dao.WordDao
import com.studykit.data.dao.WordListDao
import com.studykit.data.entity.WordList
import kotlinx.coroutines.flow.Flow

/**
 * 在线词库来源表。单词侧的按库删除经 [wordDao] 转发，避免调用方同时摸两个 DAO。
 *
 * 需要 [AppDatabase] 而非单个 DAO，只为「删词库 + 删单词」这一步的事务：两张表必须同生同死。
 */
class WordListRepository(
    private val db: AppDatabase,
) {

    private val wordListDao: WordListDao = db.wordListDao()

    private val wordDao: WordDao = db.wordDao()

    fun observeAll(): Flow<List<WordList>> = wordListDao.observeAll()

    /** [WordListDao.insert] 在 source_id 重复时抛异常（见 DAO 注释），调用方须先 [getBySourceId] 判重 */
    suspend fun add(wordList: WordList): Long = wordListDao.insert(wordList)

    suspend fun getBySourceId(sourceId: String): WordList? = wordListDao.getBySourceId(sourceId)

    suspend fun markImportedCount(id: Long, count: Int) = wordListDao.updateImportedCount(id, count)

    /**
     * 返回被一并删除的单词条数，供结果卡展示「已撤销 N 个单词」。
     * 删除跑在一个事务里：中途失败必须整体回滚，否则留下"词库行还在、单词已空"的幽灵条目，
     * 而它的 source_id 会挡住重新导入。
     */
    suspend fun deleteAlongWithWords(wordList: WordList): Int = db.withTransaction {
        val removed = wordDao.deleteByList(wordList.id)
        wordListDao.delete(wordList)
        removed
    }
}
```

`room-ktx` 已在 `gradle/libs.versions.toml:29` 与 `app/build.gradle.kts:62`，`withTransaction` 不引入新依赖。
签名已从 2.6.1 的 sources jar 核对：`suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R`
—— block **没有接收者**，事务体内直接引用本类的 DAO 属性即可；事务只保证串行与回滚，仍要求里面的 DAO 调用都是 suspend。

- [ ] **Step 6: `AppDatabase` 注册实体 + 写 `MIGRATION_2_3`**

`@Database` 的 `entities` 数组追加 `WordList::class`，`version = 3`。在 `MIGRATION_1_2` 之后加：

```kotlin
    /**
     * v2 → v3：新增在线词库来源表，并给 `words` 挂上可空的来源列。
     *
     * SQL 必须与 Room 依据实体生成的建表语句逐字一致（`exportSchema = false` 时无人替你核对），
     * 否则真机升级时抛 `IllegalStateException: Room cannot verify that the schema matches`，
     * 且 CI 全绿也测不出来 —— 因此本迁移的真机存活验证是计划里的硬步骤。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `word_lists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`source_id` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`word_num` INTEGER NOT NULL, " +
                    "`imported_count` INTEGER NOT NULL, " +
                    "`imported_at` INTEGER NOT NULL" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_word_lists_source_id` " +
                    "ON `word_lists` (`source_id`)",
            )
            // 可空列没有 DEFAULT；Room 校验列类型时只看类型与可空性
            db.execSQL("ALTER TABLE `words` ADD COLUMN `source_list_id` INTEGER")
        }
    }
```

并把 `.addMigrations(MIGRATION_1_2)` 改为 `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。

- [ ] **Step 7: `AppContainer` 挂仓库**

```kotlin
    val wordListRepository: WordListRepository = WordListRepository(database)
```

同时在 `AppDatabase` 里加抽象方法：

```kotlin
    abstract fun wordListDao(): WordListDao
```

- [ ] **Step 8: 提交并推送，确认 CI 绿**

```bash
git add app/src/main/java/com/studykit/data app/src/main/java/com/studykit/AppContainer.kt
git commit -m "feat(data): 词库来源表与 words.source_list_id，补批量入库 DAO 与 MIGRATION_2_3"
git push origin feat/v2-visual-motion
gh run watch   # 必须看到 Lint / Unit Test / Debug Build 与 Release Build 两个 job 都成功
```

- [x] **Step 9: 真机验证 schema（本任务的硬门槛，CI 无法替代）**

风险点只有一个：**`MIGRATION_2_3` 的 SQL 与 Room 依据实体生成的期望 schema 不一致**（列名/类型/可空性/索引写法写错）。它只在真机开库时抛 `IllegalStateException: Room cannot verify that the schema matches`，CI 全绿也发现不了。

先说清为什么"装旧包再覆盖装新包"这条路走不通：CI 每个 runner 现生成 debug keystore，两个 run 的产物签名互不相同，`adb install -r` 必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，迁移代码根本执行不到；本机没有 JDK/`apksigner`，也无法把新包签成旧包的签名。**但这不代表迁移执行路径不可测** —— 下面两步法把 `migrate()` 真跑了一遍（Task 1 已执行通过，过程与判据见 `.superpowers/sdd/…/task-1-report.md`）。

> 早期草案里"用 node 手建一个只含 `word_lists` 且 `user_version=3` 的库再注入"是错的，两处硬伤：(a) 它没有 `words` 表，与"`words cols` 里含 `source_list_id`"的判据自相矛盾；(b) 版本号已经等于 3 时 Room 只做校验、不做迁移，压根测不到 `migrate()`。故弃用。

**9.1 取 Room 的"标准答案"并逐字比对**：装新包（全新安装）后直接首启，此时未注入任何文件，Room 会依实体自建 v3 库。

```bash
export PATH="/e/tools/adb:$PATH"
bash auto-install.sh /tmp/sk-new/app-debug.apk 2.0.0        # 期望末行 installed versionName 匹配
adb shell monkey -p com.studykit -c android.intent.category.LAUNCHER 1
sleep 6 && adb shell pidof com.studykit                      # 期望：有 pid
adb logcat -d | grep -iE "Room cannot verify|IllegalStateException|FATAL EXCEPTION"   # 期望：无输出
adb exec-out run-as com.studykit cat /data/data/com.studykit/databases/studykit.db > /tmp/room-v3.db
node --experimental-sqlite -e "                              # 打印 Room 自建库的真 DDL，与迁移三条语句逐字比对
const {DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('/tmp/room-v3.db');
for(const r of db.prepare(\"SELECT sql,name FROM sqlite_master WHERE name IN ('word_lists','index_word_lists_source_id')\").all())console.log(r.name,'::',r.sql);
console.log('words cols=',db.prepare('PRAGMA table_info(words)').all().map(c=>c.name).join(','));
console.log('hash=',db.prepare('SELECT identity_hash FROM room_master_table').get().identity_hash);"
```

比对要点：列名/类型/可空性逐字相同（`IF NOT EXISTS` 不计，`sqlite_master` 不记录它）；`word_lists` 的 UNIQUE 索引必须 `origin = "c"`（独立 `CREATE UNIQUE INDEX` 语句）——若写成表内 `UNIQUE` 约束会是 `origin="u"`，Room 直接判为不一致，这是最容易踩的一条。存下这个 `hash`，它是 9.2 的对照组。

**9.2 造一个"老用户手里那种 v2 库"注入，逼真机跑 `onUpgrade(2→3)`**：用 node 重放 Room 原生的 v2 DDL（12 表 + 8 索引，schema-only，`PRAGMA user_version=2`，并写回 v2 的 `identity_hash`），推上设备后再首启。

```bash
# 本地：node --experimental-sqlite 重放 v2 DDL → /tmp/v2/studykit-v2-gen.db
#   自校验：user_version=2、word_lists present=false、tables=12 indexes=8、integrity_check=ok
adb shell am force-stop com.studykit
adb shell run-as com.studykit rm -f /data/data/com.studykit/databases/studykit.db{,-wal,-shm}
# 引号必须活到设备侧 shell，否则重定向以 shell uid 执行 → Permission denied
adb shell "run-as com.studykit sh -c 'cat > /data/data/com.studykit/databases/studykit.db'" < /tmp/v2/studykit-v2-gen.db
adb shell run-as com.studykit ls -l /data/data/com.studykit/databases/    # 期望：uid 是 u0_aXXXX 且字节数与本地一致
adb shell monkey -p com.studykit -c android.intent.category.LAUNCHER 1
sleep 8 && adb shell pidof com.studykit                                   # 期望：有 pid（迁移失败会直接崩）
adb logcat -d | grep -iE "Room cannot verify|IllegalStateException|AndroidRuntime"   # 期望：无输出
```

拉回本地断言（任一不符即按报错里的 "expected … found …" 逐字段改 `MIGRATION_2_3` 后重跑）：

```bash
adb exec-out run-as com.studykit cat /data/data/com.studykit/databases/studykit.db > /tmp/after-migration.db
node --experimental-sqlite -e "
const {DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('/tmp/after-migration.db');
const p=s=>db.prepare(s);
console.log('user_version=',p('PRAGMA user_version').get().user_version);          // 期望 3
console.log('hash=',p('SELECT identity_hash FROM room_master_table').get().identity_hash);  // 期望与 9.1 完全相同
console.log('words cols=',p('PRAGMA table_info(words)').all().map(c=>c.name).join(','));      // 期望含 source_list_id，且类型为 INTEGER、notnull=0、无默认值
console.log('word_lists=',!!p(\"SELECT name FROM sqlite_master WHERE name='word_lists'\").get());
console.log('idx origin=',p(\"SELECT origin FROM sqlite_master WHERE name='index_word_lists_source_id'\").get().origin); // 期望 c
console.log('rows words=',p('SELECT COUNT(*) c FROM words').get().c,'lists=',p('SELECT COUNT(*) c FROM word_lists').get().c); // 期望 0 / 0：DemoSeeder 只在 onCreate 播种，升级路径不播种
console.log('integrity=',p('PRAGMA integrity_check').get().integrity_check);"
```

**已证**：迁移的**执行**路径（`migrate()` 真跑 → `validateMigration` 比对实体期望 schema → 重写 hash）在 vivo `35152127910030J` 上通过，`identity_hash` 迁移后与 Room 自建库逐字相同。
**唯一未覆盖项**：*"同签名 A→B 覆盖安装"*这一条分发路径（本机与 CI 都没有稳定 keystore）。把这一条（且仅这一条）写进 CHANGELOG 的「已知限制」。

---

### Task 2: 解析层的地基——模型与文本清洗（TDD）

`util/importer/` 下全部是**纯 Kotlin**（不得 import 任何 `android.*` / `androidx.*`），这是本计划能被 CI 单测覆盖的前提，也是仓库既有约定（`StudyStreak`、`renderMistakeDetail` 同此形态）。

**Files:**
- Create: `app/src/main/java/com/studykit/util/importer/ImportModels.kt`
- Create: `app/src/main/java/com/studykit/util/importer/TextCleaner.kt`
- Test: `app/src/test/java/com/studykit/util/importer/TextCleanerTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `sealed interface ImportItem { val sourceLine: Int; val raw: String }` 与四个实现 `ImportItem.Word(word, meaning, example)`、`ImportItem.Question(stem, options, answerIndex, explanation)`、`ImportItem.Excerpt(book, excerpt, thought)`、`ImportItem.Mistake(subject, title, content)`。
  - `enum class RejectReason { EMPTY_FIELDS, TOO_FEW_COLUMNS, ANSWER_NOT_FOUND }`
  - `data class RejectedLine(val sourceLine: Int, val raw: String, val reason: RejectReason)`
  - `data class ImportPlan(val items: List<ImportItem>, val rejected: List<RejectedLine>)`，含 `val blankCount: Int`。
  - `sealed interface LineResult { data class Ok(item); data class Bad(rejected); data object Blank }`
  - `data class ImportOutcome(val inserted: Int, val skippedDuplicates: List<String>, val rejected: List<RejectedLine>)`
  - `TextCleaner.splitLines(text: String): List<NumberedLine>`、`TextCleaner.normalizeAccents(s: String): String`、`data class NumberedLine(val line: Int, val text: String)`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanerTest {

    @Test
    fun `行号从 1 开始且与原文行序一致`() {
        val lines = TextCleaner.splitLines("a\nb\nc")
        assertEquals(listOf(1 to "a", 2 to "b", 3 to "c"), lines.map { it.line to it.text })
    }

    @Test
    fun `空行保留行号但内容为空串`() {
        val lines = TextCleaner.splitLines("a\n\nb")
        assertEquals(3, lines.size)
        assertEquals(2, lines[1].line)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `剥离 BOM 与 CRLF`() {
        val lines = TextCleaner.splitLines("﻿word\r\nmeaning")
        assertEquals("word", lines[0].text)
        assertEquals("meaning", lines[1].text)
    }

    @Test
    fun `制表符与首尾空白被裁掉但制表符本身留给分列`() {
        val lines = TextCleaner.splitLines("  apple\t一个苹果  ")
        assertEquals("apple\t一个苹果", lines[0].text)
    }

    @Test
    fun `法语字母归一为 ASCII`() {
        assertEquals("epole", TextCleaner.normalizeAccents("épole"))
        assertEquals("canada", TextCleaner.normalizeAccents("çànaüa"))
    }

    @Test
    fun `中文与日文假名不受归一影响`() {
        assertEquals("苹果 形近词", TextCleaner.normalizeAccents("苹果 形近词"))
    }
}
```

- [ ] **Step 2: 提交测试并推送，确认 CI 变红**

```bash
git add app/src/test/java/com/studykit/util/importer/TextCleanerTest.kt
git commit -m "test(import): TextCleaner 行号/BOM/CRLF/重音归一的红测"
git push origin feat/v2-visual-motion
gh run watch    # 期望：compileDebugUnitTestKotlin FAILED，Unresolved reference 'TextCleaner'
```
把这次 run id 记进报告（这是 RED 证据）。

- [ ] **Step 3: 写 `ImportModels.kt`**

```kotlin
package com.studykit.util.importer

/**
 * 一行文本解析出来的待入库条目。四种目标类型共用一套「行号 + 原文」外壳，
 * 预览页与结果页因此只需要认这一个类型。
 */
sealed interface ImportItem {
    /** 原文行号（1 基），逐行定位与「待修正」回显都靠它 */
    val sourceLine: Int
    /** 原始文本；入库成功后不再使用，仅用于展示与二次修正 */
    val raw: String

    data class Word(
        override val sourceLine: Int,
        override val raw: String,
        val word: String,
        val meaning: String,
        val example: String = "",
    ) : ImportItem

    data class Question(
        override val sourceLine: Int,
        override val raw: String,
        val stem: String,
        val options: List<String>,
        val answerIndex: Int,
        val explanation: String = "",
    ) : ImportItem

    data class Excerpt(
        override val sourceLine: Int,
        override val raw: String,
        val book: String,
        val excerpt: String,
        val thought: String = "",
    ) : ImportItem

    data class Mistake(
        override val sourceLine: Int,
        override val raw: String,
        val subject: String,
        val title: String,
        val content: String,
    ) : ImportItem
}

/** 解析失败的原因。刻意不含「未知」：新增格式时必须在这里表态 */
enum class RejectReason { EMPTY_FIELDS, TOO_FEW_COLUMNS, ANSWER_NOT_FOUND }

/** 解析失败/信息不全的行 —— 进「待修正」区，绝不静默丢弃（spec §5.1） */
data class RejectedLine(val sourceLine: Int, val raw: String, val reason: RejectReason)

/** 单行解析结果三态：可用 / 待修正 / 空行（空行不计入任何统计） */
sealed interface LineResult {
    data class Ok(val item: ImportItem) : LineResult
    data class Bad(val rejected: RejectedLine) : LineResult
    data object Blank : LineResult
}

"""一次导入的最终账目：成功条数 / 因重复被跳过的原始键 / 待修正行。
放在模型层是因为预览页与结果页都要读它，而它不属于任何一个来源。"""
data class ImportOutcome(
    val inserted: Int,
    val skippedDuplicates: List<String>,
    val rejected: List<RejectedLine>,
)

/** 一次解析的产出：可入库项 + 待修正项 + 被跳过的空行数 */
data class ImportPlan(
    val items: List<ImportItem>,
    val rejected: List<RejectedLine>,
    val blankCount: Int = 0,
) {
    val totalMeaningful: Int get() = items.size + rejected.size
}

/** 把三态结果折叠成 ImportPlan，四个解析器共用这段折叠逻辑 */
internal fun List<LineResult>.toPlan(): ImportPlan {
    val items = ArrayList<ImportItem>(size)
    val rejected = ArrayList<RejectedLine>(4)
    var blanks = 0
    forEach { result ->
        when (result) {
            is LineResult.Ok -> items.add(result.item)
            is LineResult.Bad -> rejected.add(result.rejected)
            LineResult.Blank -> blanks++
        }
    }
    return ImportPlan(items = items, rejected = rejected, blankCount = blanks)
}
```

- [ ] **Step 4: 写 `TextCleaner.kt`**

```kotlin
package com.studykit.util.importer

/**
 * 导入文本的入口清洗。刻意做成 `object` + 纯函数：粘贴、SAF 文件、OCR 结果、
 * 在线词表四条来源都从这里进，行号语义（1 基、与原文一一对应）只在这里定义一次。
 */
object TextCleaner {

    private const val BOM = '﻿'

    /** 部分字典混入带重音的拉丁字母（kajweb/dict README 明确提示），归一后便于检索与去重 */
    private val ACCENT_FOLDINGS = listOf(
        "é" to "e", "ê" to "e", "è" to "e", "ë" to "e",
        "à" to "a", "â" to "a", "ç" to "c",
        "î" to "i", "ï" to "i", "ô" to "o",
        "ù" to "u", "û" to "u", "ü" to "u", "ÿ" to "y",
    )

    /**
     * 切行并编号：保留原始行序（空行也占一个号），因为「待修正」区要能把用户指回他刚贴的那一行。
     * 处理 BOM、CRLF/CR、每行首尾空白；不动行内的制表符与多空格（那是分列信号）。
     */
    fun splitLines(text: String): List<NumberedLine> {
        val normalized = text.removePrefix(BOM.toString()).replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split('\n').mapIndexed { index, raw ->
            NumberedLine(line = index + 1, text = raw.trim().trimEnd())
        }
    }

    fun normalizeAccents(value: String): String {
        var result = value
        ACCENT_FOLDINGS.forEach { (from, to) -> result = result.replace(from, to) }
        return result
    }
}

/** 一行原文及其 1 基行号 */
data class NumberedLine(val line: Int, val text: String)
```

- [ ] **Step 5: 提交实现并推送，确认 CI 转绿**

```bash
git add app/src/main/java/com/studykit/util/importer
git commit -m "feat(import): 解析层模型与 TextCleaner（行号/BOM/CRLF/重音归一）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿，testDebugUnitTest 里出现 TextCleanerTest 6 例
```

- [ ] **Step 6: 把 RED/GREEN 两个 run id 写进任务报告**

报告路径：`.superpowers/sdd/2026-09-20-studykit-v2-m2-input-automation/task-2-report.md`（由 SDD 流程创建）。

---

### Task 3: 单词行解析器（TDD）

spec §5.1 的单词格式是 `word \t 释义 [\t 例句]`，但用户手贴的内容里分隔符五花八门，所以分列要"自适应"。分列优先级与 `limit = 3` 是本任务的全部难点，测试必须把每条分支钉住。

**Files:**
- Create: `app/src/main/java/com/studykit/util/importer/WordLineParser.kt`
- Test: `app/src/test/java/com/studykit/util/importer/WordLineParserTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `TextCleaner.splitLines`、`TextCleaner.normalizeAccents`、`LineResult`、`ImportItem.Word`、`RejectedLine`、`RejectReason`、`ImportPlan`、`List<LineResult>.toPlan()`。
- Produces: `object WordLineParser { fun parseAll(text: String): ImportPlan; fun parseLine(lineNo: Int, raw: String): LineResult }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLineParserTest {

    private fun ok(lineNo: Int, raw: String): ImportItem.Word =
        (WordLineParser.parseLine(lineNo, raw) as LineResult.Ok).item as ImportItem.Word

    @Test
    fun `制表符分三列：词、释义、例句`() {
        val item = ok(1, "abandon\tv. 放弃\tHe had to abandon the plan.")
        assertEquals("abandon", item.word)
        assertEquals("v. 放弃", item.meaning)
        assertEquals("He had to abandon the plan.", item.example)
    }

    @Test
    fun `制表符分两列时例句为空串而非 null`() {
        val item = ok(1, "apple\tn. 苹果")
        assertEquals("", item.example)
    }

    @Test
    fun `连续两个以上空格优先于逗号分列`() {
        val item = ok(1, "benefit    n. 利益    双空格分列的例句")
        assertEquals("benefit", item.word)
        assertEquals("n. 利益", item.meaning)
        assertEquals("双空格分列的例句", item.example)
    }

    @Test
    fun `逗号分列上限三列，释义内含逗号不被切碎`() {
        val item = ok(1, "effect,n. 效果；作用,This had no effect on me.")
        assertEquals("effect", item.word)
        assertEquals("n. 效果；作用", item.meaning)
        assertEquals("This had no effect on me.", item.example)
    }

    @Test
    fun `全角逗号同样可分列`() {
        val item = ok(1, "UK，英国")
        assertEquals("UK", item.word)
        assertEquals("英国", item.meaning)
    }

    @Test
    fun `单空格兜底只切第一刀，其余全归释义`() {
        val item = ok(1, "abandon v. 放弃；抛弃")
        assertEquals("abandon", item.word)
        assertEquals("v. 放弃；抛弃", item.meaning)
        assertEquals("", item.example)
    }

    @Test
    fun `词头里的重音字母归一为 ASCII`() {
        val item = ok(1, "café\tn. 咖啡馆")
        assertEquals("cafe", item.word)
    }

    @Test
    fun `只有词没有释义时进待修正而不是丢弃`() {
        val result = WordLineParser.parseLine(7, "lonely")
        assertTrue(result is LineResult.Bad)
        val bad = result as LineResult.Bad
        assertEquals(7, bad.rejected.sourceLine)
        assertEquals(RejectReason.TOO_FEW_COLUMNS, bad.rejected.reason)
        assertEquals("lonely", bad.rejected.raw)
    }

    @Test
    fun `制表符后为空也算缺释义`() {
        assertTrue(WordLineParser.parseLine(1, "apple\t") is LineResult.Bad)
    }

    @Test
    fun `空行返回 Blank 且不计入任何统计`() {
        assertEquals(LineResult.Blank, WordLineParser.parseLine(1, ""))
        assertEquals(LineResult.Blank, WordLineParser.parseLine(1, "   "))
    }

    @Test
    fun `parseAll 保留原行号，空行只占号不产项`() {
        val plan = WordLineParser.parseAll("apple\t苹果\n\n\nbanana\t香蕉")
        assertEquals(2, plan.items.size)
        assertEquals(listOf(1, 4), plan.items.map { it.sourceLine })
        assertEquals(2, plan.blankCount)
        assertEquals(0, plan.rejected.size)
    }

    @Test
    fun `parseAll 同时收可用行与待修正行`() {
        val plan = WordLineParser.parseAll("apple\t苹果\nbadline\ncherry\tn. 樱桃")
        assertEquals(2, plan.items.size)
        assertEquals(1, plan.rejected.size)
        assertEquals(2, plan.rejected[0].sourceLine)
        assertEquals(3, plan.totalMeaningful)
    }
}
```

- [ ] **Step 2: 提交测试并推送，确认 CI 变红**

```bash
git add app/src/test/java/com/studykit/util/importer/WordLineParserTest.kt
git commit -m "test(import): 单词行解析器的分列与容错红测"
git push origin feat/v2-visual-motion
gh run watch    # 期望：Unresolved reference 'WordLineParser'
```

- [ ] **Step 3: 写实现**

```kotlin
package com.studykit.util.importer

/**
 * 单词行解析（spec §5.1）：`word \t 释义 [\t 例句]`，并容忍手贴内容的其它分隔符。
 *
 * 分列优先级（自上而下，先命中先用）：
 * 1. 制表符；
 * 2. 连续两个及以上空格；
 * 3. 半角或全角逗号；
 * 4. 兜底：只切第一个空格 —— 因为 `abandon v. 放弃；抛弃` 里剩下的空格属于释义内容，
 *    按空格全切会把释义炸成三截。
 *
 * 1–3 一律 `limit = 3`：释义本身常含逗号（"n. 效果；作用"），切过头就把正确内容弄丢了。
 */
object WordLineParser {

    private val MULTI_SPACE = Regex(" {2,}")

    /** 整段文本 → 解析计划（可用项 + 待修正项 + 空行数） */
    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val fields = splitFields(raw)
        val word = fields.getOrElse(0) { "" }.trim()
        val meaning = fields.getOrElse(1) { "" }.trim()
        val example = fields.getOrElse(2) { "" }.trim()
        if (word.isEmpty() || meaning.isEmpty()) {
            return LineResult.Bad(
                RejectedLine(sourceLine = lineNo, raw = raw, reason = RejectReason.TOO_FEW_COLUMNS),
            )
        }
        return LineResult.Ok(
            ImportItem.Word(
                sourceLine = lineNo,
                raw = raw,
                word = TextCleaner.normalizeAccents(word),
                meaning = meaning,
                example = example,
            ),
        )
    }

    private fun splitFields(raw: String): List<String> {
        if (raw.contains('\t')) {
            val byTab = raw.split('\t', limit = 3).map { it.trim() }
            if (meaningful(byTab) >= 2) return byTab
        }
        val bySpacing = MULTI_SPACE.split(raw, limit = 3).map { it.trim() }
        if (meaningful(bySpacing) >= 2) return bySpacing
        val byComma = raw.split(',', '，', limit = 3).map { it.trim() }
        if (meaningful(byComma) >= 2) return byComma
        // 兜底：第一个空格前是词头，其余整体作释义
        val trimmed = raw.trim()
        val cut = trimmed.indexOf(' ')
        return if (cut <= 0) listOf(trimmed) else listOf(trimmed.substring(0, cut), trimmed.substring(cut + 1).trim())
    }

    private fun meaningful(fields: List<String>): Int = fields.count { it.isNotEmpty() }
}
```

- [ ] **Step 4: 提交实现并推送，确认 CI 转绿**

```bash
git add app/src/main/java/com/studykit/util/importer/WordLineParser.kt
git commit -m "feat(import): 单词行解析器（制表符/多空格/逗号/单空格四级分列，limit=3）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿，WordLineParserTest 12 例通过
```

---

### Task 4: 题目行解析器（TDD）

格式 `题干 | 选项A | 选项B … | 答案`。难点是**答案写法有两种**（字母 `B`，或选项原文），且可选的「解析」列会让"最后一个字段是答案"这个直觉失效。规则必须写成代码里那样可判定，不能靠实现者猜。

**Files:**
- Create: `app/src/main/java/com/studykit/util/importer/QuestionLineParser.kt`
- Test: `app/src/test/java/com/studykit/util/importer/QuestionLineParserTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `TextCleaner`、`LineResult`、`ImportItem.Question`、`RejectedLine`、`RejectReason`、`ImportPlan`、`toPlan()`。
- Produces: `object QuestionLineParser { fun parseAll(text: String): ImportPlan; fun parseLine(lineNo: Int, raw: String): LineResult }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionLineParserTest {

    private fun ok(raw: String): ImportItem.Question =
        (QuestionLineParser.parseLine(1, raw) as LineResult.Ok).item as ImportItem.Question

    @Test
    fun `四选项与字母答案`() {
        val item = ok("1/2 + 1/3 = ?|5/6|1/6|1|2/5|A")
        assertEquals("1/2 + 1/3 = ?", item.stem)
        assertEquals(listOf("5/6", "1/6", "1", "2/5"), item.options)
        assertEquals(0, item.answerIndex)
        assertEquals("", item.explanation)
    }

    @Test
    fun `答案写成选项原文也能定位下标`() {
        val item = ok("下面哪个是过去式？|went|go|goed|going|went")
        assertEquals(0, item.answerIndex)
    }

    @Test
    fun `多出一列时按「解析」处理，答案仍是倒数第二段可解析项`() {
        val item = ok("题干？|选项一|选项二|B|应该选第二项")
        assertEquals(1, item.answerIndex)
        assertEquals("应该选第二项", item.explanation)
    }

    @Test
    fun `小写与带空格的答案都能识别`() {
        assertEquals(2, ok("Q|a|b|c|d| c ").answerIndex)
    }

    @Test
    fun `答案越界进待修正`() {
        val result = QuestionLineParser.parseLine(3, "Q|a|b|E")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.ANSWER_NOT_FOUND, (result as LineResult.Bad).rejected.reason)
        assertEquals(3, result.rejected.sourceLine)
    }

    @Test
    fun `答案写成不存在的选项原文进待修正`() {
        assertTrue(
            QuestionLineParser.parseLine(1, "Q|a|b|z") is LineResult.Bad,
        )
    }

    @Test
    fun `少于两个选项时判为列数不足`() {
        val result = QuestionLineParser.parseLine(2, "只有题干|A")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.TOO_FEW_COLUMNS, (result as LineResult.Bad).rejected.reason)
    }

    @Test
    fun `空行返回 Blank`() {
        assertEquals(LineResult.Blank, QuestionLineParser.parseLine(1, "  "))
    }

    @Test
    fun `parseAll 汇总可用与待修正`() {
        val plan = QuestionLineParser.parseAll("Q|a|b|A\n坏行|只有一列")
        assertEquals(1, plan.items.size)
        assertEquals(1, plan.rejected.size)
        assertEquals(2, plan.rejected[0].sourceLine)
    }
}
```

- [ ] **Step 2: 提交测试并推送，确认 CI 变红**

```bash
git add app/src/test/java/com/studykit/util/importer/QuestionLineParserTest.kt
git commit -m "test(import): 题目行解析器红测（字母答案/原文答案/带解析/越界）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：Unresolved reference 'QuestionLineParser'
```

- [ ] **Step 3: 写实现**

```kotlin
package com.studykit.util.importer

/**
 * 题目行解析（spec §5.1）：`题干 | 选项A | 选项B … | 答案 [ | 解析 ]`。
 *
 * 答案写法允许两种：单个字母（`A`–`Z`，大小写均可，按下标定），或某条选项的原文（按内容匹配）。
 * 判定顺序刻意是「先试最后一个字段，再试倒数第二个 + 最后一个当解析」——因为带解析列时
 * 最后一个字段是中文说明，不是答案；而选项原文本身可能长得像字母（如选项就是 `A`），
 * 所以字母优先、原文兜底。两条都不成立就判 `ANSWER_NOT_FOUND` 进待修正区，不做猜测。
 */
object QuestionLineParser {

    private const val MIN_OPTIONS = 2

    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val fields = raw.split('|').map { it.trim() }
        // 至少：题干 + MIN_OPTIONS 个选项 + 答案
        if (fields.count { it.isNotEmpty() } < MIN_OPTIONS + 2) {
            return bad(lineNo, raw, RejectReason.TOO_FEW_COLUMNS)
        }
        val stem = fields.first()
        val tail = fields.drop(1)
        resolveAnswer(tail)?.let { (index, explanation) ->
            return LineResult.Ok(
                ImportItem.Question(
                    sourceLine = lineNo,
                    raw = raw,
                    stem = stem,
                    options = tail.dropLast(if (explanation.isEmpty()) 1 else 2),
                    answerIndex = index,
                    explanation = explanation,
                ),
            )
        }
        return bad(lineNo, raw, RejectReason.ANSWER_NOT_FOUND)
    }

    /** 返回「答案下标 + 解析文本」；解析文本在答案不是最后一个字段时非空 */
    private fun resolveAnswer(tail: List<String>): Pair<Int, String>? {
        val last = tail.last()
        byLetter(last, tail)?.let { return it to "" }
        byText(last, tail)?.let { return it to "" }
        if (tail.size >= MIN_OPTIONS + 2) {
            val secondLast = tail[tail.lastIndex - 1]
            byLetter(secondLast, tail)?.let { return it to last }
            byText(secondLast, tail)?.let { return it to last }
        }
        return null
    }

    private fun byLetter(token: String, tail: List<String>): Int? {
        val letter = token.trim().uppercase().singleOrNull() ?: return null
        if (!letter.isInRange()) return null
        val index = letter - 'A'
        val options = optionsOf(tail)
        return if (index in options.indices) index else null
    }

    private fun byText(token: String, tail: List<String>): Int? {
        val options = optionsOf(tail)
        return options.indexOfFirst { it == token }.takeIf { it >= 0 }
    }

    /** 候选选项集合：去掉最后一个字段（可能是答案或解析）与倒数第二个（可能是答案）后取较长者 */
    private fun optionsOf(tail: List<String>): List<String> = tail.dropLast(1)

    private fun Char.isInRange(): Boolean = this in 'A'..'Z'

    private fun bad(lineNo: Int, raw: String, reason: RejectReason): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = reason))
}
```

> **实现者注意（本任务的已知设计张力）**：上面 `resolveAnswer` 里 `optionsOf(tail)` 恒为 `tail.dropLast(1)`，即"最后一个字段永远先当答案候选"。当带解析列时（`…|B|解析文本`），`byLetter(last)` 与 `byText(last)` 都会失败，才会退到"倒数第二个是答案、最后一个是解析"。测试 `多出一列时按「解析」处理` 与 `答案写成不存在的选项原文进待修正`（`Q|a|b|z`：`z` 既不是越界字母也不是选项原文 → 但 `tail.size == 3`，不满足 `>= MIN_OPTIONS + 2 == 4`，因此不会误判成"带解析列"）就是为这条边界写的；若你改进了 `optionsOf`，这两条测试会立刻变红。

- [ ] **Step 4: 提交实现并推送，确认 CI 转绿**

```bash
git add app/src/main/java/com/studykit/util/importer/QuestionLineParser.kt
git commit -m "feat(import): 题目行解析器（字母/原文两种答案 + 可选解析列，越界进待修正）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿，QuestionLineParserTest 9 例通过
```

---

### Task 5: 笔记行解析器（TDD）

`书名 | 摘录 | 感想`，感想可缺。摘录是读书模块的主力录入痛点（一本《人类简史》要手打十几条）。

**Files:**
- Create: `app/src/main/java/com/studykit/util/importer/NoteLineParser.kt`
- Test: `app/src/test/java/com/studykit/util/importer/NoteLineParserTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `TextCleaner`、`LineResult`、`ImportItem.Excerpt`、`RejectedLine`、`RejectReason`、`ImportPlan`、`toPlan()`。
- Produces: `object NoteLineParser { fun parseAll(text: String): ImportPlan; fun parseLine(lineNo: Int, raw: String): LineResult }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLineParserTest {

    private fun ok(raw: String): ImportItem.Excerpt =
        (NoteLineParser.parseLine(1, raw) as LineResult.Ok).item as ImportItem.Excerpt

    @Test
    fun `三段齐全`() {
        val item = ok("人类简史|农业革命是史上最大的骗局|可对照《枪炮病菌与钢铁》")
        assertEquals("人类简史", item.book)
        assertEquals("农业革命是史上最大的骗局", item.excerpt)
        assertEquals("可对照《枪炮病菌与钢铁》", item.thought)
    }

    @Test
    fun `缺感想时为空串`() {
        assertEquals("", ok("人类简史|摘录内容").thought)
    }

    @Test
    fun `竖线出现在摘录里时只按第一个与最后一个切分`() {
        val item = ok("书名|摘录|内含|竖线|感想")
        assertEquals("书名", item.book)
        assertEquals("内含|竖线", item.excerpt)
        assertEquals("感想", item.thought)
    }

    @Test
    fun `只有书名判为列数不足`() {
        val result = NoteLineParser.parseLine(4, "只有书名")
        assertTrue(result is LineResult.Bad)
        assertEquals(RejectReason.TOO_FEW_COLUMNS, (result as LineResult.Bad).rejected.reason)
        assertEquals(4, result.rejected.sourceLine)
    }

    @Test
    fun `书名为空也判列数不足`() {
        assertTrue(NoteLineParser.parseLine(1, "|摘录|感想") is LineResult.Bad)
    }

    @Test
    fun `空行返回 Blank 且 parseAll 保留行号`() {
        assertEquals(LineResult.Blank, NoteLineParser.parseLine(1, ""))
        val plan = NoteLineParser.parseAll("a|b\n\nc|d")
        assertEquals(listOf(1, 3), plan.items.map { it.sourceLine })
        assertEquals(1, plan.blankCount)
    }
}
```

- [ ] **Step 2: 提交测试并推送，确认 CI 变红**

```bash
git add app/src/test/java/com/studykit/util/importer/NoteLineParserTest.kt
git commit -m "test(import): 笔记行解析器红测（三段/缺感想/内嵌竖线/书名空）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：Unresolved reference 'NoteLineParser'
```

- [ ] **Step 3: 写实现**

```kotlin
package com.studykit.util.importer

/**
 * 笔记行解析（spec §5.1）：`书名 | 摘录 | 感想`，感想可省。
 *
 * 分列刻意是「第一个竖线之前 = 书名，最后一个竖线之后 = 感想，中间整段 = 摘录」而不是
 * `split('|')`：摘录原文里出现竖线（表格、公式、分隔号）很常见，切碎会把内容弄脏。
 * 只有两段时最后一段是摘录、感想为空 —— 与「三段」用同一个 `drop/last` 组合表达。
 */
object NoteLineParser {

    private const val SEPARATOR = '|'

    fun parseAll(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseLine(lineNo = it.line, raw = it.text) }.toPlan()

    fun parseLine(lineNo: Int, raw: String): LineResult {
        if (raw.isBlank()) return LineResult.Blank
        val first = raw.indexOf(SEPARATOR)
        val last = raw.lastIndexOf(SEPARATOR)
        if (first <= 0) return bad(lineNo, raw)
        val book = raw.substring(0, first).trim()
        if (book.isEmpty()) return bad(lineNo, raw)
        // 只两段时（first == last）最后一段就是摘录、感想为空；三段时中间整段是摘录
        val excerpt = if (first == last) raw.substring(first + 1) else raw.substring(first + 1, last)
        val thought = if (first == last) "" else raw.substring(last + 1)
        if (excerpt.isBlank()) return bad(lineNo, raw)
        return LineResult.Ok(
            ImportItem.Excerpt(
                sourceLine = lineNo,
                raw = raw,
                book = book,
                excerpt = excerpt.trim(),
                thought = thought.trim(),
            ),
        )
    }

    private fun bad(lineNo: Int, raw: String): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = RejectReason.TOO_FEW_COLUMNS))
}
```

- [ ] **Step 4: 提交实现并推送，确认 CI 转绿**

```bash
git add app/src/main/java/com/studykit/util/importer/NoteLineParser.kt
git commit -m "feat(import): 笔记行解析器（首尾竖线定界，摘录内竖线不切碎）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿，NoteLineParserTest 6 例通过
```

---

### Task 6: 去重与批量入库（TDD）

导入 3000 个词时"跳过重复"是唯一不能出错的一环：`words.word` **没有唯一索引**（Task 1 已核实），SQLite 不会替我们挡，所以去重必须在应用侧做，且批内互重也要算。去重逻辑放纯函数（可测），实体构造与事务放 ViewModel/Repository（不可测，靠真机）。

**Files:**
- Create: `app/src/main/java/com/studykit/util/importer/Dedupe.kt`
- Modify: `app/src/main/java/com/studykit/data/repository/WordRepository.kt`
- Modify: `app/src/main/java/com/studykit/data/repository/QuestionRepository.kt`
- Test: `app/src/test/java/com/studykit/util/importer/DedupeTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `ImportItem`。
- Produces:
  - `data class Deduped<T>(val kept: List<T>, val skipped: List<String>)`
  - `fun normalizeKey(word: String): String`
  - `fun dedupeWords(items: List<ImportItem.Word>, existing: Set<String>): Deduped<ImportItem.Word>`
  - `fun dedupeStrings(keys: List<String>): Deduped<String>`（题目/笔记按内容去重用）
  - `WordRepository.addAll(words: List<Word>): Int`、`WordRepository.wordTexts(): List<String>`
  - `QuestionRepository.addAll(questions: List<Question>): Int`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupeTest {

    private fun word(line: Int, text: String) = ImportItem.Word(line, text, text, "释义")

    @Test
    fun `与库内已有词重复的跳过并回报被跳过的词面`() {
        val result = dedupeWords(listOf(word(1, "apple"), word(2, "banana")), setOf("apple"))
        assertEquals(listOf("banana"), result.kept.map { it.word })
        assertEquals(listOf("apple"), result.skipped)
    }

    @Test
    fun `批内互重只保留第一条`() {
        val result = dedupeWords(listOf(word(1, "apple"), word(2, "apple"), word(3, "cherry")), emptySet())
        assertEquals(listOf(1, 3), result.kept.map { it.sourceLine })
        assertEquals(listOf("apple"), result.skipped)
    }

    @Test
    fun `大小写与首尾空白视为同一个词`() {
        val result = dedupeWords(listOf(word(1, "Apple"), word(2, "  apple ")), setOf("APPLE"))
        assertTrue(result.kept.isEmpty())
        assertEquals(listOf("Apple", "  apple "), result.skipped)
    }

    @Test
    fun `库内为空时全部保留且保持原顺序`() {
        val items = listOf(word(1, "a"), word(2, "b"), word(3, "c"))
        assertEquals(items, dedupeWords(items, emptySet()).kept)
    }

    @Test
    fun `normalizeKey 折叠空白并转小写`() {
        assertEquals("abc", normalizeKey(" A B C "))
    }

    @Test
    fun `dedupeStrings 对任意键列表同样工作`() {
        val result = dedupeStrings(listOf("x", "y", "x", " z "))
        assertEquals(listOf("x", "y", "z"), result.kept)
        assertEquals(listOf("x"), result.skipped)
    }
}
```

- [ ] **Step 2: 提交测试并推送，确认 CI 变红**

```bash
git add app/src/test/java/com/studykit/util/importer/DedupeTest.kt
git commit -m "test(import): 去重纯函数红测（库内重复/批内互重/大小写空白）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：Unresolved reference 'dedupeWords'
```

- [ ] **Step 3: 写 `Dedupe.kt`**

```kotlin
package com.studykit.util.importer

/**
 * 导入去重。`words.word` 没有唯一索引（见计划 Task 1 的事实核对），SQLite 不会替我们挡重复，
 * 因此"跳过重复"必须在这里显式实现，并且**批内互重也要算** —— 一次贴进两份相同清单很常见。
 */

/** 归一化后的保留项 + 被跳过的原始键（回显给用户，让他知道少导了哪些） */
data class Deduped<T>(val kept: List<T>, val skipped: List<String>)

/** 词面归一：小写 + 去掉所有空白。`Apple`、`apple `、`APPLE` 视为同一个词 */
fun normalizeKey(word: String): String = word.filterNot { it.isWhitespace() }.lowercase()

fun dedupeWords(items: List<ImportItem.Word>, existing: Set<String>): Deduped<ImportItem.Word> {
    val seen = HashSet<String>(existing.size + items.size)
    existing.forEach { seen.add(normalizeKey(it)) }
    val kept = ArrayList<ImportItem.Word>(items.size)
    val skipped = ArrayList<String>()
    items.forEach { item ->
        if (seen.add(normalizeKey(item.word))) kept.add(item) else skipped.add(item.word)
    }
    return Deduped(kept = kept, skipped = skipped)
}

/** 题目/笔记没有天然唯一键，退化为「整条内容归一化后去重」 */
fun dedupeStrings(keys: List<String>): Deduped<String> {
    val seen = HashSet<String>(keys.size)
    val kept = ArrayList<String>(keys.size)
    val skipped = ArrayList<String>()
    keys.forEach { key ->
        if (seen.add(normalizeKey(key))) kept.add(key) else skipped.add(key)
    }
    return Deduped(kept = kept, skipped = skipped)
}
```

- [ ] **Step 4: 仓库加批量入口**

`WordRepository.kt` 追加（放在既有 `add(...)` 之后，风格一致）：

```kotlin
    /** 批量入库；返回实际写入条数。调用方负责去重（见 `util/importer/dedupeWords`） */
    suspend fun addAll(words: List<Word>): Int = wordDao.insertAll(words).size

    /** 全量词面，供导入前去重 */
    suspend fun wordTexts(): List<String> = wordDao.getWordTexts()
```

`QuestionRepository.kt` 追加：

```kotlin
    suspend fun addAll(questions: List<Question>): Int = questionDao.insertAll(questions).size
```

并在 `QuestionDao.kt` 加（与 Task 1 的 `WordDao.insertAll` 同形）：

```kotlin
    @Insert
    suspend fun insertAll(questions: List<Question>): List<Long>
```

- [ ] **Step 5: 提交实现并推送，确认 CI 转绿**

```bash
git add app/src/main/java/com/studykit/util/importer/Dedupe.kt app/src/main/java/com/studykit/data
git commit -m "feat(import): 导入去重纯函数与仓库批量入库入口"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿，DedupeTest 6 例通过
```

---

### Task 7: 统一预览-入库层（ViewModel + 预览页 + 结果页 + 批量粘贴 + SAF）

四个来源（粘贴、文件、词库、OCR）全部汇到这一个 ViewModel 与这两张屏。这样"逐行剔除 / 待修正区 / 结果统计"只实现一次，也保证词库导入（Task 10）与 OCR 取词（Task 13）不会各写一套。

**Files:**
- Create: `app/src/main/java/com/studykit/ui/bulkimport/ImportViewModel.kt`
- Create: `app/src/main/java/com/studykit/ui/bulkimport/ImportPreviewScreen.kt`
- Create: `app/src/main/java/com/studykit/ui/bulkimport/ImportResultScreen.kt`
- Create: `app/src/main/java/com/studykit/ui/bulkimport/BulkPasteScreen.kt`
- Modify: `app/src/main/java/com/studykit/ui/nav/AppNav.kt`
- Modify: `app/src/main/java/com/studykit/ui/study/WordListScreen.kt`
- Modify: `app/src/main/java/com/studykit/ui/study/StudyHomeScreen.kt`

**Interfaces:**
- Consumes: Task 2–6 的全部解析器与 `dedupeWords`；`WordRepository`/`QuestionRepository`/`WordRepository.add`；Task 1 的 `WordListRepository`；既有 `AppCard`/`AppButton`/`AppPill`/`AppMultilineTextField`/`EmptyState`/`StatTile`/`SectionHeader`、`AppTheme`、`MotionSpec`、`OneShotGate`。
- Produces:
  - `class ImportViewModel(application: Application) : AndroidViewModel(application)`
  - `data class ImportUiState(val plan: ImportPlan, val excluded: Set<Int>, val phase: ImportPhase, val outcome: ImportOutcome?)`
  - `enum class ImportPhase { PREVIEW, COMMITTING, DONE }`
  - `data class ImportOutcome(val inserted: Int, val skippedDuplicates: List<String>, val rejected: List<RejectedLine>)`
  - `fun ImportViewModel.loadPlan(plan: ImportPlan)`、`submitWords()`、`submitQuestions()`、`toggleExcluded(sourceLine: Int)`、`reset()`
  - 路由：`ImportRoutes.PASTE = "import/paste/{kind}"`（+ `fun paste(kind: String)`）、`ImportRoutes.PREVIEW = "import/preview"`、`ImportRoutes.RESULT = "import/result"`

- [ ] **Step 1: `ImportViewModel`**

```kotlin
package com.studykit.ui.bulkimport

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.Question
import com.studykit.data.entity.Word
import com.studykit.util.OneShotGate
import com.studykit.data.entity.Question
import com.studykit.util.importer.ImportItem
import com.studykit.util.importer.ImportOutcome
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.RejectedLine
import com.studykit.util.importer.dedupeStrings
import com.studykit.util.importer.dedupeWords
import org.json.JSONArray
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportPhase { PREVIEW, COMMITTING, DONE }

/** 预览态：解析计划 + 被用户手动剔除的行号 + 阶段 + 结果 */
data class ImportUiState(
    val plan: ImportPlan? = null,
    val excluded: Set<Int> = emptySet(),
    val phase: ImportPhase = ImportPhase.PREVIEW,
    val outcome: ImportOutcome? = null,
) {
    val visibleItems: List<ImportItem> get() = plan?.items?.filterNot { it.sourceLine in excluded } ?: emptyList()
    val canSubmit: Boolean get() = phase == ImportPhase.PREVIEW && visibleItems.isNotEmpty()
}

/**
 * 四个来源（批量粘贴 / SAF 文件 / 在线词库 / OCR 取词）共用的预览与入库。
 *
 * 去重放在这里而不是解析器里：解析器不认识数据库，也不该知道"重复"是相对谁的。
 * 实体构造（uuid / 时间戳）同样收在这里，让 `util/importer/` 保持零 Android 依赖。
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

    /** 批量入词：先与库内全量词面去重，再一次性事务写入 */
    fun submitWords(sourceListId: Long? = null, onDone: () -> Unit) {
        val current = _state.value
        if (!committing.tryEnter()) return
        val words = current.visibleItems.filterIsInstance<ImportItem.Word>()
        if (words.isEmpty()) {
            committing.leave()
            return
        }
        _state.value = current.copy(phase = ImportPhase.COMMITTING)
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val deduped = dedupeWords(words, wordRepository.wordTexts().toSet())
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
                        rejected = current.plan?.rejected ?: emptyList(),
                    )
                }
                _state.value = _state.value.copy(phase = ImportPhase.DONE, outcome = outcome)
                onDone()
            } finally {
                committing.leave()
            }
        }
    }

    /** 批量入题：题目没有天然唯一键，退化为「题干+选项」内容去重 */
    fun submitQuestions(onDone: () -> Unit) {
        val current = _state.value
        if (!committing.tryEnter()) return
        val items = current.visibleItems.filterIsInstance<ImportItem.Question>()
        if (items.isEmpty()) {
            committing.leave()
            return
        }
        _state.value = current.copy(phase = ImportPhase.COMMITTING)
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    // 去重键 = 题干 + 全部选项；`kept` 里是键，用它回指原条目（键含 \u0000 分隔，不会误撞）
                    fun keyOf(item: ImportItem.Question) = item.stem + '\u0000' + item.options.joinToString("\u0001")
                    val deduped = dedupeStrings(items.map(::keyOf))
                    val keep = deduped.kept.toSet()
                    val entities = items.filter { keyOf(it) in keep }.map { item ->
                        Question(
                            subject = "未分类",
                            stem = item.stem,
                            optionsJson = JSONArray(item.options).toString(),
                            answerIndex = item.answerIndex,
                            explanation = item.explanation,
                        )
                    }
                    ImportOutcome(
                        inserted = questionRepository.addAll(entities),
                        skippedDuplicates = deduped.skipped.map { it.substringBefore('\u0000') },
                        rejected = current.plan?.rejected ?: emptyList(),
                    )
                }
                _state.value = _state.value.copy(phase = ImportPhase.DONE, outcome = outcome)
                onDone()
            } finally {
                committing.leave()
            }
        }
    }

}
```

> `Question` 的字段名以 `data/entity/Question.kt` 实际声明为准（`subject / stem / optionsJson / answerIndex / explanation`，见事实核对）；`subject` 批量导入时固定「未分类」，与 `QuestionCreateScreen` 的默认学科一致，用户可在题目列表里改。

- [ ] **Step 2: 预览页与结果页**

`ui/bulkimport/ImportPreviewScreen.kt`：

```kotlin
package com.studykit.ui.bulkimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppCard
import com.studykit.ui.components.SectionHeader
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.ImportItem
import com.studykit.util.importer.RejectReason

/**
 * 导入预览：逐行可勾选剔除、待修正区只读展示。
 * 「先看清楚再入库」是这套流程存在的理由 —— 一次贴 300 行时，用户必须能扫一眼就知道有没有解析错。
 */
@Composable
fun ImportPreviewScreen(
    viewModel: ImportViewModel,
    kindLabel: String,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "预览$kindLabel", style = texts.pageTitle)
        }
        Spacer(Modifier.height(AppTheme.space.md))
        state.plan?.let { plan ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm),
            ) {
                StatTile(value = "${plan.items.size}", label = "可导入", modifier = Modifier.weight(1f))
                StatTile(value = "${plan.rejected.size}", label = "待修正", modifier = Modifier.weight(1f))
                StatTile(
                    value = "${plan.items.size - state.visibleItems.size}",
                    label = "已剔除",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            SectionHeader(title = "逐行确认")
            Spacer(Modifier.height(AppTheme.space.sm))
            plan.items.forEach { item ->
                val excluded = item.sourceLine in state.excluded
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleExcluded(item.sourceLine) },
                    ) {
                        Checkbox(
                            checked = !excluded,
                            onCheckedChange = { viewModel.toggleExcluded(item.sourceLine) },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accentInk),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.summary(),
                                // 被剔除的行用删除线表达，而不是变灰：灰字在深色主题下与禁用态撞车
                                style = texts.body.copy(
                                    textDecoration = if (excluded) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(text = "第 ${item.sourceLine} 行", style = texts.caption)
                        }
                    }
                }
                Spacer(Modifier.height(AppTheme.space.sm))
            }
            if (plan.rejected.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.md))
                SectionHeader(title = "待修正 ${plan.rejected.size} 行")
                Spacer(Modifier.height(AppTheme.space.sm))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    plan.rejected.forEach { rejected ->
                        Text(
                            text = "第 ${rejected.sourceLine} 行 · ${rejected.reason.label()} · ${rejected.raw}",
                            style = texts.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(
                text = "导入 ${state.visibleItems.size} 条",
                enabled = state.canSubmit,
                onClick = onSubmit,
            )
            Spacer(Modifier.height(AppTheme.space.lg))
        }
    }
}

/** 每个条目取一行人类可读的摘要 */
private fun ImportItem.summary(): String = when (this) {
    is ImportItem.Word -> "$word  $meaning"
    is ImportItem.Question -> "$stem（${options.size} 选项 · 答案 ${('A' + answerIndex)}）"
    is ImportItem.Excerpt -> "$book：$excerpt"
    is ImportItem.Mistake -> "$subject · $title"
}

/** 失败原因的人类可读说法；新增 RejectReason 分支时编译器会强制这里补齐 */
private fun RejectReason.label(): String = when (this) {
    RejectReason.EMPTY_FIELDS -> "内容为空"
    RejectReason.TOO_FEW_COLUMNS -> "列数不足"
    RejectReason.ANSWER_NOT_FOUND -> "答案无法识别"
}
```

> 预览页需要 `import androidx.compose.foundation.layout.IntrinsicSize`、`androidx.compose.ui.text.style.TextDecoration`；`StatTile` 自身已 `fillMaxHeight()`，所以这一行必须包在 `height(IntrinsicSize.Max)` 的 `Row` 里（M1 真机结论）。

`ui/bulkimport/ImportResultScreen.kt`（spec §5.5 的统一结果卡）：

```kotlin
package com.studykit.ui.bulkimport

// imports 与 ImportPreviewScreen 同一批：AppCard / AppButton / StatTile / ConfettiBurst、
// AppTheme、androidx.compose 基础件、kotlinx 的 rememberSaveable/mutableStateOf/getValue/setValue

/**
 * 导入结果：成功 N / 跳过 M / 待修正 K，可展开逐行原因（spec §5.5）。
 * 有成功条目时放一次彩带 —— 与 M1 的庆祝语言一致；trigger 用 outcome 的行数组合，
 * 保证「同一次结果不会因重组再放一次」（ConfettiBurst 首次组合即播放，见其 KDoc）。
 */
@Composable
fun ImportResultScreen(
    outcome: ImportOutcome,
    onDone: () -> Unit,
    onReviewRejected: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.space.pageH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(AppTheme.space.xl * 2))
            Text(text = "导入完成", style = texts.pageTitle)
            Spacer(Modifier.height(AppTheme.space.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                StatTile(value = "${outcome.inserted}", label = "成功导入", modifier = Modifier.weight(1f))
                StatTile(value = "${outcome.skippedDuplicates.size}", label = "重复跳过", modifier = Modifier.weight(1f))
                StatTile(value = "${outcome.rejected.size}", label = "待修正", modifier = Modifier.weight(1f))
            }
            if (outcome.inserted > 0) {
                Spacer(Modifier.height(AppTheme.space.lg))
                Text(text = "一次 ${outcome.inserted} 条，比手打快多了", style = texts.caption)
            }
            if (outcome.rejected.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.lg))
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                    ) {
                        Text(text = "待修正 ${outcome.rejected.size} 行", style = texts.cardTitle, modifier = Modifier.weight(1f))
                        Text(text = if (expanded) "收起" else "展开", style = texts.caption)
                    }
                    if (expanded) {
                        Spacer(Modifier.height(AppTheme.space.sm))
                        outcome.rejected.forEach { rejected ->
                            Text(
                                text = "第 ${rejected.sourceLine} 行 · ${rejected.reason.label()}",
                                style = texts.caption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(AppTheme.space.lg))
            AppButton(text = "好", onClick = onDone)
            if (outcome.rejected.isNotEmpty()) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppButton(text = "回去修那几行", secondary = true, onClick = onReviewRejected)
            }
            Spacer(Modifier.height(AppTheme.space.xl))
        }
        if (outcome.inserted > 0) {
            ConfettiBurst(
                trigger = outcome.inserted to outcome.rejected.size,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
```

- [ ] **Step 3: 批量粘贴页（含 SAF 文件入口）**

`ui/bulkimport/BulkPasteScreen.kt`：

```kotlin
package com.studykit.ui.bulkimport

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.studykit.ui.components.AppButton
import com.studykit.ui.components.AppMultilineTextField
import com.studykit.ui.components.EmptyState
import com.studykit.ui.components.StatTile
import com.studykit.ui.theme.AppTheme
import com.studykit.util.importer.ImportPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能录入页：一个大输入框，边打边解析。文件导入走同一个解析器，因此这里只多一个 SAF 按钮。
 *
 * 解析在 `Dispatchers.Default` 上跑并做 250ms 防抖：300 行的粘贴如果每敲一个字就重解析，
 * 输入框会明显掉帧（M1 的帧率承诺不允许这种回归）。
 */
@Composable
fun BulkPasteScreen(
    viewModel: ImportViewModel,
    kind: ImportKind,
    onBack: () -> Unit,
    onPreview: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var raw by rememberSaveable { mutableStateOf("") }
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var parsing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // 读文件与解析都不在组合期做：先切 IO 读文本，再切 Default 解析
        plan = null
        parsing = true
    }

    LaunchedEffect(raw) {
        if (raw.isBlank()) {
            plan = null; parsing = false; return@LaunchedEffect
        }
        delay(250)
        parsing = true
        plan = withContext(Dispatchers.Default) { kind.parse(raw) }
        parsing = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = kind.title, style = texts.pageTitle)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { openDocument.launch(arrayOf("text/*", "text/csv", "text/plain", "*/*")) }) {
                Text(text = "选文件", style = texts.aux.copy(color = colors.accentInk))
            }
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppMultilineTextField(
            value = raw,
            onValueChange = { raw = it },
            label = "一行一条",
            placeholder = kind.placeholder,
            minLines = 8,
        )
        Spacer(Modifier.height(AppTheme.space.md))
        when {
            parsing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(AppTheme.space.sm))
                Text(text = "解析中…", style = texts.caption)
            }
            raw.isBlank() -> EmptyState(title = "贴一行试试", caption = kind.hint, icon = kind.icon)
            else -> {
                plan?.let { current ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.sm)) {
                        StatTile(value = "${current.items.size}", label = "可导入", modifier = Modifier.weight(1f))
                        StatTile(value = "${current.rejected.size}", label = "待修正", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(AppTheme.space.lg))
                    AppButton(
                        text = "下一步 · 预览 ${current.items.size} 条",
                        enabled = current.items.isNotEmpty(),
                        onClick = {
                            viewModel.loadPlan(current)
                            onPreview()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.xl))
    }
}
```

`ImportKind` 是本页与词库/OCR 共用的"这一批是什么"枚举，放 `ui/bulkimport/ImportKind.kt`：

```kotlin
package com.studykit.ui.bulkimport

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import com.studykit.util.importer.ImportPlan
import com.studykit.util.importer.NoteLineParser
import com.studykit.util.importer.QuestionLineParser
import com.studykit.util.importer.WordLineParser

/** 三种批量格式的一种：决定标题、占位文案、用哪个解析器 */
enum class ImportKind(
    val title: String,
    val placeholder: String,
    val hint: String,
    val icon: ImageVector,
) {
    WORD(
        title = "批量录入单词",
        placeholder = "abandon\tv. 放弃\nbenefit\tn. 利益",
        hint = "Tab、逗号、两个空格都能分列；第三列可选作例句",
        icon = Icons.Outlined.Translate,
    ),
    QUESTION(
        title = "批量录入题目",
        placeholder = "1/2 + 1/3 = ?|5/6|1/6|1|2/5|A",
        hint = "竖线分列：题干 | 选项… | 答案（字母或选项原文）",
        icon = Icons.Outlined.CheckCircle,
    ),
    NOTE(
        title = "批量录入摘录",
        placeholder = "人类简史|农业革命是史上最大的骗局|可对照《枪炮、病菌与钢铁》",
        hint = "竖线分列：书名 | 摘录 | 感想（感想可省）",
        icon = Icons.Outlined.Article,
    ),
    ;

    /** 解析入口保持在这里，词库/OCR 复用同一份折叠逻辑 */
    fun parse(text: String): ImportPlan = when (this) {
        WORD -> WordLineParser.parseAll(text)
        QUESTION -> QuestionLineParser.parseAll(text)
        NOTE -> NoteLineParser.parseAll(text)
    }
}
```

- [ ] **Step 4: SAF 文件读取（与粘贴同一条路）**

在 `BulkPasteScreen` 的 `openDocument` 回调里补真正的读取（上面只置了 `parsing = true`，避免组合期做 IO）：

```kotlin
    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        plan = null
        parsing = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }.orEmpty()
                }.getOrDefault("")
            }
            if (text.isBlank()) {
                Toast.makeText(context, "这个文件是空的或读不出来", Toast.LENGTH_SHORT).show()
                parsing = false
            } else {
                raw = text          // 走同一个 LaunchedEffect 解析，避免两套代码
            }
        }
    }
```

并在函数体开头加 `val scope = rememberCoroutineScope()`。CSV 不额外解析：逗号已在 `WordLineParser` 的分列优先级里，UTF-8 BOM 由 `TextCleaner` 剥离（spec §5.1 的"BOM 容错"）。

- [ ] **Step 5: 路由与入口**

`AppNav.kt` 增常量与三张屏（转场沿用既有 `MotionSpec` 分支，无需改动转场代码）：

```kotlin
object ImportRoutes {
    const val PASTE = "import/paste/{kind}"
    const val PREVIEW = "import/preview"
    const val RESULT = "import/result"
    fun paste(kind: String) = "import/paste/$kind"
}
```

```kotlin
val importViewModel: ImportViewModel = viewModel()

composable(
    route = ImportRoutes.PASTE,
    arguments = listOf(navArgument("kind") { type = NavType.StringType }),
) { entry ->
    val kind = runCatching { ImportKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }
        .getOrDefault(ImportKind.WORD)
    BulkPasteScreen(
        viewModel = importViewModel,
        kind = kind,
        onBack = { navController.popBackStack() },
        onPreview = { navController.navigate(ImportRoutes.PREVIEW) { launchSingleTop = true } },
    )
}
composable(ImportRoutes.PREVIEW) {
    ImportPreviewScreen(
        viewModel = importViewModel,
        kindLabel = "结果",
        onBack = { navController.popBackStack() },
        onSubmit = {
            importViewModel.submitWords { navController.navigate(ImportRoutes.RESULT) { launchSingleTop = true } }
        },
    )
}
composable(ImportRoutes.RESULT) {
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    ImportResultScreen(
        outcome = importState.outcome ?: ImportOutcome(0, emptyList(), emptyList()),
        onDone = { importViewModel.reset(); navController.popBackStack(ImportRoutes.PASTE, inclusive = true) },
        onReviewRejected = { importViewModel.reset(); navController.popBackStack() },
    )
}
```

入口按钮：`WordListScreen` 工具栏加「批量导入」（放在标题行右侧，样式与习惯页「+ 添加」一致）：

```kotlin
TextButton(onClick = onBulkImport) {
    Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = colors.accentInk)
    Spacer(Modifier.width(AppTheme.space.xs))
    Text(text = "批量导入", style = texts.aux.copy(color = colors.accentInk, fontWeight = FontWeight.Medium))
}
```

`WordListScreen` 签名加 `onBulkImport: () -> Unit`，`AppNav` 里传 `{ navController.navigate(ImportRoutes.paste(ImportKind.WORD.name)) { launchSingleTop = true } }`。`StudyHomeScreen` 的「录入」`TextButton` 保持不变（单条录入仍是主路径）。

- [ ] **Step 6: 提交并推送，确认 CI 绿**

```bash
git add app/src/main/java/com/studykit/ui/bulkimport app/src/main/java/com/studykit/ui/nav/AppNav.kt app/src/main/java/com/studykit/ui/study/WordListScreen.kt
git commit -m "feat(import): 统一预览-入库层（批量粘贴 + SAF 文件 + 结果卡）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿（含 lint 无新告警）
```

- [ ] **Step 7: 真机走一遍（本任务的验收口径）**

```bash
cd "C:/Users/Administrator/Desktop/StudyKit-v2.0.0" && bash auto-install.sh <apk> 2.0.0
export PATH="/e/tools/adb:$PATH"
adb shell monkey -p com.studykit -c android.intent.category.LAUNCHER 1; sleep 5
# 单词库 → 批量导入 → 贴 3 行（含 1 行坏格式）→ 预览 → 导入 → 结果卡
adb shell input tap 540 1913   # 允许通知（首装会弹）
```
必须看到：预览页统计为「可导入 2 / 待修正 1」、被剔除行有删除线、结果页出现彩带、单词库条数 +2、再导同一批显示「重复跳过 2」。这几条是 Task 7 的验收，缺一条即未完成。

---

### Task 8: 词库 NDJSON 解析（TDD）

事实核对结论（2026-09-20 实测 `PEPXiaoXue3_2.zip`）：词表 zip 里只有一个 `<id>.json`，**内容是 JSON Lines**（一行一个对象，整文件不是合法 JSON）；每行形如

```json
{"wordRank":2,"headWord":"Canada","content":{"word":{"wordHead":"Canada","wordId":"PEPXiaoXue3_2_2","content":{"sentence":{"sentences":[{"sContent":"She was domiciliated in Canada.","sCn":"她在加拿大定居。"}]},"usphone":"'kænədə","ukphone":"'kænədə","phrase":{"phrases":[{"pContent":"air canada","pCn":"n. 加拿大航空公司"}]},"trans":[{"tranCn":"加拿大","descCn":"中释"}]}}},"bookId":"PEPXiaoXue3_2"}
```

抽样 72 行：`trans` 100% 有、`sentence` 约 5% 缺、`ukphone` 多数有。因此 `trans` 缺失才判失败，例句缺失只留空。

**依赖裁定（写进计划，不让实现者临场决定）**：本仓 `app/build.gradle.kts` 没有 `testOptions`，`org.json` 在 JVM 单测里会抛 `Stub!`（既有 `parseOptions` 因此一直没被测）。本任务**新增测试依赖** `testImplementation("org.json:json:20231013")`，让解析器可测；运行时仍用 Android 内置的 `org.json`，APK 零影响。若 CI 显示 stub 错误未被消除，改为把解析器签名收在 `JsonReader` 之外的抽象上（`fun parseLine(line: String, reader: (String) -> Map<String, Any?>?)`），由调用方注入实现 —— 两条路都要在报告里写明实际走了哪条。

**Files:**
- Create: `app/src/main/java/com/studykit/util/importer/DictBookParser.kt`
- Modify: `app/build.gradle.kts`（`testImplementation` 一行 + `gradle/libs.versions.toml` 的 `[libraries]`）
- Test: `app/src/test/java/com/studykit/util/importer/DictBookParserTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `ImportItem.Word`、`RejectedLine`、`RejectReason`、`LineResult`、`ImportPlan`、`toPlan()`、`TextCleaner`。
- Produces:
  - `object DictBookParser { fun parseNdjson(text: String): ImportPlan; fun parseEntry(line: String, lineNo: Int): LineResult }`
  - 词库目录条目 `data class DictBookInfo(val id: String, val title: String, val wordNum: Int, val sizeBytes: Int, val introduce: String, val tags: List<String>, val downloadUrl: String)` 与 `fun parseCatalogue(json: String): List<DictBookInfo>`（同一文件，Task 9/10 复用）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.studykit.util.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictBookParserTest {

    private val canadaLine = """
        {"wordRank":2,"headWord":"Canada","content":{"word":{"wordHead":"Canada","wordId":"PEPXiaoXue3_2_2","content":{"sentence":{"sentences":[{"sContent":"She was domiciliated in Canada.","sCn":"她在加拿大定居。"}]},"usphone":"'kænədə","ukphone":"'kænədə","phrase":{"phrases":[{"pContent":"air canada","pCn":"n. 加拿大航空公司"}]},"trans":[{"tranCn":"加拿大","descCn":"中释"}]}}},"bookId":"PEPXiaoXue3_2"}
    """.trimIndent()

    @Test
    fun `一条 NDJSON 解析出词与释义与例句`() {
        val item = (DictBookParser.parseEntry(canadaLine, 1) as LineResult.Ok).item as ImportItem.Word
        assertEquals("Canada", item.word)
        assertEquals("加拿大", item.meaning)
        assertEquals("She was domiciliated in Canada.", item.example)
    }

    @Test
    fun `多个词性的释义用分号拼接`() {
        val line = """{"headWord":"book","content":{"word":{"content":{"trans":[{"tranCn":"n. 书"},{"tranCn":"v. 预订"}]}}}}"""
        val item = (DictBookParser.parseEntry(line, 1) as LineResult.Ok).item as ImportItem.Word
        assertEquals("n. 书；v. 预订", item.meaning)
    }

    @Test
    fun `缺例句时例句为空串而不是失败`() {
        val line = """{"headWord":"UK","content":{"word":{"content":{"trans":[{"tranCn":"英国"}]}}}}"""
        val item = (DictBookParser.parseEntry(line, 3) as LineResult.Ok).item as ImportItem.Word
        assertEquals("", item.example)
    }

    @Test
    fun `缺释义进待修正而不是丢弃`() {
        val line = """{"headWord":"weird","content":{"word":{"content":{}}}}"""
        val result = DictBookParser.parseEntry(line, 9)
        assertTrue(result is LineResult.Bad)
        assertEquals(9, (result as LineResult.Bad).rejected.sourceLine)
        assertEquals(RejectReason.EMPTY_FIELDS, result.rejected.reason)
    }

    @Test
    fun `残缺或非法 JSON 行进待修正`() {
        assertTrue(DictBookParser.parseEntry("{不是 json", 2) is LineResult.Bad)
    }

    @Test
    fun `parseNdjson 逐行编号并汇总`() {
        val plan = DictBookParser.parseNdjson("$canadaLine\n\n$canadaLine")
        assertEquals(2, plan.items.size)
        assertEquals(listOf(1, 3), plan.items.map { it.sourceLine })
        assertEquals(1, plan.blankCount)
    }

    @Test
    fun `目录 JSON 解析出词库条目与下载地址`() {
        val json = """
            {"reason":"succ","code":200,"data":{"normalBooksInfo":[
              {"id":"CET4luan_1","title":"四级真题核心词","wordNum":1162,"size":788457,
               "introduce":"有道词频统计","cover":"https://x/y.jpg",
               "tags":[{"tagName":"四级"},{"tagName":"有道"}],
               "offlinedata":"http://ydschool-online.nos.netease.com/1_CET4luan_1.zip"}
            ]}}
        """.trimIndent()
        val books = DictBookParser.parseCatalogue(json)
        assertEquals(1, books.size)
        val book = books[0]
        assertEquals("CET4luan_1", book.id)
        assertEquals("四级真题核心词", book.title)
        assertEquals(1162, book.wordNum)
        assertEquals(listOf("四级", "有道"), book.tags)
        assertEquals("http://ydschool-online.nos.netease.com/1_CET4luan_1.zip", book.downloadUrl)
    }

    @Test
    fun `目录里缺 offlinedata 的条目被丢弃而非崩溃`() {
        val json = """{"data":{"normalBooksInfo":[{"id":"x","title":"y"},{"id":"a","title":"b","offlinedata":"http://h/a.zip"}]}}"""
        val books = DictBookParser.parseCatalogue(json)
        assertEquals(listOf("a"), books.map { it.id })
    }
}
```

- [ ] **Step 2: 加测试依赖并提交测试，确认 CI 变红**

`gradle/libs.versions.toml`：

```toml
[versions]
json = "20231013"

[libraries]
json = { group = "org.json", name = "json", version.ref = "json" }
```

`app/build.gradle.kts` 的 `dependencies` 里 `testImplementation(libs.junit)` 之后：

```kotlin
    // 仅测试期：让 org.json 在 JVM 单测里可用（运行时仍用 Android 内置实现，APK 零影响）
    testImplementation(libs.json)
```

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/test/java/com/studykit/util/importer/DictBookParserTest.kt
git commit -m "test(dict): 词库 NDJSON 与目录解析红测，加 org.json 测试期依赖"
git push origin feat/v2-visual-motion
gh run watch    # 期望：Unresolved reference 'DictBookParser'（不是 Stub! 报错）
```

> 若这次红的形态是 `java.lang.RuntimeException: Stub!`，说明测试依赖没生效 —— 先修依赖（检查 `testImplementation(libs.json)` 是否被 Gradle 目录解析到），再进 Step 3。这条判据本身就是本任务的验证点之一。

- [ ] **Step 3: 写实现**

```kotlin
package com.studykit.util.importer

import org.json.JSONArray
import org.json.JSONObject

/** 在线词库（kajweb/dict）的一条书目信息 */
data class DictBookInfo(
    val id: String,
    val title: String,
    val wordNum: Int,
    val sizeBytes: Int,
    val introduce: String,
    val tags: List<String>,
    val downloadUrl: String,
)

/**
 * 词库数据解析。两件事：
 * 1. 目录 JSON（`bookLists.txt`）→ `List<DictBookInfo>`，缺 `offlinedata` 的条目直接丢弃 ——
 *    没有下载地址的书目在界面上只能显示成"点不动的行"，不如不出现；
 * 2. 词表 NDJSON → `ImportPlan`。**词表文件整体不是合法 JSON**，是一行一个对象，
 *    所以只能逐行 `JSONObject(line)`，任何一行坏了只影响它自己（进待修正区）。
 *
 * 字段路径来自 2026-09-20 对 `PEPXiaoXue3_2.zip` 的实测抽样（72 行，trans 100% 有、
 * sentence 约 95% 有），不是照文档猜的。
 */
object DictBookParser {

    fun parseCatalogue(json: String): List<DictBookInfo> {
        val books = JSONArray(
            JSONObject(json).optJSONObject("data")?.optJSONArray("normalBooksInfo") ?: JSONArray(),
        )
        return buildList {
            for (i in 0 until books.length()) {
                val book = books.optJSONObject(i) ?: continue
                val id = book.optString("id")
                val url = book.optString("offlinedata")
                if (id.isEmpty() || url.isEmpty()) continue
                val tags = book.optJSONArray("tags")?.let { array ->
                    buildList {
                        for (t in 0 until array.length()) {
                            array.optJSONObject(t)?.optString("tagName")?.takeIf { it.isNotEmpty() }?.let { add(it) }
                        }
                    }
                } ?: emptyList()
                add(
                    DictBookInfo(
                        id = id,
                        title = book.optString("title").ifEmpty { id },
                        wordNum = book.optInt("wordNum"),
                        sizeBytes = book.optInt("size"),
                        introduce = book.optString("introduce"),
                        tags = tags,
                        downloadUrl = url,
                    ),
                )
            }
        }
    }

    /** 整份 NDJSON → 解析计划（与四个行解析器共用 ImportPlan 形态，下游无需分支） */
    fun parseNdjson(text: String): ImportPlan =
        TextCleaner.splitLines(text).map { parseEntry(line = it.text, lineNo = it.line) }.toPlan()

    fun parseEntry(line: String, lineNo: Int): LineResult {
        if (line.isBlank()) return LineResult.Blank
        val root = runCatching { JSONObject(line) }.getOrNull()
            ?: return bad(lineNo, line, RejectReason.EMPTY_FIELDS)
        val inner = root.optJSONObject("content")?.optJSONObject("word")?.optJSONObject("content")
        val headWord = root.optString("headWord").ifEmpty { inner?.optJSONObject("word")?.optString("wordHead").orEmpty() }
        val trans = inner?.optJSONArray("trans")
        val meanings = buildList {
            trans?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("tranCn")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
        if (headWord.isBlank() || meanings.isEmpty()) {
            return bad(lineNo, line, RejectReason.EMPTY_FIELDS)
        }
        return LineResult.Ok(
            ImportItem.Word(
                sourceLine = lineNo,
                raw = headWord,
                word = TextCleaner.normalizeAccents(headWord),
                meaning = meanings.joinToString("；"),
                example = firstSentence(inner),
            ),
        )
    }

    private fun firstSentence(inner: JSONObject?): String =
        inner?.optJSONObject("sentence")?.optJSONArray("sentences")
            ?.optJSONObject(0)?.optString("sContent").orEmpty().trim()

    private fun bad(lineNo: Int, raw: String, reason: RejectReason): LineResult.Bad =
        LineResult.Bad(RejectedLine(sourceLine = lineNo, raw = raw, reason = reason))
}
```

- [ ] **Step 4: 提交实现并推送，确认 CI 转绿**

```bash
git add app/src/main/java/com/studykit/util/importer/DictBookParser.kt
git commit -m "feat(dict): 词库目录与 NDJSON 逐行解析（实测字段路径，缺释义进待修正）"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿，DictBookParserTest 8 例通过、无 Stub! 报错
```

---

### Task 9: 词库网络层（`HttpURLConnection` + zip 解包 + 离线目录快照）

零新依赖（spec §5.2）：`HttpURLConnection` + `java.util.zip.ZipInputStream`。网络与解压都是 IO，一律 `Dispatchers.IO`，且**不进组合期**。

**Files:**
- Create: `app/src/main/java/com/studykit/data/remote/DictRemote.kt`
- Create: `app/src/main/assets/dict/booklists.json`（Task 8 已验证可抓的快照）
- Modify: `app/src/main/AndroidManifest.xml`（`INTERNET`）
- Modify: `app/src/main/baseline-prof.txt`（新热路径）

**Interfaces:**
- Consumes: Task 8 的 `DictBookParser.parseCatalogue` / `parseNdjson`、`DictBookInfo`。
- Produces:
  - `class DictRemote(context: Context)`，方法 `suspend fun loadCatalogue(): List<DictBookInfo>`（先在线刷新，失败回落 assets 快照）、`suspend fun downloadBook(book: DictBookInfo, onProgress: (Float) -> Unit): ImportPlan`、`class DictRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)`。

- [ ] **Step 1: 加 `INTERNET` 权限**

`AndroidManifest.xml` 在 `READ_CALENDAR` 之后：

```xml
    <!-- 在线词库：下载书目目录与词表 zip（spec §5.2，零新依赖，用 HttpURLConnection） -->
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: 落离线目录快照**

```bash
mkdir -p app/src/main/assets/dict
curl -s --ssl-no-revoke -o app/src/main/assets/dict/booklists.json \
  https://raw.githubusercontent.com/kajweb/dict/master/bookLists.txt
wc -c app/src/main/assets/dict/booklists.json    # 期望 ≈68KB，且首字节是 { 而非错误页
head -c 60 app/src/main/assets/dict/booklists.json
```

- [ ] **Step 3: 写 `DictRemote`**

```kotlin
package com.studykit.data.remote

import android.content.Context
import com.studykit.util.importer.DictBookInfo
import com.studykit.util.importer.DictBookParser
import com.studykit.util.importer.ImportPlan
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 词库拉取失败（网络、格式、zip 结构）统一成一种异常，界面只需展示可重试空态 */
class DictRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 在线词库网络层。刻意零新依赖（spec §5.2）：`HttpURLConnection` + `ZipInputStream`。
 *
 * 三条从实测得到的事实决定了这段形状：
 * 1. 目录里 `offlinedata` 已经是 zip 的**绝对地址**（网易 OSS），不需要自己拼 GitHub raw 路径；
 * 2. 词表 zip 里只有一个 `<id>.json`，且内容是 NDJSON —— 所以解压时取"第一个 .json 条目"即可；
 * 3. 目录 68KB、词表 zip 40KB–800KB，都在手机网络可接受范围内，但必须给进度回调，
 *    否则用户在 800KB 那本上会以为点了没反应。
 *
 * 目录失败时回落 assets 里的快照：词库商店"至少能打开"比"永远转圈"重要。
 */
class DictRemote(private val context: Context) {

    suspend fun loadCatalogue(): List<DictBookInfo> = withContext(Dispatchers.IO) {
        val online = runCatching { readText(CATALOGUE_URL, timeoutMillis = 15_000) }
            .getOrNull()
        val source = online ?: runCatching {
            context.assets.open(CATALOGUE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: throw DictRemoteException("目录获取失败且无本地快照")
        DictBookParser.parseCatalogue(source)
    }

    /** 下载整本词表并解析；`onProgress` 的取值区间是 0f..1f，未知总长时恒为 0f */
    suspend fun downloadBook(book: DictBookInfo, onProgress: (Float) -> Unit): ImportPlan =
        withContext(Dispatchers.IO) {
            val connection = open(book.downloadUrl)
            try {
                val total = connection.contentLength.takeIf { it > 0 } ?: 0
                val json = connection.inputStream.buffered().use { stream ->
                    val bytes = java.io.ByteArrayOutputStream().also { sink ->
                        val buffer = ByteArray(8 * 1024)
                        var read = 0
                        var done = 0L
                        while (stream.read(buffer).also { read = it } >= 0) {
                            sink.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }.toByteArray()
                    extractFirstJson(bytes)
                }
                DictBookParser.parseNdjson(json)
            } catch (error: Exception) {
                throw DictRemoteException("词表下载失败：${book.title}", error)
            } finally {
                connection.disconnect()
            }
        }

    /** 取 zip 里第一个 .json 条目（实测每本词表恰好一个）；`generateSequence` 保证 nextEntry 只推进一次 */
    private fun extractFirstJson(zipBytes: ByteArray): String {
        ByteArrayInputStream(zipBytes).use { bytes ->
            ZipInputStream(BufferedInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name.endsWith(".json", ignoreCase = true) }
                    ?.let { return zip.reader(Charsets.UTF_8).readText() }
            }
        }
        throw DictRemoteException("压缩包里没有找到词表 JSON")
    }

    private fun readText(url: String, timeoutMillis: Int): String =
        open(url).inputStream.buffered().use { it.reader(Charsets.UTF_8).readText() }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMillis
        readTimeout = timeoutMillis
        instanceFollowRedirects = true
        requestProperties = mapOf("User-Agent" to "StudyKit")
        if (responseCode !in 200..299) {
            disconnect()
            throw DictRemoteException("HTTP $responseCode")
        }
    }

    private companion object {
        const val CATALOGUE_URL = "https://raw.githubusercontent.com/kajweb/dict/master/bookLists.txt"
        const val CATALOGUE_ASSET = "dict/booklists.json"
    }
}
```

> 两处刻意的写法约束：① `ZipInputStream.nextEntry` **只能推进一次**，所以用 `generateSequence { zip.nextEntry }` 而不是 `while (zip.nextEntry != null)`；② 从 `use` 里 `return` 是安全的（`finally` 照常关流），但**不能**在 `use` 外再读一次流。`java.io.ByteArrayInputStream` 需顶部 import。

- [ ] **Step 4: 提交并推送，确认 CI 绿（含 release job）**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/assets app/src/main/java/com/studykit/data/remote
git commit -m "feat(dict): HttpURLConnection 词库网络层与 zip 解包，附离线目录快照"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿；release job 必须过（assets 在 shrinkResources 下要保留）
```

- [ ] **Step 5: 真机验证下载链路（CI 无法覆盖）**

手机网络此前经验证不通外网时，用记忆里现成的反向共享：`C:\Users\Administrator\Desktop\项目\tools\gnirehtet\gnirehtet-rust-win64\gnirehtet.exe run 35152127910030J -d 119.29.29.29`（后台跑）。判据：词库商店能列出书目；断网时商店显示可重试空态**而不是崩溃**，且能回落到内置快照。

---

### Task 10: 词库商店界面与整表删除

**Files:**
- Create: `app/src/main/java/com/studykit/ui/study/DictStoreViewModel.kt`
- Create: `app/src/main/java/com/studykit/ui/study/DictStoreScreen.kt`
- Modify: `app/src/main/java/com/studykit/ui/nav/AppNav.kt`
- Modify: `app/src/main/java/com/studykit/ui/study/WordListScreen.kt`

**Interfaces:**
- Consumes: Task 9 的 `DictRemote`、Task 8 的 `DictBookInfo`、Task 1 的 `WordListRepository`（`add` / `getBySourceId` / `markImportedCount` / `deleteAlongWithWords` / `observeAll`）、Task 7 的 `ImportViewModel`（预览复用）。
- Produces:
  - `class DictStoreViewModel(application: Application) : AndroidViewModel(application)`，`val state: StateFlow<DictStoreUiState>`、`fun refresh()`、`fun setQuery(query: String)`、`fun importBook(book: DictBookInfo, importViewModel: ImportViewModel, onReady: () -> Unit)`、`fun deleteList(list: WordList, onDone: (Int) -> Unit)`
  - `data class DictStoreUiState(val phase: DictStorePhase, val books: List<DictBookInfo>, val imported: List<WordList>, val progress: Map<String, Float>, val query: String, val error: String?)`
  - `enum class DictStorePhase { LOADING, READY, FAILED }`
  - 路由 `DictRoutes.STORE = "study/dict/store"`

- [ ] **Step 1: ViewModel**

```kotlin
package com.studykit.ui.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studykit.StudyKitApp
import com.studykit.data.entity.WordList
import com.studykit.data.remote.DictRemote
import com.studykit.data.remote.DictRemoteException
import com.studykit.ui.bulkimport.ImportViewModel
import com.studykit.util.importer.DictBookInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DictStorePhase { LOADING, READY, FAILED }

data class DictStoreUiState(
    val phase: DictStorePhase = DictStorePhase.LOADING,
    val books: List<DictBookInfo> = emptyList(),
    val imported: List<WordList> = emptyList(),
    /** 词库 id → 下载进度（0f..1f）；没有键表示未在下载 */
    val progress: Map<String, Float> = emptyMap(),
    val query: String = "",
    val error: String? = null,
) {
    /** 搜索命中书名或任一标签；空查询返回全部 */
    val visibleBooks: List<DictBookInfo>
        get() = if (query.isBlank()) books else books.filter {
            it.title.contains(query, ignoreCase = true) || it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }
    val availableBooks: List<DictBookInfo> get() = visibleBooks.filterNot { book -> imported.any { it.sourceId == book.id } }
}

/**
 * 词库商店。三态（加载中 / 就绪 / 失败可重试）+ 每本独立进度，下载完成后把解析计划
 * 交给 [ImportViewModel] 走统一的预览-入库流程 —— 商店自己**不直接写 words 表**，
 * 这样"3000 个词里有一行脏数据"和"重复词"的处理只有一份实现。
 */
class DictStoreViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StudyKitApp).container
    private val remote = DictRemote(application)
    private val wordListRepository = container.wordListRepository

    private val _books = MutableStateFlow(DictStoreUiState())
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _query = MutableStateFlow("")

    private val importedFlow = wordListRepository.observeAll()

    val state: StateFlow<DictStoreUiState> = combine(_books, importedFlow, _progress, _query) { base, imported, progress, query ->
        base.copy(imported = imported, progress = progress, query = query)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DictStoreUiState())

    fun setQuery(query: String) {
        _query.value = query
    }

    fun refresh() {
        _books.value = _books.value.copy(phase = DictStorePhase.LOADING, error = null)
        viewModelScope.launch {
            runCatching { remote.loadCatalogue() }
                .onSuccess { list ->
                    _books.value = _books.value.copy(phase = DictStorePhase.READY, books = list, error = null)
                }
                .onFailure { error ->
                    _books.value = _books.value.copy(
                        phase = DictStorePhase.FAILED,
                        error = if (error is DictRemoteException) error.message else "网络异常，稍后再试",
                    )
                }
        }
    }

    /** 下载 + 解析；完成后由界面导航到预览页。方法名不能叫 `import`（Kotlin 关键字） */
    fun importBook(book: DictBookInfo, importViewModel: ImportViewModel, onReady: () -> Unit) {
        if (_progress.value.containsKey(book.id)) return
        _progress.value = _progress.value + (book.id to 0f)
        viewModelScope.launch {
            try {
                val plan = remote.downloadBook(book) { ratio ->
                    _progress.value = _progress.value + (book.id to ratio)
                }
                importViewModel.loadPlan(plan)
                onReady()
            } catch (error: Exception) {
                _books.value = _books.value.copy(error = "《${book.title}》下载失败，可重试")
            } finally {
                _progress.value = _progress.value - book.id
            }
        }
    }

    /** 整表撤销：删词库记录 + 它带进来的所有单词，回条数给界面提示 */
    fun deleteList(list: WordList, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { wordListRepository.deleteAlongWithWords(list) }
            onDone(removed)
        }
    }

}
```

> 需要 `import kotlinx.coroutines.flow.flowOn`。`DictRemote` 内部已经 `withContext(Dispatchers.IO)`，ViewModel 不再重复切线程。

- [ ] **Step 2: 商店界面**

`DictStoreScreen.kt` 的骨架（完整实现按此写，样式全部走既有组件）：

```kotlin
@Composable
fun DictStoreScreen(
    viewModel: DictStoreViewModel,
    importViewModel: ImportViewModel,
    onBack: () -> Unit,
    onPreview: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppTheme.space.pageH),
    ) {
        Spacer(Modifier.height(AppTheme.space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.accentInk,
                )
            }
            Spacer(Modifier.width(AppTheme.space.xs))
            Text(text = "词库商店", style = texts.pageTitle)
        }
        Spacer(Modifier.height(AppTheme.space.md))
        AppTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "搜书名或标签，如 四级 / 考研 / 新东方",
        )
        Spacer(Modifier.height(AppTheme.space.md))
        when (state.phase) {
            DictStorePhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            DictStorePhase.FAILED -> {
                Spacer(Modifier.height(AppTheme.space.xl))
                EmptyState(title = "词库列表没拉到", caption = state.error ?: "检查网络后重试")
                Spacer(Modifier.height(AppTheme.space.lg))
                AppButton(text = "重试", onClick = viewModel::refresh)
            }
            DictStorePhase.READY -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.space.md),
            ) {
                if (state.imported.isNotEmpty()) {
                    item(key = "imported_header") { SectionHeader(title = "已导入（可整本撤销）") }
                    items(state.imported, key = { "list_${it.id}" }) { list ->
                        ImportedListRow(
                            list = list,
                            onDelete = { viewModel.deleteList(list) { removed -> /* Toast 已撤销 removed 个单词 */ } },
                        )
                    }
                }
                items(state.availableBooks, key = { it.id }) { book ->
                    DictBookRow(
                        book = book,
                        progress = state.progress[book.id],
                        onImport = { viewModel.importBook(book, importViewModel, onPreview) },
                    )
                }
                item(key = "attribution") { AttributionCard() }
                item { Spacer(Modifier.height(AppTheme.space.md)) }
            }
        }
    }
}
```

`DictStoreScreen.kt` 的三个子组件（同一文件内，样式全走既有组件与令牌）：

> 本文件需要 `androidx.compose.foundation.lazy.LazyColumn` 与 `androidx.compose.foundation.lazy.items`、`androidx.compose.material3.AlertDialog`、`androidx.compose.material3.LinearProgressIndicator`、`androidx.compose.material3.TextButton`、`androidx.compose.ui.graphics.StrokeCap`、`androidx.compose.ui.text.font.FontWeight`、`androidx.compose.runtime.saveable.rememberSaveable`、`androidx.lifecycle.compose.collectAsStateWithLifecycle`，组件侧 `AppPill` / `AppCard` / `AppButton` / `AppTextField` / `EmptyState` / `SectionHeader` / `StatTile`，类型侧 `DictBookInfo` 与 `WordList`。

```kotlin
/** 一本可导入的词库：书名 + 词数 + 标签药丸 + 下载进度 + 导入按钮 */
@Composable
private fun DictBookRow(
    book: DictBookInfo,
    progress: Float?,
    onImport: () -> Unit,
) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = book.title, style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        val sizeMb = book.sizeBytes / 1048576.0
        Text(
            text = "${book.wordNum} 词 · 下载约 ${"%.1f".format(sizeMb)} MB",
            style = texts.caption,
        )
        if (book.tags.isNotEmpty()) {
            Spacer(Modifier.height(AppTheme.space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.space.xs)) {
                book.tags.take(3).forEach { tag ->
                    AppPill(container = colors.accentSoft, ink = colors.accentInk, label = tag)
                }
            }
        }
        Spacer(Modifier.height(AppTheme.space.md))
        if (progress != null) {
            // 下载中：进度条替代按钮，避免重复点击（ViewModel 侧也有同 id 的并发闸门）
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = colors.accent,
                trackColor = colors.divider,
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(AppTheme.space.xs))
            Text(text = "下载中 ${((progress * 100).toInt())}%", style = texts.caption)
        } else {
            AppButton(text = "导入这本", secondary = true, onClick = onImport)
        }
    }
}
```

```kotlin
/** 已导入的一本：书名 + 实际导入数 / 声称数 + 整本撤销（二次确认） */
@Composable
private fun ImportedListRow(list: WordList, onDelete: () -> Unit) {
    val colors = AppTheme.colors
    val texts = AppTheme.texts
    var confirm by rememberSaveable(list.id) { mutableStateOf(false) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = list.title, style = texts.cardTitle)
                Spacer(Modifier.height(AppTheme.space.xs))
                Text(
                    text = "共 ${list.wordNum} 词 · 已导入 ${list.importedCount}",
                    style = texts.caption,
                )
            }
            TextButton(onClick = { confirm = true }) {
                Text(
                    text = "撤销",
                    style = texts.aux.copy(color = colors.warningInk, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(text = "撤销《${list.title}》？") },
            text = { Text(text = "会删掉这本词库带进来的 ${list.importedCount} 个单词，手工录入的不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onDelete()
                    },
                ) { Text(text = "撤销", color = colors.warningInk) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(text = "留着", color = colors.accentInk) }
            },
            containerColor = AppTheme.colors.card,
        )
    }
}

/** 数据来源与许可（spec §7 明确要求，不可省略） */
@Composable
private fun AttributionCard() {
    val texts = AppTheme.texts
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "数据来源", style = texts.cardTitle)
        Spacer(Modifier.height(AppTheme.space.xs))
        Text(
            text = "词表来自开源仓库 kajweb/dict（抓取自公开背词应用），仅用于个人学习；" +
                "导入后完全离线，App 不会上传你的任何数据。",
            style = texts.caption,
        )
    }
}
```

- [ ] **Step 3: 路由与入口**

`AppNav.kt` 加 `object DictRoutes { const val STORE = "study/dict/store" }` 与 `composable(DictRoutes.STORE)`；`WordListScreen` 工具栏再加一个「词库」按钮（与 Task 7 的「批量导入」并排，样式相同），签名加 `onOpenDict: () -> Unit`。

- [ ] **Step 4: 提交并推送，确认 CI 绿**

```bash
git add app/src/main/java/com/studykit/ui/study app/src/main/java/com/studykit/ui/nav/AppNav.kt
git commit -m "feat(dict): 词库商店（搜索/进度/整本撤销）与数据来源说明"
git push origin feat/v2-visual-motion
gh run watch
```

- [ ] **Step 5: 真机验证**

导入一本小学词（40KB，72 词）：预览页应显示「可导入 ~70 / 待修正 0」，导入后单词库条数增加对应数量；再进商店，该本出现在「已导入」；点「撤销」后条数回到原值（这是 `source_list_id` + `deleteAlongWithWords` 的唯一验收方式）。

---

### Task 11: 截图分享进错题

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/studykit/util/ShareIntake.kt`
- Modify: `app/src/main/java/com/studykit/MainActivity.kt`
- Modify: `app/src/main/java/com/studykit/ui/mistake/MistakeViewModel.kt`
- Modify: `app/src/main/java/com/studykit/ui/nav/AppNav.kt`

**Interfaces:**
- Consumes: `MistakeViewModel.setPendingCapture(File?)`、`MistakeImageStore.cameraTempDir`、既有 `MistakeRoutes.CAPTURE`。
- Produces:
  - `object ShareIntake { fun extract(context: Context, intent: Intent): File?; fun extractMany(context: Context, intent: Intent): List<File> }`
  - `MainActivity` 暴露 `val sharedImage: StateFlow<File?>`（进程内一次性交接，见 Step 3 的取舍；`AppNav` 收 `sharedImage: StateFlow<File?>` 与 `onSharedConsumed: () -> Unit` 两个参数）

- [ ] **Step 1: manifest 注册分享入口**

在既有 `<activity android:name=".MainActivity" ...>` 里追加（并把 `android:launchMode="singleTop"` 加上，让重复分享走 `onNewIntent` 而不是叠新实例）：

```xml
        <intent-filter>
            <action android:name="android.intent.action.SEND" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:mimeType="image/*" />
        </intent-filter>
        <intent-filter>
            <action android:name="android.intent.action.SEND_MULTIPLE" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:mimeType="image/*" />
        </intent-filter>
```

- [ ] **Step 2: 写 `ShareIntake`**

```kotlin
package com.studykit.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.studykit.util.MistakeImageStore
import java.io.File
import java.util.UUID

/**
 * 系统分享进来的图片 → 本地临时文件。
 *
 * 必须**先把 content:// 拷进自己的目录**再交给下游：分享方授予的 URI 权限只活到本 Activity
 * 的 `onResume`，而错题录入页要显示、压缩、可能还要 OCR 识别，拖到几分钟后 URI 就失效了。
 * 落到 `MistakeImageStore.cameraTempDir` 与拍照流程同一条路，下游零分支。
 */
object ShareIntake {

    /** 单张分享；多张时取第一张（其余由 [extractMany] 处理） */
    fun extract(context: Context, intent: Intent): File? =
        extractMany(context, intent).firstOrNull()

    fun extractMany(context: Context, intent: Intent): List<File> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return emptyList()
        }
        val uris: List<Uri?> = when (intent.action) {
            Intent.ACTION_SEND -> listOf(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.map { it } ?: emptyList()
        }
        return uris.filterNotNull().mapNotNull { uri -> copyToTemp(context, uri) }
    }

    /** internal：Task 13 的「截图取词」复用同一份拷贝逻辑，不写第二遍 */
    internal fun copyToTemp(context: Context, uri: Uri): File? = runCatching {
        val target = File(MistakeImageStore.cameraTempDir(context), "shared-${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (target.length() == 0L) {
            target.delete()
            return null
        }
        target
    }.getOrNull()
}
```

- [ ] **Step 3: `MainActivity` 接 intent**

```kotlin
class MainActivity : ComponentActivity() {

    /**
     * 分享来的图片。用 ViewModel 之外的进程内交接而不是 intent 参数：
     * 图片是本地文件路径，走 navArgument 会把生命周期绑到返回栈上，
     * 用户按返回后临时文件还在，容易被二次导入。
     */
    private val sharedImageFlow = MutableStateFlow<File?>(null)
    val sharedImage: StateFlow<File?> = sharedImageFlow.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        sharedImageFlow.value = ShareIntake.extract(this, intent)
        setContent {
            StudyKitTheme {
                AppNav(sharedImage = sharedImageFlow, onSharedConsumed = { sharedImageFlow.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareIntake.extract(this, intent)?.let { sharedImageFlow.value = it }
    }
}
```

- [ ] **Step 4: `AppNav` 消费并直达预填录入页**

`AppNav` 签名改为 `fun AppNav(sharedImage: StateFlow<File?>, onSharedConsumed: () -> Unit)`；体内加：

```kotlin
    val shared by sharedImage.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        if (shared != null) {
            mistakeViewModel.setPendingCapture(shared)
            onSharedConsumed()
            navController.navigate(MistakeRoutes.CAPTURE) { launchSingleTop = true }
        }
    }
```

`MistakeCaptureScreen` 已有 `pendingCapture` 显示逻辑，无需改动即可看到分享来的图；本任务只负责"图能进来并显示"，OCR 预识别在 Task 12。

- [ ] **Step 5: 提交、CI、真机验证**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/studykit/util/ShareIntake.kt app/src/main/java/com/studykit/MainActivity.kt app/src/main/java/com/studykit/ui/nav/AppNav.kt
git commit -m "feat(mistake): 注册 SEND/SEND_MULTIPLE 图片分享，携图直达错题录入页"
git push origin feat/v2-visual-motion && gh run watch
```

真机：`adb shell am start -a android.intent.action.SEND -t image/* --eu android.intent.extra_STREAM "file:///sdcard/Download/x.jpg" com.studykit/.MainActivity`（先把一张图 push 到 `/sdcard/Download/`）。期望：直接落在错题录入页且图片已显示；按返回回到错题 Tab 而不是崩溃。

---

### Task 12: OCR 引擎与「从图片提取文字」

spec §5.4：ML Kit bundled 中文模型。事实核对：`com.google.mlkit:text-recognition-chinese:16.0.1` 依赖 `text-recognition-bundled-common:17.0.0` —— **模型打进 APK**，运行期不依赖 GMS 应用（vivo 无 GMS 可用），代价是 APK 体积 +约 4MB。

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/studykit/util/OcrTextExtractor.kt`
- Modify: `app/src/main/java/com/studykit/ui/mistake/MistakeViewModel.kt`
- Modify: `app/src/main/java/com/studykit/ui/mistake/MistakeCaptureScreen.kt`
- Modify: `app/src/main/baseline-prof.txt`

**Interfaces:**
- Consumes: `MistakeImageStore.resolve(context, relativePath)`、`cameraTempDir`、既有 `AppButton`/`AppCard`。
- Produces:
  - `object OcrTextExtractor { suspend fun recognize(file: File): OcrResult }`
  - `sealed interface OcrResult { data class Text(val value: String) : OcrResult; data class Failed(val reason: String) : OcrResult }`

- [ ] **Step 1: 加依赖**

```toml
[versions]
mlkitChineseText = "16.0.1"

[libraries]
mlkit-text-recognition-chinese = { group = "com.google.mlkit", name = "text-recognition-chinese", version.ref = "mlkitChineseText" }
```

```kotlin
    // 截图取词：bundled 中文模型进 APK，全离线、不需要 GMS（spec §5.4）
    implementation(libs.mlkit.text.recognition.chinese)
```

- [ ] **Step 2: 写封装**

```kotlin
package com.studykit.util

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 识别结果两态：成功给拼接后的多行文本，失败给一句能显示给用户的话 */
sealed interface OcrResult {
    data class Text(val value: String) : OcrResult
    data class Failed(val reason: String) : OcrResult
}

/**
 * ML Kit 中文文字识别（bundled）。
 *
 * 三条实现约束：
 * 1. `InputImage.fromFile` 与 `recognize` 都在主线程外做（前者要解 bitmap，大图可达数百毫秒）；
 * 2. 识别器是**单例并复用**：每次新建会重新加载模型，实测会明显卡顿，且必须 `close()`；
 * 3. ML Kit 的回调是一次性的 `addOnSuccessListener/onFailure`，用 `suspendCancellableCoroutine`
 *    桥接；协程取消时 `engine.cancel()` 让相机/解码资源及时释放。
 */
object OcrTextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(file: File): OcrResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext OcrResult.Failed("图片不存在或已损坏")
        }
        val image = runCatching { InputImage.fromFilePath(file.absolutePath) }.getOrNull()
            ?: return@withContext OcrResult.Failed("无法读取这张图片")
        suspendCancellableCoroutine<OcrResult> { continuation ->
            continuation.invokeOnCancellation { recognizer.cancel() }
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.textBlocks.joinToString("\n") { block ->
                        block.lines.joinToString("\n") { it.text }
                    }
                    continuation.resume(
                        if (text.isBlank()) OcrResult.Failed("没识别出文字，可手动输入")
                        else OcrResult.Text(text),
                    )
                }
                .addOnFailureListener { error ->
                    continuation.resume(OcrResult.Failed("识别失败：${error.message ?: "未知原因"}"))
                }
        }
    }
}
```

- [ ] **Step 3: ViewModel 侧入口**

`MistakeViewModel` 加：

```kotlin
    /** 识别当前待处理图片的文字；结果只回填表单，**不自动保存**（spec §5.4 要求人工修正） */
    fun extractTextFromImage(onResult: (String?) -> Unit) {
        val captured = _pendingCapture.value
        if (captured == null) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            when (val result = OcrTextExtractor.recognize(captured)) {
                is OcrResult.Text -> onResult(result.value)
                is OcrResult.Failed -> {
                    toast(result.reason)
                    onResult(null)
                }
            }
        }
    }
```

- [ ] **Step 4: 录入页按钮**

`MistakeCaptureScreen` 在图片预览卡下方加一行（仅当 `pendingCapture != null` 时出现）：

```kotlin
            if (hasImage) {
                Spacer(Modifier.height(AppTheme.space.md))
                AppButton(
                    text = if (extracting) "识别中…" else "从图片提取文字",
                    secondary = true,
                    enabled = !extracting,
                    onClick = {
                        extracting = true
                        viewModel.extractTextFromImage { text ->
                            extracting = false
                            if (text != null) {
                                // 首行当标题，其余进备注：截图题的惯例排版
                                val lines = text.lines().filter { it.isNotBlank() }
                                if (lines.isNotEmpty()) {
                                    title = lines.first().take(40)
                                    note = lines.drop(1).joinToString("\n")
                                }
                            }
                        }
                    },
                )
            }
```

`var extracting by rememberSaveable { mutableStateOf(false) }`。

- [ ] **Step 5: baseline profile 增补 + 提交 + CI**

`app/src/main/baseline-prof.txt` 追加（OCR 是点击后才走的冷路径，**不要**加进 profile —— 加进去只会拉长安装期 AOT；本步骤只加分享收件这条启动相关路径）：

```
HSPLcom/studykit/util/ShareIntake;->**(**)**
```

> Kotlin `object` 编译成单个类 `com/studykit/util/ShareIntake;`（**没有** `$Companion`，那是 Java 静态内部类的形态）。规则写错不会报错、只会被 profile 解析器**静默丢弃**，所以本步骤的验收是：跑完 release job 后，用 `gh run view <id> --log | grep -iE "ArtProfile|warning"` 确认 `compileReleaseArtProfile` 执行且零 warning。

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/studykit/util/OcrTextExtractor.kt app/src/main/java/com/studykit/ui/mistake app/src/main/baseline-prof.txt
git commit -m "feat(mistake): ML Kit bundled 中文 OCR，录错题一键提取文字并回填标题与备注"
git push origin feat/v2-visual-motion
gh run watch    # 期望：两个 job 全绿；release job 尤其重要（ML Kit 的 R8 keep 规则由 AAR 自带，需确认混淆通过）
```

- [ ] **Step 6: 真机验证 OCR（无 CI 等价物）**

截一张含中文的图（题目截图最佳）push 到 `/sdcard/Download/ocr-sample.jpg`，走 Task 11 的分享命令进录入页，点「从图片提取文字」。判据：标题与备注被填上、可编辑、保存后进错题本；识别失败时是 toast 而不是崩溃；APK 体积增长符合预期（`ls -la` 对比 Task 11 的产物，预期 +3~5MB）。

---

### Task 13: 分享自动预识别 + 详情页长按识别 + 截图取词入单词

spec §5.4 的三个入口 + 「复用同一能力做单词截图取词」。

**Files:**
- Modify: `app/src/main/java/com/studykit/ui/mistake/MistakeCaptureScreen.kt`
- Modify: `app/src/main/java/com/studykit/ui/mistake/MistakeDetailScreen.kt`
- Modify: `app/src/main/java/com/studykit/ui/mistake/MistakeViewModel.kt`
- Modify: `app/src/main/java/com/studykit/ui/study/WordListScreen.kt`
- Modify: `app/src/main/java/com/studykit/ui/nav/AppNav.kt`

**Interfaces:**
- Consumes: Task 12 的 `OcrTextExtractor`/`OcrResult`、Task 6 的 `WordLineParser`、Task 7 的 `ImportViewModel` + `ImportKind` + 预览/结果屏、Task 11 的分享链路。
- Produces:
  - `fun MistakeViewModel.autoExtractFromShare(onResult: (String?) -> Unit)`（静默降级：失败不 toast）
  - `fun MistakeViewModel.extractFromImageFile(relativePath: String, onResult: (String?) -> Unit)`（详情页用，按已入库的相对路径）
  - 截图取词**不新增路由**：识别文本经 `WordLineParser` 产出 `ImportPlan` 后交给 `ImportViewModel.loadPlan()`，直接复用 Task 7 的 `ImportRoutes.PREVIEW` / `RESULT`

- [ ] **Step 1: 分享进来自动预识别（失败静默）**

`MistakeCaptureScreen` 加：

```kotlin
    // 分享进来的图（有文件但表单还是空的）自动跑一次 OCR：用户从相册点"分享→StudyKit"
    // 的意图就是"把这题收进来"，多点一次按钮是多余成本。
    // 失败静默 —— 识别不出来时不该拿 toast 打扰用户，手动按钮仍在原地。
    LaunchedEffect(pendingPath) {
        if (pendingPath != null && title.isBlank() && note.isBlank() && !autoExtractTried) {
            autoExtractTried = true
            viewModel.autoExtractFromShare { text ->
                if (text != null) {
                    val lines = text.lines().filter { it.isNotBlank() }
                    if (lines.isNotEmpty()) {
                        title = lines.first().take(40)
                        note = lines.drop(1).joinToString("\n")
                    }
                }
            }
        }
    }
```

`var autoExtractTried by rememberSaveable { mutableStateOf(false) }`（转屏不重跑，避免二次识别）。`MistakeViewModel.autoExtractFromShare(onResult: (String?) -> Unit)` 与 Task 12 的 `extractTextFromImage` 同实现，差别只在失败时**不** toast。

- [ ] **Step 2: 详情页长按图片补识别**

`MistakeDetailScreen` 的大图 `AsyncImage` 外包一层：

```kotlin
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { showFullImage = true },
                            onLongClick = { recognizeOnDetail() },
                        ),
                ) { /* 既有 AsyncImage 原样 */ }
```

`recognizeOnDetail()` 走 `viewModel.extractFromImageFile(relativePath) { text -> if (text != null) { detailNote = text; showNoteDialog = true } else Unit }`，识别中用 `CircularProgressIndicator(color = colors.accent)` 占位。长按的可见性提示：图片卡右下角加一行 `Text("长按图片可识别文字", style = texts.caption)`（长按没有原生可发现性，必须写出来）。

- [ ] **Step 3: 截图取词入单词**

`WordListScreen` 工具栏再加「截图取词」：走 `ActivityResultContracts.PickVisualMedia()`（图片选择器，Android 13+ 免权限；minSdk 26 时系统会回落到旧选择器，无需分支权限）→ 拷到临时目录 → `OcrTextExtractor.recognize` → `WordLineParser.parseAll(识别文本)` → `importViewModel.loadPlan(plan)` → 预览页。

```kotlin
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrRunning = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { ShareIntake.copyToTemp(context, uri) }
            val plan = when (val result = OcrTextExtractor.recognize(file)) {
                is OcrResult.Text -> WordLineParser.parseAll(result.value)
                is OcrResult.Failed -> {
                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                    ocrRunning = false
                    return@launch
                }
            }
            ocrRunning = false
            if (plan.items.isEmpty()) {
                Toast.makeText(context, "没认出词表，检查截图是否为一行一词", Toast.LENGTH_SHORT).show()
            } else {
                importViewModel.loadPlan(plan)
                onPreviewImport()
            }
        }
    }
```

Task 11 已把 `ShareIntake.copyToTemp` 提为 `internal`，这里直接调它：`ShareIntake.copyToTemp(context, uri)`，不新增第二个拷贝函数。

- [ ] **Step 4: 提交、CI、真机验证**

```bash
git add app/src/main/java/com/studykit/ui/mistake app/src/main/java/com/studykit/ui/study app/src/main/java/com/studykit/util/ShareIntake.kt app/src/main/java/com/studykit/ui/nav/AppNav.kt
git commit -m "feat(ocr): 分享自动预识别、详情页长按识别补录、截图取词批量入单词"
git push origin feat/v2-visual-motion && gh run watch
```

真机三条判据：①分享一张题目截图 → 不点任何按钮，标题与备注已被填好（识别失败时表单为空且无 toast，手动按钮可用）；②错题详情页长按图片 → 弹出备注编辑框且内容已填；③单词库「截图取词」选一张四行「word 释义」的截图 → 预览页显示 4 条可导入。

---

### Task 14: 版本、文档与最终走查

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `CHANGELOG.md`、`README.md`、`README.en.md`、`README.ru.md`
- Modify: `docs/superpowers/specs/2026-09-18-studykit-v2-design.md`
- Create: `docs/superpowers/plans/2026-09-20-studykit-v2-m2-input-automation.md`（本文件，随首个任务提交）

- [ ] **Step 1: 版本号**

`versionCode = 3`、`versionName = "2.1.0"`（M2 是功能新增，不破坏既有数据；Room 从 2 升到 3 由 Task 1 的迁移承接）。

- [ ] **Step 2: CHANGELOG 新增 2.1.0 段**

必须包含：五件套各一条；**「数据来源与许可」一条**（kajweb/dict 抓取数据、仅个人学习、导入后离线）；「已知限制」补三条：① `MIGRATION_2_3` 的执行与 schema 校验已在真机验证（Task 1 Step 9），未覆盖的仅为**同签名 A→B 覆盖安装**这一条分发路径（本机与 CI 都无稳定 keystore）；② 同因，**升级后原有数据行完好保留**也未实测（Step 9 注入的是 schema-only 重放库，行数恒为 0），发布前若拿到稳定 keystore 应补测；③ OCR 对低质量截图识别率有限、离线目录快照需随版本更新。

- [ ] **Step 3: README 三语**

功能表加"批量导入 / 文件导入 / 在线词库 / 截图分享 / OCR 取词"；技术栈表加 ML Kit；APK 体积变化说明（bundled 模型）；三语保持同结构，不要只改中文。

- [ ] **Step 4: spec 回写**

`docs/superpowers/specs/2026-09-18-studykit-v2-design.md` §5 各小节末尾标注实际落地形态（例如"词表 zip 内为 NDJSON，解析按行"、"答案支持字母与选项原文两种写法"），与代码不一致处一律以代码为准并划掉原文（沿用 M1 的 `~~原文~~` 记法）。

- [ ] **Step 5: 提交、CI、真机总走查**

```bash
git add app/build.gradle.kts CHANGELOG.md README.md README.en.md README.ru.md docs
git commit -m "chore(release): v2.1.0 与 M2 文档（含词库数据来源与许可说明）"
git push origin feat/v2-visual-motion && gh run watch
```

真机走查按 M1 的清单重跑一遍（回归），再加 M2 专项：批量粘贴、选文件、词库导入与撤销、分享进错题、OCR 三入口、APK 体积、深色下新增界面（预览页/商店/结果卡都要看，新增屏最容易漏 ink 规则）。

---

### Task 15: M1 遗留的两项 M2 待办——预测式返回手势 + baseline profile 收益复测

spec §7 的「M2 待办清单」共三条：②五件套已由 Task 2–13 承接；①预测式返回与③profile 收益复测在这里收口。M1 把①推后的理由是"本机无设备无法验证系统手势"—— 现在有了 adb 全自动真机，这个理由不再成立。

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `CHANGELOG.md`（「已知限制」里那条要么删掉要么改成已接）

**Interfaces:**
- Consumes: Task 14 之后 `versionName = 2.1.0` 的产物；既有 `MotionSpec.nav*` 四个转场工厂。
- Produces: 无新对外接口，只产出实测数据与结论。

- [ ] **Step 1: 打开系统预测式返回开关**

`<application>` 标签加一行：

```xml
        <!-- Android 13+ 预测式返回：开启后系统左缘手势会驱动 Compose 的 pop 转场 -->
        android:enableOnBackInvokedCallback="true"
```

- [ ] **Step 2: 提交、CI、装包**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(nav): 打开 enableOnBackInvokedCallback，接系统预测式返回"
git push origin feat/v2-visual-motion && gh run watch
cd "C:/Users/Administrator/Desktop/StudyKit-v2.0.0" && bash auto-install.sh StudyKit-v2.0.0-debug.apk 2.1.0
```

- [ ] **Step 3: 真机逐条判据**

```bash
export PATH="/e/tools/adb:$PATH"
adb shell monkey -p com.studykit -c android.intent.category.LAUNCHER 1; sleep 5
adb shell input tap 540 378                      # 进单词库
sleep 2
adb shell input swipe 20 1200 700 1200 300       # 左缘向右滑 = 系统返回手势
sleep 2
adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus
```

三条判据：①手势后回到学习 Tab 且不闪退；②转场有可观察的预览位移（截图存 `walk/gesture-*.png`）；③**真正的风险点**：在背单词卡片页做同样的左缘横滑，期望是"返回"，**不能**被 `SwipeRatingCard` 的右滑=认识吃掉。

- [ ] **Step 4: 冲突时的处置（不许含糊）**

判据③失败时按优先级二选一：
1. 收窄评价手势的命中区 —— `draggable` 外层加 `Modifier.padding(start = 24.dp)` 或用 `pointerInput` 判定起始 `x > 左缘阈值`，保留 flag；
2. 仍冲突则 `git revert` 本任务提交，并在 CHANGELOG「已知限制」写明"预测式返回与滑动评价手势冲突，待 navigation-compose 提供稳定的 `predictivePopSpec` 后重开"。

两种结果都要写进报告，附截图与 `dumpsys window` 原文。**注意**：vivo V2156A 是 Android 11，`enableOnBackInvokedCallback` 只对 Android 13+ 生效 —— 本机若完全无手势可测，必须如实写"该开关在本设备不可观测，仅有 Android 13+ 设备时才能判定"，不得凭"没崩"就当通过。

- [ ] **Step 5: baseline profile 收益复测（spec §7 待办③）**

安装期 AOT 不可直接观测，用冷启动耗时做前后对比。取 Task 14 产物（含 profile）与 M1 `e5d7e10` 产物各测 5 次：

```bash
export PATH="/e/tools/adb:$PATH"
for i in 1 2 3 4 5; do
  adb shell am force-stop com.studykit
  sleep 2
  adb shell am start -W -S com.studykit/.MainActivity | tr -d '\r' | grep -E "TotalTime|WaitTime"
done
```

判据：报告给出两组 `TotalTime` 的**中位数与最小值**。差值落在噪声内（<15%）就如实写"未见收益"——**不许**把"CI 里有 profile 断言且通过"当成收益证据。顺带记录 `am start -W` 有无 `ClassNotFound` / `VerifyError`（profile 规则写坏会在这里暴露）。

- [ ] **Step 6: 提交实测结论**

```bash
git add CHANGELOG.md
git commit -m "docs: 记录预测式返回与 baseline profile 的真机实测结论"
git push origin feat/v2-visual-motion
```

---

## 验收（计划级）

1. CI 两个 job 全绿：`lint testDebugUnitTest assembleDebug` + `assembleRelease` 与 baseline profile 断言；新增单测（解析器 5 套 + 去重 + 词库解析）全部通过。
2. 真机专项（用户执行，交付检查单见 Task 7 Step 7 / Task 10 Step 5 / Task 11 Step 5 / Task 12 Step 6 / Task 13 Step 4）：五件套每条至少一次成功 + 一次失败路径（断网、坏行、识别不出）。
3. 数据回归：M1 的四模块走查清单重跑一遍不新增问题；`PRAGMA user_version` 为 3；已导入词库可整本撤销。
4. 全部通过后：分支合入与发布**仍需用户明确同意**（本计划不含 merge / tag / Release）。


