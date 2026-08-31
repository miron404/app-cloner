package io.github.miron404.appcloner.clone

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    fun list(): List<CloneRecord> = read().sortedBy { it.cloneLabel.lowercase() }

    fun get(id: String): CloneRecord? = read().firstOrNull { it.id == id }

    fun put(record: CloneRecord) {
        write(read().filterNot { it.id == record.id } + record)
    }

    fun remove(id: String) {
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

    private fun read(): List<CloneRecord> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<List<CloneRecord>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun write(records: List<CloneRecord>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(records))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}
