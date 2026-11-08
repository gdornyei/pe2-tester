package io.github.gdornyei.pe2tester

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.gdornyei.pe2tester.data.Project
import io.github.gdornyei.pe2tester.export.Exporters
import io.github.gdornyei.pe2tester.ui.AppRoot
import io.github.gdornyei.pe2tester.ui.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val perms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private var afterBle: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (!res.values.all { it }) { afterBle = null; vm.showMessage("Cannot connect without Bluetooth permission"); return@registerForActivityResult }
        if (afterBle != null) bleReady() else runAfterChecks()
    }

    private fun bleReady() {
        val cb = afterBle ?: return
        if (!vm.ble.bluetoothEnabled) { runCatching { btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }; return }
        afterBle = null; cb()
    }

    private val btEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!vm.ble.bluetoothEnabled) return@registerForActivityResult
        if (afterBle != null) bleReady() else vm.runOrStop()
    }

    private fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun runAfterChecks() {
        if (!vm.ble.bluetoothEnabled) {
            runCatching { btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            return
        }
        vm.runOrStop()
    }

    private fun onRun() {
        if (vm.test.isRunning) { vm.runOrStop(); return }
        if (hasPerms()) runAfterChecks() else permLauncher.launch(perms)
    }

    private fun shareText(subject: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun export(p: Project, o: io.github.gdornyei.pe2tester.data.ReportOptions) {
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    buildList {
                        if (o.pdf) add(Exporters.pdf(this@MainActivity, p, o, vm.owner.value, vm.settings.logo()))
                        if (o.csv) add(Exporters.csv(this@MainActivity, p, o))
                    }
                }
                if (files.isNotEmpty()) Exporters.share(this@MainActivity, files, p.name)
                if (files.isNotEmpty() && vm.driveReports.value) withDriveToken { t -> vm.driveUpload(t, files) }
            } catch (e: Exception) {
                vm.showMessage("Export error: ${e.message}")
            }
        }
    }

    private var photoTarget: Triple<String, String, java.io.File?>? = null
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val t = photoTarget ?: return@registerForActivityResult
        photoTarget = null
        if (ok) vm.addPhoto(t.first, t.second, null, t.third) else t.third?.delete()
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val t = photoTarget ?: return@registerForActivityResult
        photoTarget = null
        if (uri != null) vm.addPhoto(t.first, t.second, uri, null)
    }

    private var onDriveToken: ((String) -> Unit)? = null
    private val driveAuthLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val r = runCatching { com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data) }.getOrNull()
        val t = r?.accessToken
        val cb = onDriveToken; onDriveToken = null
        if (t != null && cb != null) cb(t) else vm.showMessage("Google authorization cancelled")
    }

    /** Gets a Drive access token (drive.file scope); asks the user the first time. */
    private fun withDriveToken(cb: (String) -> Unit) {
        val req = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/drive.file"))).build()
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    onDriveToken = cb
                    runCatching { driveAuthLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) }
                } else r.accessToken?.let(cb) ?: vm.showMessage("No Google token received")
            }
            .addOnFailureListener { vm.showMessage("Google authorization error: ${it.message}") }
    }

    private val logoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setLogo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = UiActions(
            runTest = ::onRun,
            shareText = ::shareText,
            export = ::export,
            withBle = { cb -> afterBle = cb; if (hasPerms()) bleReady() else permLauncher.launch(perms) },
            shareFile = { f, title -> Exporters.share(this, listOf(f), title) },
            driveSync = { withDriveToken { t -> vm.driveSync(t) } },
            takePhoto = { pid, mid ->
                val f = vm.newPhotoFile()
                photoTarget = Triple(pid, mid, f)
                runCatching { cameraLauncher.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)) }
                    .onFailure { vm.showMessage("Camera not available") }
            },
            pickPhoto = { pid, mid ->
                photoTarget = Triple(pid, mid, null)
                photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            pickLogo = { logoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF4FA3E0), onPrimary = Color(0xFF00213A))
            else lightColorScheme(primary = Color(0xFF1C6FB5), onPrimary = Color.White)
            MaterialTheme(colorScheme = scheme) { AppRoot(vm, actions) }
        }
    }

    override fun onDestroy() {
        if (isFinishing) vm.disconnect()
        super.onDestroy()
    }
}
