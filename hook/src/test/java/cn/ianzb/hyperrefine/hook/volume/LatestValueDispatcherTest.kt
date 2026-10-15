package cn.ianzb.hyperrefine.hook.volume

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LatestValueDispatcherTest {
    private class ControlledExecutor : Executor {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() = checkNotNull(tasks.poll(2, TimeUnit.SECONDS)).run()
    }

    @Test fun burstQueuesOneWorkerAndOnlyTheFinalValueOfEachUid() {
        val executor = ControlledExecutor()
        val applied = mutableListOf<Pair<Int, Int>>()
        val dispatcher = LatestValueDispatcher<Int, Pair<Int, Int>>(executor, { applied.add(it) })
        repeat(10_000) { dispatcher.submit(10_276, 10_276 to it) }
        dispatcher.submit(99_910_276, 99_910_276 to 47)
        assertEquals(1, executor.tasks.size)
        executor.runNext()
        assertEquals(listOf(10_276 to 9999, 99_910_276 to 47), applied)
        assertTrue(executor.tasks.isEmpty())
    }

    @Test fun slowIpcKeepsFinalValueWithoutLosingAnotherAppOrSchedulingMoreWorkers() {
        val executor = ControlledExecutor()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val done = CountDownLatch(1)
        val applied = mutableListOf<Pair<Int, Int>>()
        val dispatcher = LatestValueDispatcher<Int, Pair<Int, Int>>(executor, {
            if (it == 10_276 to 0) {
                entered.countDown()
                check(resume.await(2, TimeUnit.SECONDS))
            }
            applied.add(it)
        })
        dispatcher.submit(10_276, 10_276 to 0)
        val worker = Thread { try { executor.runNext() } finally { done.countDown() } }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(10_000) { dispatcher.submit(10_276, 10_276 to it) }
            dispatcher.submit(10_301, 10_301 to 63)
            assertTrue(executor.tasks.isEmpty())
        } finally { resume.countDown() }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(10_276 to 0, 10_276 to 9999, 10_301 to 63), applied)
        dispatcher.submit(10_276, 10_276 to 12)
        executor.runNext()
        assertEquals(10_276 to 12, applied.last())
    }

    @Test fun failedWriteAndFailedErrorCallbackDoNotStrandTheFinalValue() {
        val executor = ControlledExecutor()
        val applied = mutableListOf<Int>()
        lateinit var dispatcher: LatestValueDispatcher<Int, Int>
        dispatcher = LatestValueDispatcher(executor, {
            if (it == 1) {
                dispatcher.submit(1, 2)
                error("released player")
            }
            applied.add(it)
        }, { error("logging failed") })
        dispatcher.submit(1, 1)
        executor.runNext()
        assertEquals(listOf(2), applied)
        dispatcher.submit(1, 3)
        executor.runNext()
        assertEquals(listOf(2, 3), applied)
    }

    @Test fun rejectedWorkerDoesNotPreventTheNextSubmission() {
        var reject = true
        val controlled = ControlledExecutor()
        val applied = mutableListOf<Int>()
        val executor = Executor { if (reject) error("rejected") else controlled.execute(it) }
        val dispatcher = LatestValueDispatcher<Int, Int>(executor, { applied.add(it) })
        dispatcher.submit(1, 5)
        reject = false
        dispatcher.submit(1, 8)
        controlled.runNext()
        assertEquals(listOf(8), applied)
    }
}
