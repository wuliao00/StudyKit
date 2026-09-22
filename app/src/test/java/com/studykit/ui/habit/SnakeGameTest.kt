package com.studykit.ui.habit

import com.studykit.ui.habit.SnakeDirection.DOWN
import com.studykit.ui.habit.SnakeDirection.LEFT
import com.studykit.ui.habit.SnakeDirection.RIGHT
import com.studykit.ui.habit.SnakeDirection.UP
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贪吃蛇的黄金轨迹。这条游戏已经做过三次，前两次都死在"逻辑长在 Composable 里够不着"，
 * 这次的全部赌注押在这里：每一局的每一步都能在 JVM 里复现，UI 只剩画和手势。
 */
class SnakeGameTest {

    private val rng = Random(42)

    /** 真机那块棋盘：7 行（星期）× 20 列（周） */
    private val boardCols = 20
    private val boardRows = 7

    private fun game(
        snake: List<SnakeCell>,
        direction: SnakeDirection = RIGHT,
        food: SnakeCell = SnakeCell(18, 0),
        checkedIn: Set<SnakeCell> = emptySet(),
        cols: Int = boardCols,
        rows: Int = boardRows,
        stepMillis: Int = SnakeGame.START_STEP_MILLIS,
    ) = SnakeGame(
        cols = cols, rows = rows, checkedIn = checkedIn,
        snake = snake, direction = direction, food = food,
        score = 0, eaten = 0, stepMillis = stepMillis, isOver = false,
    )

    // ── 开局 ─────────────────────────────────────────────────────

    @Test
    fun `start puts a right-facing snake of two near the middle`() {
        val g = SnakeGame.start(20, 7, random = rng)
        assertEquals(2, g.snake.size)
        assertEquals(RIGHT, g.direction)
        assertEquals(SnakeGame.START_STEP_MILLIS, g.stepMillis)
        assertTrue("开局头必须在棋盘内", g.snake.first().x in 0 until 20 && g.snake.first().y in 0 until 7)
        assertTrue("果子不能压在蛇身上", g.food !in g.snake)
    }

    @Test
    fun `start rejects a board too small for the opening snake`() {
        try {
            SnakeGame.start(3, 7, random = rng)
            throw AssertionError("3 列应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("棋盘太小"))
        }
    }

    // ── 转向 ─────────────────────────────────────────────────────

    /** 蛇不许 180° 掉头 —— 那等于原地撞进自己第二节 */
    @Test
    fun `reversing into yourself is ignored`() {
        val g = game(snake = listOf(SnakeCell(5, 3), SnakeCell(4, 3)), direction = RIGHT)
        assertEquals(RIGHT, g.turn(LEFT).direction)
        assertEquals(RIGHT, g.turn(RIGHT).direction)
        assertEquals(UP, g.turn(UP).direction)
    }

    @Test
    fun `turning after game over is a no-op`() {
        val g = game(snake = listOf(SnakeCell(5, 3), SnakeCell(4, 3)), direction = RIGHT).copy(isOver = true)
        assertEquals(g, g.turn(UP))
    }

    // ── 移动与死亡 ───────────────────────────────────────────────

    @Test
    fun `tick advances the head one cell and drops the tail`() {
        val g = game(snake = listOf(SnakeCell(2, 3), SnakeCell(1, 3), SnakeCell(0, 3)), direction = RIGHT)
        val next = g.tick(rng)
        assertEquals(SnakeCell(3, 3), next.snake.first())
        assertEquals(listOf(SnakeCell(3, 3), SnakeCell(2, 3), SnakeCell(1, 3)), next.snake)
    }

    @Test
    fun `hitting the wall ends the game`() {
        val g = game(snake = listOf(SnakeCell(0, 3), SnakeCell(1, 3)), direction = LEFT)
        assertTrue(g.tick(rng).isOver)
    }

    /** 经典规则：不吃果时尾巴会让位，蛇头可以进"当前尾巴"那格 */
    @Test
    fun `following your own tail is legal`() {
        val g = game(
            snake = listOf(SnakeCell(2, 1), SnakeCell(1, 1), SnakeCell(0, 1)),
            direction = RIGHT,
            food = SnakeCell(18, 0),
        )
        val next = g.tick(rng)
        assertTrue(next.snake.containsAll(listOf(SnakeCell(3, 1), SnakeCell(2, 1), SnakeCell(1, 1))))
        assertTrue("跟尾不该判死", !next.isOver)
    }

    @Test
    fun `biting your own body ends the game`() {
        // 头 (1,1) 朝下撞 (1,2) —— (1,2) 在身子中段（尾巴是 (2,1)，不参与判定）
        val g = game(
            snake = listOf(
                SnakeCell(1, 1), SnakeCell(1, 2), SnakeCell(2, 2), SnakeCell(2, 1),
            ),
            direction = DOWN,
            food = SnakeCell(18, 0),
        )
        assertTrue(g.tick(rng).isOver)
    }

    // ── 计分 ─────────────────────────────────────────────────────

    @Test
    fun `plain fruit is worth ten and speeds up`() {
        val g = game(
            snake = listOf(SnakeCell(1, 3), SnakeCell(0, 3)),
            direction = RIGHT,
            food = SnakeCell(2, 3),
        )
        val next = g.tick(rng)
        assertEquals(SnakeGame.SCORE_PLAIN, next.score)
        assertEquals(3, next.snake.size)
        assertEquals(SnakeGame.START_STEP_MILLIS - SnakeGame.STEP_SPEEDUP_MILLIS, next.stepMillis)
        assertEquals(1, next.eaten)
        assertTrue("新果子不能落在蛇身上", next.food !in next.snake)
    }

    /** 金果是这张图存在的理由：果子落在打卡过的格子上，比普通果贵 */
    @Test
    fun `fruit on a checked-in day is worth twenty-five`() {
        val golden = SnakeCell(2, 3)
        val g = game(
            snake = listOf(SnakeCell(1, 3), SnakeCell(0, 3)),
            direction = RIGHT,
            food = golden,
            checkedIn = setOf(golden),
        )
        assertEquals(SnakeGame.SCORE_GOLDEN, g.tick(rng).score)
    }

    @Test
    fun `speed never drops below the floor`() {
        val g = game(
            snake = listOf(SnakeCell(1, 3), SnakeCell(0, 3)),
            direction = RIGHT,
            food = SnakeCell(2, 3),
            stepMillis = 91,
        )
        assertEquals(SnakeGame.MIN_STEP_MILLIS, g.tick(rng).stepMillis)
    }

    /**
     * 满盘通关：吃下最后一颗果子后蛇占满棋盘、无格落果 —— 按结束处理，分数保留。
     * 11 节蛇铺满 4x3 里除 (2,1) 外的所有格，头 (2,0) 朝下吃掉 (2,1)。
     */
    @Test
    fun `filling the board ends the game instead of crashing`() {
        val snake = listOf(
            SnakeCell(2, 0), SnakeCell(3, 0), SnakeCell(3, 1), SnakeCell(3, 2),
            SnakeCell(2, 2), SnakeCell(1, 2), SnakeCell(0, 2), SnakeCell(0, 1),
            SnakeCell(1, 1), SnakeCell(1, 0), SnakeCell(0, 0),
        )
        val g = SnakeGame(
            cols = 4, rows = 3, checkedIn = emptySet(),
            snake = snake, direction = DOWN, food = SnakeCell(2, 1),
            score = 100, eaten = 9, stepMillis = 90, isOver = false,
        )
        val next = g.tick(rng)
        assertTrue(next.isOver)
        assertEquals(100 + SnakeGame.SCORE_PLAIN, next.score)
        assertEquals(12, next.snake.size)
    }

    // ── 压力 ─────────────────────────────────────────────────────

    /** 随机玩 1000 步：不死则不变式恒成立，死了也必须体面（分数不丢、状态一致） */
    @Test
    fun `thousand random steps keep every invariant`() {
        var g = SnakeGame.start(boardCols, boardRows, random = rng)
        var steps = 0
        while (!g.isOver && steps < 1000) {
            val dirs = SnakeDirection.entries
            g = g.turn(dirs[Random(steps).nextInt(dirs.size)]).tick(rng)
            steps++
            assertTrue("分数不能为负：${g.score}", g.score >= 0)
            assertTrue(
                "蛇身越界：${g.snake.first()}",
                g.isOver || g.snake.all { it.x in 0 until boardCols && it.y in 0 until boardRows },
            )
            if (!g.isOver) {
                assertEquals("蛇身不许有重复格", g.snake.size, g.snake.toSet().size)
                assertTrue("果子不能压在蛇身上", g.food !in g.snake)
                assertTrue("速度不许低于下限", g.stepMillis >= SnakeGame.MIN_STEP_MILLIS)
                assertTrue("速度不许高于开局值", g.stepMillis <= SnakeGame.START_STEP_MILLIS)
            }
        }
        assertTrue("1000 步内应当分出胜负或正常进行", steps > 0)
    }

    /** 打卡格全填满的棋盘：任何落果都是金果 —— 金果判定必须读的是调用方给的集合 */
    @Test
    fun `every cell checked-in makes every fruit golden`() {
        val all = buildSet {
            for (x in 0 until 4) for (y in 0 until 3) add(SnakeCell(x, y))
        }
        var g = SnakeGame.start(4, 3, checkedIn = all, random = Random(7))
        assertTrue("开局果应落在打卡格上", g.food in g.checkedIn)
        g = g.tick(rng)
        assertTrue(g.isOver || g.score == SnakeGame.SCORE_GOLDEN || g.score == 0)
    }

    /** turn 与 tick 的方向一致：转向后走一步，头必须朝新方向挪 */
    @Test
    fun `turn takes effect on the next tick`() {
        val g = game(snake = listOf(SnakeCell(5, 3), SnakeCell(4, 3)), direction = RIGHT)
        val next = g.turn(UP).tick(rng)
        assertEquals(SnakeCell(5, 2), next.snake.first())
        assertNotEquals(g.snake, next.snake)
    }
}
