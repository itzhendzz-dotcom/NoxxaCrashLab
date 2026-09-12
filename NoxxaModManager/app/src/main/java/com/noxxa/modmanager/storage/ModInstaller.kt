package com.noxxa.modmanager.storage

import android.content.Context
import android.net.Uri
import com.noxxa.modmanager.data.ModRegistry
import com.noxxa.modmanager.model.Conflict
import com.noxxa.modmanager.model.InstalledFile
import com.noxxa.modmanager.model.InstalledMod
import com.noxxa.modmanager.model.ScanResult
import com.noxxa.modmanager.model.TargetArea
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class ModInstaller(
    private val context: Context,
    private val paths: PathConfig,
    private val registry: ModRegistry
) {
    companion object {
        private const val MAX_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024
    }

    fun conflicts(scan: ScanResult, ignoreModId: String? = null): List<Conflict> {
        val owners = registry.list().filter { it.enabled && it.id != ignoreModId }
        val result = mutableListOf<Conflict>()
        scan.files.forEach { sf ->
            owners.forEach { mod ->
                val scanTarget = targetFile(sf.targetArea, sf.relativeTarget).absolutePath
                if (mod.files.any { owned ->
                        if (owned.absoluteTarget.isNotBlank()) owned.absoluteTarget == scanTarget
                        else owned.relativeTarget == sf.relativeTarget && owned.targetArea == sf.targetArea
                    }) {
                    result += Conflict(sf.relativeTarget, sf.targetArea, mod.id, mod.name)
                }
            }
        }
        return result.distinctBy { Triple(it.relativeTarget, it.targetArea, it.ownerId) }
    }

    fun install(scan: ScanResult): InstalledMod {
        require(conflicts(scan).isEmpty()) { "Conflicting enabled mod detected. Disable it first." }
        paths.ensureWorkspace()
        val id = slug(scan.displayName) + "-" + UUID.randomUUID().toString().take(8)
        val packageDir = File(paths.packagesDir(), id)
        val backupDir = File(paths.backupsDir(), id)
        packageDir.mkdirs(); backupDir.mkdirs()

        var installedFiles: List<InstalledFile>? = null
        try {
            extractPayload(scan, packageDir)
            installedFiles = applyTransaction(scan, packageDir, backupDir)
            val mod = InstalledMod(
                id = id,
                name = scan.displayName.removeSuffix(".zip").removeSuffix(".ZIP"),
                installedAt = System.currentTimeMillis(),
                enabled = true,
                categories = scan.categories,
                files = installedFiles
            )
            registry.save(mod)
            return mod
        } catch (t: Throwable) {
            installedFiles?.let { rollbackTouched(it.asReversed(), backupDir) }
            packageDir.deleteRecursively()
            backupDir.deleteRecursively()
            throw t
        }
    }

    private fun extractPayload(scan: ScanResult, packageDir: File) {
        val bySource = scan.files.associateBy { normalizeName(it.sourceEntry) }
        var extracted = 0L
        context.contentResolver.openInputStream(Uri.parse(scan.sourceUri))?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val sf = bySource[normalizeName(entry.name)] ?: continue
                    val areaDir = File(packageDir, sf.targetArea.name.lowercase())
                    val out = safeChild(areaDir, sf.relativeTarget)
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n <= 0) break
                            extracted += n
                            if (extracted > MAX_EXTRACTED_BYTES) error("ZIP extraction exceeded safety limit")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
        } ?: error("Cannot reopen ZIP")

        scan.files.forEach { sf ->
            val payload = payloadFile(packageDir, sf.targetArea, sf.relativeTarget)
            require(payload.isFile) { "Missing extracted payload: ${sf.relativeTarget}" }
        }
    }

    private fun applyTransaction(scan: ScanResult, packageDir: File, backupDir: File): List<InstalledFile> {
        val records = mutableListOf<InstalledFile>()
        val touched = mutableListOf<InstalledFile>()
        try {
            scan.files.forEach { sf ->
                val dest = targetFile(sf.targetArea, sf.relativeTarget)
                val payload = payloadFile(packageDir, sf.targetArea, sf.relativeTarget)
                require(!dest.exists() || dest.isFile) { "Target is a directory, not a file: ${sf.relativeTarget}" }
                val hadOriginal = dest.isFile
                val originalHash = if (hadOriginal) Hashing.sha256(dest) else null
                if (hadOriginal) {
                    val backup = backupFile(backupDir, sf.targetArea, sf.relativeTarget)
                    backup.parentFile?.mkdirs()
                    dest.copyTo(backup, overwrite = true)
                }
                val record = InstalledFile(
                    relativeTarget = sf.relativeTarget,
                    targetArea = sf.targetArea,
                    absoluteTarget = dest.absolutePath,
                    hadOriginal = hadOriginal,
                    originalSha256 = originalHash,
                    payloadSha256 = Hashing.sha256(payload)
                )
                touched += record
                dest.parentFile?.mkdirs()
                payload.copyTo(dest, overwrite = true)
                records += record
            }
            return records
        } catch (t: Throwable) {
            rollbackTouched(touched.asReversed(), backupDir)
            throw t
        }
    }

    private fun rollbackTouched(records: List<InstalledFile>, backupDir: File) {
        records.forEach { record ->
            runCatching {
                val dest = targetFile(record)
                if (record.hadOriginal) {
                    val backup = backupFile(backupDir, record.targetArea, record.relativeTarget)
                    if (backup.isFile) {
                        dest.parentFile?.mkdirs()
                        backup.copyTo(dest, overwrite = true)
                    }
                } else {
                    dest.delete()
                }
            }
        }
    }

    fun disable(mod: InstalledMod): InstalledMod {
        if (!mod.enabled) return mod
        verifyActiveState(mod)
        val backupDir = File(paths.backupsDir(), mod.id)
        mod.files.asReversed().forEach { record ->
            val dest = targetFile(record)
            if (record.hadOriginal) {
                val backup = backupFile(backupDir, record.targetArea, record.relativeTarget)
                require(backup.isFile) { "Backup missing: ${record.relativeTarget}" }
                if (record.originalSha256 != null) {
                    require(Hashing.sha256(backup) == record.originalSha256) { "Backup hash mismatch: ${record.relativeTarget}" }
                }
                dest.parentFile?.mkdirs()
                backup.copyTo(dest, overwrite = true)
            } else {
                dest.delete()
            }
        }
        return mod.copy(enabled = false).also(registry::save)
    }

    fun enable(mod: InstalledMod): InstalledMod {
        if (mod.enabled) return mod
        val owners = registry.list().filter { it.enabled && it.id != mod.id }
        val conflict = mod.files.firstOrNull { mine ->
            val myTarget = targetFile(mine).absolutePath
            owners.any { owner -> owner.files.any { targetFile(it).absolutePath == myTarget } }
        }
        require(conflict == null) { "Another enabled mod now owns: ${conflict?.relativeTarget}" }

        val packageDir = File(paths.packagesDir(), mod.id)
        mod.files.forEach { record ->
            val dest = targetFile(record)
            if (record.hadOriginal) {
                require(dest.isFile) { "Original target disappeared while mod was disabled: ${record.relativeTarget}" }
                if (record.originalSha256 != null) {
                    val current = Hashing.sha256(dest)
                    require(current == record.originalSha256) {
                        "Target changed while mod was disabled: ${record.relativeTarget}"
                    }
                }
            } else {
                require(!dest.exists()) { "A new file appeared while mod was disabled: ${record.relativeTarget}" }
            }
            val payload = payloadFile(packageDir, record.targetArea, record.relativeTarget)
            require(payload.isFile) { "Stored payload missing: ${record.relativeTarget}" }
            dest.parentFile?.mkdirs()
            payload.copyTo(dest, overwrite = true)
        }
        return mod.copy(enabled = true).also(registry::save)
    }

    private fun verifyActiveState(mod: InstalledMod) {
        mod.files.forEach { record ->
            val dest = targetFile(record)
            require(dest.isFile) { "Managed file is missing: ${record.relativeTarget}" }
            require(Hashing.sha256(dest) == record.payloadSha256) {
                "Managed file was changed outside Noxxa: ${record.relativeTarget}"
            }
        }
    }

    fun uninstall(mod: InstalledMod) {
        val current = registry.find(mod.id) ?: mod
        if (current.enabled) disable(current)
        File(paths.packagesDir(), mod.id).deleteRecursively()
        File(paths.backupsDir(), mod.id).deleteRecursively()
        registry.remove(mod.id)
    }

    private fun targetFile(record: InstalledFile): File =
        if (record.absoluteTarget.isNotBlank()) File(record.absoluteTarget)
        else targetFile(record.targetArea, record.relativeTarget)

    private fun targetFile(area: TargetArea, relative: String): File = safeChild(
        if (area == TargetArea.DATA) paths.dataRoot() else paths.obbRoot(),
        relative
    )

    private fun payloadFile(packageDir: File, area: TargetArea, relative: String): File =
        safeChild(File(packageDir, area.name.lowercase()), relative)

    private fun backupFile(backupDir: File, area: TargetArea, relative: String): File =
        safeChild(File(backupDir, area.name.lowercase()), relative)

    private fun safeChild(root: File, relative: String): File {
        val base = root.canonicalFile
        val child = File(base, relative).canonicalFile
        require(child.path == base.path || child.path.startsWith(base.path + File.separator)) { "Unsafe path: $relative" }
        return child
    }

    private fun slug(name: String): String = name.lowercase()
        .replace(Regex("\\.zip$"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(32)
        .ifBlank { "mod" }

    private fun normalizeName(name: String): String = name.replace('\\', '/').trimStart('/')
}
