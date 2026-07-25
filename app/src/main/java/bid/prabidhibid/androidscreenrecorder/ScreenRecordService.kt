package bid.prabidhibid.androidscreenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that captures the screen via [MediaProjection] together with microphone
 * audio via [MediaRecorder], writing an MP4 file into the shared Movies/ScreenRecorder folder.
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var isRecording = false

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

    private fun stopRecording() {
        if (!isRecording && mediaRecorder == null) {
            releaseProjection()
            return
        }
        isRecording = false

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

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen recording")
            .setContentText("Recording screen and microphone…")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(NOTIFICATION_ID, notification, type)
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

        const val ACTION_STOP = "bid.prabidhibid.androidscreenrecorder.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
    }
}
