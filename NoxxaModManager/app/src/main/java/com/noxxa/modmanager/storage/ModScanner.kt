package com.noxxa.modmanager.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.noxxa.modmanager.model.ModCategory
import com.noxxa.modmanager.model.ScanResult
import com.noxxa.modmanager.model.ScannedFile
import com.noxxa.modmanager.model.TargetArea
import java.util.zip.ZipInputStream

class ModScanner(private val context: Context) {
    companion object {
        const val MAX_FILES = 10_000
        const val MAX_DECLARED_UNCOMPRESSED = 4L * 1024 * 1024 * 1024
    }

    fun scan(uri: Uri): ScanResult {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(resolver, uri) ?: "Imported Mod.zip"
        val files = mutableListOf<ScannedFile>()
        val skipped = mutableListOf<String>()
        var declaredTotal = 0L

        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (files.size >= MAX_FILES) error("ZIP has more than $MAX_FILES files")

                    val normalized = normalizeEntry(entry.name)
                    if (normalized == null) {
                        skipped += entry.name
                        continue
                    }

                    if (entry.size > 0) {
                        declaredTotal += entry.size
                        if (declaredTotal > MAX_DECLARED_UNCOMPRESSED) error("ZIP is too large to install safely")
                    }

                    val (relative, area) = mapTarget(normalized)
                    if (relative.isBlank() || relative.contains("../") || relative.startsWith("../")) {
                        skipped += entry.name
                        continue
                    }
                    files += ScannedFile(
                        sourceEntry = entry.name,
                        relativeTarget = relative,
                        size = entry.size.coerceAtLeast(0),
                        category = classify(relative),
                        targetArea = area
                    )
                }
            }
        } ?: error("Cannot open selected ZIP")

        if (files.isEmpty()) error("No installable files found in ZIP")
        val duplicateTargets = files.groupBy { it.targetArea to it.relativeTarget.lowercase() }.filterValues { it.size > 1 }
        if (duplicateTargets.isNotEmpty()) {
            error("ZIP maps multiple entries to the same target: ${duplicateTargets.keys.first().second}")
        }
        val categories = files.map { it.category }.toSet().let { if (it.size > 1) it + ModCategory.MIXED else it }
        return ScanResult(displayName, uri.toString(), files, skipped, categories)
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun normalizeEntry(raw: String): String? {
        var path = raw.replace('\\', '/').trimStart('/')
        if (path.isBlank()) return null
        if (path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith(".")) return null
        val pieces = path.split('/').filter { it.isNotBlank() }
        if (pieces.any { it == ".." }) return null
        path = pieces.joinToString("/")
        return path
    }

    private fun mapTarget(path: String): Pair<String, TargetArea> {
        val lower = path.lowercase()
        val packageName = "com.rockstargames.gtasa"

        val dataNeedle = "android_unprotected/data/$packageName/"
        val obbNeedle = "android_unprotected/obb/$packageName/"
        val dataIdx = lower.indexOf(dataNeedle)
        if (dataIdx >= 0) return path.substring(dataIdx + dataNeedle.length) to TargetArea.DATA
        val obbIdx = lower.indexOf(obbNeedle)
        if (obbIdx >= 0) return path.substring(obbIdx + obbNeedle.length) to TargetArea.OBB

        val androidDataNeedle = "android/data/$packageName/"
        val androidObbNeedle = "android/obb/$packageName/"
        val adIdx = lower.indexOf(androidDataNeedle)
        if (adIdx >= 0) return path.substring(adIdx + androidDataNeedle.length) to TargetArea.DATA
        val aoIdx = lower.indexOf(androidObbNeedle)
        if (aoIdx >= 0) return path.substring(aoIdx + androidObbNeedle.length) to TargetArea.OBB

        val pkgNeedle = "$packageName/"
        val pkgIdx = lower.indexOf(pkgNeedle)
        if (pkgIdx >= 0) {
            val before = lower.substring(0, pkgIdx)
            val area = if (before.contains("obb")) TargetArea.OBB else TargetArea.DATA
            return path.substring(pkgIdx + pkgNeedle.length) to area
        }

        val rockstarMatch = Regex("(?i)com\\.rockstargames[^/]*/").find(path)
        if (rockstarMatch != null) {
            val before = lower.substring(0, rockstarMatch.range.first)
            val area = if (before.contains("obb")) TargetArea.OBB else TargetArea.DATA
            return path.substring(rockstarMatch.range.last + 1) to area
        }

        if (lower.startsWith("obb/")) return path.substring(4) to TargetArea.OBB
        if (lower.startsWith("data/")) return path.substring(5) to TargetArea.DATA
        return path to if (lower.endsWith(".obb")) TargetArea.OBB else TargetArea.DATA
    }

    private fun classify(path: String): ModCategory {
        val p = path.lowercase()
        val ext = p.substringAfterLast('.', "")
        return when {
            ext == "so" -> ModCategory.NATIVE
            ext == "cs" || ext == "csa" -> ModCategory.CLEO
            ext == "ifp" -> ModCategory.ANIMATION
            ext in setOf("dff", "txd", "img", "col", "ipl", "ide") -> ModCategory.ASSET
            ext == "obb" -> ModCategory.OBB
            ext in setOf("dat", "ini", "cfg", "xml", "json", "txt") -> ModCategory.CONFIG
            p.contains("shader") || ext in setOf("fsh", "vsh", "fx") -> ModCategory.GRAPHICS
            else -> ModCategory.UNKNOWN
        }
    }
}
