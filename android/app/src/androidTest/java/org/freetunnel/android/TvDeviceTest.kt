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
import android.view.WindowManager
import android.widget.Button
import android.text.TextUtils
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
// overlays (every dialog, menu, picker and confirmation of the app is one, a view of
// its own window found by its tag) that survive it and take the focus, Back, the
// screens TVs leave out, and Receive from phone. Both device legs run it: the TV leg for the TV, the phone leg
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
            // A box of text a remote scrolls, such as a license: the surface, with the field's accent border.
            key == "licenses:text" -> {
                assertEquals("$name: the border of a focused box of text", theme.accent, look.focusedEdge)
                assertEquals("$name: a box of text at rest", theme.surface, look.restEdge)
            }
            Regex("(domain|app):\\d+").matches(key) -> assertEquals("$name: a ChipX focuses in danger", theme.danger, look.focusedFill)
            // The red button, whichever confirmation it answers.
            key == "confirm:ok" -> assertEquals("$name: the red button focuses in danger", theme.danger, look.focusedFill)
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
        answerYes(a)
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
    @Test fun aRemoteNeverLandsOnShowTomlAndPassword() {
        val a = open()
        leaveTouchMode(a)
        onMain { method("editConfig", Int::class.javaPrimitiveType).invoke(a, 0) }
        instrumentation.waitForIdleSync()
        // The check runs on the activity's worker and answers on the main thread.
        fun checked() {
            (field("validationWorker").get(a) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
        }
        fun requests() = onMain { field("validationRequest").getLong(a) }
        val name = byKey(a, "editor:name") as EditText
        val raw = byKey(a, "editor:toml") as EditText
        val save = byKey(a, "editor:save")
        val original = onMain { raw.text.toString() }
        try {
            eventually("The editor must open on the name") { focusedKey(a) == "editor:name" }
            assertEquals("Profile 0", onMain { name.text.toString() })
            val before = requests()
            onMain {
                raw.setText("not a configuration")
                assertTrue("Save must take the focus", save.requestFocus())
                save.performClick()
                save.performClick()
            }
            assertEquals("A second OK while the check runs starts no other", before + 1, requests())
            checked()
            onMain {
                assertNotNull("A refused Save leaves the editor open", root(a).findViewWithTag<View?>("overlay:editor"))
                assertSame("A refused Save keeps the focus", save, a.currentFocus)
                assertNotEquals("and never moves it onto Show TOML and password", "editor:show", a.currentFocus?.tag)
                assertTrue(save.isEnabled)
                assertFalse((root(a).findViewWithTag<View>("editor:show") as ToggleView).isChecked)
                save.performClick()
            }
            assertEquals("Once refused, Save checks again", before + 2, requests())
            checked()
            // A tap, as on a phone, greys Save out until the check answers.
            instrumentation.setInTouchMode(true)
            eventually("The editor must be in touch mode") { inTouchMode(a) }
            onMain { save.performClick(); assertFalse("Save must be greyed out during a check", save.isEnabled) }
            checked()
            assertTrue(onMain { save.isEnabled })
        } finally {
            // As it was when it opened, so that closing it asks nothing.
            onMain { raw.setText(original) }
            onMain { root(a).findViewWithTag<View?>("editor:cancel")?.performClick() }
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

    // An overlay of the app's own shows the focus as the desktop's controls do, in the theme's colours: no foreground, a
    // background that changes with the state, the accent with the focus, danger for the red button, and an accent
    // border for a field or a box of text; and it is a view of the activity's window, found by its tag.
    private class Shown(val what: String, val tag: String, val start: () -> Any?)

    @Test fun overlayButtonsShowTheDesktopFocusLook() {
        assertTrue("Grant REQUEST_INSTALL_PACKAGES appop before device tests", context.packageManager.canRequestPackageInstalls())
        // With the VPN on, Install update warns first.
        serviceState(2)
        val a = open()
        leaveTouchMode(a)
        val overlays = listOf(
            Shown("Delete", "confirm") { method("confirmRemove", Int::class.javaPrimitiveType).invoke(a, 0) },
            Shown("the editor", "editor") { method("editConfig", Int::class.javaPrimitiveType).invoke(a, -1) },
            Shown("the app picker", "picker") { method("appPicker").invoke(a) },
            Shown("Install update", "confirm") { method("installUpdate").invoke(a) },
            Shown("the licenses", "licenses") { method("openSourceLicenses").invoke(a) },
            Shown("Receive from phone", "receive") { method("receiveFromPhone").invoke(a) })
        for (shown in overlays) {
            onMain { shown.start() }
            instrumentation.waitForIdleSync()
            if (shown.tag == "picker") eventually("The apps must be listed") { onMain { root(a).findViewWithTag<View?>("picker:status") == null } }
            onMain {
                val layer = root(a).findViewWithTag<View?>("overlay:${shown.tag}")
                assertNotNull("${shown.what} must be an overlay of the window", layer)
                val controls = descendants(layer!!).filter { it.isShown && it.isFocusable }.toList()
                assertTrue("${shown.what} shows no controls", controls.isNotEmpty())
                for (view in controls) {
                    val name = "${view.javaClass.simpleName} \"${view.tag}\" in ${shown.what}"
                    assertTrue("$name has no key", view.tag is String)
                    assertFocusLook(view, name, theme(a))
                }
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            eventually("${shown.what} must close with Back") { overlayTag(a, shown.tag) == null }
        }
    }

    // A remote reaches every control of an overlay with the arrows, from the one that has the focus first, and none leaves it.
    @Test fun theArrowsReachEveryControlOfAnOverlay() {
        val a = open()
        leaveTouchMode(a)
        val overlays = listOf(
            Shown("the editor", "editor") { method("editConfig", Int::class.javaPrimitiveType).invoke(a, 0) },
            Shown("the app picker", "picker") { method("appPicker").invoke(a) },
            Shown("the licenses", "licenses") { method("openSourceLicenses").invoke(a) },
            Shown("a confirmation with an alternative", "confirm") {
                method("showConfirm", CharSequence::class.java, CharSequence::class.java, CharSequence::class.java,
                    Runnable::class.java, Runnable::class.java, Runnable::class.java)
                    .invoke(a, "Replace it?", "Replace", "Add copy", null, null, null)
            })
        for (shown in overlays) {
            onMain { shown.start() }
            instrumentation.waitForIdleSync()
            if (shown.tag == "picker") eventually("The apps must be listed") { onMain { root(a).findViewWithTag<View?>("picker:status") == null } }
            onMain {
                val layer = root(a).findViewWithTag<ViewGroup>("overlay:${shown.tag}")
                val keys = descendants(layer).filter { it.isShown && it.isFocusable }.mapNotNull { it.tag as? String }.toSet()
                assertTrue("${shown.what} has controls", keys.isNotEmpty())
                val seen = HashSet<View>()
                val queue = ArrayDeque<View>().apply { add(a.currentFocus ?: layer.findViewWithTag<View>(keys.first())) }
                while (queue.isNotEmpty()) {
                    val view = queue.removeFirst()
                    if (!seen.add(view)) continue
                    for (direction in listOf(View.FOCUS_UP, View.FOCUS_DOWN, View.FOCUS_LEFT, View.FOCUS_RIGHT)) {
                        val next = FocusFinder.getInstance().findNextFocus(layer, view, direction)
                        if (next != null) {
                            assertTrue("An arrow leaves ${shown.what} for ${next.tag}", (next.tag as? String) in keys)
                            queue.add(next)
                        }
                    }
                }
                assertEquals("Controls of ${shown.what} that the arrows never reach", emptySet<String>(), keys - seen.mapNotNull { it.tag as? String }.toSet())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            eventually("${shown.what} must close with Back") { overlayTag(a, shown.tag) == null }
        }
    }

    // Settings, About, Open-source licenses lists the notices and the license texts
    // the APK carries, and not the desktop's, in one card. With a remote, a text starts
    // with the focus on it, its box shows the accent border, and Down scrolls it; Back
    // and the arrow go back to the list, and from the list out.
    @Test fun theOpenSourceLicensesOpenAndScrollWithTheArrows() {
        val a = open()
        leaveTouchMode(a)
        render(a, 3)
        click(a, "settings:licenses")
        instrumentation.waitForIdleSync()
        val items = onMain {
            descendants(root(a).findViewWithTag<View>("overlay:licenses")).filter { (it.tag as? String)?.matches(Regex("licenses:\\d+")) == true }
                .map { (it as TextView).text.toString() }
        }
        assertEquals(listOf("THIRD_PARTY_NOTICES.md", "LICENSE"), items.take(2))
        assertTrue("The core's license texts are listed", items.contains("licenses/common/nghttp2.txt"))
        assertTrue("The app's license texts are listed", items.contains("licenses/android/EPL-1.0.txt"))
        assertFalse("The desktop's license texts stay out of the APK", items.any { it.startsWith("licenses/desktop/") })
        eventually("A remote starts on the first row") { focusedKey(a) == "licenses:0" }
        click(a, "licenses:0")
        instrumentation.waitForIdleSync()
        val scroll = byKey(a, "licenses:text") as ScrollView
        onMain {
            assertTrue((scroll.getChildAt(0) as TextView).text.startsWith("# Third-party notices"))
            assertTrue("The card's title is the file",
                descendants(root(a).findViewWithTag<View>("overlay:licenses")).filterIsInstance<TextView>().any { it.text.toString() == "THIRD_PARTY_NOTICES.md" })
            assertFocusLook(scroll, "the license text", theme(a))
            assertEquals("The text is monospace", android.graphics.Typeface.MONOSPACE, (scroll.getChildAt(0) as TextView).typeface)
        }
        eventually("A remote starts on the text") { onMain { scroll.isFocused } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        eventually("Down scrolls the text") { onMain { scroll.scrollY > 0 } }
        // Back goes to the list, with the focus on the file that was open, and then out.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        eventually("Back goes back to the list") { overlayTag(a, "licenses") != null && onMain { root(a).findViewWithTag<View?>("licenses:text") == null } }
        eventually("with the focus on the file that was open") { focusedKey(a) == "licenses:0" }
        click(a, "licenses:1")
        instrumentation.waitForIdleSync()
        click(a, "licenses:back")
        eventually("The arrow goes back to the list too") { onMain { root(a).findViewWithTag<View?>("licenses:text") == null } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        eventually("Back from the list leaves the card") { overlayTag(a, "licenses") == null }
        eventually("and the focus is back on what opened it") { focusedKey(a) == "settings:licenses" }
    }

    // Receive from phone. The link is DeepLinkImportTest's V2_PLAIN: vpn.example.com,
    // with no name of its own.
    private val link = "tt://?AAECAQ92cG4uZXhhbXBsZS5jb20FBWFsaWNlBgZzZWNyZXQCCzEuMi4zLjQ6NDQz"
    private fun receiver(a: MainActivity) = onMain { field("linkReceiver").get(a) as LinkReceiver? }
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

    // The add menu under the + has the desktop's rows, in its order and its words, with Receive from phone first on a TV
    // and only there; each row runs its action.
    @Test fun receiveFromPhoneIsOfferedFirstOnATvAndNowhereElse() {
        val a = open()
        render(a, 1)
        click(a, "configs:add")
        instrumentation.waitForIdleSync()
        val rows = onMain {
            descendants(root(a).findViewWithTag<View>("overlay:addmenu")).filter { (it.tag as? String)?.startsWith("add:") == true }
                .map { it.tag as String to descendants(it).filterIsInstance<TextView>().first().text.toString() }
        }
        val others = listOf("add:paste" to context.getString(R.string.text_paste_from_clipboard),
            "add:file" to context.getString(R.string.add_from_file), "add:create" to context.getString(R.string.add_create_new))
        if (!isTv) {
            assertEquals(others, rows)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            eventually("Back closes the menu") { overlayTag(a, "addmenu") == null }
            // Create new… opens the editor, as the desktop's does.
            click(a, "configs:add")
            click(a, "add:create")
            eventually("Create new… opens the editor") { overlayTag(a, "editor") != null }
            assertTrue(shows(a, context.getString(R.string.text_new_configuration)))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            return
        }
        assertEquals(listOf("add:receive" to context.getString(R.string.text_receive_from_phone)) + others, rows)
        // Each item still runs its own action, now that the TV's comes first.
        click(a, "add:receive")
        eventually("Receive from phone must open the receiver") { receiver(a) != null }
        assertNotNull(overlayTag(a, "receive"))
        onMain { method("stopReceiver").invoke(a) }
        eventually("and stopping it closes the card") { overlayTag(a, "receive") == null }
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
            val card = root(a).findViewWithTag<View>("overlay:receive")
            assertTrue("The address is on the card, to type", descendants(card).any { view -> view is TextView && view.text.toString() == url })
            assertFocusLook(card.findViewWithTag<View>("receive:close"), "Close", theme(a))
            assertTrue("The screen stays on while the card is open", a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            // The QR code: square, black on white, a whole number of pixels a module,
            // with the quiet zone and the top left finder pattern where they belong.
            val code = descendants(card).filterIsInstance<ImageView>().single { it.drawable is BitmapDrawable }
            assertEquals("on its own white tile, in either theme", Color.WHITE, ((code.parent as View).background as GradientDrawable).color?.defaultColor)
            val qr = (code.drawable as BitmapDrawable).bitmap
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
        // One text, and the port closes, and the card with it.
        eventually("The receiver must stop") { receiver(a) == null }
        assertClosed(url)
        assertNull("The card closes with the port", overlayTag(a, "receive"))
        assertFalse("and the screen may go off again", onMain { a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 })
        // The TV shows the server and the sender's address, and asks: the desktop's question, the server on its second line
        // and where it was sent from on its third.
        val from = context.getString(R.string.text_nsent_from_s, host).trim()
        eventually("The confirmation must name the server and \"$from\"", 30_000) {
            confirmMessage(a)?.let { it.contains("vpn.example.com") && it.contains(from) } == true
        }
        val lines = confirmMessage(a)!!.split("\n")
        assertTrue(lines[0], lines[0].startsWith(context.getString(R.string.import_question_link)))
        assertEquals(context.getString(R.string.import_server, "vpn.example.com"), lines[1])
        assertEquals(from, lines[2])
        assertEquals("Nothing is added before the TV confirms", 2, saved().size)
        answerYes(a)
        eventually("Import must save it") { saved().size == 3 }
        assertEquals("vpn.example.com", saved().last().getString("name"))
        assertTrue(saved().last().getString("toml").contains("hostname = \"vpn.example.com\""))
        // The toast says what was added; the page stays where it was.
        assertEquals(listOf(context.getString(R.string.config_added, "vpn.example.com")), toastTexts(a))
        assertEquals("An import does not change the page", 1, onMain { field("page").getInt(a) })
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
        // picked on the TV is. It is a file, not a link, that is asked about.
        val name = "Home\\Office \"TV\" VPN.toml"
        val from = context.getString(R.string.text_nsent_from_s, URI(url).host).trim()
        eventually("The confirmation must name the file, the server and \"$from\"", 30_000) {
            confirmMessage(a)?.let { it.contains(name) && it.contains("file.example.com") && it.contains(from) } == true
        }
        assertTrue(confirmMessage(a)!!.startsWith(context.getString(R.string.import_question_file)))
        assertEquals("Nothing is added before the TV confirms", 2, saved().size)
        answerYes(a)
        eventually("Import must save it") { saved().size == 3 }
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
            eventually("The confirmation must say \"$named\", $server and \"$from\"", 30_000) {
                confirmMessage(a)?.let { it.contains(named) && it.contains(server) && it.contains(from) } == true
            }
            val before = saved().size
            answerYes(a)
            eventually("Import must save it") { saved().size == before + 1 }
            assertEquals(named, saved().last().getString("name"))
            assertTrue(saved().last().getString("toml").contains("hostname = \"$server\""))
        }
    }

    // Close, Back and the arrow close the card and stop the receiver; so does leaving the app. A remote starts on Close, and the
    // screen stays on while the card is open.
    @Test fun closeBackTheArrowOrLeavingTheAppClosesThePort() {
        val a = open()
        // With a remote, the card opens on Close, not on the arrow above the code.
        leaveTouchMode(a)
        fun keepOn() = onMain { a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        fun openCard(): String {
            onMain { method("receiveFromPhone").invoke(a) }
            instrumentation.waitForIdleSync()
            assertNotNull("The card must be up", overlayTag(a, "receive"))
            assertTrue("No screensaver while the card is up", keepOn())
            return receiver(a)!!.url()
        }
        val first = openCard()
        eventually("A remote must start on Close") { focusedKey(a) == "receive:close" }
        click(a, "receive:close")
        eventually("Close must close the card") { overlayTag(a, "receive") == null }
        assertNull("Close must stop the receiver", receiver(a))
        assertClosed(first)
        assertFalse("and the screen may go off again", keepOn())
        // Each time is a new code on a new port.
        val second = openCard()
        assertNotEquals(first, second)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        eventually("Back must close the card") { overlayTag(a, "receive") == null }
        assertNull(receiver(a))
        assertClosed(second)
        val third = openCard()
        click(a, "receive:back")
        eventually("The arrow must close the card") { overlayTag(a, "receive") == null }
        assertNull(receiver(a))
        assertClosed(third)
        val fourth = openCard()
        // Home, as a screensaver does, stops the activity.
        shell("input keyevent KEYCODE_HOME")
        eventually("Leaving the app must stop the receiver") { receiver(a) == null }
        assertClosed(fourth)
    }

    // A file sets the name and the server that the confirmation shows. Line breaks,
    // characters that reverse or hide text, a long run of text or a tall stack of
    // marks in them must not push what comes after, the sender and that verification
    // is off, out of a message a remote cannot scroll, or paint over it. The
    // confirmation is opened directly, as after the checks: whether the core takes
    // such a server name is the core's to say.
    @Test fun theConfirmationShowsTheSenderWhateverTheFileSays() {
        val a = open()
        // The confirmation's message for that name and server, then cancelled.
        fun message(name: String, hostname: String): String {
            val toml = source.replace("hostname = \"vpn.invalid\"", "hostname = \"$hostname\"\nskip_verification = true")
            onMain {
                method("showImportConfirmation", String::class.java, Boolean::class.javaPrimitiveType,
                    RoutingConfig.Import::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(a, name, false, RoutingConfig.Import(toml, false), "192.0.2.7", false)
            }
            instrumentation.waitForIdleSync()
            try {
                return confirmMessage(a)!!
            } finally {
                click(a, "confirm:cancel")
                eventually("The confirmation must close") { overlayTag(a, "confirm") == null }
            }
        }
        // Each on one line and cut to 80 characters, so the sender is the third line.
        fun assertShown(message: String, shownName: String, shownServer: String) {
            val sentFrom = context.getString(R.string.text_nsent_from_s, "192.0.2.7")
            assertTrue(message, message.startsWith(context.getString(R.string.import_question_file) + " \u201C" + shownName + "\u201D\n" +
                context.getString(R.string.import_server, shownServer) + sentFrom + "\n" + context.getString(R.string.import_skips_file)))
            assertEquals(sentFrom.trim(), message.split("\n")[2])
        }
        // On one line, the name is 80, so it is shown whole, and the server is 81.
        assertShown(message("My \uD83D\uDD12 VPN" + "\u2028\u2029\r\u202E\u2066".repeat(20) + "y".repeat(72),
                "my-real-vpn.example${"\\n".repeat(40)}${"x".repeat(62)}"),
            "My \uD83D\uDD12 VPN" + "y".repeat(72), "my-real-vpn.example" + "x".repeat(60) + "\u2026")
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
    @Test fun aFilesExtensionIsNoOtherAlphabet() {
        val a = open()
        val note = context.getString(R.string.import_mixed_name)
        val lookalike = "\u0440\u0430\u0443\u0440\u0430\u04cf.com"
        for ((name, namedAfterFile, mixed) in listOf(Triple("\u0414\u043e\u043c.toml", true, false),
                Triple("\u0414o\u043c.toml", true, true), Triple("\u0414\u043e\u043c.t\u043eml", true, true),
                Triple("home.toml", true, false), Triple(lookalike, false, true), Triple("\u0414\u043e\u043c.toml", false, true),
                Triple("home.toml", false, false))) {
            onMain {
                method("showImportConfirmation", String::class.java, Boolean::class.javaPrimitiveType,
                    RoutingConfig.Import::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(a, name, namedAfterFile, RoutingConfig.Import(source, false), "192.0.2.7", false)
            }
            instrumentation.waitForIdleSync()
            try {
                val message = confirmMessage(a)!!
                assertEquals("$name, named after a file: $namedAfterFile: $message", mixed, message.contains(note))
            } finally {
                click(a, "confirm:cancel")
                eventually("The confirmation must close") { overlayTag(a, "confirm") == null }
            }
        }
    }

    // "Create manually" reads a pasted link as the link, under the name typed there: on
    // a TV, a phone's keyboard can paste one where a whole TOML file is too much.
    @Test fun theEditorReadsAPastedLink() {
        val a = open()
        onMain { method("editConfig", Int::class.javaPrimitiveType).invoke(a, -1) }
        instrumentation.waitForIdleSync()
        val save = byKey(a, "editor:save")
        val name = byKey(a, "editor:name") as EditText
        val raw = byKey(a, "editor:toml") as EditText
        fun requests() = onMain { field("validationRequest").getLong(a) }
        val before = requests()
        // A link with no server settings is refused as such a link is, before any check, and in a toast.
        onMain { name.setText("Pasted"); raw.setText("tt://?AAEC"); save.performClick() }
        instrumentation.waitForIdleSync()
        assertEquals("A refused link starts no check", before, requests())
        assertNotNull(overlayTag(a, "editor"))
        assertEquals(listOf(context.getString(R.string.text_the_link_is_missing_required_server_settings)), toastTexts(a))
        onMain { raw.setText(" $link\n"); save.performClick() }
        (field("validationWorker").get(a) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        eventually("Save must close the editor") { overlayTag(a, "editor") == null }
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
    // The control with this key, in whichever overlay or page has it.
    private fun byKey(a: MainActivity, key: String): View = onMain {
        root(a).findViewWithTag<View?>(key) ?: throw AssertionError("No control has the key $key")
    }
    // A confirmation's red button, and its alternative, take no click for the first 400 ms.
    private fun answerYes(a: MainActivity) {
        SystemClock.sleep(500)
        click(a, "confirm:ok")
    }
    private fun toastTexts(a: MainActivity): List<String> = onMain {
        val layer = field("toastLayer").get(a) as ViewGroup
        (0 until layer.childCount).map { (layer.getChildAt(it) as TextView).text.toString() }
    }

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

    // A name already in the list, or the same file, is the desktop's name clash: Cancel, Add copy and Replace, in that
    // order, with Cancel first in the focus. The red button and Add copy take no click for 400 ms. Replace puts the config
    // where the one it replaces is; Add copy adds another. The page does not change, and the toast says what was added.
    @Test fun aNameClashOffersAddCopyAndReplaceAndReplaceKeepsThePosition() {
        val a = open()
        leaveTouchMode(a)
        render(a, 3)
        fun importAs(name: String, hostname: String) {
            val toml = source.replace("vpn.invalid", hostname)
            onMain {
                method("showImportConfirmation", String::class.java, Boolean::class.javaPrimitiveType,
                    RoutingConfig.Import::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(a, name, false, RoutingConfig.Import(toml, false), null, true)
            }
            instrumentation.waitForIdleSync()
        }
        importAs("Profile 1", "other.example.com")
        assertEquals(context.getString(R.string.import_exists, "Profile 1") + "\n" + context.getString(R.string.import_server, "other.example.com"),
            confirmMessage(a))
        eventually("Cancel takes the focus first") { focusedKey(a) == "confirm:cancel" }
        onMain {
            assertEquals(context.getString(R.string.import_replace), (root(a).findViewWithTag<View>("confirm:ok") as TextView).text.toString())
            assertEquals(context.getString(R.string.import_add_copy), (root(a).findViewWithTag<View>("confirm:alt") as TextView).text.toString())
            val keys = descendants(root(a).findViewWithTag<View>("overlay:confirm")).mapNotNull { it.tag as? String }.filter { it.startsWith("confirm:") && it != "confirm:message" }
            assertEquals("Cancel, the alternative and the red button, in that order", listOf("confirm:cancel", "confirm:alt", "confirm:ok"), keys)
            // Asked and clicked in the same moment: inside the 400 ms, however slow the device is.
            assertTrue(root(a).findViewWithTag<View>("confirm:alt").performClick())
            assertTrue(root(a).findViewWithTag<View>("confirm:ok").performClick())
        }
        assertEquals("Neither answer counts in the first 400 ms", 2, saved().size)
        assertNotNull(overlayTag(a, "confirm"))
        answerYes(a)
        eventually("Replace saves it") { saved().first { it.getString("name") == "Profile 1" }.getString("toml").contains("other.example.com") }
        assertEquals("Replace adds nothing, and the position is the same", listOf("Profile 0", "Profile 1"), saved().map { it.getString("name") })
        assertEquals("The page does not change", 3, onMain { field("page").getInt(a) })
        assertEquals(listOf(context.getString(R.string.config_added, "Profile 1")), toastTexts(a))
        eventually("The question closes") { overlayTag(a, "confirm") == null }
        // Add copy.
        importAs("Profile 0", "copy.example.com")
        SystemClock.sleep(500)
        click(a, "confirm:alt")
        eventually("Add copy adds another") { saved().size == 3 }
        assertEquals(listOf("Profile 0", "Profile 1", "Profile 0"), saved().map { it.getString("name") })
        assertTrue(saved().last().getString("toml").contains("copy.example.com"))
        assertEquals("The first one is as it was", "vpn.invalid", org.tomlj.Toml.parse(saved().first().getString("toml")).getString("endpoint.hostname"))
        eventually("The question closes") { overlayTag(a, "confirm") == null }
        // Cancel adds nothing, and a name that is new has two buttons only.
        importAs("Profile 1", "cancelled.example.com")
        click(a, "confirm:cancel")
        eventually("Cancel closes it") { overlayTag(a, "confirm") == null }
        assertEquals(3, saved().size)
        importAs("Brand new", "new.example.com")
        assertEquals(context.getString(R.string.import_question_link) + " \u201CBrand new\u201D\n" + context.getString(R.string.import_server, "new.example.com"),
            confirmMessage(a))
        assertNull("A name that is new has no alternative", onMain { root(a).findViewWithTag<View?>("confirm:alt") })
        assertEquals(context.getString(R.string.import_confirm), onMain { (root(a).findViewWithTag<View>("confirm:ok") as TextView).text.toString() })
        answerYes(a)
        eventually("Import adds it") { saved().size == 4 }
        assertEquals("Brand new", saved().last().getString("name"))
    }

    // Delete asks with the name cut in the middle to 36, as the desktop's does, in an overlay of its own, and Cancel keeps it.
    @Test fun theDeleteConfirmationNamesTheConfigCutInTheMiddleAndCancelKeepsIt() {
        val long = "A".repeat(30) + "—" + "Z".repeat(30)
        val profiles = org.json.JSONArray()
            .put(org.json.JSONObject().put("name", long).put("toml", source))
            .put(org.json.JSONObject().put("name", "Second").put("toml", source))
        assertTrue(ConfigStore.write(prefs, profiles.toString()))
        val a = open()
        render(a, 1)
        onMain { method("confirmRemove", Int::class.javaPrimitiveType).invoke(a, 0) }
        instrumentation.waitForIdleSync()
        assertEquals(context.getString(R.string.delete_config_question, ShownText.elideMiddle(long, 36)), confirmMessage(a))
        assertEquals(context.getString(R.string.text_delete), onMain { (root(a).findViewWithTag<View>("confirm:ok") as TextView).text.toString() })
        click(a, "confirm:cancel")
        eventually("Cancel closes it") { overlayTag(a, "confirm") == null }
        assertEquals("Cancel keeps it", 2, saved().size)
        onMain { method("confirmRemove", Int::class.javaPrimitiveType).invoke(a, 0) }
        instrumentation.waitForIdleSync()
        answerYes(a)
        eventually("Delete removes it") { saved().size == 1 }
        assertEquals("Second", saved()[0].getString("name"))
    }

    // The editor asks before it drops what was typed, whichever way out is taken, and asks once; Cancel keeps what was typed.
    // The window is secure while it is up: the TOML holds the password.
    @Test fun theEditorAsksBeforeItDropsWhatWasTypedAndKeepsTheWindowSecureWhileItIsOpen() {
        val a = open()
        render(a, 1)
        fun secure() = onMain { a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
        assertFalse(secure())
        onMain { method("editConfig", Int::class.javaPrimitiveType).invoke(a, -1) }
        instrumentation.waitForIdleSync()
        assertTrue("The window is secure while the editor is open", secure())
        assertTrue(shows(a, context.getString(R.string.text_new_configuration)))
        // Nothing typed: Back closes it at once.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        eventually("An unchanged editor closes at once") { overlayTag(a, "editor") == null }
        assertNull("and asks nothing", overlayTag(a, "confirm"))
        assertFalse("The window is not secure once it is closed", secure())
        // Something typed.
        onMain { method("editConfig", Int::class.javaPrimitiveType).invoke(a, 0) }
        instrumentation.waitForIdleSync()
        assertTrue(shows(a, context.getString(R.string.text_edit_configuration)))
        val name = byKey(a, "editor:name") as EditText
        onMain { name.setText("Typed") }
        for (way in listOf("the Back key", "editor:cancel", "editor:back")) {
            if (way == "the Back key") instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) else click(a, way)
            eventually("$way must ask first") { confirmMessage(a) == context.getString(R.string.editor_discard_question) }
            assertNotNull("and the editor is still up", overlayTag(a, "editor"))
            assertTrue(secure())
            // Asked again while the question is up or waits, it is not asked twice.
            click(a, "editor:cancel")
            click(a, "confirm:cancel")
            SystemClock.sleep(300)
            assertNull("one question only, the answer to which was no", overlayTag(a, "confirm"))
            assertEquals("Cancel keeps what was typed", "Typed", onMain { name.text.toString() })
        }
        click(a, "editor:cancel")
        eventually("The editor asks") { confirmMessage(a) == context.getString(R.string.editor_discard_question) }
        assertEquals(context.getString(R.string.editor_discard), onMain { (root(a).findViewWithTag<View>("confirm:ok") as TextView).text.toString() })
        answerYes(a)
        eventually("Discard closes the editor") { overlayTag(a, "editor") == null }
        assertFalse(secure())
        assertEquals("Nothing was saved", 2, saved().size)
        assertEquals("Profile 0", saved().first().getString("name"))
    }

    // The app picker is a card: a search box, rows of a name over a package, no checkboxes, no icons. A phone has the search box
    // first; a TV the first row, once the apps are in. A row adds that one application and closes the picker. While it looks,
    // it says so; typed into the box, a name or a package narrows the list, and what matches nothing says that.
    @Test fun theAppPickerAddsOneApplicationAndCloses() {
        val a = open()
        if (isTv) leaveTouchMode(a)
        render(a, 2)
        // Held back, the scan shows what it is doing.
        val gate = java.util.concurrent.CountDownLatch(1)
        (field("scanWorker").get(a) as ExecutorService).execute { gate.await() }
        onMain { method("appPicker").invoke(a) }
        instrumentation.waitForIdleSync()
        try {
            assertEquals(context.getString(R.string.picker_scanning), onMain { (root(a).findViewWithTag<View>("picker:status") as TextView).text.toString() })
        } finally {
            gate.countDown()
        }
        eventually("The installed apps must be listed") { onMain { root(a).findViewWithTag<View?>("picker:0") != null } }
        if (isTv) eventually("A TV's remote starts on the first row") { focusedKey(a) == "picker:0" }
        else eventually("A phone starts in the search box") { focusedKey(a) == "picker:search" }
        // A name over a package, and nothing else.
        val first = onMain {
            val layer = root(a).findViewWithTag<View>("overlay:picker")
            assertTrue("No checkboxes", descendants(layer).none { it is android.widget.CompoundButton })
            assertTrue("No icons", descendants(layer).none { it is ImageView })
            val texts = descendants(layer.findViewWithTag<View>("picker:0")).filterIsInstance<TextView>().toList()
            assertEquals("A name and a package", 2, texts.size)
            assertEquals("The package is cut at its left", TextUtils.TruncateAt.START, texts[1].ellipsize)
            assertEquals("and the card has no Done and no file to choose", 0, descendants(layer).filterIsInstance<Button>().count())
            texts[1].text.toString()
        }
        val search = byKey(a, "picker:search") as EditText
        onMain { search.setText("zzzzzz-no-such-app") }
        instrumentation.waitForIdleSync()
        assertEquals(context.getString(R.string.picker_nothing), onMain { (root(a).findViewWithTag<View>("picker:status") as TextView).text.toString() })
        // A package narrows it as a name does.
        onMain { search.setText(first) }
        instrumentation.waitForIdleSync()
        val row = onMain {
            descendants(root(a).findViewWithTag<View>("overlay:picker")).first { v ->
                (v.tag as? String)?.matches(Regex("picker:\\d+")) == true && descendants(v).filterIsInstance<TextView>().any { it.text.toString() == first }
            }.tag as String
        }
        click(a, row)
        eventually("One application adds itself and closes the picker") { overlayTag(a, "picker") == null }
        assertTrue("It is in the apps the VPN carries or leaves", prefs.getStringSet("apps", emptySet())!!.contains(first))
        assertEquals("The page does not change", 2, onMain { field("page").getInt(a) })
    }

    // A theme and a mode are chosen in select popups, under their control: the current one first, and the focus back on the
    // control after.
    @Test fun theThemeAndTheModeAreChosenInSelectPopups() {
        val a = open()
        leaveTouchMode(a)
        render(a, 3)
        click(a, "settings:theme")
        instrumentation.waitForIdleSync()
        fun rows() = onMain {
            descendants(root(a).findViewWithTag<View>("overlay:select")).filterIsInstance<TextView>().map { it.text.toString() }
        }
        val names = listOf(R.string.text_theme_system, R.string.text_light_78, R.string.text_dark_79).map { context.getString(it) }
        assertEquals(listOf(names[0], "\u2713", names[1], names[2]), rows())
        eventually("The current choice has the focus") { focusedKey(a) == "select:0" }
        click(a, "select:2")
        eventually("Dark is the theme") { prefs.getString("theme_mode", null) == "dark" }
        eventually("The popup closes") { overlayTag(a, "select") == null }
        eventually("The focus is back on the Theme row") { focusedKey(a) == "settings:theme" }
        render(a, 2)
        click(a, "split:mode")
        instrumentation.waitForIdleSync()
        assertEquals(listOf(context.getString(R.string.text_bypass_vpn_61), "\u2713", context.getString(R.string.text_through_vpn_62)), rows())
        click(a, "select:1")
        eventually("Through VPN is the mode") { prefs.getBoolean("through", false) }
        eventually("The popup closes") { overlayTag(a, "select") == null }
    }

    // A storage that fails is the desktop's amber banner at the top of Configs and in Settings' Security, and a toast: not a dialog.
    @Test fun aStorageFailureIsTheDesktopsAmberBannerAndAToast() {
        val a = open()
        onMain { field("storageFailed").setBoolean(a, true); method("rootStorageWarning").invoke(a) }
        val problem = context.getString(R.string.text_configurations_are_unavailable) + ". " +
            context.getString(R.string.text_android_could_not_read_storage_saved_data_is_unchang)
        assertEquals(listOf(problem), toastTexts(a))
        for (page in listOf(1, 3)) {
            render(a, page)
            val banner = onMain { root(a).findViewWithTag<TextView?>("storage:banner") }
            assertNotNull("The banner is on page $page", banner)
            assertEquals(problem, onMain { banner!!.text.toString() })
        }
        // Adding is refused, and opens nothing.
        render(a, 1)
        onMain { method("addMenu").invoke(a) }
        assertNull(overlayTag(a, "addmenu"))
        onMain { field("storageFailed").setBoolean(a, false); field("storageProblem").set(a, null) }
        render(a, 1)
        assertNull("The banner goes with the failure", onMain { root(a).findViewWithTag<View?>("storage:banner") })
    }

    // What used to be a one-button dialog is a toast: a refusal, whatever its length, and nothing is asked.
    @Test fun aRefusalIsAToastNotADialog() {
        val a = open()
        render(a, 1)
        onMain { method("refuseImport", Int::class.javaPrimitiveType).invoke(a, R.string.text_subscription_links_are_not_supported_yet) }
        assertEquals(listOf(context.getString(R.string.text_subscription_links_are_not_supported_yet)), toastTexts(a))
        assertNull("and no overlay is up", overlayTag(a, "confirm"))
        onMain { method("refuseImport", Int::class.javaPrimitiveType).invoke(a, R.string.text_this_server_needs_a_newer_trusttunnel_core) }
        assertEquals("a new one replaces the old", listOf(context.getString(R.string.text_this_server_needs_a_newer_trusttunnel_core)), toastTexts(a))
    }
}
