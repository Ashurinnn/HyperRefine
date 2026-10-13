package cn.ianzb.hyperrefine.hook.notification

import org.junit.Assert.*
import org.junit.Test

class ImportancePersistenceTest {
    private class Channel(initialLock: Int = 8) : ImportanceChannel {
        override var importance = 3
        override var userLockedFields = initialLock
        override fun lockFields(fields: Int) { userLockedFields = userLockedFields or fields }
        override fun unlockFields(fields: Int) { userLockedFields = userLockedFields and fields.inv() }
    }

    @Test fun voidBackendSeesNewImportanceAndUserLockBeforeSaving() {
        val channel = Channel()
        assertTrue(ImportancePersistence.persist(channel, 4) {
            assertEquals(4, channel.importance)
            assertEquals(12, channel.userLockedFields)
            null
        })
        assertEquals(4, channel.importance)
        assertEquals(12, channel.userLockedFields)
    }

    @Test fun backendFalseRestoresImportanceAndPreservesOtherUserLocks() {
        val channel = Channel()
        assertThrows(IllegalStateException::class.java) {
            ImportancePersistence.persist(channel, 4) { false }
        }
        assertEquals(3, channel.importance)
        assertEquals(8, channel.userLockedFields)
    }

    @Test fun binderExceptionRestoresImportanceWithoutRemovingAnExistingImportanceLock() {
        val channel = Channel(initialLock = 12)
        assertThrows(IllegalArgumentException::class.java) {
            ImportancePersistence.persist(channel, 4) { throw IllegalArgumentException("backend failure") }
        }
        assertEquals(3, channel.importance)
        assertEquals(12, channel.userLockedFields)
    }
}
