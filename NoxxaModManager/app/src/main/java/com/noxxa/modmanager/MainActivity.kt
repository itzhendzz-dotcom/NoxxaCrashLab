package com.noxxa.modmanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.noxxa.modmanager.data.ModRegistry
import com.noxxa.modmanager.model.Conflict
import com.noxxa.modmanager.model.InstalledMod
import com.noxxa.modmanager.model.ScanResult
import com.noxxa.modmanager.storage.ModInstaller
import com.noxxa.modmanager.storage.ModScanner
import com.noxxa.modmanager.storage.PathConfig
import com.noxxa.modmanager.ui.NoxxaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var paths: PathConfig
    private lateinit var registry: ModRegistry
    private lateinit var scanner: ModScanner
    private lateinit var installer: ModInstaller

    private var state by mutableStateOf(UiState())

    private val openZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scanUri(uri)
        }
    }

    private val legacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = PathConfig(this)
        registry = ModRegistry(paths)
        scanner = ModScanner(this)
        installer = ModInstaller(this, paths, registry)

        setContent {
            NoxxaApp(
                state = state,
                onImport = { openZip.launch(arrayOf("application/zip", "application/octet-stream")) },
                onGrantAccess = ::requestStorageAccess,
                onRefresh = ::refresh,
                onSavePaths = { data, obb ->
                    paths.save(data, obb)
                    refresh("Paths updated")
                },
                onInstall = ::installScanned,
                onDismissScan = { state = state.copy(scan = null, conflicts = emptyList()) },
                onToggle = ::toggleMod,
                onUninstall = ::uninstallMod,
                onClearMessage = { state = state.copy(message = null, error = null) }
            )
        }
        refresh()
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::paths.isInitialized) refresh()
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(::scanUri)
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }.getOrElse {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun refresh(message: String? = null) {
        state = state.copy(
            hasStorageAccess = hasStorageAccess(),
            dataPath = paths.dataPath(),
            obbPath = paths.obbPath(),
            dataDetected = paths.dataDetected(),
            obbDetected = paths.obbDetected(),
            installedMods = if (hasStorageAccess()) registry.list() else emptyList(),
            message = message ?: state.message
        )
    }

    private fun scanUri(uri: Uri) {
        if (state.busy) return
        state = state.copy(busy = true, error = null, message = "Scanning ZIP…")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { scanner.scan(uri) }
            withContext(Dispatchers.Main) {
                result.onSuccess { scan ->
                    val conflicts = if (hasStorageAccess()) installer.conflicts(scan) else emptyList()
                    state = state.copy(busy = false, scan = scan, conflicts = conflicts, message = null)
                }.onFailure { e ->
                    state = state.copy(busy = false, error = e.message ?: "Could not scan ZIP", message = null)
                }
            }
        }
    }

    private fun installScanned() {
        val scan = state.scan ?: return
        if (!hasStorageAccess()) {
            state = state.copy(error = "Grant Manage all files access before installing.")
            return
        }
        if (!paths.dataDetected() && scan.files.any { it.targetArea.name == "DATA" }) {
            state = state.copy(error = "GTA data root not found. Check Paths first.")
            return
        }
        if (!paths.obbDetected() && scan.files.any { it.targetArea.name == "OBB" }) {
            state = state.copy(error = "GTA OBB root not found. Check Paths first.")
            return
        }
        if (state.conflicts.isNotEmpty()) {
            state = state.copy(error = "Conflict blocked. Disable the owning mod first.")
            return
        }

        state = state.copy(busy = true, message = "Installing ${scan.displayName}…", error = null)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { installer.install(scan) }
            withContext(Dispatchers.Main) {
                result.onSuccess { mod ->
                    state = state.copy(
                        busy = false,
                        scan = null,
                        conflicts = emptyList(),
                        installedMods = registry.list(),
                        message = "${mod.name} installed safely ✓"
                    )
                }.onFailure { e ->
                    state = state.copy(busy = false, error = e.message ?: "Install failed", message = null)
                }
            }
        }
    }

    private fun toggleMod(mod: InstalledMod) {
        if (state.busy) return
        state = state.copy(busy = true, message = if (mod.enabled) "Disabling ${mod.name}…" else "Enabling ${mod.name}…", error = null)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { if (mod.enabled) installer.disable(mod) else installer.enable(mod) }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    state = state.copy(busy = false, installedMods = registry.list(), message = "${mod.name}: ${if (it.enabled) "ACTIVE" else "DISABLED"}")
                }.onFailure { e ->
                    state = state.copy(busy = false, error = e.message ?: "Operation failed", message = null)
                }
            }
        }
    }

    private fun uninstallMod(mod: InstalledMod) {
        if (state.busy) return
        state = state.copy(busy = true, message = "Restoring & uninstalling ${mod.name}…", error = null)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { installer.uninstall(mod) }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    state = state.copy(busy = false, installedMods = registry.list(), message = "${mod.name} removed; originals restored ✓")
                }.onFailure { e ->
                    state = state.copy(busy = false, error = e.message ?: "Uninstall failed", message = null)
                }
            }
        }
    }
}

data class UiState(
    val hasStorageAccess: Boolean = false,
    val dataPath: String = "",
    val obbPath: String = "",
    val dataDetected: Boolean = false,
    val obbDetected: Boolean = false,
    val installedMods: List<InstalledMod> = emptyList(),
    val scan: ScanResult? = null,
    val conflicts: List<Conflict> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null
)
