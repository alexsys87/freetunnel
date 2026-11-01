package org.freetunnel.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors

/**
 * Keeps the geo lists of the chosen mode (GeoRouting) up to date, as the desktop's GeoListUpdater does: the lists a mode
 * needs are downloaded when it is chosen, checked again once a day while the daily check is on, and tried again an hour after
 * a failed attempt. It runs in the app's process while the app or the VPN is: the app checks when it starts and when a mode
 * or a source is changed, and the VPN service while it runs. A session that is running is built again when the entries it
 * routes by change (FreeTunnelVpnService.reroute).
 *
 * The files and what is built from them are GeoStore's.
 */
object GeoUpdater {
    private const val PREFS = "freetunnel"
    // A stalled read is given up on; the deadline ends a transfer that trickles.
    private const val READ_TIMEOUT_MS = 60_000
    private const val TRANSFER_DEADLINE_MS = 15 * 60_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "freetunnel-geo").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val TIMER = Any()
    private val listeners = LinkedHashSet<Runnable>()
    @Volatile private var busy = false
    // A check asked for while one runs, and whether it was to check everything.
    @Volatile private var queued = false
    @Volatile private var queuedForce = false

    @JvmStatic fun store(context: Context): GeoStore = GeoStore(File(context.noBackupFilesDir, "geo"))

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic fun mode(prefs: SharedPreferences): GeoRouting.Mode = GeoRouting.Mode.of(prefs.getString(GeoRouting.MODE_KEY, null))
    @JvmStatic fun autoUpdate(prefs: SharedPreferences): Boolean = prefs.getBoolean(GeoRouting.AUTO_UPDATE_KEY, true)

    /** The sources the user chose, by slot id; a slot missing here reads its default. */
    @JvmStatic fun sources(prefs: SharedPreferences): Map<String, String> =
        GeoRouting.SLOTS.mapNotNull { slot -> prefs.getString(GeoRouting.SOURCE_KEY_PREFIX + slot.id, null)?.takeIf { it.isNotBlank() }?.let { slot.id to it } }.toMap()

    /** Whether lists are being downloaded now. */
    @JvmStatic fun busy(): Boolean = busy

    /** Called on the main thread whenever busy or the lists change. The app's screen registers while it is shown. */
    @JvmStatic fun observe(listener: Runnable) { synchronized(listeners) { listeners.add(listener) } }
    @JvmStatic fun unobserve(listener: Runnable) { synchronized(listeners) { listeners.remove(listener) } }
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it.run() } }

    /**
     * Checks the lists of the chosen mode that are due, or all of them with [force], in the background. A check asked for
     * while one runs follows it.
     */
    @JvmStatic @JvmOverloads fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(TIMER)
        if (busy) { queuedForce = queuedForce || force; queued = true; return }
        worker.execute { run(app, force) }
    }

    private fun run(context: Context, force: Boolean) {
        val prefs = prefs(context)
        val mode = mode(prefs)
        val sources = sources(prefs)
        val store = store(context)
        publish(store, mode, sources)
        val due = store.due(mode, sources, autoUpdate(prefs), force, System.currentTimeMillis())
        if (due.isNotEmpty()) {
            busy = true
            changed()
            val error = try {
                store.update(mode, sources, due, ::fetch) { System.currentTimeMillis() }
            } catch (failure: RuntimeException) {
                failure.javaClass.simpleName
            }
            // As the desktop logs it (BackendGeo.cpp).
            val status = publish(store, mode, sources)
            if (error.isEmpty()) log(context, "Geo lists checked: ${status.sites} sites, ${status.addresses} addresses")
            else log(context, "Geo lists: $error", LogLines.WARN)
            busy = false
        }
        // A running session takes in entries other than those it was built with: a list that changed, a source the user
        // chose, the first lists of a mode. Not when the mode or the sources have changed since this began: the start of the
        // check that follows does it.
        if (mode(prefs) == mode && sources(prefs) == sources) {
            val routed = FreeTunnelVpnService.routedGeo()
            if (routed != null && routed != store.version(mode, sources)) FreeTunnelVpnService.reroute(context)
        }
        changed()
        if (queued) {
            val again = queuedForce
            queued = false
            queuedForce = false
            worker.execute { run(context, again) }
            return
        }
        schedule(context)
    }

    @Volatile private var shown: GeoStore.Status? = null
    @Volatile private var shownMode: GeoRouting.Mode? = null

    private fun publish(store: GeoStore, mode: GeoRouting.Mode, sources: Map<String, String>): GeoStore.Status {
        val status = store.status(mode, sources)
        shown = status
        shownMode = mode
        return status
    }

    /**
     * What the window shows of the lists of [mode], as the last check left them; null while none has been made for it since
     * the app started, or since the mode or a source changed (check() makes one).
     */
    @JvmStatic fun status(mode: GeoRouting.Mode): GeoStore.Status? = shown.takeIf { shownMode == mode }

    /** The mode or a source changed: the status is the new one's once check() has read it. */
    @JvmStatic fun forget() { shown = null; shownMode = null; changed() }

    private fun log(context: Context, message: String, level: String = LogLines.INFO) {
        if (!prefs(context).getBoolean("logging", true)) return
        runCatching {
            LogFile.append(File(context.filesDir, LogFile.NAME),
                LogLines.entry(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), level, message))
        }
    }

    // When the next list is due, at the latest an hour from now so that a clock that jumps (sleep, a time zone change)
    // is caught up with soon after. The handler's time stops in deep sleep, and nothing here wakes the phone.
    private fun schedule(context: Context) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val next = store(context).nextDue(mode(prefs), sources(prefs), autoUpdate(prefs), now)
        main.removeCallbacksAndMessages(TIMER)
        if (next < 0) return
        val delay = (next - now).coerceIn(0, GeoStore.RETRY_INTERVAL_MS)
        main.postAtTime({ check(context) }, TIMER, SystemClock.uptimeMillis() + delay)
    }

    // https only, to https only after a redirect (GitHub's latest/download addresses redirect twice, the second time to
    // another host), a size limit, and the deadline.
    private fun fetch(url: String, maximum: Long): ByteArray {
        val deadline = SystemClock.elapsedRealtime() + TRANSFER_DEADLINE_MS
        var current = URI(url)
        repeat(6) { redirect ->
            if (current.scheme != "https" || current.rawUserInfo != null || current.host.isNullOrEmpty()) throw IOException("not an https address")
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "FreeTunnel-Android/${BuildConfig.VERSION_NAME}")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirect >= 5) throw IOException("too many redirects")
                    current = current.resolve(connection.getHeaderField("Location") ?: throw IOException("a redirect without an address"))
                    return@repeat
                }
                if (status != 200) throw IOException("HTTP $status")
                if (connection.contentLengthLong > maximum) throw IOException("the file is larger than ${maximum / (1024 * 1024)} MB")
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream(connection.contentLengthLong.coerceIn(0, maximum).toInt())
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (SystemClock.elapsedRealtime() > deadline) throw SocketTimeoutException("the server stopped responding")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > maximum) throw IOException("the file is larger than ${maximum / (1024 * 1024)} MB")
                        out.write(buffer, 0, count)
                    }
                    return out.toByteArray()
                }
            } catch (timeout: SocketTimeoutException) {
                throw IOException("the server stopped responding", timeout)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
