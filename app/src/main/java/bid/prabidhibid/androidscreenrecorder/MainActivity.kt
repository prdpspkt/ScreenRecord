package bid.prabidhibid.androidscreenrecorder

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import bid.prabidhibid.androidscreenrecorder.ui.theme.AndroidScreenRecorderTheme

class MainActivity : ComponentActivity() {

    // Set to true when launched from the Quick Settings tile so recording starts immediately.
    private val startTrigger = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AndroidScreenRecorderTheme {
                AppRoot(startTrigger)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_RECORDING, false) == true) {
            startTrigger.value = true
            // Consume it so a config change / recreate doesn't retrigger recording.
            intent.removeExtra(EXTRA_START_RECORDING)
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

    companion object {
        const val EXTRA_START_RECORDING = "extra_start_recording"
    }
}

@Composable
private fun AppRoot(startTrigger: MutableState<Boolean>) {
    var showRecordings by remember { mutableStateOf(false) }
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        if (showRecordings) {
            RecordingsScreen(
                modifier = Modifier.padding(innerPadding),
                onBack = { showRecordings = false }
            )
        } else {
            RecorderScreen(
                modifier = Modifier.padding(innerPadding),
                startTrigger = startTrigger,
                onOpenRecordings = { showRecordings = true }
            )
        }
    }
}

@Composable
private fun RecorderScreen(
    modifier: Modifier = Modifier,
    startTrigger: MutableState<Boolean>,
    onOpenRecordings: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as MainActivity

    var isRecording by remember { mutableStateOf(ScreenRecordService.isRunning) }
    var isAdminActive by remember {
        mutableStateOf(isDeviceAdminActive(context))
    }

    val projectionManager = remember {
        context.getSystemService(MediaProjectionManager::class.java)!!
    }
    val devicePolicyManager = remember {
        context.getSystemService(DevicePolicyManager::class.java)!!
    }

    // Keep the in-app button in sync with the service after the app is minimized/reopened
    // (the recording may have been stopped from the floating overlay or notification).
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isRecording = ScreenRecordService.isRunning
                isAdminActive = isDeviceAdminActive(context)
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // Step 3: user granted screen-capture consent -> launch the foreground recording service,
    // then minimize the app so the recording captures what's behind it.
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
            activity.moveTaskToBack(true)
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

    // Returns from the "Display over other apps" settings screen; nothing to do but let the
    // user tap Start again now that the overlay permission may be granted.
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    // Step 4: user picked an admin component from the system dialog.
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isAdminActive = isDeviceAdminActive(context)
    }

    // Shared entry point for the Start button and the Quick Settings tile.
    val beginStart: () -> Unit = {
        // Step 0: the floating controls need the "Display over other apps" permission.
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Allow \"Display over other apps\", then tap Start again",
                Toast.LENGTH_LONG
            ).show()
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } else {
            // Step 1: request microphone (+ notification on Android 13+) permissions.
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // Kick off recording automatically when launched from the Quick Settings tile.
    LaunchedEffect(startTrigger.value) {
        if (startTrigger.value) {
            startTrigger.value = false
            if (!ScreenRecordService.isRunning) beginStart()
        }
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
                onClick = beginStart,
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

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenRecordings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("View Recordings")
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
