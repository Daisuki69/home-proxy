package com.siren.homeproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SirenBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SirenBoot"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action !in BOOT_ACTIONS) return

        Log.i(TAG, "Boot detected [${intent.action}] — waking target: ${SirenConfig.TARGET_PACKAGE}")

        try {
            val reviveIntent = Intent(SirenConfig.REVIVE_ACTION).apply {
                setPackage(SirenConfig.TARGET_PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(reviveIntent)
            Log.i(TAG, "Target revival broadcast sent on boot.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send revival broadcast on boot: ${e.message}")
        }
    }
}
