package com.studykit.ui.habit

import kotlin.random.Random

/** 棋盘上一格。x = 第几周（列），y = 星期几（行，0=周一） */
data class SnakeCell(val x: Int, val y: Int) {
    /** 与打卡日集合共用的稳定键（棋盘固定 7 行，×8 留余量） */
    val key: Long get() = x.toLong() * 8 + y
}

enum class SnakeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * 一次贪吃蛇对局的全部状态与规则。**零 Compose、零 Android**。
 *
 * 为什么单独一层：这条游戏已经是第三次做了。前两次失败的根本原因不是画得丑，
 * 是逻辑长在 Composable 里 —— 单测够不着，手感错只能靠真机试出来。
 * 这一层用**不可变状态机**（每次 tick/turn 返回新对局），随机数由调用方注入，
 * 所以每一局都能在 JVM 里被精确复现。
 *
 * 计分：普通果 +10；落在**打过卡的格子**上的金果 +25。
 * 那些格子来自真实打卡记录 —— 玩一把之后"哪天打了卡"多一层肌肉记忆，
 * 这是把这个游戏挂在习惯页而不是做成独立小游戏页的全部理由
 * （游戏化效果有条件成立，见 Hamari 2014，别指望它替习惯做什么）。
 */
data class SnakeGame(
    val cols: Int,
    val rows: Int,
    /** 打过卡的格子（金果判定用），与展示层共享同一份打卡数据 */
    val checkedIn: Set<SnakeCell>,
    val snake: List<SnakeCell>,
    val direction: SnakeDirection,
    val food: SnakeCell,
    val score: Int,
    val eaten: Int,
    val stepMillis: Int,
    val isOver: Boolean,
) {
    companion object {
        const val SCORE_PLAIN = 10
        const val SCORE_GOLDEN = 25
        const val START_STEP_MILLIS = 150
        const val MIN_STEP_MILLIS = 90
        const val STEP_SPEEDUP_MILLIS = 2

        /**
         * 开新局：蛇长 2、朝右，放在棋盘左中；果子随机。
         * [checkedIn] 允许为空 —— 空棋盘也要能玩。
         */
        fun start(
            cols: Int,
            rows: Int,
            checkedIn: Set<SnakeCell> = emptySet(),
            random: Random = Random.Default,
        ): SnakeGame {
            require(cols >= 4 && rows >= 3) { "棋盘太小放不下开局蛇：${cols}x$rows" }
            val headY = rows / 2
            val game = SnakeGame(
                cols = cols, rows = rows, checkedIn = checkedIn,
                snake = listOf(SnakeCell(1, headY), SnakeCell(0, headY)),
                direction = SnakeDirection.RIGHT,
                food = SnakeCell(0, 0), score = 0, eaten = 0,
                stepMillis = START_STEP_MILLIS, isOver = false,
            )
            return game.copy(food = game.spawnFood(random))
        }

        private fun SnakeGame.bodyKeys(): Set<Long> = snake.mapTo(HashSet()) { it.key }

        private fun SnakeGame.freeCells(): List<SnakeCell> {
            val body = bodyKeys()
            val out = ArrayList<SnakeCell>(cols * rows)
            for (x in 0 until cols) for (y in 0 until rows) {
                val c = SnakeCell(x, y)
                if (c.key !in body) out += c
            }
            return out
        }

        private fun SnakeGame.spawnFood(random: Random): SnakeCell {
            val free = freeCells()
            // 蛇占满全盘时无格可放；调用方（tick）在吃果后先查这个再落果，
            // start() 理论上到不了这里（开局蛇长 2）
            return free[random.nextInt(free.size)]
        }
    }

    /** 转向。**180° 掉头无效**（蛇不能原地撞进自己第二节），与当前方向相同也无效。
     * 转向从下一格 tick 开始生效 —— 它只改方向，不移动蛇身。 */
    fun turn(dir: SnakeDirection): SnakeGame {
        if (isOver) return this
        val reverse = when (direction) {
            SnakeDirection.UP -> SnakeDirection.DOWN
            SnakeDirection.DOWN -> SnakeDirection.UP
            SnakeDirection.LEFT -> SnakeDirection.RIGHT
            SnakeDirection.RIGHT -> SnakeDirection.LEFT
        }
        return if (dir == reverse || dir == direction) this else copy(direction = dir)
    }

    /** 走一格。撞墙、咬自己 → 对局结束；吃到果子 → 加分、变长、加速、换果子 */
    fun tick(random: Random = Random.Default): SnakeGame {
        if (isOver) return this
        val head = snake.first()
        val next = when (direction) {
            SnakeDirection.UP -> head.copy(y = head.y - 1)
            SnakeDirection.DOWN -> head.copy(y = head.y + 1)
            SnakeDirection.LEFT -> head.copy(x = head.x - 1)
            SnakeDirection.RIGHT -> head.copy(x = head.x + 1)
        }
        if (next.x < 0 || next.x >= cols || next.y < 0 || next.y >= rows) {
            return copy(isOver = true)
        }
        val eating = next == food
        // 不吃果时尾巴会让位，蛇头可以进"当前尾巴"那格；吃果时全身都算障碍
        val bodyToCheck = if (eating) snake else snake.dropLast(1)
        if (bodyToCheck.any { it == next }) return copy(isOver = true)

        val newSnake = (listOf(next) + snake).let { if (eating) it else it.dropLast(1) }
        if (!eating) return copy(snake = newSnake)

        val nextGame = copy(
            snake = newSnake,
            score = score + if (next in checkedIn) SCORE_GOLDEN else SCORE_PLAIN,
            eaten = eaten + 1,
            stepMillis = (stepMillis - STEP_SPEEDUP_MILLIS).coerceAtLeast(MIN_STEP_MILLIS),
        )
        val free = nextGame.freeCells()
        // 蛇占满全盘：无格落果 —— 通关，按结束处理，分数保留
        if (free.isEmpty()) return nextGame.copy(isOver = true)
        return nextGame.copy(food = free[random.nextInt(free.size)])
    }
}
