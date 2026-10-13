package cn.ianzb.hyperrefine.hook.notification

internal interface ImportanceChannel {
    var importance: Int
    val userLockedFields: Int
    fun lockFields(fields: Int)
    fun unlockFields(fields: Int)
}

/** Preserve the in-memory channel if Settings' backend rejects the update. */
internal object ImportancePersistence {
    private const val USER_LOCKED_IMPORTANCE = 4

    fun persist(channel: ImportanceChannel, next: Int, update: () -> Any?): Boolean {
        val previous = channel.importance
        val locked = channel.userLockedFields
        var persisted = false
        try {
            channel.importance = next
            channel.lockFields(USER_LOCKED_IMPORTANCE)
            // Supports both a void backend and a backend returning a success flag.
            check(update() != false) { "Notification backend rejected importance update" }
            persisted = true
            return true
        } finally {
            if (!persisted) {
                channel.importance = previous
                if (locked and USER_LOCKED_IMPORTANCE == 0) channel.unlockFields(USER_LOCKED_IMPORTANCE)
            }
        }
    }
}
