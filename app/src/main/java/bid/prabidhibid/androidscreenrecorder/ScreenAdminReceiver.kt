package bid.prabidhibid.androidscreenrecorder

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device administrator component for the app. Declaring this (and enabling it from the UI)
 * lets the app use privileged device-policy features such as locking the screen on demand.
 */
class ScreenAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device admin enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "Device admin disabled", Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, ScreenAdminReceiver::class.java)
    }
}
