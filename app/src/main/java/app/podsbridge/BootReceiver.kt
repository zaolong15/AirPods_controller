package app.podsbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** 开机后若用户开启了「待命模式」，自动拉起前台服务等待 AirPods 连接。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Prefs.init(ctx)
        if (!Prefs.bool("standby", false)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, PodsService::class.java).setAction(PodsService.ACTION_STANDBY)
        )
    }
}
