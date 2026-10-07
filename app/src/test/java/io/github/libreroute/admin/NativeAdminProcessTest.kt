package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeAdminProcessTest {
    private fun runFixture(mode: String, timeout: Long = 10): Pair<Int, String> {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable")
        val classes = File(NativeAdminProcessFixture::class.java.protectionDomain!!.codeSource.location.toURI())
        return NativeAdminProcess.run(
            listOf(java.absolutePath, "-cp", classes.absolutePath, NativeAdminProcessFixture::class.java.name, mode),
            File(System.getProperty("java.io.tmpdir")), "{}", timeout
        )
    }

    @Test fun drainsFullErrorPipeWithoutMixingItIntoJson() {
        val (code, output) = runFixture("ready")
        assertEquals(0, code)
        assertEquals("{\"status\":\"ready\"}", output)
    }

    @Test fun terminatesCommandThatDoesNotRespond() {
        val (code, output) = runFixture("timeout", 1)
        assertEquals(-1, code)
        assertTrue(output.contains("Тайм-аут"))
    }

    @Test fun rejectsTruncatedResponseAfterDrainingIt() {
        assertEquals(-2, runFixture("overflow").first)
    }
}
