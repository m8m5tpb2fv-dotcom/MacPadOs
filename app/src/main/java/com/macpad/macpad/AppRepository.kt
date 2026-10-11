package com.macpad.macpad

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * One launchable activity of an installed app (an app can expose several, and the same app can
 * exist in a work / cloned-app profile).
 *
 * [key] is stable across restarts and is what Dock pins and desktop shortcuts store.
 */
data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val isMainProfile: Boolean,
    val icon: Bitmap,
) {
    /** Lower-cased label used for case-insensitive search. */
    val searchLabel: String = AppRepository.normalize(label)
}

enum class LaunchResult { OK, PROFILE_PAUSED, FAILED }

/**
 * Loads the list of launchable apps through [LauncherApps] (all user profiles visible to the
 * launcher), renders uniform icons off the main thread, keeps the list fresh through
 * [LauncherApps.Callback], and launches apps.
 *
 * Package visibility: the manifest declares a `<queries>` entry for MAIN/LAUNCHER intents, which
 * is exactly what a launcher needs on Android 11+; QUERY_ALL_PACKAGES is not required.
 */
class AppRepository(context: Context) {

    private val ctx = context.applicationContext
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val userManager = ctx.getSystemService(UserManager::class.java)
    private val pm: PackageManager = ctx.packageManager
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconCache = ConcurrentHashMap<String, Bitmap>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var registered = false
    private val reloadRunnable = Runnable { reload() }

    /** Size in px of rendered icon bitmaps. */
    val iconSizePx: Int = ctx.dp(80).coerceIn(96, 224)

    /** Current sorted list (main thread). Empty until the first load completes. */
    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    /** True once the first load has completed. */
    var isLoaded = false
        private set

    @Volatile
    private var lastSignature: String? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach { changed(it) }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach { changed(it) }
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) =
            packageNames.forEach { changed(it) }
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) =
            packageNames.forEach { changed(it) }
    }

    private fun changed(packageName: String) {
        val prefix = "$packageName/"
        iconCache.keys.filter { it.startsWith(prefix) }.forEach { iconCache.remove(it) }
        // Several callbacks usually arrive together (e.g. during an update): coalesce them.
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 300)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Starts listening for package changes and performs the first load. */
    fun start() {
        if (!registered) {
            launcherApps.registerCallback(callback, main)
            registered = true
        }
        reload()
    }

    fun stop() {
        if (registered) {
            launcherApps.unregisterCallback(callback)
            registered = false
        }
        main.removeCallbacks(reloadRunnable)
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    /** Reloads the full list in the background and notifies listeners on the main thread. */
    fun reload() {
        // A posted reload or a menu action can arrive after shutdown(): execute() would throw.
        if (executor.isShutdown) return
        executor.execute {
            val list = try {
                loadAll()
            } catch (e: Exception) {
                null
            }
            if (list != null) {
                lastSignature = signatureOf(list.map { it.key })
                main.post {
                    apps = list
                    isLoaded = true
                    listeners.forEach { it() }
                }
            }
        }
    }

    /**
     * Cheap check used when MacPad returns to the foreground: reloads only if the set of
     * launchable activities differs from what is shown (e.g. a callback was missed).
     */
    fun refreshIfChanged() {
        if (executor.isShutdown) return
        executor.execute {
            val keys = try {
                profiles().flatMap { user ->
                    val serial = userManager.getSerialNumberForUser(user)
                    launcherApps.getActivityList(null, user)
                        .filter { it.componentName.packageName != ctx.packageName }
                        .map { keyOf(it.componentName, serial) }
                }
            } catch (e: Exception) {
                return@execute
            }
            if (signatureOf(keys) != lastSignature) main.post { reload() }
        }
    }

    private fun signatureOf(keys: List<String>): String = keys.distinct().sorted().joinToString("|")

    private fun profiles(): List<UserHandle> = try {
        launcherApps.profiles.ifEmpty { listOf(Process.myUserHandle()) }
    } catch (e: Exception) {
        listOf(Process.myUserHandle())
    }

    private fun keyOf(component: ComponentName, serial: Long) = "${component.flattenToShortString()}#$serial"

    private fun loadAll(): List<AppEntry> {
        val me = Process.myUserHandle()
        val result = LinkedHashMap<String, AppEntry>()
        for (user in profiles()) {
            val serial = userManager.getSerialNumberForUser(user)
            val activities: List<LauncherActivityInfo> = try {
                launcherApps.getActivityList(null, user)
            } catch (e: Exception) {
                emptyList()
            }
            for (info in activities) {
                val cn = info.componentName
                if (cn.packageName == ctx.packageName) continue
                val key = keyOf(cn, serial)
                if (result.containsKey(key)) continue
                val label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName }
                val icon = iconCache[key] ?: renderIcon(info, user, me).also { iconCache[key] = it }
                result[key] = AppEntry(
                    key = key,
                    label = label,
                    packageName = cn.packageName,
                    component = cn,
                    user = user,
                    isMainProfile = user == me,
                    icon = icon,
                )
            }
        }
        // Drop icons for apps that no longer exist.
        iconCache.keys.retainAll(result.keys)
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        return result.values.sortedWith { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.key.compareTo(b.key)
        }
    }

    private fun renderIcon(info: LauncherActivityInfo, user: UserHandle, me: UserHandle): Bitmap {
        val drawable = try {
            info.getIcon(DisplayMetrics.DENSITY_XXXHIGH)
        } catch (e: Exception) {
            null
        } ?: pm.defaultActivityIcon
        var bmp = try {
            IconRenderer.renderAppIcon(drawable, iconSizePx)
        } catch (e: Exception) {
            IconRenderer.renderBuiltin(BuiltinIcon.APP_INFO, iconSizePx)
        }
        if (user != me) {
            // Add the system work/clone badge so the two copies of an app can be told apart.
            try {
                val badged = pm.getUserBadgedIcon(BitmapDrawable(ctx.resources, bmp), user)
                val out = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
                badged.setBounds(0, 0, iconSizePx, iconSizePx)
                badged.draw(Canvas(out))
                bmp = out
            } catch (_: Exception) {
            }
        }
        return bmp
    }

    fun findByKey(key: String): AppEntry? = apps.firstOrNull { it.key == key }

    fun findByPackage(packageName: String): AppEntry? =
        apps.firstOrNull { it.packageName == packageName && it.isMainProfile }
            ?: apps.firstOrNull { it.packageName == packageName }

    /**
     * Case-insensitive search by app name. Exact and prefix matches come first, then matches at
     * the start of a word, then anywhere in the name; ties keep alphabetical order.
     */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<AppEntry> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return apps.take(limit)
        return apps.mapNotNull { a ->
            val l = a.searchLabel
            val score = when {
                l == q -> 0
                l.startsWith(q) -> 1
                l.split(' ', '-', '_', '.', '(', ')').any { it.startsWith(q) } -> 2
                l.contains(q) -> 3
                else -> return@mapNotNull null
            }
            score to a
        }.sortedBy { it.first }.map { it.second }.take(limit)
    }

    /**
     * Launches [entry] the way a launcher should: through LauncherApps (works for work/clone
     * profiles), falling back to an explicit intent and finally the package's launch intent.
     * [source] (if given) is used for the scale-up launch animation.
     */
    fun launch(activity: Activity, entry: AppEntry, source: View?): LaunchResult {
        try {
            if (userManager.isQuietModeEnabled(entry.user)) return LaunchResult.PROFILE_PAUSED
        } catch (_: Exception) {
        }
        var bounds: Rect? = null
        var opts: android.os.Bundle? = null
        if (source != null && source.isAttachedToWindow && source.width > 0) {
            val loc = IntArray(2)
            source.getLocationOnScreen(loc)
            bounds = Rect(loc[0], loc[1], loc[0] + source.width, loc[1] + source.height)
            opts = ActivityOptions.makeScaleUpAnimation(source, 0, 0, source.width, source.height).toBundle()
        }
        try {
            launcherApps.startMainActivity(entry.component, entry.user, bounds, opts)
            return LaunchResult.OK
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        if (entry.user == Process.myUserHandle()) {
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(entry.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                activity.startActivity(intent, opts)
                return LaunchResult.OK
            } catch (_: Exception) {
            }
            try {
                val intent = pm.getLaunchIntentForPackage(entry.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent, opts)
                    return LaunchResult.OK
                }
            } catch (_: Exception) {
            }
        }
        // The list is probably stale (app disabled/removed): refresh it.
        reload()
        return LaunchResult.FAILED
    }

    /** Opens the system "App info" screen for [entry]. Returns false if it could not be opened. */
    fun openSystemAppInfo(activity: Activity, entry: AppEntry): Boolean {
        try {
            launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
            return true
        } catch (_: Exception) {
        }
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", entry.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sensible first-run Dock pins: system Settings, the default browser, an app store, files,
     * camera and gallery — whichever of these are actually installed.
     */
    fun defaultDockKeys(): List<String> {
        val keys = mutableListOf<String>()
        fun addPkg(vararg pkgs: String) {
            for (p in pkgs) {
                val e = findByPackage(p)
                if (e != null && !keys.contains(e.key)) {
                    keys.add(e.key)
                    return
                }
            }
        }
        addPkg("com.android.settings")
        try {
            val browser = pm.resolveActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
            if (browser != null && browser != "android") addPkg(browser)
        } catch (_: Exception) {
        }
        addPkg("com.android.chrome", "com.hihonor.browser", "org.mozilla.firefox", "com.huawei.browser")
        addPkg("com.android.vending", "com.hihonor.appmarket", "com.huawei.appmarket")
        addPkg("com.hihonor.filemanager", "com.google.android.apps.nbu.files", "com.android.documentsui")
        addPkg("com.hihonor.camera", "com.android.camera", "com.google.android.GoogleCamera")
        addPkg("com.hihonor.photos", "com.google.android.apps.photos", "com.android.gallery3d")
        return keys.distinct()
    }

    companion object {
        fun normalize(s: String): String = s.lowercase(Locale.getDefault()).replace('ё', 'е')
    }
}
