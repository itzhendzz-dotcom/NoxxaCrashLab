package com.noxxa.modmanager.storage

import android.content.Context
import android.os.Environment
import java.io.File

class PathConfig(private val context: Context) {
    private val prefs = context.getSharedPreferences("noxxa_paths", Context.MODE_PRIVATE)

    private val storageRoot: File
        get() = Environment.getExternalStorageDirectory()

    private fun discoverRockstarRoot(kind: String): File {
        val base = File(storageRoot, "Android_unprotected/$kind")
        val preferred = File(base, "com.rockstargames.gtasa")
        if (preferred.isDirectory) return preferred
        val candidate = base.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("com.rockstargames", ignoreCase = true) }
            ?.sortedWith(compareByDescending<File> { it.name.contains("gtasa", ignoreCase = true) }.thenBy { it.name })
            ?.firstOrNull()
        return candidate ?: preferred
    }

    fun defaultDataPath(): String = discoverRockstarRoot("data").absolutePath
    fun defaultObbPath(): String = discoverRockstarRoot("obb").absolutePath

    fun dataPath(): String = prefs.getString("data_path", null) ?: defaultDataPath()
    fun obbPath(): String = prefs.getString("obb_path", null) ?: defaultObbPath()

    fun save(dataPath: String, obbPath: String) {
        prefs.edit().putString("data_path", dataPath.trim()).putString("obb_path", obbPath.trim()).apply()
    }

    fun dataRoot() = File(dataPath())
    fun obbRoot() = File(obbPath())

    fun dataDetected() = dataRoot().isDirectory
    fun obbDetected() = obbRoot().isDirectory

    fun workspaceRoot(): File = File(storageRoot, "NoxxaModManager")
    fun registryDir(): File = File(workspaceRoot(), "registry")
    fun packagesDir(): File = File(workspaceRoot(), "packages")
    fun backupsDir(): File = File(workspaceRoot(), "backups")
    fun stagingDir(): File = File(workspaceRoot(), "staging")

    fun ensureWorkspace() {
        listOf(workspaceRoot(), registryDir(), packagesDir(), backupsDir(), stagingDir()).forEach { it.mkdirs() }
    }
}
