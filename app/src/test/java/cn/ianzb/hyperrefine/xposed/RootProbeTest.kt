package cn.ianzb.hyperrefine.xposed

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class RootProbeTest {
    private class ProbeProcess(val text: String, var completed: Boolean = true, val exit: Int = 0) : Process() {
        var destroyed = false
        var inputClosed = false
        var outputClosed = false
        var timeoutMs = 0L
        val input = object : ByteArrayInputStream(text.toByteArray()) {
            override fun close() { inputClosed = true; super.close() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = output
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timeoutMs = unit.toMillis(timeout)
            return completed
        }
        override fun exitValue(): Int = if (completed) exit else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = !completed
        override fun destroy() { destroyed = true; completed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun grantedRootRequiresSuccessfulUidZeroOutputAndClosesStreams() {
        val process = ProbeProcess("uid=0(root) gid=0(root)\n")
        assertTrue(RootProbe.check({ process }, 250))
        assertEquals(250, process.timeoutMs)
        assertTrue(process.inputClosed)
        assertTrue(process.outputClosed)
        assertFalse(process.destroyed)
    }

    @Test fun denialAndNonRootOutputRemainUnavailable() {
        assertFalse(RootProbe.check({ ProbeProcess("permission denied", exit = 1) }))
        assertFalse(RootProbe.check({ ProbeProcess("uid=2000(shell) gid=2000(shell)") }))
        assertFalse(RootProbe.check({ ProbeProcess("uid=0(root)", exit = 1) }))
    }

    @Test fun permissionTimeoutDestroysTheProbeAndClosesItsStreams() {
        val process = ProbeProcess("", completed = false)
        assertFalse(RootProbe.check({ process }, 10))
        assertEquals(10, process.timeoutMs)
        assertTrue(process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.outputClosed)
    }

    @Test fun rootResultIsNotPersistedAcrossSeparateProbes() {
        assertTrue(RootProbe.check({ ProbeProcess("uid=0(root)") }))
        assertFalse(RootProbe.check({ ProbeProcess("root revoked", exit = 1) }))
        assertFalse(RootProbe.check({ throw java.io.IOException("su missing") }))
    }
}
