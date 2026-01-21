package app.olauncherredux.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.app.ActivityCompat
import app.olauncherredux.BuildConfig
import app.olauncherredux.R
import app.olauncherredux.data.AppModel
import app.olauncherredux.data.Constants
import app.olauncherredux.data.Constants.BACKUP_READ
import app.olauncherredux.data.Constants.BACKUP_WRITE
import app.olauncherredux.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.Collator
import java.util.*
import kotlin.math.pow
import kotlin.math.sqrt


fun showToastLong(context: Context, message: String) {
    val toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG)
    toast.setGravity(Gravity.CENTER, 0, 0)
    toast.show()
}

fun showToastShort(context: Context, message: String) {
    val toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
    toast.setGravity(Gravity.CENTER, 0, 0)
    toast.show()
}

suspend fun getAppsList(context: Context, showHiddenApps: Boolean = false): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val appList: MutableList<AppModel> = mutableListOf()

        try {
            val prefs = Prefs(context)
            if (!prefs.hiddenAppsUpdated) upgradeHiddenApps(prefs)
            val hiddenApps = prefs.hiddenApps

            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val collator = Collator.getInstance()

            for (profile in userManager.userProfiles) {
                for (app in launcherApps.getActivityList(null, profile)) {


                    // we have changed the alias identifier from app.label to app.applicationInfo.packageName
                    // therefore, we check if the old one is set if the new one is empty
                    val appAlias = prefs.getAppAlias(app.applicationInfo.packageName).ifEmpty {
                        prefs.getAppAlias(app.label.toString())
                    }

                    if (showHiddenApps && app.applicationInfo.packageName != BuildConfig.APPLICATION_ID) {
                        val appModel = AppModel(
                            app.label.toString(),
                            collator.getCollationKey(app.label.toString()),
                            app.applicationInfo.packageName,
                            app.componentName.className,
                            profile,
                            appAlias,
                        )
                        appList.add(appModel)
                    } else if (!hiddenApps.contains(app.applicationInfo.packageName + "|" + profile.toString())
                        && app.applicationInfo.packageName != BuildConfig.APPLICATION_ID
                    ) {
                        val appModel = AppModel(
                            app.label.toString(),
                            collator.getCollationKey(app.label.toString()),
                            app.applicationInfo.packageName,
                            app.componentName.className,
                            profile,
                            appAlias,
                        )
                        appList.add(appModel)
                    }

                }
            }

            // Sort based on preference
            when (prefs.drawerSortOrder) {
                Constants.SortOrder.MostUsed -> {
                    val usageScores = getAppUsageScores(context)
                    if (usageScores.isNotEmpty()) {
                        // Get apps that are already quick-accessible (home screen + gestures)
                        val quickAccessApps = getQuickAccessApps(prefs)

                        // Sort by usage score descending, then alphabetically for apps with no usage
                        // Deprioritize apps that are already on home screen or assigned to gestures
                        appList.sortWith { a, b ->
                            val aIsQuickAccess = quickAccessApps.contains(a.appPackage)
                            val bIsQuickAccess = quickAccessApps.contains(b.appPackage)

                            // Quick access apps go to the bottom
                            when {
                                aIsQuickAccess && !bIsQuickAccess -> 1
                                !aIsQuickAccess && bIsQuickAccess -> -1
                                else -> {
                                    // Both quick access or both not - sort by usage score
                                    val scoreA = usageScores.getOrDefault(a.appPackage, 0L)
                                    val scoreB = usageScores.getOrDefault(b.appPackage, 0L)
                                    when {
                                        scoreA != scoreB -> scoreB.compareTo(scoreA) // Higher score first
                                        else -> {
                                            // Alphabetical fallback
                                            val nameA = if (a.appAlias.isEmpty()) a.appLabel.lowercase() else a.appAlias.lowercase()
                                            val nameB = if (b.appAlias.isEmpty()) b.appLabel.lowercase() else b.appAlias.lowercase()
                                            nameA.compareTo(nameB)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // No usage data available, fall back to alphabetical
                        appList.sortBy {
                            if (it.appAlias.isEmpty()) it.appLabel.lowercase() else it.appAlias.lowercase()
                        }
                    }
                }
                Constants.SortOrder.Alphabetical -> {
                    appList.sortBy {
                        if (it.appAlias.isEmpty()) it.appLabel.lowercase() else it.appAlias.lowercase()
                    }
                }
            }

        } catch (e: java.lang.Exception) {
            Log.d("backup", "$e")
        }
        appList
    }
}

suspend fun getHiddenAppsList(context: Context): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val pm = context.packageManager
        if (!Prefs(context).hiddenAppsUpdated) upgradeHiddenApps(Prefs(context))

        val hiddenAppsSet = Prefs(context).hiddenApps
        val appList: MutableList<AppModel> = mutableListOf()
        if (hiddenAppsSet.isEmpty()) return@withContext appList

        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val collator = Collator.getInstance()
        for (hiddenPackage in hiddenAppsSet) {
            val appPackage = hiddenPackage.split("|")[0]
            val userString = hiddenPackage.split("|")[1]
            var userHandle = android.os.Process.myUserHandle()
            for (user in userManager.userProfiles) {
                if (user.toString() == userString) userHandle = user
            }
            try {
                val appInfo = pm.getApplicationInfo(appPackage, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val appKey = collator.getCollationKey(appName)
                // TODO: hidden apps settings ignore activity name for backward compatibility. Fix it.
                appList.add(AppModel(appName, appKey, appPackage, "", userHandle, Prefs(context).getAppAlias(appName)))
            } catch (e: NameNotFoundException) {

            }
        }
        appList.sort()
        appList
    }
}

// This is to ensure backward compatibility with older app versions
// which did not support multiple user profiles
private fun upgradeHiddenApps(prefs: Prefs) {
    val hiddenAppsSet = prefs.hiddenApps
    val newHiddenAppsSet = mutableSetOf<String>()
    for (hiddenPackage in hiddenAppsSet) {
        if (hiddenPackage.contains("|")) newHiddenAppsSet.add(hiddenPackage)
        else newHiddenAppsSet.add(hiddenPackage + android.os.Process.myUserHandle().toString())
    }
    prefs.hiddenApps = newHiddenAppsSet
    prefs.hiddenAppsUpdated = true
}

fun getUserHandleFromString(context: Context, userHandleString: String): UserHandle {
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    for (userHandle in userManager.userProfiles) {
        if (userHandle.toString() == userHandleString) {
            return userHandle
        }
    }
    return android.os.Process.myUserHandle()
}

fun isOlauncherDefault(context: Context): Boolean {
    val launcherPackageName = getDefaultLauncherPackage(context)
    return BuildConfig.APPLICATION_ID == launcherPackageName
}

fun getDefaultLauncherPackage(context: Context): String {
    val intent = Intent()
    intent.action = Intent.ACTION_MAIN
    intent.addCategory(Intent.CATEGORY_HOME)
    val packageManager = context.packageManager
    val result = packageManager.resolveActivity(intent, 0)
    return if (result?.activityInfo != null) {
        result.activityInfo.packageName
    } else "android"
}

// Source: https://stackoverflow.com/a/13239706
fun resetDefaultLauncher(context: Context) {
    try {
        val packageManager = context.packageManager
        val componentName = ComponentName(context, FakeHomeActivity::class.java)
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        val selector = Intent(Intent.ACTION_MAIN)
        selector.addCategory(Intent.CATEGORY_HOME)
        context.startActivity(selector)
        packageManager.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openAppInfo(context: Context, userHandle: UserHandle, packageName: String) {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val intent: Intent? = context.packageManager.getLaunchIntentForPackage(packageName)
    intent?.let {
        launcher.startAppDetailsActivity(intent.component, userHandle, null, null)
    } ?: showToastShort(context, "Unable to to open app info")
}

fun openDialerApp(context: Context) {
    try {
        val sendIntent = Intent(Intent.ACTION_DIAL)
        context.startActivity(sendIntent)
    } catch (e: java.lang.Exception) {

    }
}

fun openCameraApp(context: Context) {
    try {
        val sendIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        context.startActivity(sendIntent)
    } catch (e: java.lang.Exception) {

    }
}

fun openAlarmApp(context: Context) {
    try {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        context.startActivity(intent)
    } catch (e: java.lang.Exception) {
        Log.d("TAG", e.toString())
    }
}

fun openCalendar(context: Context) {
    try {
        val cal: Calendar = Calendar.getInstance()
        cal.time = Date()
        val time = cal.time.time
        val builder: Uri.Builder = CalendarContract.CONTENT_URI.buildUpon()
        builder.appendPath("time")
        builder.appendPath(time.toString())
        context.startActivity(Intent(Intent.ACTION_VIEW, builder.build()))
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_CALENDAR)
            context.startActivity(intent)
        } catch (e: Exception) {
        }
    }
}

fun isTablet(context: Context): Boolean {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    windowManager.defaultDisplay.getMetrics(metrics)
    val widthInches = metrics.widthPixels / metrics.xdpi
    val heightInches = metrics.heightPixels / metrics.ydpi
    val diagonalInches = sqrt(widthInches.toDouble().pow(2.0) + heightInches.toDouble().pow(2.0))
    if (diagonalInches >= 7.0) return true
    return false
}

fun initActionService(context: Context): ActionService? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val actionService = ActionService.instance()
        if (actionService != null) {
            return actionService
        } else {
            openAccessibilitySettings(context)
        }
    } else {
        showToastLong(context, "This action requires Android P (9) or higher" )
    }

    return null
}

fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    val cs = ComponentName(context.packageName, ActionService::class.java.name).flattenToString()
    val bundle = Bundle()
    bundle.putString(":settings:fragment_args_key", cs)
    intent.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(":settings:fragment_args_key", cs)
        putExtra(":settings:show_fragment_args", bundle)
    }
    context.startActivity(intent)
}

fun showStatusBar(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        activity.window.insetsController?.show(WindowInsets.Type.statusBars())
    else
        @Suppress("DEPRECATION", "InlinedApi")
        activity.window.decorView.apply {
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
}

fun hideStatusBar(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        activity.window.insetsController?.hide(WindowInsets.Type.statusBars())
    else {
        @Suppress("DEPRECATION")
        activity.window.decorView.apply {
            systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }
}

fun uninstallApp(context: Context, appPackage: String) {
    val intent = Intent(Intent.ACTION_DELETE)
    intent.data = Uri.parse("package:$appPackage")
    context.startActivity(intent)
}

fun dp2px(resources: Resources, dp: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()
}
fun storeFile(activity: Activity) {
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, "backup.txt")
    }
    ActivityCompat.startActivityForResult(activity, intent, BACKUP_WRITE, null)
}

fun loadFile(activity: Activity) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/plain"
    }
    ActivityCompat.startActivityForResult(activity, intent, BACKUP_READ, null)
}

@Suppress("SpellCheckingInspection")
@SuppressLint("WrongConstant")
fun expandNotificationDrawer(context: Context) {
    // Source: https://stackoverflow.com/a/51132142
    try {
        val statusBarService = context.getSystemService("statusbar")
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val method = statusBarManager.getMethod("expandNotificationsPanel")
        method.invoke(statusBarService)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Suppress("SpellCheckingInspection")
@SuppressLint("WrongConstant")
fun expandQuickSettings(context: Context) {
    try {
        val statusBarService = context.getSystemService("statusbar")
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val method = statusBarManager.getMethod("expandSettingsPanel")
        method.invoke(statusBarService)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Get package names of apps that are already quick-accessible via home screen or gestures.
 * These should be deprioritized in the "most used" sort since they're already easy to access.
 */
fun getQuickAccessApps(prefs: Prefs): Set<String> {
    val quickAccessApps = mutableSetOf<String>()

    // Home screen apps
    for (i in 0 until Constants.MAX_HOME_APPS) {
        val app = prefs.getHomeAppModel(i)
        if (app.appPackage.isNotEmpty()) {
            quickAccessApps.add(app.appPackage)
        }
    }

    // Gesture apps
    listOf(
        prefs.appSwipeLeft,
        prefs.appSwipeRight,
        prefs.appSwipeUp,
        prefs.appSwipeDown,
        prefs.appClickClock,
        prefs.appClickDate,
        prefs.appDoubleTap
    ).forEach { app ->
        if (app.appPackage.isNotEmpty()) {
            quickAccessApps.add(app.appPackage)
        }
    }

    return quickAccessApps
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun openUsageAccessSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Get app usage scores with weighted recency.
 * Recent usage (last 7 days) is weighted 3x more than older usage (8-30 days).
 * Returns a map of package name to usage score.
 */
fun getAppUsageScores(context: Context): Map<String, Long> {
    if (!hasUsageStatsPermission(context)) {
        return emptyMap()
    }

    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val startTime30Days = endTime - (30L * 24 * 60 * 60 * 1000)
    val startTime7Days = endTime - (7L * 24 * 60 * 60 * 1000)

    val scores = mutableMapOf<String, Long>()

    try {
        // Get stats for the last 30 days
        val stats30Days = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime30Days,
            endTime
        )

        // Get stats for the last 7 days (weighted more heavily)
        val stats7Days = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime7Days,
            endTime
        )

        // Aggregate older usage (8-30 days) with weight 1
        val recentPackages = stats7Days.map { it.packageName }.toSet()
        for (stat in stats30Days) {
            if (stat.packageName !in recentPackages) {
                val current = scores.getOrDefault(stat.packageName, 0L)
                // Use total time in foreground as a proxy for usage
                scores[stat.packageName] = current + stat.totalTimeInForeground
            }
        }

        // Add recent usage (last 7 days) with weight 3
        for (stat in stats7Days) {
            val current = scores.getOrDefault(stat.packageName, 0L)
            scores[stat.packageName] = current + (stat.totalTimeInForeground * 3)
        }
    } catch (e: Exception) {
        Log.e("UsageStats", "Error getting usage stats: $e")
    }

    return scores
}
