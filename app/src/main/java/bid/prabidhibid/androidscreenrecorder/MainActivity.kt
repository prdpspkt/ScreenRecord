package bid.prabidhibid.androidscreenrecorder

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import bid.prabidhibid.androidscreenrecorder.ui.theme.AndroidScreenRecorderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidScreenRecorderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecorderScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    /** Screen resolution and density of the default display, used to size the capture. */
    fun screenSpec(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        val densityDpi = resources.configuration.densityDpi
        // H.264 requires even dimensions.
        return Triple(width and 1.inv(), height and 1.inv(), densityDpi)
    }
}

@Composable
private fun RecorderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as MainActivity

    var isRecording by remember { mutableStateOf(false) }
    var isAdminActive by remember {
        mutableStateOf(isDeviceAdminActive(context))
    }

    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)!!
    }
    val devicePolicyManager = remember {
        context.getSystemService(DevicePolicyManager::class.java)!!
    }

    // Step 3: user granted screen-capture consent -> launch the foreground recording service.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val (width, height, dpi) = activity.screenSpec()
            val serviceIntent = Intent(context, ScreenRecordService::class.java).apply {
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_DATA, result.data)
                putExtra(ScreenRecordService.EXTRA_WIDTH, width)
                putExtra(ScreenRecordService.EXTRA_HEIGHT, height)
                putExtra(ScreenRecordService.EXTRA_DPI, dpi)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            isRecording = true
        } else {
            Toast.makeText(context, "Screen capture was denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 2: runtime permissions granted -> ask for screen-capture consent.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (audioGranted) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required to record audio",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Step 4: user picked an admin component from the system dialog.
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isAdminActive = isDeviceAdminActive(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Screen Recorder",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(32.dp))

        if (!isRecording) {
            Button(
                onClick = {
                    // Step 1: request microphone (+ notification on Android 13+) permissions.
                    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Recording")
            }
        } else {
            Button(
                onClick = {
                    val stopIntent = Intent(context, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                    isRecording = false
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop Recording")
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = if (isAdminActive) "Device admin: enabled" else "Device admin: disabled",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))

        if (!isAdminActive) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            ScreenAdminReceiver.componentName(context)
                        )
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Enable to let Screen Recorder lock the screen."
                        )
                    }
                    adminLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable Device Admin")
            }
        } else {
            OutlinedButton(
                onClick = { devicePolicyManager.lockNow() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lock Screen Now")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    devicePolicyManager.removeActiveAdmin(
                        ScreenAdminReceiver.componentName(context)
                    )
                    isAdminActive = false
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disable Device Admin")
            }
        }
    }
}

private fun isDeviceAdminActive(context: Context): Boolean {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)!!
    return dpm.isAdminActive(ScreenAdminReceiver.componentName(context))
}
