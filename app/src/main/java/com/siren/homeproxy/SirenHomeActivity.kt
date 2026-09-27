package com.siren.homeproxy

import android.app.Activity
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

class SirenHomeActivity : Activity() {

    companion object {
        private const val TAG = "SirenHome"
        private const val PREFS_NAME = "siren_home_proxy"
        private const val KEY_LAUNCHER_PKG = "selected_launcher_pkg"
        private const val KEY_LAUNCHER_CLS = "selected_launcher_cls"
        const val ACTION_REQUEST_HOME = "com.siren.homeproxy.ACTION_REQUEST_HOME"
        private const val REQUEST_CODE_ROLE_HOME = 1001

        @Volatile
        private var sCachedLauncher: ComponentName? = null

        private val sExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "SirenSync").apply { isDaemon = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(null)
        super.onCreate(savedInstanceState)
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        if (intent?.action == ACTION_REQUEST_HOME) {
            requestHomeRole()
        } else {
            handleHomeIntent()
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    val roleRequestIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    @Suppress("DEPRECATION")
                    startActivityForResult(roleRequestIntent, REQUEST_CODE_ROLE_HOME)
                    return
                }
            }
        }

        try {
            val settingsIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(settingsIntent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(fallbackIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open home settings: ${e.message}")
            }
        }
        finishWithNoAnim()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_ROLE_HOME) {
            finishWithNoAnim()
        }
    }

    private fun handleHomeIntent() {
        var launcher = sCachedLauncher
        if (launcher == null) {
            launcher = getDiskCachedLauncher() ?: discoverRealLauncher()
            sCachedLauncher = launcher
        }

        if (launcher != null) {
            delegate(launcher)
        }

        sExecutor.execute {
            checkAndSyncWithTarget()
        }

        finishWithNoAnim()
    }

    private fun delegate(launcher: ComponentName) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                component = launcher
                setPackage(launcher.packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra("android.intent.extra.FROM_HOME_KEY", true)
                putExtra("com.android.internal.policy.impl.PhoneWindowManager.FROM_HOME_KEY", true)
            }
            val options = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
            startActivity(homeIntent, options)
            Log.d(TAG, "Delegated HOME to: ${launcher.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Delegation failed: ${e.message}")
            sCachedLauncher = null
        }
    }

    private fun checkAndSyncWithTarget() {
        try {
            val uri = Uri.parse("content://${SirenConfig.PROVIDER_AUTHORITY}")
            val bundle = contentResolver.call(uri, "getLauncher", null, null)

            if (bundle != null) {
                val pkg = bundle.getString("package") ?: bundle.getString("launcher_package")
                val cls = bundle.getString("class") ?: bundle.getString("launcher_class")

                if (!pkg.isNullOrBlank() && (sCachedLauncher?.packageName != pkg)) {
                    val resolved = resolveLauncherComponent(pkg, cls)
                    if (resolved != null) {
                        sCachedLauncher = resolved
                        saveLauncherToDisk(resolved.packageName, resolved.className)
                    }
                }
            } else {
                reviveTarget()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Target provider unavailable: ${e.message}")
            reviveTarget()
        }
    }

    private fun reviveTarget() {
        try {
            val reviveIntent = Intent(SirenConfig.REVIVE_ACTION).apply {
                setPackage(SirenConfig.TARGET_PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(reviveIntent)
            Log.i(TAG, "Revival broadcast sent to: ${SirenConfig.TARGET_PACKAGE}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send revival broadcast: ${e.message}")
        }
    }

    private fun resolveLauncherComponent(pkg: String, cls: String? = null): ComponentName? {
        if (!cls.isNullOrBlank()) {
            return try {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                ComponentName(pkg, cls)
            } catch (_: Exception) {
                null
            }
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        val matches = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val act = matches.firstOrNull()?.activityInfo
        if (act != null) {
            return ComponentName(act.packageName, act.name)
        }

        return packageManager.getLaunchIntentForPackage(pkg)?.component
    }

    private fun saveLauncherToDisk(pkg: String, cls: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAUNCHER_PKG, pkg)
            .putString(KEY_LAUNCHER_CLS, cls)
            .apply()
    }

    private fun getDiskCachedLauncher(): ComponentName? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_LAUNCHER_PKG, null) ?: return null
        val cls = prefs.getString(KEY_LAUNCHER_CLS, null) ?: return null

        return try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            ComponentName(pkg, cls)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_LAUNCHER_PKG).remove(KEY_LAUNCHER_CLS).apply()
            null
        }
    }

    private fun discoverRealLauncher(): ComponentName? {
        val myPkg = packageName
        val queryIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_DEFAULT_ONLY)

        for (info in candidates) {
            val ai = info.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            val actName = ai.name ?: ""

            if (pkg == myPkg) continue
            if (pkg == SirenConfig.TARGET_PACKAGE) continue
            if (info.priority < 0) continue
            if (actName.lowercase().contains("fallbackhome")) continue
            if (pkg == "com.android.settings" || pkg.contains(".settings")) continue
            if (pkg.contains("setupwizard") || pkg.contains("provision")) continue

            saveLauncherToDisk(pkg, ai.name)
            Log.i(TAG, "Discovered real launcher: $pkg / ${ai.name}")
            return ComponentName(pkg, ai.name)
        }

        return null
    }

    private fun finishWithNoAnim() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
