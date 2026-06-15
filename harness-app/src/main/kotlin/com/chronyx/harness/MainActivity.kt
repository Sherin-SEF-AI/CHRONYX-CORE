package com.chronyx.harness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.chronyx.harness.ui.CalibrationScreen
import com.chronyx.harness.ui.ChronyxHeader
import com.chronyx.harness.ui.DiagnosticsScreen
import com.chronyx.harness.ui.FileBrowserScreen
import com.chronyx.harness.ui.RecordScreen
import com.chronyx.harness.ui.SettingsScreen
import com.chronyx.harness.ui.theme.ChronyxColors
import com.chronyx.harness.ui.theme.ChronyxTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {

    private val vm: HarnessViewModel by viewModels()

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.permissionsGranted.value = hasAllPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.permissionsGranted.value = hasAllPermissions()
        if (!hasAllPermissions()) permissionLauncher.launch(requiredPermissions)

        setContent {
            ChronyxTheme {
                Box(Modifier.fillMaxSize().background(ChronyxColors.Surface)) {
                    HarnessRoot(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Mid-session permission revocation is a defined state: re-evaluate on every resume.
        vm.permissionsGranted.value = hasAllPermissions()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

private enum class Tab(val label: String) { RECORD("ARM"), DIAG("SYNC"), CALIB("CALIB"), FILES("FILES"), SETTINGS("SET") }

@Composable
private fun HarnessRoot(vm: HarnessViewModel) {
    var tab by remember { mutableStateOf(Tab.RECORD) }
    val recording by vm.recording.collectAsState()
    val diag by vm.diagnostics.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ChronyxHeader(subtitle = tab.label, locked = recording && diag?.syncLocked == true)
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.RECORD -> RecordScreen(vm)
                Tab.DIAG -> DiagnosticsScreen(vm)
                Tab.CALIB -> CalibrationScreen(vm)
                Tab.FILES -> FileBrowserScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(ChronyxColors.SurfaceRaised),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == tab
                // The accent is reserved for earned live state; tab selection is greyscale.
                Text(
                    t.label,
                    color = if (selected) ChronyxColors.TextPrimary else ChronyxColors.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { tab = t }
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}
