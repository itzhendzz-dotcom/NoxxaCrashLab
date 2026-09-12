package com.noxxa.modmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noxxa.modmanager.UiState
import com.noxxa.modmanager.model.InstalledMod
import com.noxxa.modmanager.model.ScanResult
import com.noxxa.modmanager.model.TargetArea
import java.text.DateFormat
import java.util.Date

private val NoxxaGreen = Color(0xFFB7FF4A)
private val NoxxaBg = Color(0xFF090B0D)
private val NoxxaSurface = Color(0xFF11151A)
private val NoxxaSurface2 = Color(0xFF171D23)
private val NoxxaText = Color(0xFFE8EAED)
private val NoxxaMuted = Color(0xFF8A949E)
private val NoxxaDanger = Color(0xFFFF6B6B)
private val NoxxaWarning = Color(0xFFFFD166)

private val colors: ColorScheme = darkColorScheme(
    primary = NoxxaGreen,
    onPrimary = Color(0xFF101400),
    background = NoxxaBg,
    onBackground = NoxxaText,
    surface = NoxxaSurface,
    onSurface = NoxxaText,
    surfaceVariant = NoxxaSurface2,
    onSurfaceVariant = NoxxaMuted,
    error = NoxxaDanger
)

@Composable
fun NoxxaApp(
    state: UiState,
    onImport: () -> Unit,
    onGrantAccess: () -> Unit,
    onRefresh: () -> Unit,
    onSavePaths: (String, String) -> Unit,
    onInstall: () -> Unit,
    onDismissScan: () -> Unit,
    onToggle: (InstalledMod) -> Unit,
    onUninstall: (InstalledMod) -> Unit,
    onClearMessage: () -> Unit
) {
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = NoxxaBg) {
            var showSettings by remember { mutableStateOf(false) }
            var uninstallCandidate by remember { mutableStateOf<InstalledMod?>(null) }

            Column(modifier = Modifier.fillMaxSize()) {
                Header(onSettings = { showSettings = true }, onRefresh = onRefresh)

                if (state.busy) {
                    Box(
                        modifier = Modifier.fillMaxWidth().background(Color(0x14FFFFFF)).padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(state.message ?: "Working…", fontSize = 12.sp, color = NoxxaMuted)
                        }
                    }
                } else if (state.error != null || state.message != null) {
                    StatusBanner(state.error, state.message, onClearMessage)
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { HeroCard(state, onGrantAccess, onImport) }
                    item { RootStatusCard(state) }
                    item {
                        SectionTitle("MY MODS", "${state.installedMods.count { it.enabled }} active • ${state.installedMods.size} registered")
                    }
                    if (state.installedMods.isEmpty()) {
                        item { EmptyModsCard(onImport) }
                    } else {
                        items(state.installedMods, key = { it.id }) { mod ->
                            ModCard(
                                mod = mod,
                                enabled = !state.busy,
                                onToggle = { onToggle(mod) },
                                onUninstall = { uninstallCandidate = mod }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }

            if (showSettings) {
                PathSettingsDialog(
                    dataPath = state.dataPath,
                    obbPath = state.obbPath,
                    onDismiss = { showSettings = false },
                    onSave = { d, o -> showSettings = false; onSavePaths(d, o) }
                )
            }

            state.scan?.let { scan ->
                ScanDialog(
                    scan = scan,
                    state = state,
                    onDismiss = onDismissScan,
                    onInstall = onInstall,
                    onGrantAccess = onGrantAccess
                )
            }

            uninstallCandidate?.let { mod ->
                AlertDialog(
                    onDismissRequest = { uninstallCandidate = null },
                    title = { Text("Uninstall ${mod.name}?") },
                    text = { Text("Noxxa will restore backed-up originals and remove its stored payload. This cannot restore unrelated manual edits made after installation.") },
                    confirmButton = {
                        TextButton(onClick = { uninstallCandidate = null; onUninstall(mod) }) {
                            Text("RESTORE & REMOVE", color = NoxxaDanger)
                        }
                    },
                    dismissButton = { TextButton(onClick = { uninstallCandidate = null }) { Text("CANCEL") } }
                )
            }
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("NOXXA //", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, color = NoxxaGreen, fontSize = 13.sp)
            Text("MOD MANAGER", fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontSize = 22.sp)
        }
        TextButton(onClick = onRefresh) { Text("SYNC") }
        TextButton(onClick = onSettings) { Text("PATHS") }
    }
}

@Composable
private fun HeroCard(state: UiState, onGrantAccess: () -> Unit, onImport: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NoxxaSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GTA SA Android / Unprotected", color = NoxxaMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("Install. Disable. Restore.\nWithout file-manager archaeology.", fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onImport,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("IMPORT ZIP", fontWeight = FontWeight.Bold) }
                if (!state.hasStorageAccess) {
                    OutlinedButton(onClick = onGrantAccess, modifier = Modifier.weight(1f)) { Text("GRANT ACCESS") }
                }
            }
            Text(
                if (state.hasStorageAccess) "● Direct storage engine ready" else "○ Scan works now; install needs Manage all files access",
                color = if (state.hasStorageAccess) NoxxaGreen else NoxxaWarning,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun RootStatusCard(state: UiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = NoxxaSurface2),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GAME ROOT", fontFamily = FontFamily.Monospace, color = NoxxaGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            PathLine("DATA", state.dataPath, state.dataDetected)
            PathLine("OBB", state.obbPath, state.obbDetected)
        }
    }
}

@Composable
private fun PathLine(label: String, path: String, detected: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(if (detected) "✓" else "!", color = if (detected) NoxxaGreen else NoxxaWarning, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text("$label  ${if (detected) "DETECTED" else "NOT FOUND"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(path.ifBlank { "Not configured" }, color = NoxxaMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(title, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(subtitle, color = NoxxaMuted, fontSize = 11.sp)
    }
}

@Composable
private fun EmptyModsCard(onImport: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = NoxxaSurface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No managed mods yet", fontWeight = FontWeight.Bold)
            Text("Import a ZIP and Noxxa will build a reversible install record.", color = NoxxaMuted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onImport) { Text("CHOOSE MOD ZIP") }
        }
    }
}

@Composable
private fun ModCard(mod: InstalledMod, enabled: Boolean, onToggle: () -> Unit, onUninstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = NoxxaSurface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mod.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        mod.categories.filter { it.name != "MIXED" }.joinToString(" • ") { it.label }.ifBlank { "Mod" },
                        color = NoxxaMuted,
                        fontSize = 11.sp
                    )
                }
                Text(
                    if (mod.enabled) "● ACTIVE" else "○ DISABLED",
                    color = if (mod.enabled) NoxxaGreen else NoxxaMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "${mod.files.size} files • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(mod.installedAt))}",
                fontSize = 11.sp,
                color = NoxxaMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onToggle,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (mod.enabled) NoxxaSurface2 else NoxxaGreen)
                ) {
                    Text(if (mod.enabled) "DISABLE" else "ENABLE", color = if (mod.enabled) NoxxaText else Color(0xFF101400))
                }
                OutlinedButton(onClick = onUninstall, enabled = enabled) { Text("UNINSTALL", color = NoxxaDanger) }
            }
        }
    }
}

@Composable
private fun StatusBanner(error: String?, message: String?, onClear: () -> Unit) {
    val isError = error != null
    Row(
        modifier = Modifier.fillMaxWidth().background(if (isError) Color(0x22FF6B6B) else Color(0x1FB7FF4A)).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(error ?: message.orEmpty(), modifier = Modifier.weight(1f), fontSize = 12.sp, color = if (isError) NoxxaDanger else NoxxaGreen)
        TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("×") }
    }
}

@Composable
private fun PathSettingsDialog(dataPath: String, obbPath: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var data by remember(dataPath) { mutableStateOf(dataPath) }
    var obb by remember(obbPath) { mutableStateOf(obbPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game paths") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Default is your unprotected GTA layout. Change only if your build uses a different package/folder.", color = NoxxaMuted, fontSize = 12.sp)
                OutlinedTextField(value = data, onValueChange = { data = it }, label = { Text("DATA root") }, singleLine = false)
                OutlinedTextField(value = obb, onValueChange = { obb = it }, label = { Text("OBB root") }, singleLine = false)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(data, obb) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
private fun ScanDialog(scan: ScanResult, state: UiState, onDismiss: () -> Unit, onInstall: () -> Unit, onGrantAccess: () -> Unit) {
    val blocked = state.conflicts.isNotEmpty()
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(scan.displayName.removeSuffix(".zip"), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(modifier = Modifier.height(430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("${scan.files.size} installable files • ${scan.categories.filter { it.name != "MIXED" }.joinToString(" / ") { it.label }}", color = NoxxaMuted, fontSize = 12.sp)
                }
                if (blocked) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0x22FFD166))) {
                            Column(Modifier.padding(12.dp)) {
                                Text("⚠ CONFLICT BLOCKED", color = NoxxaWarning, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                state.conflicts.take(5).forEach { c ->
                                    Text("${c.targetArea.name}/${c.relativeTarget} ← ${c.ownerName}", fontSize = 11.sp, color = NoxxaMuted)
                                }
                                if (state.conflicts.size > 5) Text("+${state.conflicts.size - 5} more", color = NoxxaMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (scan.skipped.isNotEmpty()) item { Text("${scan.skipped.size} unsafe/metadata entries skipped", color = NoxxaWarning, fontSize = 11.sp) }
                items(scan.files.take(80)) { file ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(if (file.targetArea == TargetArea.DATA) "D" else "O", color = NoxxaGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        Column {
                            Text(file.relativeTarget, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(file.category.label, color = NoxxaMuted, fontSize = 10.sp)
                        }
                    }
                }
                if (scan.files.size > 80) item { Text("…and ${scan.files.size - 80} more files", color = NoxxaMuted, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            if (state.hasStorageAccess) {
                Button(onClick = onInstall, enabled = !blocked && !state.busy) { Text(if (blocked) "RESOLVE CONFLICT" else "INSTALL") }
            } else {
                Button(onClick = onGrantAccess) { Text("GRANT ACCESS") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("CANCEL") } }
    )
}
