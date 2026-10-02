package io.github.miron404.appcloner.clone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * Somewhere dex files can be rewritten.
 *
 * On a device that is a process of its own ([RemoteDexWorker]). The object model of one large dex
 * can take most of what a phone allows an app, and in the same process as the screen it had to
 * share that with the resource table being rewritten and with whatever the UI was holding — so
 * leaving the app mid-build and coming back was enough to exhaust the heap, and the thread that
 * paid for it was the one drawing the screen. In a process of its own the model has the whole heap,
 * and running out of it ends that process rather than this one.
 *
 * Tests use [InProcessDexWorker], which does the same work through the same [DexJobRunner].
 */
interface DexWorker : AutoCloseable {
    /** The most heap the process doing the rewrite will ever be given. */
    val heapLimit: Long

    suspend fun rewrite(job: DexJob): DexOutcome
}

/**
 * One dex to rewrite, described by paths so the description can cross into another process.
 *
 * Both processes run as the same user, so whatever one can read the other can too, and nothing
 * larger than this ever crosses the boundary: a binder transaction tops out at about a megabyte,
 * which a single dex exceeds many times over.
 */
@Serializable
class DexJob(
    val apk: String,
    /** The entry within [apk], such as `classes2.dex`. */
    val entry: String,
    val oldPackage: String,
    val newPackage: String,
    /** A [DexRewriter.ClassIndex] as written by [DexRewriter.ClassIndex.write]. */
    val classIndex: String,
    /** Written only when [DexOutcome.written] says so. */
    val output: String,
)

/** What became of a [DexJob]. When it did not work, exactly one of the failure fields is set. */
@Serializable
class DexOutcome(
    val written: Boolean = false,
    val changed: Int = 0,
    val skipped: Int = 0,
    /** The rewrite threw [OutOfMemoryError] and the worker lived to say so. */
    val outOfMemory: Boolean = false,
    /** The process doing the rewrite went away before it answered. */
    val workerDied: Boolean = false,
    val error: String? = null,
)

/**
 * The result of this outcome for [job], or an exception that says in the user's terms why there is
 * none. [dexBytes] may be zero when the archive did not declare the entry's size.
 */
fun DexOutcome.requireRewritten(job: DexJob, dexBytes: Long, heapLimit: Long): DexRewriter.Rewritten {
    val apkName = File(job.apk).name
    return when {
        outOfMemory || workerDied -> throw IllegalStateException(
            DexRewriter.ranOutMessage(job.entry, apkName, dexBytes, heapLimit, died = workerDied)
        )
        error != null -> throw IllegalStateException("Could not rewrite ${job.entry} in $apkName: $error")
        else -> DexRewriter.Rewritten(written, changed, skipped)
    }
}

/** How a [DexJob] and its [DexOutcome] are written down to cross a process boundary. */
object DexWorkerProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(job: DexJob): String = json.encodeToString(job)
    fun decodeJob(text: String): DexJob = json.decodeFromString(text)

    fun encode(outcome: DexOutcome): String = json.encodeToString(outcome)
    fun decodeOutcome(text: String): DexOutcome = json.decodeFromString(text)
}

/**
 * Carries out a [DexJob] in whichever process calls it: the body of every [DexWorker].
 *
 * It never throws. Whatever goes wrong comes back as a [DexOutcome], because in the worker process
 * an exception has nowhere useful to go — the caller is on the other side of a binder call and
 * needs an answer it can turn into a message.
 */
class DexJobRunner {
    private var indexPath: String? = null
    private var index: DexRewriter.ClassIndex? = null

    @Synchronized
    fun run(job: DexJob): DexOutcome {
        return try {
            val index = indexFor(job.classIndex)
            ZipFile(job.apk).use { zip ->
                val entry = zip.getEntry(job.entry)
                    ?: return DexOutcome(error = "${job.entry} is not in ${File(job.apk).name}")
                val result = zip.getInputStream(entry).use { stream ->
                    DexRewriter.rewrite(
                        input = stream,
                        oldPackage = job.oldPackage,
                        newPackage = job.newPackage,
                        index = index,
                        output = File(job.output),
                    )
                }
                DexOutcome(written = result.written, changed = result.changed, skipped = result.skipped)
            }
        } catch (_: OutOfMemoryError) {
            // The model is unreachable once this unwinds, so there is heap enough to answer.
            DexOutcome(outOfMemory = true)
        } catch (error: Throwable) {
            DexOutcome(error = error.message ?: error.javaClass.simpleName)
        }
    }

    /** Every dex of one build shares one index, so it is read once rather than once per dex. */
    private fun indexFor(path: String): DexRewriter.ClassIndex {
        index?.takeIf { indexPath == path }?.let { return it }
        return DexRewriter.ClassIndex.read(File(path)).also {
            index = it
            indexPath = path
        }
    }
}

/** Rewrites in the calling process. What the tests use, and what a JVM without Android has. */
class InProcessDexWorker : DexWorker {
    private val runner = DexJobRunner()

    override val heapLimit: Long get() = DexRewriter.heapLimit()

    override suspend fun rewrite(job: DexJob): DexOutcome =
        withContext(Dispatchers.IO) { runner.run(job) }

    override fun close() = Unit
}
