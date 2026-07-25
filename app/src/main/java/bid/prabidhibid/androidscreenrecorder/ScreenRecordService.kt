package bid.prabidhibid.androidscreenrecorder

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Foreground service that captures the screen via [MediaProjection] together with microphone
 * audio via [MediaRecorder], writing an MP4 file into the shared Movies/ScreenRecorder folder.
 *
 * While recording it shows a small, draggable, semi-transparent overlay: a dot that blinks
 * red/green while recording (tap to pause — it turns solid gray — tap again to resume) plus a
 * stop button next to it.
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var isRecording = false
    private var isPaused = false

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dotDrawable: GradientDrawable? = null
    private var dotIsRed = true
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!isRecording || isPaused) return
            dotIsRed = !dotIsRed
            dotDrawable?.setColor(if (dotIsRed) COLOR_RED else COLOR_GREEN)
            blinkHandler.postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked the projection (e.g. via the system dialog); tear everything down.
            stopRecording()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            stopSelf()
            return START_NOT_STICKY
        }

        // A media-projection foreground service must already be in the foreground before the
        // MediaProjection is obtained, so promote to foreground first.
        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, RESULT_INVALID) ?: RESULT_INVALID
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        val width = intent?.getIntExtra(EXTRA_WIDTH, 720) ?: 720
        val height = intent?.getIntExtra(EXTRA_HEIGHT, 1280) ?: 1280
        val dpi = intent?.getIntExtra(EXTRA_DPI, 320) ?: 320

        if (data == null || resultCode == RESULT_INVALID) {
            Log.e(TAG, "Missing projection permission data; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startRecording(resultCode, data, width, height, dpi)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
            stopRecording()
            stopSelf()
        }
        return START_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent, width: Int, height: Int, dpi: Int) {
        if (isRecording) return

        val projectionManager = getSystemService(MediaProjectionManager::class.java)!!
        val projection = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Unable to obtain MediaProjection")
        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        setUpMediaRecorder(width, height)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecordDisplay",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null,
            null
        )

        mediaRecorder!!.start()
        isRecording = true
        isPaused = false
        isRunning = true
        showOverlay()
        startBlink()
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
    }

    private fun setUpMediaRecorder(width: Int, height: Int) {
        val fileName = "ScreenRec_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/ScreenRecorder"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        outputUri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Unable to create output file in MediaStore")
        outputPfd = contentResolver.openFileDescriptor(outputUri!!, "w")
            ?: throw IllegalStateException("Unable to open output file descriptor")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(8_000_000)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputPfd!!.fileDescriptor)
            prepare()
        }
        mediaRecorder = recorder
    }

    /** Toggle between paused and recording, driven by a tap on the overlay dot. */
    private fun togglePause() {
        if (!isRecording) return
        if (isPaused) resumeRecording() else pauseRecording()
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        try {
            mediaRecorder?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause() failed", e)
            return
        }
        isPaused = true
        stopBlink()
        dotDrawable?.setColor(COLOR_GRAY)
        updateNotification()
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        try {
            mediaRecorder?.resume()
        } catch (e: Exception) {
            Log.w(TAG, "resume() failed", e)
            return
        }
        isPaused = false
        startBlink()
        updateNotification()
    }

    private fun stopRecording() {
        removeOverlay()

        if (!isRecording && mediaRecorder == null) {
            releaseProjection()
            isRunning = false
            return
        }
        isRecording = false
        isPaused = false
        isRunning = false

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            // stop() throws if no frames were captured; the file is unusable in that case.
            Log.w(TAG, "MediaRecorder.stop() failed", e)
        }
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        releaseProjection()

        try {
            outputPfd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing file descriptor failed", e)
        }
        outputPfd = null

        // Publish the finished file so it becomes visible to the gallery and other apps.
        outputUri?.let { uri ->
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        }
        outputUri = null

        Toast.makeText(this, "Recording saved to Movies/ScreenRecorder", Toast.LENGTH_LONG).show()
    }

    private fun releaseProjection() {
        mediaProjection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        mediaProjection = null
    }

    // ---------------------------------------------------------------------------------------------
    // Floating overlay
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(0x66000000) // translucent pill so it reads over any content
            }
            alpha = 0.7f // "half transparent" so it doesn't obscure content underneath
        }

        // Blinking recording dot — tap to pause/resume.
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_RED)
            }
        }
        dotDrawable = dot.background as GradientDrawable
        container.addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)))

        // Stop button — a dark circle with a white square icon.
        val stop = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF444444.toInt())
            }
        }
        val square = View(this).apply { setBackgroundColor(Color.WHITE) }
        stop.addView(square, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        container.addView(
            stop,
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(12) }
        )

        overlayView = container

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE keeps touches outside the overlay flowing to the app beneath it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }
        overlayParams = params

        attachDragAndTap(dot, params) { togglePause() }
        attachDragAndTap(stop, params) {
            stopRecording()
            stopSelf()
        }

        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to add overlay (permission?)", e)
            overlayView = null
        }
    }

    /**
     * Lets a child view drag the whole overlay window when moved past the touch slop, and fire
     * [onTap] when it was a tap rather than a drag.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(
        view: View,
        params: WindowManager.LayoutParams,
        onTap: () -> Unit
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTap()
                    true
                }

                else -> false
            }
        }
    }

    private fun startBlink() {
        blinkHandler.removeCallbacks(blinkRunnable)
        dotIsRed = true
        dotDrawable?.setColor(COLOR_RED)
        blinkHandler.postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
    }

    private fun stopBlink() {
        blinkHandler.removeCallbacks(blinkRunnable)
    }

    private fun removeOverlay() {
        stopBlink()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed", e)
            }
        }
        overlayView = null
        dotDrawable = null
        overlayParams = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------------

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isPaused) "Recording paused" else "Screen recording")
            .setContentText(
                if (isPaused) "Tap the overlay dot to resume"
                else "Recording screen and microphone…"
            )
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_INVALID = 0
        private const val BLINK_INTERVAL_MS = 550L

        private const val COLOR_RED = 0xFFFF0000.toInt()
        private const val COLOR_GREEN = 0xFF00D000.toInt()
        private const val COLOR_GRAY = 0xFF888888.toInt()

        /** True while a recording session is active; read by the UI to reflect state. */
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_STOP = "bid.prabidhibid.androidscreenrecorder.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
    }
}
