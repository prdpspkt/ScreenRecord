package bid.prabidhibid.androidscreenrecorder

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A recording saved under Movies/ScreenRecorder. */
data class Recording(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long
)

@Composable
fun RecordingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            loading = true
            val list = withContext(Dispatchers.IO) { queryRecordings(context) }
            recordings = list
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Recordings",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            recordings.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No recordings yet")
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { it.uri.toString() }) { rec ->
                    RecordingRow(
                        recording = rec,
                        onPlay = { playRecording(context, rec.uri) },
                        onDelete = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    deleteRecording(context, rec.uri)
                                }
                                Toast.makeText(
                                    context,
                                    if (ok) "Deleted" else "Could not delete",
                                    Toast.LENGTH_SHORT
                                ).show()
                                if (ok) refresh()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(formatDuration(recording.durationMs))
                        append(" · ")
                        append(formatSize(recording.sizeBytes))
                        append(" · ")
                        append(formatDate(recording.dateAddedSec))
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

private fun queryRecordings(context: Context): List<Recording> {
    val result = mutableListOf<Recording>()
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_ADDED
    )
    val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("%${android.os.Environment.DIRECTORY_MOVIES}/ScreenRecorder%")
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

    context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            result += Recording(
                uri = ContentUris.withAppendedId(collection, id),
                name = c.getString(nameCol) ?: "recording.mp4",
                durationMs = c.getLong(durCol),
                sizeBytes = c.getLong(sizeCol),
                dateAddedSec = c.getLong(dateCol)
            )
        }
    }
    return result
}

private fun playRecording(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Play recording"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app to play video", Toast.LENGTH_SHORT).show()
    }
}

private fun deleteRecording(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        false
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}

private fun formatDate(epochSeconds: Long): String {
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        .format(Date(epochSeconds * 1000))
}
