package com.noxxa.modmanager.model

import org.json.JSONArray
import org.json.JSONObject

enum class ModCategory(val label: String) {
    GRAPHICS("Graphics"),
    ANIMATION("Animation"),
    NATIVE("Native Plugin"),
    CLEO("CLEO"),
    ASSET("Model / Texture"),
    CONFIG("Config / Data"),
    OBB("OBB"),
    MIXED("Mixed"),
    UNKNOWN("Unknown")
}

data class ScannedFile(
    val sourceEntry: String,
    val relativeTarget: String,
    val size: Long,
    val category: ModCategory,
    val targetArea: TargetArea = TargetArea.DATA
)

enum class TargetArea { DATA, OBB }

data class ScanResult(
    val displayName: String,
    val sourceUri: String,
    val files: List<ScannedFile>,
    val skipped: List<String>,
    val categories: Set<ModCategory>
)

data class InstalledFile(
    val relativeTarget: String,
    val targetArea: TargetArea,
    val absoluteTarget: String,
    val hadOriginal: Boolean,
    val originalSha256: String?,
    val payloadSha256: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("relativeTarget", relativeTarget)
        put("targetArea", targetArea.name)
        put("absoluteTarget", absoluteTarget)
        put("hadOriginal", hadOriginal)
        put("originalSha256", originalSha256)
        put("payloadSha256", payloadSha256)
    }

    companion object {
        fun fromJson(o: JSONObject) = InstalledFile(
            relativeTarget = o.getString("relativeTarget"),
            targetArea = TargetArea.valueOf(o.optString("targetArea", TargetArea.DATA.name)),
            absoluteTarget = o.optString("absoluteTarget"),
            hadOriginal = o.optBoolean("hadOriginal", false),
            originalSha256 = o.optString("originalSha256").takeIf { it.isNotBlank() && it != "null" },
            payloadSha256 = o.optString("payloadSha256")
        )
    }
}

data class InstalledMod(
    val id: String,
    val name: String,
    val installedAt: Long,
    val enabled: Boolean,
    val categories: Set<ModCategory>,
    val files: List<InstalledFile>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("installedAt", installedAt)
        put("enabled", enabled)
        put("categories", JSONArray().apply { categories.forEach { put(it.name) } })
        put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): InstalledMod {
            val cats = mutableSetOf<ModCategory>()
            val ca = o.optJSONArray("categories") ?: JSONArray()
            for (i in 0 until ca.length()) {
                runCatching { ModCategory.valueOf(ca.getString(i)) }.getOrNull()?.let(cats::add)
            }
            val files = mutableListOf<InstalledFile>()
            val fa = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until fa.length()) files += InstalledFile.fromJson(fa.getJSONObject(i))
            return InstalledMod(
                id = o.getString("id"),
                name = o.getString("name"),
                installedAt = o.optLong("installedAt"),
                enabled = o.optBoolean("enabled", true),
                categories = cats,
                files = files
            )
        }
    }
}

data class Conflict(
    val relativeTarget: String,
    val targetArea: TargetArea,
    val ownerId: String,
    val ownerName: String
)
