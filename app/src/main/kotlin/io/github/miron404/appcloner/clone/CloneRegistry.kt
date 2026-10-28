package io.github.miron404.appcloner.clone

import android.content.Context
import io.github.miron404.appcloner.core.writeBytesAtomically
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * The clones this app has built.
 *
 * Nothing secret lives here: it is package names, labels and the version each clone was built
 * from. That last field is the whole point of the file — it is what "an update is available" is
 * measured against, and it is the only place that knowledge exists, because the clone itself has
 * no link back to the app it came from.
 */
class CloneRegistry(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Held across every read-modify-write. A build finishing in the application scope and the user
     * forgetting a clone on the main thread would otherwise each write back the list as they read
     * it, and whichever wrote second would undo the other.
     */
    private val lock = Any()

    fun list(): List<CloneRecord> = read().sortedBy { it.cloneLabel.lowercase() }

    fun get(id: String): CloneRecord? = read().firstOrNull { it.id == id }

    fun put(record: CloneRecord) = synchronized(lock) {
        write(read().filterNot { it.id == record.id } + record)
    }

    fun remove(id: String) = synchronized(lock) {
        write(read().filterNot { it.id == id })
    }

    /** Joins each record with what the package manager says about it right now. */
    fun statuses(context: Context): List<CloneStatus> = list().map { record ->
        val source = ApkSources.versionOf(context, record.source.packageName)
        CloneStatus(
            record = record,
            sourceVersionCode = source?.first,
            sourceVersionName = source?.second,
            installedVersionCode = ApkSources.versionOf(context, record.clonePackage)?.first,
        )
    }

    /**
     * The records that can be read, each on its own.
     *
     * Decoding the list as a whole would make one record this version cannot read — an enum value
     * since removed, a field whose type changed — cost all of them, and the next [put] would then
     * write that empty list over the file. So a bad record drops only itself, and whenever anything
     * is dropped the file as it stood is copied aside first, where it can still be recovered from.
     */
    private fun read(): List<CloneRecord> {
        synchronized(lock) {
            if (!file.isFile) return emptyList()
            val text = file.readText()
            val elements = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            val records = elements.orEmpty().mapNotNull { element ->
                runCatching { json.decodeFromJsonElement<CloneRecord>(element) }.getOrNull()
            }
            if (elements == null || records.size < elements.size) preserve(text)
            return records
        }
    }

    /**
     * Copies a file that could not be read in full to `clones.json.unreadable-<hash>`.
     *
     * Named after its contents, so reading the same broken file over and over keeps one copy of
     * it rather than one per read, while a file that breaks differently later gets its own.
     */
    private fun preserve(text: String) {
        val hash = text.hashCode().toUInt().toString(16)
        val copy = File(file.absoluteFile.parentFile, "${file.name}.$UNREADABLE-$hash")
        if (!copy.exists()) runCatching { copy.writeText(text) }
    }

    private fun write(records: List<CloneRecord>) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeBytesAtomically(json.encodeToString(records).toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** Infix of the copies [read] keeps of a file it could not read in full. */
        const val UNREADABLE = "unreadable"
    }
}
