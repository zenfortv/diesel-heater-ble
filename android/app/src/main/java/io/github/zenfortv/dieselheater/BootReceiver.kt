package io.github.zenfortv.dieselheater

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Phone restarted: bring the heater link (and the timers) back without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)
        if (p.getString("address", "").isNullOrEmpty() || p.getBoolean("quit", false)) return
        if (p.getInt("terms", 0) < MainActivity.TERMS_VERSION) return
        if (Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        ctx.startForegroundService(Intent(ctx, HeaterService::class.java))
    }
}
