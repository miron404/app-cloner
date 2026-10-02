package io.github.miron404.appcloner

import io.github.miron404.appcloner.clone.DexJob
import io.github.miron404.appcloner.clone.DexJobRunner
import io.github.miron404.appcloner.clone.DexOutcome
import io.github.miron404.appcloner.clone.DexRewriter
import io.github.miron404.appcloner.clone.DexWorkerProtocol
import io.github.miron404.appcloner.clone.requireRewritten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything that crosses into the dex worker's process, checked without that process.
 *
 * A JVM test cannot stand in for a binder, so the service itself is a few lines that read a string
 * and write one back. What those strings say, how the class index travels, and how the runner
 * behaves when the job is impossible are all here instead — the parts that would otherwise only
 * fail on a phone, mid-build.
 */
class DexWorkerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val megabyte = 1024L * 1024

    @Test
    fun `the class index survives the trip through a file`() {
        val names = setOf(
            "com.old.Plain",
            "com.old.Inner\$Nested",
            // A space has been legal in a simple name since dex 040, so lines must not be trimmed.
            "com.old.Has Space",
            "com.old.Ünïcödé",
            // Outside the basic multilingual plane: a surrogate pair in a Kotlin string.
            "com.old.😀",
        )
        val file = temporaryFolder.newFile("index.txt")

        DexRewriter.ClassIndex(names).write(file)
        val read = DexRewriter.ClassIndex.read(file)

        assertEquals(names.size, read.size)
        for (name in names) assertTrue(name, name in read)
        assertFalse("com.old.Has" in read)
    }

    @Test
    fun `an empty index is still an index`() {
        val file = temporaryFolder.newFile("empty.txt")
        DexRewriter.ClassIndex(emptySet()).write(file)
        assertEquals(0, DexRewriter.ClassIndex.read(file).size)
    }

    @Test
    fun `a job and its outcome come through the protocol intact`() {
        val job = job(apk = "/data/app/x/base.apk", entry = "classes2.dex")
        val decoded = DexWorkerProtocol.decodeJob(DexWorkerProtocol.encode(job))

        assertEquals(job.apk, decoded.apk)
        assertEquals(job.entry, decoded.entry)
        assertEquals(job.oldPackage, decoded.oldPackage)
        assertEquals(job.newPackage, decoded.newPackage)
        assertEquals(job.classIndex, decoded.classIndex)
        assertEquals(job.output, decoded.output)

        val outcome = DexOutcome(written = true, changed = 7, skipped = 2)
        val back = DexWorkerProtocol.decodeOutcome(DexWorkerProtocol.encode(outcome))
        assertTrue(back.written)
        assertEquals(7, back.changed)
        assertEquals(2, back.skipped)
        assertFalse(back.outOfMemory)
        assertFalse(back.workerDied)
        assertNull(back.error)
    }

    /** In the worker process an exception has nowhere to go, so it has to come back as an answer. */
    @Test
    fun `a dex the apk does not contain is an answer, not an exception`() {
        val apk = zip("only-a-manifest.apk", "AndroidManifest.xml" to byteArrayOf(0))
        val outcome = DexJobRunner().run(job(apk = apk.path, entry = "classes.dex"))

        assertNotNull(outcome.error)
        val error = outcome.error.orEmpty()
        assertTrue(error, error.contains("classes.dex"))
        assertFalse(outcome.written)
    }

    @Test
    fun `an apk that is not an archive is an answer, not an exception`() {
        val notAZip = temporaryFolder.newFile("garbage.apk").apply { writeText("not a zip") }
        val outcome = DexJobRunner().run(job(apk = notAZip.path, entry = "classes.dex"))

        assertNotNull(outcome.error)
        assertFalse(outcome.outOfMemory)
    }

    @Test
    fun `running out of memory becomes a refusal naming the dex`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            DexOutcome(outOfMemory = true)
                .requireRewritten(job(entry = "classes3.dex"), 18 * megabyte, 512 * megabyte)
        }
        val message = failure.message.orEmpty()

        assertTrue(message, message.contains("classes3.dex"))
        assertTrue(message, message.contains("base.apk"))
        assertTrue(message, message.contains("18 MB"))
        assertTrue(message, message.contains("ran out"))
        assertTrue(message, message.contains("512 MB"))
        assertTrue(message, message.contains("deep rename"))
    }

    /** The process vanishing is reported as what it most likely was, without claiming certainty. */
    @Test
    fun `a worker that died is reported as stopped`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            DexOutcome(workerDied = true)
                .requireRewritten(job(entry = "classes.dex"), 0, 512 * megabyte)
        }
        val message = failure.message.orEmpty()

        assertTrue(message, message.contains("stopped before it finished"))
        assertFalse("an unknown size must not be printed as 0 MB", message.contains("0 MB)"))
        assertTrue(message, message.contains("deep rename"))
    }

    @Test
    fun `any other failure carries its own reason`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            DexOutcome(error = "bad magic").requireRewritten(job(), 1, 512 * megabyte)
        }
        assertTrue(failure.message, failure.message.orEmpty().contains("bad magic"))
    }

    @Test
    fun `a successful outcome is passed through`() {
        val result = DexOutcome(written = true, changed = 3, skipped = 1)
            .requireRewritten(job(), 1, 512 * megabyte)

        assertTrue(result.written)
        assertEquals(3, result.changed)
        assertEquals(1, result.skipped)
    }

    private fun job(apk: String = "/tmp/base.apk", entry: String = "classes.dex") = DexJob(
        apk = apk,
        entry = entry,
        oldPackage = "com.old",
        newPackage = "com.old.clone2",
        classIndex = File(temporaryFolder.root, "job-index.txt")
            .apply { if (!exists()) DexRewriter.ClassIndex(emptySet()).write(this) }
            .path,
        output = File(temporaryFolder.root, "out-$entry").path,
    )

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = File(temporaryFolder.root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entryName, bytes) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }
}
