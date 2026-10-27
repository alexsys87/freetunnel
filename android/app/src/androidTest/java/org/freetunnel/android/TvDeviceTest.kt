package org.freetunnel.android

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.nayuki.qrcodegen.QrCode
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

// Android TV: the TV launcher entry, a focus stop with a key and the desktop's focus
// look on every control, focus and scroll kept when render() rebuilds the views, the
// overlays that survive it and take the focus, Back, the screens TVs leave out, and
// Receive from phone. Both device legs run it: the TV leg for the TV, the phone leg
// for phones, which must work as before.
class TvDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("freetunnel", Context.MODE_PRIVATE)
    private val isTv get() =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    private var oldPreferences: Map<String, *> = emptyMap<String, Any>()
    private var activity: MainActivity? = null
    private var touchModeBefore: Boolean? = null
    private val source = """
        vpn_mode = "general"
        killswitch_enabled = true
        exclusions = []
        [endpoint]
        hostname = "vpn.invalid"
        addresses = ["192.0.2.1:443"]
        username = "test"
        password = "test"
        upstream_protocol = "http2"
        has_ipv6 = false
        [listener.tun]
        included_routes = ["0.0.0.0/0", "::/0"]
        excluded_routes = []
        mtu_size = 1350
    """.trimIndent()

    private fun seed(count: Int) {
        val profiles = org.json.JSONArray()
        for (i in 0 until count) profiles.put(org.json.JSONObject().put("name", "Profile $i").put("toml", source))
        assertTrue(ConfigStore.write(prefs, profiles.toString()))
    }
    @Before fun setup() {
        oldPreferences = prefs.all
        prefs.edit().clear().putBoolean("logging", false).putStringSet("domains", setOf("example.com", "example.org")).commit()
        // Another installed app this one can see, for an app row on the routing page.
        val pm = context.packageManager
        val other = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
            .map { it.activityInfo.packageName }.firstOrNull { it != context.packageName }
        if (other != null) prefs.edit().putStringSet("apps", setOf(other)).commit()
        seed(2)
    }
    @After fun cleanup() {
        activity?.let { opened ->
            // While this app's window has the focus, which Android requires to change it.
            touchModeBefore?.let { instrumentation.setInTouchMode(it) }
            instrumentation.runOnMainSync { opened.finish() }
        }
        serviceState(0)
        updater("officialBuild", null); updater("certificatePin", BuildConfig.RELEASE_CERTIFICATE_SHA256)
        updater("state", AndroidUpdater.State.IDLE)
        val editor = prefs.edit().clear()
        oldPreferences.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
        editor.commit()
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun method(name: String, vararg types: Class<*>?) =
        MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
    private fun updater(name: String, value: Any?) =
        AndroidUpdater::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    private fun serviceState(state: Int) =
        FreeTunnelVpnService::class.java.getDeclaredField("currentState").apply { isAccessible = true }.setInt(null, state)
    // apksigner's digest of the debug signer, which Android CI passes on both legs. As
    // the pin, it makes this debug build official, so Settings shows its update action.
    private fun officialBuild() {
        val digest = InstrumentationRegistry.getArguments().getString("debugCertificate")
        assertTrue("Run with -Pandroid.testInstrumentationRunnerArguments.debugCertificate=<apksigner's SHA-256 digest>, as Android CI does",
            digest != null && Regex("[0-9a-f]{64}").matches(digest))
        updater("officialBuild", null); updater("certificatePin", digest)
    }
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    // Runs block on the main thread and returns what it returns. What it throws is
    // thrown again here: a failed assertion on the main thread would crash the
    // instrumentation process and skip every later test.
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    private fun eventually(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(what, condition())
    }
    private fun open(): MainActivity {
        val opened = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        activity = opened
        instrumentation.waitForIdleSync()
        return opened
    }
    private fun inTouchMode(a: MainActivity) = onMain { a.window.decorView.isInTouchMode }
    // As a remote's first key press does. A phone starts in touch mode, a TV without.
    private fun leaveTouchMode(a: MainActivity) {
        if (touchModeBefore == null) touchModeBefore = inTouchMode(a)
        instrumentation.setInTouchMode(false)
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (inTouchMode(a) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        // The first arrow press leaves touch mode as well, and is used up by that.
        if (inTouchMode(a)) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        eventually("The window must leave touch mode") { !inTouchMode(a) }
    }
    // The window's one view: the page, the overlays and the toast. pageRoot is the page alone.
    private fun root(a: MainActivity) = field("host").get(a) as ViewGroup
    private fun pageRoot(a: MainActivity) = field("pageRoot").get(a) as ViewGroup
    private fun theme(a: MainActivity) = Theme.of(field("dark").getBoolean(a))
    // Shows the page as a tab or Back would: render() rebuilds every view.
    private fun render(a: MainActivity, page: Int) {
        onMain { field("page").setInt(a, page); method("render").invoke(a) }
        instrumentation.waitForIdleSync()
    }
    private fun focusedKey(a: MainActivity): String? = onMain { a.currentFocus?.tag as? String }
    private fun focusOn(a: MainActivity, key: String) = onMain {
        val view = root(a).findViewWithTag<View>(key)
        assertNotNull("No control has the key $key", view)
        assertTrue("$key must take the focus", view.requestFocus())
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun shows(a: MainActivity, text: String): Boolean = onMain {
        ArrayList<View>().also { a.window.decorView.findViewsWithText(it, text, View.FIND_VIEWS_WITH_TEXT) }.isNotEmpty()
    }

    @Test fun theTvLauncherOpensMainActivityWithTheBanner() {
        val pm = context.packageManager
        assertEquals(MainActivity::class.java.name, pm.getLeanbackLaunchIntentForPackage(context.packageName)?.component?.className)
        assertEquals(MainActivity::class.java.name, pm.getLaunchIntentForPackage(context.packageName)?.component?.className)
        assertNotEquals("A TV launcher shows the application's banner", 0, context.applicationInfo.banner)
        // What these tests expect of a TV follows the ui mode, and Android CI tells the
        // TV leg by the leanback feature. A TV image without a TV ui mode would pass the
        // TV leg with a phone's expectations.
        assertEquals("A TV, and only a TV, reports a TV ui mode", pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK), isTv)
    }

    // What a control looks like at rest, pressed and focused, read from its background: the fill
    // of each state, and the colour of the edge, which a border has no getter for, so it is the
    // pixel at the middle of the left edge of what the background draws in that state.
    private class FocusLook(val restFill: Int, val focusedFill: Int, val restEdge: Int, val focusedEdge: Int)
    private fun fillOf(drawable: Drawable) = (drawable as GradientDrawable).color?.defaultColor ?: Color.TRANSPARENT
    private fun edgeOf(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 160, 240)
        drawable.draw(Canvas(bitmap))
        return bitmap.getPixel(0, 120)
    }
    private fun focusLook(view: View): FocusLook {
        assertNull("${view.javaClass.simpleName} \"${view.tag}\" has a foreground: a ring, not the desktop's look", view.foreground)
        val states = view.background as? StateListDrawable
        assertTrue("${view.javaClass.simpleName} \"${view.tag}\" has no background that changes with its state", states != null && states.isStateful)
        states!!
        val saved = states.state
        try {
            states.setState(intArrayOf())
            val rest = states.current
            val restFill = fillOf(rest)
            val restEdge = edgeOf(rest)
            states.setState(intArrayOf(android.R.attr.state_focused))
            val focused = states.current
            return FocusLook(restFill, fillOf(focused), restEdge, edgeOf(focused))
        } finally {
            states.setState(saved)
            view.refreshDrawableState()
        }
    }
    // A control shows its focus as its background and nothing else: no foreground, a background that
    // changes with the state, and with the focus the accent: as a fill, as danger for a destructive
    // control (a ChipX), and as the border of a text field.
    private fun assertFocusLook(view: View, name: String, theme: Theme) {
        val look = focusLook(view)
        val key = view.tag as String
        when {
            view is EditText || key == "apps:add" -> {
                assertEquals("$name: the border of a focused field", theme.accent, look.focusedEdge)
                assertEquals("$name: the border of a field at rest", theme.inputBorder, look.restEdge)
            }
            Regex("(domain|app):\\d+").matches(key) -> assertEquals("$name: a ChipX focuses in danger", theme.danger, look.focusedFill)
            else -> assertEquals("$name: the focus is the accent", theme.accent, look.focusedFill)
        }
    }

    @Test fun everyControlIsAFocusStopWithAKeyAndTheDesktopFocusLook() {
        // Reconnect shows only while traffic is blocked, and Settings' update action
        // only in an official build.
        serviceState(6)
        officialBuild()
        val a = open()
        leaveTouchMode(a)
        for (page in 0..4) {
            render(a, page)
            onMain {
                val controls = descendants(root(a)).filter { it.isShown && it.isClickable }.toList()
                assertTrue("Page $page shows no controls", controls.isNotEmpty())
                for (view in controls) {
                    // The log, selectable on a phone, is a control of its own there.
                    if (view is TextView && view.isTextSelectable) { assertFalse("Selectable text on a TV", isTv); continue }
                    val name = "${view.javaClass.simpleName} \"${(view as? TextView)?.text ?: view.contentDescription}\" on page $page"
                    assertTrue("$name is no focus stop", view.isFocusable)
                    assertTrue("$name has no key", view.tag is String)
                    assertFocusLook(view, name, theme(a))
                }
            }
        }
        render(a, 0)
        assertNotNull(onMain { root(a).findViewWithTag<View>("home:reconnect") })
        render(a, 3)
        assertNotNull(onMain { root(a).findViewWithTag<View>("settings:update") })
    }

    // A tab rests on the page, and the current one on infoBg, over it; with the focus, any of them
    // is the accent, the current one too, so that where the remote is never looks like where the app is.
    @Test fun theCurrentTabRestsOnInfoBgAndEveryTabFocusesInTheAccent() {
        val a = open()
        leaveTouchMode(a)
        for (page in 0..4) {
            render(a, page)
            onMain {
                val theme = theme(a)
                for (tab in 0..4) {
                    val look = focusLook(root(a).findViewWithTag<View>("tab:$tab"))
                    assertEquals("Tab $tab on page $page", if (tab == page) theme.infoBg else Color.TRANSPARENT, look.restFill)
                    assertEquals("Tab $tab on page $page focuses in the accent", theme.accent, look.focusedFill)
                }
            }
        }
    }

    // The focus is around what a control holds, never across the screen: Home's selector, the links
    // and the values of the dropdowns hug their words.
    @Test fun theSelectorAndTheLinksAreNoWiderThanTheirWords() {
        serviceState(6)
        officialBuild()
        val a = open()
        leaveTouchMode(a)
        render(a, 0)
        onMain {
            val selector = root(a).findViewWithTag<TextView>("home:config")
            assertTrue("The selector is ${selector.width} wide on a ${root(a).width} wide screen", selector.width < root(a).width / 2)
        }
        var links = 0
        for (page in 0..4) {
            render(a, page)
            onMain {
                for (link in descendants(root(a)).filterIsInstance<Ui.LinkText>().filter { it.isShown }) {
                    links++
                    val words = Math.ceil(link.paint.measureText(link.text.toString()).toDouble()).toInt()
                    assertTrue("\"${link.text}\" is ${link.width} wide: its words are $words, its padding ${link.paddingLeft + link.paddingRight}",
                        link.width <= words + link.paddingLeft + link.paddingRight + 1)
                }
                for (key in listOf("settings:theme", "split:mode"))
                    root(a).findViewWithTag<View?>(key)?.let { assertTrue("$key spans the screen", it.width < root(a).width / 2) }
            }
        }
        assertTrue("There are links to check", links >= 6)
    }

    @Test fun theArrowsReachEveryControl() {
        serviceState(6)
        officialBuild()
        val a = open()
        leaveTouchMode(a)
        for (page in 0..4) {
            render(a, page)
            onMain {
                val content = root(a)
                val keys = descendants(content).filter { it.isShown && it.isFocusable }.mapNotNull { it.tag as? String }.toSet()
                // Every control the arrows reach from the page's tab, one press at a time.
                val seen = HashSet<View>()
                val queue = ArrayDeque<View>().apply { add(content.findViewWithTag<View>("tab:$page")) }
                while (queue.isNotEmpty()) {
                    val view = queue.removeFirst()
                    if (!seen.add(view)) continue
                    for (direction in listOf(View.FOCUS_UP, View.FOCUS_DOWN, View.FOCUS_LEFT, View.FOCUS_RIGHT))
                        FocusFinder.getInstance().findNextFocus(content, view, direction)?.let { queue.add(it) }
                }
                assertEquals("Controls on page $page that the arrows never reach", emptySet<String>(),
                    keys - seen.mapNotNull { it.tag as? String }.toSet())
            }
        }
    }

    @Test fun aRemoteLandsOnThePagesMainControl() {
        serviceState(6)
        val a = open()
        leaveTouchMode(a)
        render(a, 1)
        focusOn(a, "config:1:edit")
        // Nothing on Home has that key, as after Back from Configurations on a TV.
        render(a, 0)
        eventually("Reconnect first, while traffic is blocked") { focusedKey(a) == "home:reconnect" }
        serviceState(0)
        onMain { field("vpnState").setInt(a, 0) }
        render(a, 0)
        eventually("then Connect") { focusedKey(a) == "home:toggle" }
        render(a, 2)
        eventually("and the page's tab elsewhere") { focusedKey(a) == "tab:2" }
        // A remote's keys.
        render(a, 1)
        focusOn(a, "config:0:select")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        eventually("Right from Select") { focusedKey(a) == "config:0:edit" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        eventually("Down to the next configuration") { focusedKey(a) == "config:1:edit" }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun focusAndScrollSurviveARebuild() {
        seed(12)
        val a = open()
        leaveTouchMode(a)
        render(a, 1)
        focusOn(a, "config:10:edit")
        instrumentation.waitForIdleSync()
        fun scrollY() = onMain { (field("pageScroll").get(a) as ScrollView).scrollY }
        val y = scrollY()
        assertTrue("Twelve configurations must scroll, or this shows nothing", y > 0)
        // As a ping result, Select or a saved edit rebuilds it.
        render(a, 1)
        eventually("The focus must stay") { focusedKey(a) == "config:10:edit" }
        assertEquals(y, scrollY())
        // Twice before a frame, as the last ping result and the end of the ping: the
        // second finds the first one's views, not focused or scrolled yet.
        onMain { method("render").invoke(a); method("render").invoke(a) }
        instrumentation.waitForIdleSync()
        eventually("The focus must stay over two rebuilds in one frame") { focusedKey(a) == "config:10:edit" }
        assertEquals(y, scrollY())
        // Deleting the focused one moves the focus to the one before.
        focusOn(a, "config:11:remove")
        onMain { method("confirmRemove", Int::class.javaPrimitiveType).invoke(a, 11) }
        instrumentation.waitForIdleSync()
        onMain {
            WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(android.R.id.button1) != null }
                .findViewById<Button>(android.R.id.button1).performClick()
        }
        eventually("The focus must move to the one before") { focusedKey(a) == "config:10:remove" }
        // A VPN state broadcast rebuilds Home.
        render(a, 0)
        focusOn(a, "home:config")
        val before = onMain { root(a) }
        context.sendBroadcast(Intent(FreeTunnelVpnService.ACTION_STATE).setPackage(context.packageName))
        eventually("The broadcast must rebuild Home") { onMain { root(a) } !== before }
        eventually("The focus must stay") { focusedKey(a) == "home:config" }
    }

    @Test fun inTouchModeARebuildFocusesNothing() {
        val a = open()
        if (touchModeBefore == null) touchModeBefore = inTouchMode(a)
        instrumentation.setInTouchMode(true)
        eventually("The window must be in touch mode") { inTouchMode(a) }
        render(a, 2)
        // A tap focuses the text field. Restored after a rebuild, the focus would
        // bring up the keyboard by itself.
        onMain { assertTrue(root(a).findViewWithTag<View>("domains:input").requestFocus()) }
        render(a, 2)
        assertNull(onMain { a.currentFocus })
    }

    @Test fun backGoesHomeFirstOnATv() {
        val a = open()
        render(a, 3)
        if (isTv) {
            leaveTouchMode(a)
            // Where OK on the Settings tab leaves it.
            focusOn(a, "tab:3")
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        if (isTv) {
            assertEquals("Back on a TV goes to Home", 0, onMain { field("page").getInt(a) })
            eventually("on Home's own control: on the Settings tab, the next OK would go back") { focusedKey(a) == "home:toggle" }
            assertFalse(onMain { a.isFinishing })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        // From Home on a TV, and from any page on a phone, as before.
        eventually("Back must leave the app") { onMain { a.isFinishing || !a.hasWindowFocus() } }
    }

    @Test fun alwaysOnIsOfferedOnlyWhereAndroidHasIt() {
        val a = open()
        render(a, 3)
        val screen = context.packageManager.resolveActivity(Intent(Settings.ACTION_VPN_SETTINGS), 0) != null
        if (!isTv) assertTrue("Package visibility must not hide Android's VPN settings on a phone", screen)
        assertEquals(screen, shows(a, context.getString(R.string.text_android_vpn_settings)))
        assertEquals(!screen, shows(a, context.getString(R.string.text_no_always_on_here)))
    }

    @Test fun onATvContentKeepsClearOfTheEdges() {
        val a = open()
        render(a, 4)
        val padding = if (isTv) onMain { Math.round(48 * a.resources.displayMetrics.density) } else 0
        eventually("Overscan padding on a TV, none on a phone") { onMain { root(a).paddingLeft } == padding }
        val log = onMain { descendants(pageRoot(a)).filterIsInstance<TextView>().last() }
        assertEquals("On a TV, selectable text would take the arrows", !isTv, onMain { log.isTextSelectable })
    }

    @Test fun aMissingScreenIsReportedInsteadOfCrashing() {
        val a = open()
        render(a, 1)
        // Nothing handles this action, as a TV may have nothing for a file or VPN consent.
        val missing = Intent("org.freetunnel.android.test.NO_SUCH_SCREEN")
        assertEquals(false, onMain { method("startForResult", Intent::class.java, Int::class.javaPrimitiveType).invoke(a, missing, 99) })
        assertFalse(onMain { a.isFinishing })
        // The file picker asks for OPEN_DOCUMENT first, and GET_CONTENT only without it.
        // These monitors answer instead of the pickers, so no other app opens.
        fun picker(action: String) = instrumentation.addMonitor(
            IntentFilter(action, "*/*").apply { addCategory(Intent.CATEGORY_OPENABLE) },
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        val openDocument = picker(Intent.ACTION_OPEN_DOCUMENT)
        val getContent = picker(Intent.ACTION_GET_CONTENT)
        try {
            onMain { method("pickFile").invoke(a) }
            assertEquals(1, openDocument.hits)
            assertEquals(0, getContent.hits)
        } finally {
            instrumentation.removeMonitor(openDocument)
            instrumentation.removeMonitor(getContent)
        }
        // Where there is no OPEN_DOCUMENT, as on such a TV, starting it throws.
        val content = picker(Intent.ACTION_GET_CONTENT)
        val noDocuments = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) throw ActivityNotFoundException("No OPEN_DOCUMENT here") else null
        }.also { instrumentation.addMonitor(it) }
        try {
            onMain { method("pickFile").invoke(a) }
            assertEquals("GET_CONTENT where OPEN_DOCUMENT is missing", 1, content.hits)
            assertFalse(onMain { a.isFinishing })
        } finally {
            instrumentation.removeMonitor(content)
            instrumentation.removeMonitor(noDocuments)
        }
    }

    // The updater's guard (C1 in the TV plan): without the install permission, Install
    // update opens Android's screen for it, or says where the setting is on a TV that
    // has none, and stays available. Taking the permission away stops the app, and
    // these tests with it, so Android CI runs this alone before it grants the
    // permission, with withoutInstallPermission=true. With the permission it is skipped.
    @Test fun installWithoutThePermissionKeepsTheUpdateReady() {
        val allowed = context.packageManager.canRequestPackageInstalls()
        if (InstrumentationRegistry.getArguments().getString("withoutInstallPermission") == "true")
            assertFalse("Run this before granting REQUEST_INSTALL_PACKAGES", allowed)
        else assumeFalse("Runs only without the install permission", allowed)
        val resolved = shell("cmd package resolve-activity --brief -a android.settings.MANAGE_UNKNOWN_APP_SOURCES -d package:${context.packageName}")
        // Where Android has the screen, this answers instead of it, so that no Settings
        // window opens. Where it has none, starting it throws.
        val screen = if (resolved.contains("No activity found")) null else instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { addDataScheme("package") },
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            updater("state", AndroidUpdater.State.READY)
            val a = open()
            onMain { AndroidUpdater.install(a) }
            instrumentation.waitForIdleSync()
            screen?.let { assertEquals("Install update must open Android's screen for the permission", 1, it.hits) }
            assertFalse(onMain { a.isFinishing || a.isDestroyed })
            assertEquals("Install update stays available", AndroidUpdater.State.READY, AndroidUpdater.state)
        } finally {
            screen?.let { instrumentation.removeMonitor(it) }
        }
    }

    // With a remote, the focus must never land on "Show TOML and password" by itself,
    // where an OK shows the password to the room: not as the editor opens, and not
    // after a Save that the check refuses.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun aRemoteNeverLandsOnShowTomlAndPassword() {
        val a = open()
        leaveTouchMode(a)
        onMain { method("editConfig", Int::class.javaPrimitiveType).invoke(a, 0) }
        instrumentation.waitForIdleSync()
        val editor = onMain { WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(android.R.id.button1) != null } }
        // The check runs on the activity's worker and answers on the main thread.
        fun checked() {
            (field("validationWorker").get(a) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
        }
        fun requests() = onMain { field("validationRequest").getLong(a) }
        try {
            eventually("The editor must open on the name") {
                onMain { (editor.findFocus() as? EditText)?.text?.toString() == "Profile 0" }
            }
            val save = onMain { editor.findViewById<Button>(android.R.id.button1) }
            val before = requests()
            onMain {
                descendants(editor).filterIsInstance<EditText>().last().setText("not a configuration")
                assertTrue("Save must take the focus", save.requestFocus())
                save.performClick()
                save.performClick()
            }
            assertEquals("A second OK while the check runs starts no other", before + 1, requests())
            checked()
            onMain {
                assertTrue("A refused Save leaves the editor open", editor.isShown)
                assertSame("A refused Save keeps the focus", save, editor.findFocus())
                assertTrue(save.isEnabled)
                assertFalse(descendants(editor).filterIsInstance<CheckBox>().single().isChecked)
                save.performClick()
            }
            assertEquals("Once refused, Save checks again", before + 2, requests())
            checked()
            // A tap, as on a phone, greys Save out until the check answers.
            instrumentation.setInTouchMode(true)
            eventually("The editor must be in touch mode") { onMain { editor.isInTouchMode } }
            onMain { save.performClick(); assertFalse("Save must be greyed out during a check", save.isEnabled) }
            checked()
            assertTrue(onMain { save.isEnabled })
        } finally {
            onMain { editor.findViewById<Button>(android.R.id.button2).performClick() }
            instrumentation.waitForIdleSync()
        }
    }

    // While a check or an install runs, the update action ignores OK and keeps a
    // remote's focus. Disabled, it would send the focus to the top of the window.
    @Test fun theUpdateActionKeepsTheFocusWhileItWaits() {
        officialBuild()
        val a = open()
        leaveTouchMode(a)
        render(a, 3)
        focusOn(a, "settings:update")
        val action = onMain { root(a).findViewWithTag<TextView>("settings:update") }
        updater("committed", false)
        for (state in listOf(AndroidUpdater.State.CHECKING, AndroidUpdater.State.INSTALLING)) {
            updater("state", state)
            onMain { method("refreshUpdateControls").invoke(a) }
            instrumentation.waitForIdleSync()
            assertFalse("$state: the action must ignore OK", onMain { action.isClickable })
            assertEquals("$state: the focus must stay", "settings:update", focusedKey(a))
        }
        updater("state", AndroidUpdater.State.IDLE)
        onMain { method("refreshUpdateControls").invoke(a) }
        assertTrue(onMain { action.isClickable })
        assertEquals("settings:update", focusedKey(a))
    }

    // Dialogs stay dark in both themes, and a remote needs to see which button has the focus.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun dialogButtonsAndCheckBoxesShowTheFocus() {
        assertTrue("Grant REQUEST_INSTALL_PACKAGES appop before device tests", context.packageManager.canRequestPackageInstalls())
        // With the VPN on, Install update warns first.
        serviceState(2)
        val a = open()
        val dialogs = listOf<Pair<String, () -> Any?>>(
            "Delete" to { method("confirmRemove", Int::class.javaPrimitiveType).invoke(a, 0) },
            "the editor" to { method("editConfig", Int::class.javaPrimitiveType).invoke(a, -1) },
            "the app picker" to { method("appPicker").invoke(a) },
            "Install update" to { method("installUpdate").invoke(a) })
        for ((name, start) in dialogs) {
            onMain { start() }
            instrumentation.waitForIdleSync()
            onMain {
                val window = WindowInspector.getGlobalWindowViews().last { it.findViewById<View>(android.R.id.button2) != null }
                try {
                    for (id in listOf(android.R.id.button1, android.R.id.button2))
                        assertTrue("$name: a button shows no focus ring", window.findViewById<Button>(id).foreground?.isStateful == true)
                    for (box in descendants(window).filterIsInstance<CheckBox>())
                        assertTrue("$name: \"${box.text}\" shows no focus ring", box.foreground?.isStateful == true)
                } finally {
                    window.findViewById<Button>(android.R.id.button2).performClick()
                }
            }
            instrumentation.waitForIdleSync()
        }
    }

    // Settings, About, Open-source licenses lists the notices and the license texts
    // the APK carries, and not the desktop's. With a remote, a text starts with the
    // focus on it, its frame shows the ring, and Down scrolls it.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun theOpenSourceLicensesOpenAndScrollWithTheArrows() {
        val a = open()
        leaveTouchMode(a)
        render(a, 3)
        onMain { assertTrue(root(a).findViewWithTag<View>("settings:licenses").performClick()) }
        instrumentation.waitForIdleSync()
        val list = onMain { windows().mapNotNull { window -> descendants(window).filterIsInstance<ListView>().firstOrNull() }.last() }
        val items = onMain { (0 until list.adapter.count).map { list.adapter.getItem(it).toString() } }
        assertEquals(listOf("THIRD_PARTY_NOTICES.md", "LICENSE"), items.take(2))
        assertTrue("The core's license texts are listed", items.contains("licenses/common/nghttp2.txt"))
        assertTrue("The app's license texts are listed", items.contains("licenses/android/EPL-1.0.txt"))
        assertFalse("The desktop's license texts stay out of the APK", items.any { it.startsWith("licenses/desktop/") })
        onMain { list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0)) }
        instrumentation.waitForIdleSync()
        val dialog = onMain { windows().last { it.findViewById<View>(android.R.id.button1) != null } }
        try {
            // The text's, not the button bar's: in the framework's Material
            // AlertDialog the buttons sit in a ScrollView too (buttonPanel, shown
            // while the dialog has a button), around a ButtonBarLayout.
            val scroll = onMain { descendants(dialog).filterIsInstance<ScrollView>().single { it.isShown && it.getChildAt(0) is TextView } }
            onMain {
                assertTrue((scroll.getChildAt(0) as TextView).text.startsWith("# Third-party notices"))
                assertTrue("The text's frame shows the focus", (scroll.parent as View).foreground?.isStateful == true)
                assertTrue("Done shows the focus", dialog.findViewById<Button>(android.R.id.button1).foreground?.isStateful == true)
            }
            eventually("A remote starts on the text") { onMain { scroll.isFocused } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            eventually("Down scrolls the text") { onMain { scroll.scrollY > 0 } }
        } finally {
            onMain { dialog.findViewById<Button>(android.R.id.button1).performClick() }
            instrumentation.waitForIdleSync()
        }
    }

    // Receive from phone. The link is DeepLinkImportTest's V2_PLAIN: vpn.example.com,
    // with no name of its own.
    private val link = "tt://?AAECAQ92cG4uZXhhbXBsZS5jb20FBWFsaWNlBgZzZWNyZXQCCzEuMi4zLjQ6NDQz"
    private fun receiver(a: MainActivity) = onMain { field("linkReceiver").get(a) as LinkReceiver? }
    private fun windows() = WindowInspector.getGlobalWindowViews()
    private fun saved() = org.json.JSONArray(ConfigStore.read(prefs)).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
    // As a phone's browser sends it, over a raw socket: this test runs in the app's
    // process, where the default network security config refuses cleartext HTTP to
    // HttpURLConnection. request() is given the Host and the page's path.
    private fun http(url: String, request: (host: String, path: String) -> String): String =
        httpBytes(url) { host, path -> request(host, path).toByteArray() }
    private fun httpBytes(url: String, request: (host: String, path: String) -> ByteArray): String {
        val page = URI(url)
        return Socket(page.host, page.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write(request("${page.host}:${page.port}", page.rawPath))
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    // The urlencoded form the page sent before it took files, which the TV still takes.
    private fun formPost(host: String, path: String, text: String): String {
        val body = "link=" + URLEncoder.encode(text, "UTF-8")
        return "POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: ${body.length}\r\n\r\n$body"
    }
    // The page's own form as Chrome sends it, with a file chosen and nothing pasted
    // unless text is given: the text field, then the file field with its name, as the
    // browser writes it, and its content. With no file chosen, both are empty.
    private fun filePost(host: String, path: String, fileName: String, content: ByteArray, text: String = ""): ByteArray {
        val boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
        val body = ("--$boundary\r\nContent-Disposition: form-data; name=\"link\"\r\n\r\n$text\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray() + content + "\r\n--$boundary--\r\n".toByteArray()
        return ("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: multipart/form-data; boundary=$boundary\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray() + body
    }
    // stop() closes the port, but the receiver's thread, waiting in accept(), lets go
    // of it only once it wakes, a moment later on a slow emulator. Until then a
    // connection is taken or reset rather than refused. With a connect timeout: a
    // port that no one takes connections on any more fills its queue, and a connect
    // would then wait for minutes.
    private fun assertClosed(url: String) = eventually("$url must be closed", 2_000) {
        val page = URI(url)
        try { Socket().use { it.connect(java.net.InetSocketAddress(page.host, page.port), 500) }; false }
        catch (_: ConnectException) { true }
        catch (_: java.io.IOException) { false }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun receiveFromPhoneIsOfferedFirstOnATvAndNowhereElse() {
        val a = open()
        render(a, 1)
        onMain { method("addMenu").invoke(a) }
        instrumentation.waitForIdleSync()
        val menu = onMain { windows().mapNotNull { window -> descendants(window).filterIsInstance<ListView>().firstOrNull() }.last() }
        val items = onMain { (0 until menu.adapter.count).map { menu.adapter.getItem(it).toString() } }
        val others = listOf(R.string.text_import_toml_file, R.string.text_paste_from_clipboard, R.string.text_create_manually)
            .map { context.getString(it) }
        if (!isTv) {
            assertEquals(others, items)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            return
        }
        assertEquals(listOf(context.getString(R.string.text_receive_from_phone)) + others, items)
        // Each item still runs its own action, now that the TV's comes first.
        onMain { menu.performItemClick(menu.getChildAt(0), 0, menu.adapter.getItemId(0)) }
        instrumentation.waitForIdleSync()
        assertNotNull("Receive from phone must open the receiver", receiver(a))
        onMain { method("stopReceiver").invoke(a) }
    }

    // Both legs: what a phone's browser does, end to end, through the emulator's own
    // network address.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun aLinkFromAPhoneIsAddedOnlyOnceTheTvConfirmsIt() {
        val a = open()
        render(a, 1)
        onMain { method("receiveFromPhone").invoke(a) }
        instrumentation.waitForIdleSync()
        val opened = receiver(a)
        assertNotNull("The emulator must have a private-network IPv4 address on Wi-Fi or Ethernet", opened)
        val url = opened!!.url()
        val host = URI(url).host
        assertTrue("$url must be on the network a phone is on", Regex("http://(10|172|192)\\.[0-9.]+:[0-9]+/[0-9A-Z]{4}-[0-9A-Z]{4}").matches(url))
        onMain {
            val dialog = windows().last { descendants(it).any { view -> view is TextView && view.text.toString() == url } }
            assertTrue("Cancel shows the focus", dialog.findViewById<Button>(android.R.id.button2).foreground?.isStateful == true)
            // The QR code: square, black on white, a whole number of pixels a module,
            // with the quiet zone and the top left finder pattern where they belong.
            val qr = (descendants(dialog).filterIsInstance<ImageView>().single { it.drawable is BitmapDrawable }.drawable as BitmapDrawable).bitmap
            val modules = QrCode.encodeText(url, QrCode.Ecc.MEDIUM).size + 8
            val scale = qr.width / modules
            assertTrue(scale >= 2)
            assertEquals(modules * scale, qr.width)
            assertEquals(qr.width, qr.height)
            assertEquals(Color.WHITE, qr.getPixel(0, 0))
            assertEquals(Color.BLACK, qr.getPixel(4 * scale, 4 * scale))
        }
        // Any path shows the page, in the TV's language.
        val page = http(url) { h, _ -> "GET /favicon.ico HTTP/1.1\r\nHost: $h\r\n\r\n" }
        assertTrue(page, page.startsWith("HTTP/1.1 200 ") && page.contains(context.getString(R.string.receive_page_send)))
        // A wrong code is turned away, and the TV goes on waiting.
        val wrong = http(url) { h, path -> formPost(h, path.dropLast(1) + (if (path.endsWith("Z")) "Y" else "Z"), link) }
        assertTrue(wrong, wrong.startsWith("HTTP/1.1 404 "))
        assertSame(opened, receiver(a))
        val sent = http(url) { h, path -> formPost(h, path, link) }
        assertTrue(sent, sent.startsWith("HTTP/1.1 200 ") && sent.contains(context.getString(R.string.receive_page_sent)))
        // One text, and the port closes.
        eventually("The receiver must stop") { receiver(a) == null }
        assertClosed(url)
        // The TV shows the server and the sender's address, and asks.
        val from = context.getString(R.string.text_nsent_from_s, host).trim()
        fun confirmation() = windows().lastOrNull { window ->
            window.findViewById<TextView>(android.R.id.message)?.text?.let { it.contains("vpn.example.com") && it.contains(from) } == true
        }
        eventually("The confirmation must name the server and \"$from\"", 30_000) { onMain { confirmation() != null } }
        assertEquals("Nothing is added before the TV confirms", 2, saved().size)
        onMain { confirmation()!!.findViewById<Button>(android.R.id.button1).performClick() }
        eventually("Add must save it") { saved().size == 3 }
        assertEquals("vpn.example.com", saved().last().getString("name"))
        assertTrue(saved().last().getString("toml").contains("hostname = \"vpn.example.com\""))
    }

    // Both legs: a file chosen on the phone instead of pasted. A send with nothing
    // in it or one too large to take is no strike. A TOML file with no name of its
    // own is named after its file, without the escape the browser wrote for the
    // quote, and with the backslash a name on a phone or a Mac may have. It is the
    // TOML that VpnDeviceTest validates natively, with a byte order mark and CRLF
    // line ends.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun aFileFromAPhoneIsNamedAfterItAndAddedOnlyOnceTheTvConfirmsIt() {
        val a = open()
        render(a, 1)
        onMain { method("receiveFromPhone").invoke(a) }
        instrumentation.waitForIdleSync()
        val opened = receiver(a)
        assertNotNull("The emulator must have a private-network IPv4 address on Wi-Fi or Ethernet", opened)
        val url = opened!!.url()
        val page = http(url) { h, _ -> "GET / HTTP/1.1\r\nHost: $h\r\n\r\n" }
        assertTrue(page, page.contains("enctype=\"multipart/form-data\"") &&
            page.contains("<input type=\"file\" id=\"file\" name=\"file\">") &&
            page.contains(LinkReceiver.escape(context.getString(R.string.receive_page_file))))
        // Sent with nothing chosen, the form comes back, and the TV goes on waiting.
        val empty = httpBytes(url) { h, path -> filePost(h, path, "", ByteArray(0)) }
        assertTrue(empty, empty.startsWith("HTTP/1.1 400 ") &&
            empty.contains(LinkReceiver.escape(context.getString(R.string.receive_page_retry))))
        assertSame(opened, receiver(a))
        // A photo chosen by mistake gets the page that says it is too large: the TV
        // reads the rest before it closes, so the answer is not lost to a reset.
        val photo = httpBytes(url) { h, path -> filePost(h, path, "photo.jpg", ByteArray(2 * LinkReceiver.MAX_BODY_BYTES)) }
        assertTrue(photo, photo.startsWith("HTTP/1.1 413 ") &&
            photo.contains(LinkReceiver.escape(context.getString(R.string.receive_page_too_large))))
        assertSame(opened, receiver(a))
        val toml = "\uFEFF" + source.replace("vpn.invalid", "file.example.com").replace("\n", "\r\n") + "\r\n"
        val sent = httpBytes(url) { h, path -> filePost(h, path, "Home\\Office %22TV%22\u202E VPN.toml", toml.toByteArray()) }
        assertTrue(sent, sent.startsWith("HTTP/1.1 200 ") && sent.contains(context.getString(R.string.receive_page_sent)))
        eventually("The receiver must stop") { receiver(a) == null }
        assertClosed(url)
        // Shown without what would reverse the rest; saved as the file has it, as one
        // picked on the TV is.
        val name = "Home\\Office \"TV\" VPN.toml"
        val from = context.getString(R.string.text_nsent_from_s, URI(url).host).trim()
        fun confirmation() = windows().lastOrNull { window ->
            window.findViewById<TextView>(android.R.id.message)?.text
                ?.let { it.contains(name) && it.contains("file.example.com") && it.contains(from) } == true
        }
        eventually("The confirmation must name the file, the server and \"$from\"", 30_000) { onMain { confirmation() != null } }
        assertEquals("Nothing is added before the TV confirms", 2, saved().size)
        onMain { confirmation()!!.findViewById<Button>(android.R.id.button1).performClick() }
        eventually("Add must save it") { saved().size == 3 }
        assertEquals("Home\\Office \"TV\"\u202E VPN.toml", saved().last().getString("name"))
        assertTrue(saved().last().getString("toml").contains("hostname = \"file.example.com\""))
    }

    // Both legs: a TOML file with no name of its own and no file's name to go by,
    // pasted into the page or in a file whose name shows nothing, is named for where
    // it came from.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun aTomlFromAPhoneWithNoFileNameIsNamedForWhereItCameFrom() {
        val a = open()
        render(a, 1)
        val named = context.getString(R.string.text_from_another_device)
        for ((server, fileName) in listOf("pasted.example.com" to null, "nameless.example.com" to "\u202E\u200F")) {
            onMain { method("receiveFromPhone").invoke(a) }
            instrumentation.waitForIdleSync()
            val opened = receiver(a)
            assertNotNull("The emulator must have a private-network IPv4 address on Wi-Fi or Ethernet", opened)
            val url = opened!!.url()
            val toml = source.replace("vpn.invalid", server)
            val sent = httpBytes(url) { h, path ->
                if (fileName == null) filePost(h, path, "", ByteArray(0), text = toml) else filePost(h, path, fileName, toml.toByteArray())
            }
            assertTrue(sent, sent.startsWith("HTTP/1.1 200 ") && sent.contains(context.getString(R.string.receive_page_sent)))
            eventually("The receiver must stop") { receiver(a) == null }
            val from = context.getString(R.string.text_nsent_from_s, URI(url).host).trim()
            fun confirmation() = windows().lastOrNull { window ->
                window.findViewById<TextView>(android.R.id.message)?.text
                    ?.let { it.contains(named) && it.contains(server) && it.contains(from) } == true
            }
            eventually("The confirmation must say \"$named\", $server and \"$from\"", 30_000) { onMain { confirmation() != null } }
            val before = saved().size
            onMain { confirmation()!!.findViewById<Button>(android.R.id.button1).performClick() }
            eventually("Add must save it") { saved().size == before + 1 }
            assertEquals(named, saved().last().getString("name"))
            assertTrue(saved().last().getString("toml").contains("hostname = \"$server\""))
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun cancelOrLeavingTheAppClosesThePort() {
        val a = open()
        // With a remote, the dialog opens on Cancel, not on the code above it.
        leaveTouchMode(a)
        onMain { method("receiveFromPhone").invoke(a) }
        instrumentation.waitForIdleSync()
        val first = receiver(a)!!.url()
        val cancel = onMain { windows().last { it.findViewById<View>(android.R.id.button2) != null }.findViewById<Button>(android.R.id.button2) }
        eventually("A remote must start on Cancel") { onMain { cancel.isFocused } }
        onMain { cancel.performClick() }
        instrumentation.waitForIdleSync()
        assertNull("Cancel must stop the receiver", receiver(a))
        assertClosed(first)
        // Each time is a new code on a new port.
        onMain { method("receiveFromPhone").invoke(a) }
        instrumentation.waitForIdleSync()
        val second = receiver(a)!!.url()
        assertNotEquals(first, second)
        // Home, as a screensaver does, stops the activity.
        shell("input keyevent KEYCODE_HOME")
        eventually("Leaving the app must stop the receiver") { receiver(a) == null }
        assertClosed(second)
    }

    // A file sets the name and the server that the confirmation shows. Line breaks,
    // characters that reverse or hide text, a long run of text or a tall stack of
    // marks in them must not push what comes after, the sender and that verification
    // is off, out of a message a remote cannot scroll, or paint over it. The
    // confirmation is opened directly, as after the checks: whether the core takes
    // such a server name is the core's to say.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun theConfirmationShowsTheSenderWhateverTheFileSays() {
        val a = open()
        // The confirmation's message for that name and server, then cancelled.
        fun message(name: String, hostname: String): String {
            val toml = source.replace("hostname = \"vpn.invalid\"", "hostname = \"$hostname\"\nskip_verification = true")
            onMain {
                method("showImportConfirmation", String::class.java, Boolean::class.javaPrimitiveType,
                    RoutingConfig.Import::class.java, String::class.java)
                    .invoke(a, name, false, RoutingConfig.Import(toml, false), "192.0.2.7")
            }
            instrumentation.waitForIdleSync()
            val dialog = onMain { windows().last { it.findViewById<View>(android.R.id.button2) != null } }
            try {
                return onMain { dialog.findViewById<TextView>(android.R.id.message).text.toString() }
            } finally {
                onMain { dialog.findViewById<Button>(android.R.id.button2).performClick() }
                instrumentation.waitForIdleSync()
            }
        }
        // Each on one line and cut to 80 characters, so the sender is the third line.
        fun assertShown(message: String, shownName: String, shownServer: String) {
            assertTrue(message, message.startsWith(context.getString(R.string.text_add_configuration_36) + shownName +
                context.getString(R.string.text_nserver) + shownServer + context.getString(R.string.text_nsent_from_s, "192.0.2.7") +
                context.getString(R.string.text_nserver_certificate_verification_is_disabled)))
            assertEquals(context.getString(R.string.text_nsent_from_s, "192.0.2.7").trim(), message.split("\n")[2])
        }
        // On one line, the name is 80, so it is shown whole, and the server is 81.
        assertShown(message("My \uD83D\uDD12 VPN" + "\u2028\u2029\r\u202E\u2066".repeat(20) + "y".repeat(72),
                "my-real-vpn.example${"\\n".repeat(40)}${"x".repeat(62)}"),
            "My \uD83D\uDD12 VPN" + "y".repeat(72), "my-real-vpn.example" + "x".repeat(60) + "…")
        // A letter and 79 marks under it fit in 80 characters, and drawn, a stack that
        // tall would cover the lines below it. Each character is shown with two at most.
        val stack = "\u0316\u0317\u0318\u0319".repeat(20).dropLast(1)
        assertShown(message("a$stack", "vpn${stack}.example"), "a\u0316\u0317", "vpn\u0316\u0317.example")
    }

    // A configuration named after its file keeps the file's extension, which is not
    // counted as another alphabet: "Дом.toml" is no mix, but a name that mixes them
    // itself, or in its extension, still is. A link's or an export's own name is
    // counted whole, as is the server a link without one is named after: "раураӏ.com"
    // is Cyrillic made to look like "paypal.com".
    @SdkSuppress(minSdkVersion = 29)
    @Test fun aFilesExtensionIsNoOtherAlphabet() {
        val a = open()
        val note = context.getString(R.string.text_nthe_name_mixes_similar_characters_from_different_al).trim()
        val lookalike = "\u0440\u0430\u0443\u0440\u0430\u04cf.com"
        for ((name, namedAfterFile, mixed) in listOf(Triple("\u0414\u043e\u043c.toml", true, false),
                Triple("\u0414o\u043c.toml", true, true), Triple("\u0414\u043e\u043c.t\u043eml", true, true),
                Triple("home.toml", true, false), Triple(lookalike, false, true), Triple("\u0414\u043e\u043c.toml", false, true),
                Triple("home.toml", false, false))) {
            onMain {
                method("showImportConfirmation", String::class.java, Boolean::class.javaPrimitiveType,
                    RoutingConfig.Import::class.java, String::class.java)
                    .invoke(a, name, namedAfterFile, RoutingConfig.Import(source, false), "192.0.2.7")
            }
            instrumentation.waitForIdleSync()
            val dialog = onMain { windows().last { it.findViewById<View>(android.R.id.button2) != null } }
            try {
                val message = onMain { dialog.findViewById<TextView>(android.R.id.message).text.toString() }
                assertEquals("$name, named after a file: $namedAfterFile: $message", mixed, message.contains(note))
            } finally {
                onMain { dialog.findViewById<Button>(android.R.id.button2).performClick() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    // "Create manually" reads a pasted link as the link, under the name typed there: on
    // a TV, a phone's keyboard can paste one where a whole TOML file is too much.
    @SdkSuppress(minSdkVersion = 29)
    @Test fun theEditorReadsAPastedLink() {
        val a = open()
        onMain { method("editConfig", Int::class.javaPrimitiveType).invoke(a, -1) }
        instrumentation.waitForIdleSync()
        val editor = onMain { windows().last { it.findViewById<View>(android.R.id.button1) != null } }
        val save = onMain { editor.findViewById<Button>(android.R.id.button1) }
        val fields = onMain { descendants(editor).filterIsInstance<EditText>().toList() }
        fun requests() = onMain { field("validationRequest").getLong(a) }
        val before = requests()
        // A link with no server settings is refused as such a link is, before any check.
        onMain { fields.first().setText("Pasted"); fields.last().setText("tt://?AAEC"); save.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals("A refused link starts no check", before, requests())
        assertTrue(onMain { editor.isShown })
        onMain { fields.last().setText(" $link\n"); save.performClick() }
        (field("validationWorker").get(a) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        eventually("Save must close the editor") { onMain { !editor.isShown } }
        assertEquals(3, saved().size)
        assertEquals("Pasted", saved().last().getString("name"))
        assertTrue(saved().last().getString("toml").contains("hostname = \"vpn.example.com\""))
    }

    // The in-app overlays (showOverlay): a confirmation, a select popup and a menu, the toast. Each
    // is a view in the window, found by its tag, so none of them is a system dialog.

    private fun overlayTag(a: MainActivity, name: String): View? = onMain { root(a).findViewWithTag<View?>("overlay:$name") }
    // Asks for a confirmation. On the main thread, which is where the activity asks for one.
    private fun askConfirm(a: MainActivity, message: String, onConfirm: () -> Unit = {}, onCancel: () -> Unit = {}) {
        method("showConfirm", CharSequence::class.java, CharSequence::class.java, CharSequence::class.java,
            Runnable::class.java, Runnable::class.java, Runnable::class.java)
            .invoke(a, message, "Delete", null, Runnable { onConfirm() }, null, Runnable { onCancel() })
    }
    private fun showConfirm(a: MainActivity, message: String, onConfirm: () -> Unit = {}, onCancel: () -> Unit = {}) {
        onMain { askConfirm(a, message, onConfirm, onCancel) }
        instrumentation.waitForIdleSync()
    }
    private fun confirmMessage(a: MainActivity): String? =
        onMain { root(a).findViewWithTag<TextView?>("confirm:message")?.text?.toString() }
    private fun click(a: MainActivity, key: String) = onMain { assertTrue("$key must be clickable", root(a).findViewWithTag<View>(key).performClick()) }

    // render() rebuilds the page and leaves the overlays alone. Back closes the top overlay, and goes
    // no further: not Home on a TV, and not out of the app.
    @Test fun anOverlaySurvivesARebuildAndBackClosesItBeforeAnythingElse() {
        val a = open()
        render(a, 3)
        showConfirm(a, "Delete this?")
        assertNotNull("The confirmation must be in the window", overlayTag(a, "confirm"))
        render(a, 3)
        assertNotNull("An overlay must survive render()", overlayTag(a, "confirm"))
        assertEquals("Delete this?", confirmMessage(a))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        eventually("Back must close the overlay") { overlayTag(a, "confirm") == null }
        assertFalse("Back must not leave the app while an overlay is up", onMain { a.isFinishing })
        assertEquals("Back must not change the page while an overlay is up", 3, onMain { field("page").getInt(a) })
    }

    // While an overlay is up the page behind it takes no focus and no accessibility, the overlay's
    // default control has the focus and no arrow leaves it, and when it closes the focus is back on
    // what opened it.
    @Test fun anOverlayTakesTheFocusAndGivesItBack() {
        val a = open()
        leaveTouchMode(a)
        render(a, 1)
        focusOn(a, "configs:add")
        showConfirm(a, "Delete this?")
        eventually("Cancel takes the focus first") { focusedKey(a) == "confirm:cancel" }
        onMain {
            assertEquals("The page behind takes no focus", ViewGroup.FOCUS_BLOCK_DESCENDANTS, pageRoot(a).descendantFocusability)
            assertEquals("The page behind is hidden from accessibility", View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, pageRoot(a).importantForAccessibility)
        }
        for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_TAB)) {
            instrumentation.sendKeyDownUpSync(key)
            instrumentation.waitForIdleSync()
            val focused = focusedKey(a)
            assertTrue("The focus left the overlay for $focused", focused != null && focused.startsWith("confirm:"))
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        eventually("Back gives the focus back to what opened it") { focusedKey(a) == "configs:add" }
        onMain {
            assertEquals(ViewGroup.FOCUS_BEFORE_DESCENDANTS, pageRoot(a).descendantFocusability)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, pageRoot(a).importantForAccessibility)
        }
    }

    // A confirmation asked for while another is up waits its turn and does not replace it. The red
    // button takes no click for its first 400 ms, so that an OK held or repeated on a remote cannot
    // answer what a phone or a web page asked. A tap on the backdrop, like Back, is Cancel.
    @Test fun aConfirmationWaitsForTheOneShownAndIgnoresClicksUntilItIsArmed() {
        val a = open()
        val answers = java.util.concurrent.CopyOnWriteArrayList<String>()
        // Asked and clicked in the same moment, on the main thread: inside the 400 ms, however slow the device is.
        onMain {
            askConfirm(a, "Delete?", onConfirm = { answers.add("yes") }, onCancel = { answers.add("no") })
            assertTrue(root(a).findViewWithTag<View>("confirm:ok").performClick())
        }
        assertEquals("A click in the first 400 ms is ignored", emptyList<String>(), answers.toList())
        assertNotNull(overlayTag(a, "confirm"))
        SystemClock.sleep(500)
        click(a, "confirm:ok")
        assertEquals(listOf("yes"), answers.toList())
        eventually("Delete closes it") { overlayTag(a, "confirm") == null }

        answers.clear()
        showConfirm(a, "First?", onConfirm = { answers.add("first yes") }, onCancel = { answers.add("first no") })
        showConfirm(a, "Second?", onConfirm = { answers.add("second yes") }, onCancel = { answers.add("second no") })
        assertEquals("The second waits: it does not replace the first", "First?", confirmMessage(a))
        onMain { root(a).findViewWithTag<OverlayLayer>("overlay:confirm").backdrop().performClick() }
        eventually("The second shows after the first is answered") { confirmMessage(a) == "Second?" }
        assertEquals("A tap on the backdrop cancels", listOf("first no"), answers.toList())
        click(a, "confirm:cancel")
        eventually("Cancel closes the second") { overlayTag(a, "confirm") == null }
        assertEquals(listOf("first no", "second no"), answers.toList())
    }

    // A select popup sits under the right edge of its control, 4 below it, and after a render it is put
    // back there; with its control gone it closes. The current choice is in the accent with a check mark.
    @Test fun aPopupGoesBackUnderItsControlAfterARebuildAndClosesWithoutIt() {
        val a = open()
        render(a, 3)
        val pickType = Class.forName("org.freetunnel.android.MainActivity\$Pick")
        val picks = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val pick = java.lang.reflect.Proxy.newProxyInstance(pickType.classLoader, arrayOf(pickType)) { _, _, args -> picks.add(args[0] as Int); null }
        onMain {
            method("showSelect", String::class.java, Array<String>::class.java, Int::class.javaPrimitiveType, pickType)
                .invoke(a, "settings:theme", arrayOf("System", "Light", "Dark"), 1, pick)
        }
        instrumentation.waitForIdleSync()
        // Where the popup is against its anchor: how far its right edge is from the anchor's, and its gap below.
        fun placed(): List<Int> = onMain {
            val anchor = root(a).findViewWithTag<View>("settings:theme")
            val panel = (root(a).findViewWithTag<View>("overlay:select") as ViewGroup).getChildAt(1)
            val at = IntArray(2).also { anchor.getLocationInWindow(it) }
            val inWindow = IntArray(2).also { panel.getLocationInWindow(it) }
            listOf(at[0] + anchor.width - (inWindow[0] + panel.width), inWindow[1] - (at[1] + anchor.height))
        }
        val first = placed()
        val four = onMain { Math.round(4 * a.resources.displayMetrics.density) }
        assertEquals("The popup's right edge is the control's", 0, first[0])
        assertEquals("The popup is 4 under the control", four, first[1])
        // The colour at rest: the one with the focus is accentText, whichever row a remote starts on.
        val texts = onMain { descendants(root(a).findViewWithTag<View>("overlay:select")).filterIsInstance<TextView>().map { it.text.toString() to it.textColors.defaultColor } }
        assertEquals(listOf("System", "Light", "✓", "Dark"), texts.map { it.first })
        assertEquals("The current choice is in the accent", theme(a).accent, texts.single { it.first == "Light" }.second)
        assertEquals("and so is its check mark", theme(a).accent, texts.single { it.first == "✓" }.second)
        assertEquals("The others are in the text colour", theme(a).text, texts.single { it.first == "Dark" }.second)
        render(a, 3)
        instrumentation.waitForIdleSync()
        assertEquals("After a render the popup is under its control again", first, placed())
        click(a, "select:2")
        assertEquals("Picking a row runs its choice", listOf(2), picks.toList())
        eventually("and closes the popup") { overlayTag(a, "select") == null }
        // With the control gone, such as after a tab, a popup closes.
        onMain {
            method("showSelect", String::class.java, Array<String>::class.java, Int::class.javaPrimitiveType, pickType)
                .invoke(a, "settings:theme", arrayOf("System", "Light", "Dark"), 0, pick)
        }
        assertNotNull(overlayTag(a, "select"))
        render(a, 0)
        eventually("A popup closes when its control is gone") { overlayTag(a, "select") == null }
    }

    // A menu: rows of 40 with a separator slot of 11, under the control and centred in the window;
    // a row runs its action and closes it. Its first row has the focus.
    @Test fun aMenuHasRowsOf40AndASeparatorAndRunsWhatARowSays() {
        val a = open()
        leaveTouchMode(a)
        render(a, 1)
        val entry = Class.forName("org.freetunnel.android.MainActivity\$MenuEntry")
        val make = entry.getDeclaredConstructor(String::class.java, String::class.java, Runnable::class.java).apply { isAccessible = true }
        val separator = entry.getDeclaredField("SEPARATOR").apply { isAccessible = true }.get(null)
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        val items = java.util.ArrayList<Any?>().apply {
            add(make.newInstance("Paste from clipboard", "add:paste", Runnable { ran.add("paste") }))
            add(make.newInstance("From file…", "add:file", Runnable { ran.add("file") }))
            add(separator)
            add(make.newInstance("Create new…", "add:create", Runnable { ran.add("create") }))
        }
        onMain { method("showMenu", String::class.java, String::class.java, java.util.List::class.java).invoke(a, "addmenu", "configs:add", items) }
        eventually("The first row has the focus") { focusedKey(a) == "add:paste" }
        onMain {
            val dp = a.resources.displayMetrics.density
            for (key in listOf("add:paste", "add:file", "add:create"))
                assertEquals("$key is 40 high", Math.round(40 * dp), root(a).findViewWithTag<View>(key).height)
            val panel = (root(a).findViewWithTag<View>("overlay:addmenu") as ViewGroup).getChildAt(1)
            val width = panel.width
            assertTrue("The menu is at least 140 wide", width >= Math.round(140 * dp))
            assertTrue("The menu is centred in the window", Math.abs((panel.left + panel.right) / 2 - root(a).width / 2) <= 2)
            val file = root(a).findViewWithTag<View>("add:file")
            val create = root(a).findViewWithTag<View>("add:create")
            val gap = create.top - file.bottom
            assertTrue("The separator's slot (11) and the 1 between rows are between them: $gap", gap >= Math.round(11 * dp))
        }
        click(a, "add:file")
        assertEquals(listOf("file"), ran.toList())
        eventually("A row closes the menu") { overlayTag(a, "addmenu") == null }
    }

    // The toast is in the window, one at a time, at the bottom or, while an overlay is open, at the top.
    // A tap dismisses it.
    @Test fun theToastIsInTheWindowOneAtATimeAndATapDismissesIt() {
        val a = open()
        fun toasts(): List<String> = onMain {
            val layer = field("toastLayer").get(a) as ViewGroup
            (0 until layer.childCount).map { (layer.getChildAt(it) as TextView).text.toString() }
        }
        fun gravity(): Int = onMain { ((field("toastLayer").get(a) as ViewGroup).getChildAt(0).layoutParams as FrameLayout.LayoutParams).gravity }
        // Long enough to stay for as long as this takes: 55 ms a character.
        val second = "Second notice " + "x".repeat(150)
        onMain { method("showToast", CharSequence::class.java).invoke(a, "First notice") }
        assertEquals(listOf("First notice"), toasts())
        onMain { method("showToast", CharSequence::class.java).invoke(a, second) }
        assertEquals("A new toast replaces the old", listOf(second), toasts())
        assertEquals(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, gravity())
        showConfirm(a, "Delete?")
        assertEquals("Behind an overlay it would not be seen: at the top", Gravity.TOP or Gravity.CENTER_HORIZONTAL, gravity())
        click(a, "confirm:cancel")
        eventually("and at the bottom again once it is closed") { gravity() == (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) }
        onMain {
            val toast = (field("toastLayer").get(a) as ViewGroup).getChildAt(0)
            assertFalse("A toast is no control a remote could focus", toast.isFocusable)
            val now = SystemClock.uptimeMillis()
            toast.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 5f, 5f, 0))
            toast.dispatchTouchEvent(MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 5f, 5f, 0))
        }
        eventually("A tap dismisses the toast") { toasts().isEmpty() }
    }
}
