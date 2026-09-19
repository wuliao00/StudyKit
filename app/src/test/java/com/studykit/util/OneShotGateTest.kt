package com.studykit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OneShotGate] 纯逻辑单元测试 —— 终审 C4 那条「连点两次只产生 1 条插入、1 次 pop」的机制本体。
 *
 * 门开在 ViewModel 侧（六个保存入口共用这一枚原语），页面不再各写一套 `enabled` 态；
 * 这里钉的是它的三条契约：首放、重放被挡、放行后可再用（早退与异常都走 `finally`，不留残锁）。
 */
class OneShotGateTest {

    @Test fun `首次进入放行，重复进入被挡`() {
        val gate = OneShotGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertFalse(gate.tryEnter())
    }

    @Test fun `占用期间 occupied 为真`() {
        val gate = OneShotGate()
        assertFalse(gate.occupied)
        gate.tryEnter()
        assertTrue(gate.occupied)
    }

    @Test fun `leave 之后可以再次进入`() {
        val gate = OneShotGate()
        assertTrue(gate.tryEnter())
        gate.leave()
        assertFalse(gate.occupied)
        assertTrue(gate.tryEnter())
    }

    @Test fun `早退分支经 finally 放行后不锁死`() {
        // 保存方法里「校验不通过就 return@launch」的那条路：finally 仍会执行，
        // 用户改完内容再点必须还能保存
        val gate = OneShotGate()

        fun attempt(wouldPassValidation: Boolean): Boolean {
            if (!gate.tryEnter()) return false
            try {
                if (!wouldPassValidation) return false
                return true
            } finally {
                gate.leave()
            }
        }

        assertFalse(attempt(wouldPassValidation = false))
        assertTrue("校验失败的早退把门关死了", attempt(wouldPassValidation = true))
    }

    @Test fun `写入抛异常后也不会永久关门`() {
        val gate = OneShotGate()

        fun attemptThatThrows() {
            if (!gate.tryEnter()) return
            try {
                error("repository 写入失败")
            } finally {
                gate.leave()
            }
        }

        runCatching { attemptThatThrows() }.also { assertTrue(it.isFailure) }
        // 第二次不该因为上一把残锁被静默吃掉（表现就是「点了没反应」）
        assertTrue(gate.tryEnter())
    }

    @Test fun `两枚门各自独立，不串台`() {
        // 同一个 ViewModel 里承载两个录入入口时，保存单词不该挡住录题目
        val savingWord = OneShotGate()
        val savingQuestion = OneShotGate()
        assertTrue(savingWord.tryEnter())
        assertTrue(savingQuestion.tryEnter())
        assertFalse(savingWord.tryEnter())
        assertTrue(savingWord.occupied && savingQuestion.occupied)
    }

    @Test fun `写入未返回时的连点只产生一次插入`() {
        val gate = OneShotGate()
        var inserted = 0

        // 模拟一次点击：抢到门才 launch。`launch` 体要等 Room 写完才跑到 finally/leave，
        // 所以这里刻意**不**放行 —— 连点的五次全部落在写入在飞的那段时间里。
        fun click() {
            if (!gate.tryEnter()) return
            inserted += 1
        }

        repeat(5) { click() }
        assertEquals(1, inserted)

        // 写库回来、门已放行之后，用户再进同一个页面录第二条仍是正常路径（不是永久锁死）
        gate.leave()
        click()
        assertEquals(2, inserted)
    }
}
