package com.noxxa.modmanager.data

import com.noxxa.modmanager.model.InstalledMod
import com.noxxa.modmanager.storage.PathConfig
import org.json.JSONObject
import java.io.File

class ModRegistry(private val paths: PathConfig) {
    init { paths.ensureWorkspace() }

    fun list(): List<InstalledMod> = paths.registryDir().listFiles { f -> f.extension == "json" }
        ?.mapNotNull { file -> runCatching { InstalledMod.fromJson(JSONObject(file.readText())) }.getOrNull() }
        ?.sortedByDescending { it.installedAt }
        ?: emptyList()

    fun find(id: String): InstalledMod? {
        val f = File(paths.registryDir(), "$id.json")
        if (!f.isFile) return null
        return runCatching { InstalledMod.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(mod: InstalledMod) {
        paths.ensureWorkspace()
        val target = File(paths.registryDir(), "${mod.id}.json")
        val temp = File(paths.registryDir(), "${mod.id}.json.tmp")
        temp.writeText(mod.toJson().toString(2))
        if (target.exists() && !target.delete()) error("Could not replace registry entry")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun remove(id: String) {
        File(paths.registryDir(), "$id.json").delete()
    }
}
