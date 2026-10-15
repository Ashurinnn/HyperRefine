package cn.ianzb.hyperrefine.hook.volume

import org.junit.Assert.*
import org.junit.Test

class OwnedResourcesTest {
    @Test fun detachThenAnimationCancelAndEndReleaseEachColumnOnlyOnce() {
        val released = mutableListOf<String>()
        val panel = OwnedResources<String>({ released.add(it) })
        panel.add("apple")
        panel.add("bilibili")
        panel.dispose() // detach
        panel.dispose() // cancel
        panel.dispose() // end
        assertEquals(listOf("apple", "bilibili"), released)
        assertTrue(panel.values.isEmpty())
    }

    @Test fun lateOldPanelCallbackDoesNotReleaseTheReplacementPanelsColumns() {
        val released = mutableListOf<String>()
        val old = OwnedResources<String>({ released.add(it) })
        old.add("old")
        old.dispose()
        val current = OwnedResources<String>({ released.add(it) })
        current.add("current")
        old.dispose()
        assertEquals(listOf("old"), released)
        assertEquals(listOf("current"), current.values)
        current.dispose()
        assertEquals(listOf("old", "current"), released)
    }

    @Test fun reentrantDisposalAndAReleaseFailureStillReleaseTheOtherResources() {
        val released = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        lateinit var panel: OwnedResources<String>
        panel = OwnedResources({
            assertTrue(panel.values.isEmpty())
            panel.dispose()
            if (it == "broken") error("native release failed")
            released.add(it)
        }, { failures.add(it) })
        panel.add("broken")
        panel.add("healthy")
        panel.dispose()
        assertEquals(listOf("healthy"), released)
        assertEquals(1, failures.size)
    }

    @Test fun partiallyConstructedPanelCannotRetainResourcesAddedAfterDisposal() {
        val released = mutableListOf<String>()
        val panel = OwnedResources<String>({ released.add(it) })
        panel.dispose()
        panel.add("late-column")
        assertEquals(listOf("late-column"), released)
        assertTrue(panel.values.isEmpty())
    }
}
