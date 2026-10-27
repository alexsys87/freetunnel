package org.freetunnel.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import android.text.method.PasswordTransformationMethod;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android adaptation of the FreeTunnel desktop interface. */
public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 21;
    private static final int PREPARE_VPN = 22;
    private static final int EXPORT_CONFIG = 24;
    private static final String PREFS = "freetunnel";
    private String[] tabNames;
    // The Home tab is the logo, in its own greys: the app never tints it.
    private final int[] tabIcons = {R.drawable.ic_logo, R.drawable.ic_connection, R.drawable.ic_network,
            R.drawable.ic_settings, R.drawable.ic_log};
    private SharedPreferences prefs;
    private final List<Config> configs = new ArrayList<>();
    private final Map<Config, String> pingResults = new IdentityHashMap<>();
    // Each press of the ping button starts a new generation, and the answers of an older one are dropped. The probes
    // run side by side on a few threads, as the desktop's do.
    private int pingGeneration;
    private final ExecutorService pingWorker = Executors.newFixedThreadPool(4);
    // Writes an exported file, which a document provider may be slow to take.
    private final ExecutorService ioWorker = Executors.newSingleThreadExecutor();
    // The config an export is being asked for: the file picker answers later, when the list may have changed.
    private Config exporting;
    private int activeIndex;
    private int page;
    // The theme setting, "system" (the default), "light" or "dark", and the theme in
    // use: for "system" it follows the device's night mode (see Theme.isDark).
    private String themeMode = Theme.SYSTEM;
    private boolean dark = true;
    private Ui ui;
    // The window's one view, set once. render() rebuilds pageRoot, the nav and the page,
    // and nothing else: the overlays and the toast stay where they are.
    private FrameLayout host;
    private LinearLayout pageRoot;
    private FrameLayout overlayLayer;
    private FrameLayout toastLayer;
    // render() rebuilds every view. On a remote, the focused control's key and the
    // page's scroll position are carried over the rebuild (see restoreFocus).
    private ScrollView pageScroll;
    // The dot on the Settings tab while an update is waiting.
    private View settingsDot;
    private int renderedPage = -1;
    private int renderGeneration;
    private boolean restorePending;
    private String restoreKey;
    private int restoreScrollY;
    // Makes the next render() focus the page's own control (see onBackPressed).
    private boolean pageFocus;
    private int vpnState;
    // From the user's Disconnect until the service says it is off: the desktop's disconnecting.
    private boolean stopping;
    private final Runnable clearStopping = () -> { if (stopping) { stopping = false; if (page <= 1) render(); } };
    private String pendingConfig;
    private String pendingProfile;
    // The Split page's profiles are in the preferences (SplitStore); this is how they are reached. They are read from there
    // whenever they are wanted, and a change is a read, the change and a commit under one lock, so that the VPN service,
    // which saves there too, and this never overwrite one another.
    private SplitPrefs splitPrefs;
    // The "+" chip's field for the name of a new profile: open or not, what was typed in it, and the field itself while the
    // page shows it. It stays on the page, hidden, so that opening and closing it rebuilds nothing.
    private boolean profileNameOpen;
    private String profileNameDraft = "";
    private EditText profileNameInput;
    // True while render() takes the page apart: a field that loses its focus to that has not been left.
    private boolean rendering;
    // The key of what takes the focus when the next render() is done, if it is none of what had it.
    private String focusAfterRender;
    private boolean receiverRegistered;
    private boolean storageFailed;
    // What to tell the user while storage is failed: the amber banner on Configs and Settings, and a toast.
    private String storageProblem;
    // Settings' update line: see maintenance().
    private TextView updateAction;
    // Receive from phone: the open port and its card, both closed by stopReceiver().
    private LinkReceiver linkReceiver;
    // The texts it took that the TV has not answered yet: the one being checked or asked about and those
    // waiting for their turn, one at a time, so that they are asked about in the order they came. The receiver
    // counts them too, in the queue's count, and turns a send away with the busy page at LinkReceiver.MAX_WAITING.
    // They outlive the port: what was taken is the TV's to answer.
    private final ReceivedQueue receivedTexts = new ReceivedQueue(new AtomicInteger());
    // The notice that said why the last was refused is up, and the next waits until it is gone or until receivedHoldUntil.
    private boolean receivedHold;
    private long receivedHoldUntil;
    private boolean receivedPollPending;
    // Texts taken by the open receiver, for its card's line, and that line while the card is up.
    private int receivedCount;
    private TextView receivedLine;
    // Bumped when the app is left: texts the receiver's thread handed over before that, which only
    // reach the main thread after it, are dropped.
    private int receiveGeneration;
    private long validationRequest;
    private final ExecutorService validationWorker = Executors.newSingleThreadExecutor();
    // The installed applications are listed here, so that the app picker never waits on a scan.
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            vpnState = FreeTunnelVpnService.state(context);
            if (vpnState == 0 && stopping) { stopping = false; host.removeCallbacks(clearStopping); }
            // Home shows the state, and so does the selected config's badge on Configs.
            if (page <= 1) render();
            QuickTileService.refresh(context);
        }
    };

    private static final class Config {
        String name;
        String toml;
        // The split profile this config uses, as it is named there: Default unless the config was given another in
        // the editor. A name that is no longer a profile counts as Default (SplitProfiles.effective).
        String profile;
        Config(String name, String toml) { this(name, toml, SplitProfiles.DEFAULT); }
        Config(String name, String toml, String profile) { this.name = name; this.toml = toml; this.profile = profile; }
    }

    // The words are in the language chosen in Settings, which Android 8 to 12 only knows from this (AppLocale).
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    // The page is kept over a recreation, which a change of language (and of the system's own theme) is: the user is
    // still on Settings afterwards. Nothing else is, not even an open overlay.
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) page = Math.max(0, Math.min(4, state.getInt("page", 0)));
        tabNames = new String[] {getString(R.string.text_home), getString(R.string.text_configurations), getString(R.string.text_routing), getString(R.string.text_settings), getString(R.string.text_log)};
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // The old "dark" boolean was written only when the user picked Light or Dark.
        themeMode = Theme.mode(prefs.getString("theme_mode", null), prefs.contains("dark") ? Boolean.valueOf(prefs.getBoolean("dark", true)) : null);
        if (!prefs.contains("theme_mode")) prefs.edit().putString("theme_mode", themeMode).remove("dark").apply();
        dark = Theme.isDark(themeMode, nightMode());
        ui = new Ui(this);
        buildHost();
        activeIndex = prefs.getInt("active", 0);
        vpnState = FreeTunnelVpnService.state(this);
        // Before anything else touches the preferences: the first start of this version makes the profiles out of what
        // is there, and tells a new installation (nothing saved at all) from an update by that (SplitStore).
        splitPrefs = new SplitPrefs(prefs);
        splitModel();
        loadConfigs();
        syncMirror();
        render();
        if (state == null && prefs.getBoolean("check_updates_on_launch", false)) AndroidUpdater.INSTANCE.check(this);
        if (state == null) handleIncomingIntent(getIntent());
        if (state == null && prefs.getBoolean("auto_connect", false) && vpnState == 0
                && !configs.isEmpty() && !Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            host.post(() -> { if (FreeTunnelVpnService.state(this) == 0) connect(); });
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 23);

    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        FreeTunnelVpnService.clearStaleAlert(this);
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(FreeTunnelVpnService.ACTION_STATE);
            ContextCompat.registerReceiver(this, vpnReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        AndroidUpdater.INSTANCE.observe(() -> { refreshUpdateDot(); if (page == 3) refreshUpdateControls(); });
        AndroidUpdater.INSTANCE.recoverInstall(this);
        vpnState = FreeTunnelVpnService.state(this);
        if (vpnState == 0) stopping = false;
        // Settings is only refreshed, not rebuilt, so it keeps its scroll position.
        if (page <= 1) render();
        else if (page == 3) refreshUpdateControls();
        else if (page == 4) { refreshLog(false); scheduleLogTick(); }
    }

    @Override protected void onPause() {
        resumed = false;
        host.removeCallbacks(homeTicker);
        host.removeCallbacks(logTicker);
        // Nothing animates while the app is not shown, and the speeds are of the time it is.
        hero.release();
        speedMeter.reset();
        // The logo does not animate across the time the app was not shown.
        homeFresh = true;
        AndroidUpdater.INSTANCE.observe(null);
        if (receiverRegistered) {
            unregisterReceiver(vpnReceiver);
            receiverRegistered = false;
        }
        super.onPause();
    }

    // Home, a screensaver or a recreation closes the port: it is open only while its
    // card is on the screen. What it took and the TV has not got to is dropped with it,
    // except the one being asked about, whose question is still there to answer.
    @Override protected void onStop() {
        stopReceiver();
        dropReceived();
        super.onStop();
    }

    // The manifest keeps the activity through a turn of the screen, a resize and a keyboard coming or going
    // (configChanges), so that an open card, and what is typed in the editor, are not lost to them. The
    // page is laid out for the new size; the overlays lay themselves out. A change of theme or of language
    // still recreates the activity, which the colours and the words need.
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // The dropdown was sized and placed for the window it opened in: a resize closes it, as it does on the desktop.
        Overlay picker = overlay("homepicker");
        if (picker != null) closeOverlay(picker);
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        intent.setData(null); // Do not import again if Android recreates the activity.
        if (uri != null) importDeepLink(uri.toString(), null);
    }

    private int dp(float n) { return ui.dp(n); }
    // The theme in use: the desktop's colours, in the dark or the light theme.
    private Theme t() { return Theme.of(dark); }
    private int nightMode() { return getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK; }
    private LinearLayout column() { return ui.column(); }
    private LinearLayout row() { return ui.row(); }
    private LinearLayout.LayoutParams lp(int w, int h) { return ui.lp(w, h); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); }
    private TextView label(String value, float size, int color) { return ui.label(value, size, color); }
    private void gap(LinearLayout p, int size) { ui.gap(p, size); }
    // SectionLabel: 12 in textFaint as it is typed, 16 below the section before it.
    private void section(LinearLayout p, String title) {
        if (p.getChildCount() > 0) ui.section(p, title);
        else p.addView(ui.sectionLabel(title), lp(-1, -2));
    }
    // Every notice is the desktop's toast, in the window; the system's Toast is used only where no
    // window of ours can show one (the tile, the VPN consent screen).
    private void toast(String text) { showToast(text); }
    // Android TV, Google TV and Fire TV: driven by a remote, seen from across the room.
    private boolean isTv() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }
    // TVs often leave out screens that phones have, such as the file picker or the VPN
    // consent dialog, and starting one that is missing throws. False when it is missing.
    private boolean startForResult(Intent intent, int request) {
        try { startActivityForResult(intent, request); return true; }
        catch (ActivityNotFoundException e) { return false; }
    }

    // The window's one view, set once: render() never calls setContentView again, which
    // would take every overlay and the toast with it. The page is rebuilt in pageRoot;
    // overlays and the toast are in layers above it that render() leaves alone.
    private void buildHost() {
        host = new FrameLayout(this);
        pageRoot = new LinearLayout(this);
        pageRoot.setOrientation(LinearLayout.VERTICAL);
        host.addView(pageRoot, new FrameLayout.LayoutParams(-1, -1));
        overlayLayer = new FrameLayout(this);
        host.addView(overlayLayer, new FrameLayout.LayoutParams(-1, -1));
        toastLayer = new FrameLayout(this);
        host.addView(toastLayer, new FrameLayout.LayoutParams(-1, -1));
        // A TV may crop the edges of the picture (overscan), so content keeps clear of them.
        // Set here as well as with the bars: before Android 15 the window is not edge to
        // edge, the decor takes the insets, and this listener may never run. On the TV
        // emulator (Android 14) it did not.
        int sides = isTv() ? dp(48) : 0;
        int ends = isTv() ? dp(27) : 0;
        setHostPadding(sides, ends, sides, ends);
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                // The keyboard, when it is up, covers the bottom bar too: its inset is the larger.
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                setHostPadding(sides, bars.top + ends, sides, Math.max(bars.bottom, keyboard.bottom) + ends);
            } else {
                setHostPadding(sides, insets.getSystemWindowInsetTop() + ends, sides, insets.getSystemWindowInsetBottom() + ends);
            }
            return insets;
        });
        setContentView(host);
    }
    // The padding that keeps the page, the popups and the toast clear of the edges. An
    // overlay's backdrop still covers all of the window, so the layer is as large as it
    // and each overlay places its panel inside the padding.
    private void setHostPadding(int left, int top, int right, int bottom) {
        host.setPadding(left, top, right, bottom);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) overlayLayer.getLayoutParams();
        p.setMargins(-left, -top, -right, -bottom);
        overlayLayer.setLayoutParams(p);
        for (Overlay overlay : overlays) overlay.layer.setSafe(left, top, right, bottom);
    }

    private void render() {
        // Until the last render()'s views are drawn, nothing in them has the focus and
        // they have not scrolled, so a render() before that, such as the last ping result
        // and the end of the ping in one frame, keeps what that one is about to restore.
        if (!restorePending) {
            View focused = getCurrentFocus();
            restoreKey = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
            restoreScrollY = pageScroll != null && renderedPage == page ? pageScroll.getScrollY() : 0;
        } else if (renderedPage != page) restoreScrollY = 0;
        if (pageFocus) { restoreKey = null; pageFocus = false; }
        // The control a change asked to have the focus after it, in place of whichever had it: a field left open for the
        // next one, or the + chip a new profile's name was typed from.
        final String asked = focusAfterRender;
        if (focusAfterRender != null) { restoreKey = focusAfterRender; focusAfterRender = null; }
        int generation = ++renderGeneration;
        // The field for a new profile's name belongs to the Split page: on any other it is dropped.
        if (page != 2) { profileNameOpen = false; profileNameDraft = ""; }
        homeContinues = !homeFresh && renderedPage == 0 && page == 0;
        homeFresh = false;
        dark = Theme.isDark(themeMode, nightMode());
        ui.update(dark);
        Theme t = t();
        getWindow().setStatusBarColor(t.bg); getWindow().setNavigationBarColor(t.bg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // The window itself too, so that nothing of the system theme's dark shows before a frame, or at an edge.
        getWindow().setBackgroundDrawable(new ColorDrawable(t.bg));
        host.setBackgroundColor(t.bg);
        // A field that has the focus loses it to this, and has not been left (see closeProfileName).
        rendering = true;
        try { pageRoot.removeAllViews(); } finally { rendering = false; }
        profileNameInput = null;
        nav();
        pageScroll = null;
        if (page != 4) { logText = null; logBox = null; logPlaceholder = null; }
        else if (renderedPage != 4) { logAutoScroll = true; logStamp = -1; }
        if (page != 0) {
            // Nothing of Home is shown: its logo may not go on pulsing at views that are gone.
            hero.release();
            speedMeter.reset();
            homeStatus = null; homeDown = null; homeUp = null; homeSelectorRow = null; homeSpeedRow = null;
        }
        if (page == 0) home();
        else if (page == 4) {
            // The log fills the page, and scrolls in its own box, as the desktop's does: nothing scrolls round it. 18 on each side,
            // 6 at the top and 12 at the foot.
            LinearLayout body = column();
            body.setPadding(dp(18), dp(6), dp(18), dp(12));
            // The toggle ends at the margin, and the row round it fills a little beyond it with the focus. That row is a child of a line
            // of the page, which clips what it holds to its bounds unless its own parent, this, says it need not.
            body.setClipToPadding(false);
            body.setClipChildren(false);
            pageRoot.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
            logs(body);
        } else {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(false);
            // The desktop's pages fill the width with 18 on each side and have no widest: at a TV's width a config's row goes from edge
            // to edge with its icons at the far right (the TV's own overscan is the host's padding). Settings has 6 above and 14 below.
            int side = dp(18);
            LinearLayout body = column(); body.setPadding(side, dp(page == 3 ? 6 : 8), side, dp(page == 3 ? 14 : 26)); scroll.addView(body);
            // Links, toggles and dropdown values end at the margin, and a toggle's row fills a little beyond it with the focus.
            body.setClipToPadding(false);
            pageRoot.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            pageScroll = scroll;
            switch (page) {
                case 1: configs(body); break;
                case 2: split(body); break;
                default: settings(body); break;
            }
        }
        renderedPage = page;
        restoreFocus(generation, restoreKey, restoreScrollY);
        refocusFields(asked);
        // Its list is the configs as they were: with another page or another list it is no longer what it was opened on.
        Overlay picker = overlay("homepicker");
        if (picker != null && (page != 0 || configs.size() != homePickerCount)) closeOverlay(picker);
        reanchorOverlays();
        scheduleHomeTick();
        scheduleLogTick();
        // Home's toast sits between the selector and the tiles, which are only where they are once laid out.
        host.post(this::placeToast);
    }

    // Puts a remote's focus back on the control that had it before render(), and the
    // page back where it was scrolled to. A phone in touch mode shows no focus and is
    // left as it was. This runs once the new views are laid out, because a ScrollView
    // cannot scroll before that, and only for the latest render(): ping results can
    // render several times in one frame, and restorePending keeps the key and the
    // scroll position the same over them. With an overlay up the focus is in it, and
    // stays there: the page behind it takes none.
    private void restoreFocus(int generation, String key, int scrollY) {
        View decor = getWindow().getDecorView();
        restorePending = !decor.isInTouchMode();
        if (!restorePending) return;
        ScrollView scroll = pageScroll;
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                if (generation != renderGeneration) return true;
                restorePending = false;
                if (decor.isInTouchMode()) return true;
                if (scroll != null) scroll.scrollTo(0, scrollY);
                if (!overlays.isEmpty()) return true;
                View target = focusTarget(key);
                if (target != null) target.requestFocus();
                return true;
            }
        });
    }
    // In touch mode restoreFocus does nothing, which suits a page that was only drawn again (a ping result, a turn of the screen:
    // none of them brings a keyboard up), but not a field that is being typed in: the one for a new profile's name while it is open,
    // and the domains field (or Settings' routes field) after one Enter, for the next, which is what asked is: the key a change asked
    // the focus for (focusAfterRender), and null for any other render.
    private void refocusFields(String asked) {
        if ((page != 2 && page != 3) || !getWindow().getDecorView().isInTouchMode()) return;
        String wanted = page == 2 ? (profileNameOpen ? "profile:name" : "domains:input".equals(asked) ? asked : null)
                : "routes:input".equals(asked) ? asked : null;
        if (wanted == null) return;
        View field = pageRoot.findViewWithTag(wanted);
        if (!(field instanceof EditText)) return;
        field.post(() -> {
            if (!field.isAttachedToWindow() || !overlays.isEmpty() || field.getVisibility() != View.VISIBLE) return;
            field.requestFocus();
            showKeyboard(field);
        });
    }
    private static final Pattern NUMBERED_KEY = Pattern.compile("([a-z]+):(\\d{1,5})(:[a-z]+)?");
    // The control with this key. When it is gone, such as after a deletion, the nearest
    // one before it: config:2:edit, then config:1:edit and config:0:edit; and where there is none of its kind, what is beside
    // where it was (see neighbours). Otherwise the page's own: Reconnect while traffic is blocked, else Connect, on Home, and the
    // page's tab elsewhere.
    private View focusTarget(String key) {
        View found = key == null ? null : host.findViewWithTag(key);
        Matcher numbered = NUMBERED_KEY.matcher(key == null ? "" : key);
        if (found == null && numbered.matches()) {
            String rest = numbered.group(3) == null ? "" : numbered.group(3);
            for (int n = Integer.parseInt(numbered.group(2)) - 1; n >= 0 && found == null; n--)
                found = host.findViewWithTag(numbered.group(1) + ":" + n + rest);
        }
        if (found == null && key != null) {
            for (String next : neighbours(key)) {
                found = pageRoot.findViewWithTag(next);
                if (found != null) break;
            }
        }
        if (found == null && page == 0) found = pageRoot.findViewWithTag("home:reconnect");
        if (found == null) found = pageRoot.findViewWithTag(page == 0 ? "home:toggle" : "tab:" + page);
        return found;
    }
    // What is beside a control that is gone when no other of its kind is left, by key, nearest first: a rule's Clear all that has cleared
    // the rules goes to the link beside it, the last chip of a list to the link or the field above it, the last config to the words
    // that stand in for the list, the last custom profile to the chip before it. Without these a deletion would send the remote
    // to the page's tab, at the top, while the page stays scrolled to where the control was.
    private static String[] neighbours(String key) {
        Matcher numbered = NUMBERED_KEY.matcher(key);
        if (numbered.matches()) {
            int n = Integer.parseInt(numbered.group(2));
            switch (numbered.group(1)) {
                case "config": return new String[]{"configs:hint", "configs:add"};
                case "profile": return new String[]{"profile:" + Math.max(0, n - 1), "profile:add"};
                case "domain": return new String[]{"domains:russia", "domains:input"};
                case "app": return new String[]{"apps:choose"};
                case "route": return new String[]{"routes:restore", "routes:input"};
                default: return new String[0];
            }
        }
        switch (key) {
            case "domains:clear": return new String[]{"domains:russia", "domains:input"};
            case "apps:clear": return new String[]{"apps:choose"};
            case "routes:clear": return new String[]{"routes:restore", "routes:input"};
            default: return new String[0];
        }
    }

    // Back closes what is on top first: an overlay. Without tabs to tap, Back on a TV then
    // goes to Home, and leaves the app from there. Home's own control gets the focus: left
    // on the tab it was on, the next OK would go straight back to that page.
    // Still delivered at targetSdk 35; targetSdk 36 needs an OnBackInvokedCallback.
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        Overlay top = topOverlay();
        if (top != null) { escape(top); return; }
        // The field for a new profile's name is what Back closes first, as Escape does on the desktop.
        if (profileNameOpen) { closeProfileName(); return; }
        if (isTv() && page != 0) { page = 0; pageFocus = true; render(); return; }
        super.onBackPressed();
    }
    // Escape is Back on a keyboard, as it is on the desktop.
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        Overlay top = topOverlay();
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && top != null) { escape(top); return true; }
        return super.onKeyUp(keyCode, event);
    }

    // Overlays: a card (the editor, a picker), a confirmation, a popup (a dropdown, a menu).
    //
    // showOverlay is the only code that adds to overlayLayer, and every overlay in the
    // app goes through it (tests/test_android_tv_dialogs.sh). What it gives each:
    //  - a backdrop, black at 0.45 under a card or a confirmation and clear under a popup,
    //    which swallows touches, and a tap on which is that overlay's Escape;
    //  - the desktop's card: bg, a 1 px border, radius 14 (12 for a confirmation, 10 for a
    //    popup, which has a shadow), fading in over 130 ms as it slides down 8;
    //  - a place in a stack. While anything is open the page behind takes no focus and is
    //    hidden from accessibility, and so does every overlay below the top one;
    //  - a default focus, and the focus back on what opened it, by key, when it closes;
    //  - a life that render() does not end: a popup is put back under its anchor, by key,
    //    after a render, and closes if the anchor is gone;
    //  - Back: it closes the top one (see onBackPressed).

    private final List<Overlay> overlays = new ArrayList<>();
    private boolean pageBlocked;

    private final class Overlay {
        final String name;
        final OverlayLayer.Kind kind;
        final String anchorKey;
        final Runnable onClose;
        final String openerKey;
        OverlayLayer layer;
        // What Back, Escape and a tap on the backdrop do: close it, unless it asks first.
        Runnable escape;
        // The key of what takes the focus when it opens, else its first control.
        String defaultKey;
        boolean closed;
        Overlay(String name, OverlayLayer.Kind kind, String anchorKey, Runnable onClose, String openerKey) {
            this.name = name; this.kind = kind; this.anchorKey = anchorKey; this.onClose = onClose; this.openerKey = openerKey;
            this.escape = () -> closeOverlay(this);
        }
        Overlay width(int px) { layer.setPreferredWidth(px); return this; }
        void close() { closeOverlay(this); }
    }

    private Overlay topOverlay() { return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1); }
    private Overlay overlay(String name) {
        for (Overlay open : overlays) if (open.name.equals(name)) return open;
        return null;
    }
    private void escape(Overlay overlay) { if (!overlay.closed) overlay.escape.run(); }

    // content goes inside the card, which is as wide as it holds (a card's width: width()).
    // A second overlay of the same name is not opened: the one that is up is returned.
    private Overlay showOverlay(String name, OverlayLayer.Kind kind, View content, String anchorKey, Runnable onClose) {
        Overlay open = overlay(name);
        if (open != null) return open;
        Theme t = t();
        View focused = getCurrentFocus();
        String opener = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        // A question asked over a form puts the keyboard away: it would cover it.
        if (kind == OverlayLayer.Kind.CONFIRM && focused instanceof EditText) hideKeyboard();
        final Overlay overlay = new Overlay(name, kind, anchorKey, onClose, opener);
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(ui.outlined(t.bg, t.border, kind == OverlayLayer.Kind.CARD ? 14 : kind == OverlayLayer.Kind.CONFIRM ? 12 : 10));
        // A touch on the card is the card's: it never reaches the backdrop, whose tap is Escape.
        panel.setOnTouchListener((v, e) -> true);
        panel.addView(content, new FrameLayout.LayoutParams(-1, -2));
        View backdrop = new View(this);
        backdrop.setBackgroundColor(kind == OverlayLayer.Kind.POPUP ? Color.TRANSPARENT : t.backdrop);
        backdrop.setOnClickListener(v -> escape(overlay));
        backdrop.setFocusable(false);
        OverlayLayer layer = new OverlayLayer(this, kind, backdrop, panel);
        layer.setTag("overlay:" + name);
        layer.setSafe(host.getPaddingLeft(), host.getPaddingTop(), host.getPaddingRight(), host.getPaddingBottom());
        if (anchorKey != null) layer.setAnchor(l -> anchorRect(anchorKey, l), OverlayLayer.UNDER_RIGHT_EDGE, dp(4));
        if (kind == OverlayLayer.Kind.POPUP) {
            panel.setElevation(dp(8));
            if (Build.VERSION.SDK_INT >= 28) {
                panel.setOutlineAmbientShadowColor(t.popupShadow);
                panel.setOutlineSpotShadowColor(t.popupShadow);
            }
        }
        overlay.layer = layer;
        overlays.add(overlay);
        overlayLayer.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        updateOverlayState();
        backdrop.setAlpha(0f);
        panel.setAlpha(0f);
        panel.setTranslationY(-dp(8));
        backdrop.animate().alpha(1f).setDuration(130).start();
        panel.animate().alpha(1f).translationY(0f).setDuration(130).setInterpolator(new DecelerateInterpolator(1.5f)).start();
        // After the caller has said which control comes first. A phone in touch mode has no focus to give.
        host.post(() -> {
            if (!overlay.closed && topOverlay() == overlay && !getWindow().getDecorView().isInTouchMode()) focusOverlay(overlay);
        });
        return overlay;
    }
    private void focusOverlay(Overlay overlay) {
        View target = overlay.defaultKey == null ? null : overlay.layer.findViewWithTag(overlay.defaultKey);
        if (target == null) {
            ArrayList<View> controls = new ArrayList<>();
            overlay.layer.addFocusables(controls, View.FOCUS_FORWARD);
            if (!controls.isEmpty()) target = controls.get(0);
        }
        if (target != null) target.requestFocus();
    }

    // Closes it: gone from the stack at once, and its views fade out over 130 ms. Its keys
    // are no one's from then on, so that a control with the same key in the next overlay,
    // such as a queued confirmation's buttons, is the only one a search finds.
    private void closeOverlay(Overlay overlay) {
        if (overlay.closed) return;
        overlay.closed = true;
        // A field of it that had the keyboard takes it away: left up, it would cover the page.
        View focused = getCurrentFocus();
        boolean typing = focused instanceof EditText && isInside(focused, overlay.layer);
        overlays.remove(overlay);
        retag(overlay.layer);
        overlay.layer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        updateOverlayState();
        // Before the focus goes back: what it does may be to show the control that is to have it, such as the empty Configs page's
        // "Add a config", which is hidden while its own menu is open, and a view that is not shown takes no focus.
        if (overlay.onClose != null) overlay.onClose.run();
        returnFocus(overlay);
        if (typing) hideKeyboard();
        // The fade ends in taking the view away. Not left to the animation alone: with the activity stopped, as
        // Receive from phone's card is when the app is left, it may never run, and the card would be there,
        // invisible and in the way, when the app comes back.
        Runnable remove = () -> overlayLayer.removeView(overlay.layer);
        overlay.layer.animate().alpha(0f).setDuration(130).withEndAction(remove).start();
        host.postDelayed(remove, 400);
    }
    private static boolean isInside(View view, View ancestor) {
        for (View up = view; up != null; ) {
            if (up == ancestor) return true;
            up = up.getParent() instanceof View ? (View) up.getParent() : null;
        }
        return false;
    }
    private void retag(View view) {
        if (view.getTag() instanceof String) view.setTag(null);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) retag(((ViewGroup) view).getChildAt(i));
    }
    // To what opened it, by key; failing that the overlay now on top; failing that the page's
    // own control (focusTarget). A phone in touch mode has no focus to return.
    // A control that refuses the focus (hidden, or behind the overlay that is now on top) is no more a place to put it than one
    // that is gone: left there, the focus stays in the layer that is closing, and when that goes Android hands it to the first
    // control of the window, the Home tab.
    private void returnFocus(Overlay closed) {
        if (getWindow().getDecorView().isInTouchMode()) return;
        View target = closed.openerKey == null ? null : host.findViewWithTag(closed.openerKey);
        if (target != null && target.requestFocus()) return;
        Overlay top = topOverlay();
        if (top != null) { focusOverlay(top); return; }
        // Gone, the nearest one to it; or there, and refusing, the page's own: the same key would only find the same control.
        View next = focusTarget(target == null ? closed.openerKey : null);
        if (next != null) next.requestFocus();
    }

    // The page behind an overlay takes no focus and no accessibility, nor does an overlay
    // under another: the top one is the only part of the window a remote can reach.
    private void updateOverlayState() {
        boolean any = !overlays.isEmpty();
        if (any != pageBlocked) {
            pageBlocked = any;
            pageRoot.setDescendantFocusability(any ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            pageRoot.setImportantForAccessibility(any ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        for (int i = 0; i < overlays.size(); i++) {
            boolean topmost = i == overlays.size() - 1;
            OverlayLayer layer = overlays.get(i).layer;
            layer.setDescendantFocusability(topmost ? ViewGroup.FOCUS_BEFORE_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            layer.setImportantForAccessibility(topmost ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        placeToast();
    }

    // The control a popup belongs to, in the layer's coordinates, or null where it is gone.
    private Rect anchorRect(String key, View layer) {
        View anchor = host.findViewWithTag(key);
        if (anchor == null) return null;
        int[] at = new int[2];
        int[] in = new int[2];
        anchor.getLocationInWindow(at);
        layer.getLocationInWindow(in);
        return new Rect(at[0] - in[0], at[1] - in[1], at[0] - in[0] + anchor.getWidth(), at[1] - in[1] + anchor.getHeight());
    }
    // After render(): the popups go back under their controls, or close if a control is gone.
    private void reanchorOverlays() {
        for (Overlay overlay : new ArrayList<>(overlays)) {
            if (overlay.anchorKey == null) continue;
            if (host.findViewWithTag(overlay.anchorKey) == null) closeOverlay(overlay);
            else overlay.layer.requestLayout();
        }
    }

    // ConfirmDialog: a card with a message that wraps, and scrolls where it is taller than
    // the window leaves room for, and buttons: Cancel, an alternate beside it if there is
    // one, and the confirmation, always red and always last. Escape, Back and a tap on the
    // backdrop cancel. The confirmation ignores a click, and OK, for its first 400 ms, so
    // that an OK held or repeated on a remote cannot answer what a phone or a web page
    // asked; the alternate is as much a yes, and waits too. Confirmations queue: one asked
    // for while another is up waits its turn, and never takes its place. Cancel has the focus
    // first. Keys: confirm:message, confirm:cancel, confirm:alt and confirm:ok.
    private static final class ConfirmRequest {
        final CharSequence message;
        final CharSequence confirm;
        final CharSequence alternate;
        final Runnable onConfirm;
        final Runnable onAlternate;
        final Runnable onCancel;
        ConfirmRequest(CharSequence message, CharSequence confirm, CharSequence alternate, Runnable onConfirm, Runnable onAlternate, Runnable onCancel) {
            this.message = message; this.confirm = confirm; this.alternate = alternate;
            this.onConfirm = onConfirm; this.onAlternate = onAlternate; this.onCancel = onCancel;
        }
    }
    private static final long CONFIRM_ARMED_MS = 400;
    private final ArrayDeque<ConfirmRequest> confirmQueue = new ArrayDeque<>();
    private boolean confirmShowing;

    private void showConfirm(CharSequence message, CharSequence confirmLabel, Runnable onConfirm) {
        showConfirm(message, confirmLabel, null, onConfirm, null, null);
    }
    private void showConfirm(CharSequence message, CharSequence confirmLabel, CharSequence alternateLabel,
                             Runnable onConfirm, Runnable onAlternate, Runnable onCancel) {
        confirmQueue.add(new ConfirmRequest(message, confirmLabel, alternateLabel, onConfirm, onAlternate, onCancel));
        nextConfirm();
    }
    private void nextConfirm() {
        if (confirmShowing || isDestroyed()) return;
        ConfirmRequest request = confirmQueue.poll();
        if (request == null) return;
        confirmShowing = true;
        Theme t = t();
        LinearLayout box = column();
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        CappedScrollView scroll = new CappedScrollView(this);
        scroll.setMaxHeight(Math.max(dp(80), getResources().getDisplayMetrics().heightPixels - dp(160)));
        TextView message = ui.label(request.message, 14, t.text);
        message.setGravity(Gravity.CENTER);
        // Wraps anywhere: a long host name has no space to break at.
        message.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        message.setTag("confirm:message");
        scroll.addView(message, new FrameLayout.LayoutParams(-1, -2));
        box.addView(scroll, lp(-1, -2));
        gap(box, 14);
        LinearLayout buttons = row();
        buttons.setGravity(Gravity.CENTER);
        final Overlay[] shown = new Overlay[1];
        Button cancel = ui.button(getString(R.string.text_cancel), Ui.Kind.NEUTRAL, 76, "confirm:cancel");
        cancel.setOnClickListener(v -> escape(shown[0]));
        buttons.addView(cancel, lp(-2, 32));
        final long armedAt = SystemClock.uptimeMillis() + CONFIRM_ARMED_MS;
        if (request.alternate != null) {
            ui.gapH(buttons, 8);
            Button alternate = ui.button(request.alternate, Ui.Kind.NEUTRAL, 76, "confirm:alt");
            alternate.setOnClickListener(v -> {
                if (SystemClock.uptimeMillis() < armedAt) return;
                closeOverlay(shown[0]);
                if (request.onAlternate != null) request.onAlternate.run();
            });
            buttons.addView(alternate, lp(-2, 32));
        }
        ui.gapH(buttons, 8);
        Button ok = ui.button(request.confirm, Ui.Kind.DESTRUCTIVE, 76, "confirm:ok");
        ok.setOnClickListener(v -> {
            if (SystemClock.uptimeMillis() < armedAt) return;
            closeOverlay(shown[0]);
            if (request.onConfirm != null) request.onConfirm.run();
        });
        buttons.addView(ok, lp(-2, 32));
        box.addView(buttons, lp(-1, -2));
        Overlay overlay = showOverlay("confirm", OverlayLayer.Kind.CONFIRM, box, null, () -> {
            confirmShowing = false;
            host.post(this::nextConfirm);
        });
        shown[0] = overlay;
        overlay.defaultKey = "confirm:cancel";
        overlay.escape = () -> {
            closeOverlay(overlay);
            if (request.onCancel != null) request.onCancel.run();
        };
    }

    // SelectPopup: the choices of a dropdown, under the right edge of the control they
    // belong to, 4 below it, or above it where they do not fit below, at most 8 rows with
    // the rest scrolling. The current choice is in accent with a check mark; a menu of
    // actions has none (current -1). Rows are 36 high, and focus as a menu row does.
    // Keys: select:0, select:1 and so on.
    private interface Pick { void picked(int index); }

    private Overlay showSelect(String anchorKey, String[] options, int current, Pick pick) {
        // One at a time: a choice asked for while another is up replaces it.
        Overlay previous = overlay("select");
        if (previous != null) closeOverlay(previous);
        Theme t = t();
        CappedScrollView scroll = new CappedScrollView(this);
        scroll.setMaxHeight(dp(8 * 36 + 12));
        LinearLayout list = column();
        list.setPadding(dp(6), dp(6), dp(6), dp(6));
        int widest = 0;
        final Overlay[] shown = new Overlay[1];
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            widest = Math.max(widest, textWidth(options[i], 14));
            int color = i == current ? t.accent : t.text;
            LinearLayout rowView = row();
            rowView.setPadding(dp(12), 0, dp(12), 0);
            TextView name = ui.label(options[i], 14, color);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            rowView.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            if (i == current) rowView.addView(ui.label("✓", 14, t.accent), lp(-2, -1));
            ui.focus(rowView, "select:" + i, Ui.Look.MENU_ROW).setOnClickListener(v -> {
                closeOverlay(shown[0]);
                pick.picked(index);
            });
            list.addView(rowView, lp(-1, 36));
        }
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        Overlay overlay = showOverlay("select", OverlayLayer.Kind.POPUP, scroll, anchorKey, null);
        shown[0] = overlay;
        overlay.width(Math.max(dp(140), widest + dp(56) + dp(12)));
        overlay.defaultKey = "select:" + Math.max(0, current);
        return overlay;
    }

    // MenuPopup: rows of actions, 40 high, with separators (a null item), under the control
    // that opened it and centred in the window: the Configs page's add menu. Keys are the
    // items'.
    private static final class MenuEntry {
        static final MenuEntry SEPARATOR = new MenuEntry(null, null, null);
        final String label;
        final String key;
        final Runnable action;
        MenuEntry(String label, String key, Runnable action) { this.label = label; this.key = key; this.action = action; }
    }

    private Overlay showMenu(String name, String anchorKey, List<MenuEntry> items) {
        return showMenu(name, anchorKey, items, null);
    }
    // onClose runs once it is closed, however it was.
    private Overlay showMenu(String name, String anchorKey, List<MenuEntry> items, Runnable onClose) {
        Overlay previous = overlay(name);
        if (previous != null) closeOverlay(previous);
        Theme t = t();
        LinearLayout list = column();
        list.setPadding(dp(5), dp(6), dp(5), dp(6));
        int widest = 0;
        String first = null;
        final Overlay[] shown = new Overlay[1];
        for (MenuEntry item : items) {
            if (item == MenuEntry.SEPARATOR) {
                // An 11 high slot holding a 1 px line, 8 in from each side.
                FrameLayout slot = new FrameLayout(this);
                View line = ui.sep();
                FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(-1, Math.max(1, dp(1)), Gravity.CENTER_VERTICAL);
                lineLp.setMargins(dp(3), 0, dp(3), 0);
                slot.addView(line, lineLp);
                list.addView(slot, lp(-1, 11));
                continue;
            }
            widest = Math.max(widest, textWidth(item.label, 14));
            if (first == null) first = item.key;
            TextView text = ui.label(item.label, 14, t.text);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setPadding(dp(9), 0, dp(9), 0);
            LinearLayout rowView = row();
            rowView.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
            ui.focus(rowView, item.key, Ui.Look.MENU_ROW).setOnClickListener(v -> {
                closeOverlay(shown[0]);
                item.action.run();
            });
            LinearLayout.LayoutParams rowLp = lp(-1, 40);
            rowLp.bottomMargin = dp(1);
            list.addView(rowView, rowLp);
        }
        Overlay overlay = showOverlay(name, OverlayLayer.Kind.POPUP, list, anchorKey, onClose);
        shown[0] = overlay;
        if (anchorKey != null) overlay.layer.setAnchor(l -> anchorRect(anchorKey, l), OverlayLayer.UNDER_CENTRE_OF_WINDOW, dp(4));
        overlay.width(Math.max(dp(140), widest + dp(40) + dp(10)));
        overlay.defaultKey = first;
        return overlay;
    }
    private int textWidth(String text, float sp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        return (int) Math.ceil(paint.measureText(text));
    }

    // The toast: the one notice the desktop has, in the window. One at a time, a new one
    // replacing the old. surface, a 1 px border, radius 9, 13 centred up to 6 lines, as
    // wide as its longest line plus 24 (80 at least, 36 less than the window at most) and
    // at least 40 high; 26 above the bottom, or at the top, 48 down, while an overlay is
    // open, where it would be behind it. It fades in over 180 ms and stays for 55 ms a
    // character, at least 3.2 s and at most 10; a tap dismisses it. It is announced to
    // accessibility.
    private final Handler toastHandler = new Handler(Looper.getMainLooper());
    private Runnable toastHide;
    private TextView toastView;

    private void showToast(CharSequence text) {
        if (isDestroyed()) return;
        hideToastNow();
        Theme t = t();
        TextView view = ui.label(text, 13, t.text);
        view.setGravity(Gravity.CENTER);
        view.setMaxLines(6);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setBackground(ui.outlined(t.surface, t.border, 9));
        view.setPadding(dp(12), dp(9), dp(12), dp(9));
        view.setMinHeight(dp(40));
        view.setMinWidth(dp(80));
        int window = host.getWidth() > 0 ? host.getWidth() - host.getPaddingLeft() - host.getPaddingRight() : getResources().getDisplayMetrics().widthPixels;
        view.setMaxWidth(Math.max(dp(80), window - dp(36)));
        // Not clickable: a toast is no control, and a tap on it only takes it away.
        view.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) hideToast(view);
            return true;
        });
        toastView = view;
        toastLayer.addView(view, toastParams());
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(180).start();
        view.announceForAccessibility(text);
        toastHide = () -> hideToast(view);
        toastHandler.postDelayed(toastHide, Math.min(10_000, Math.max(3_200, 55L * text.length())));
    }
    private FrameLayout.LayoutParams toastParams() {
        boolean top = !overlays.isEmpty();
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        p.topMargin = dp(48);
        p.bottomMargin = dp(26);
        if (!top && page == 0 && toastView != null) p.bottomMargin = homeToastMargin(toastView, p.bottomMargin);
        return p;
    }
    // How far above the bottom the toast sits on Home (HomePage.qml toastMargin): in the middle of the gap between the selector and
    // the speed tiles when it fits there; a longer one covers the tiles, whole, and not the selector, the one thing here people
    // tap. Where Home is not laid out yet, or is not there, the usual 26.
    private int homeToastMargin(View toast, int otherwise) {
        View selector = homeSelectorRow;
        View tiles = homeSpeedRow;
        if (selector == null || tiles == null || !selector.isAttachedToWindow() || !tiles.isAttachedToWindow()
                || selector.getHeight() == 0 || tiles.getHeight() == 0 || toastLayer.getHeight() == 0) return otherwise;
        toast.measure(View.MeasureSpec.makeMeasureSpec(toastLayer.getWidth(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = toast.getMeasuredHeight();
        int[] layerAt = new int[2];
        int[] selectorAt = new int[2];
        int[] tilesAt = new int[2];
        toastLayer.getLocationInWindow(layerAt);
        selector.getLocationInWindow(selectorAt);
        tiles.getLocationInWindow(tilesAt);
        int layerBottom = layerAt[1] + toastLayer.getHeight();
        int gapBottom = layerBottom - tilesAt[1];
        int gapTop = layerBottom - (selectorAt[1] + selector.getHeight());
        int room = gapTop - gapBottom;
        if (height + dp(8) <= room) return gapBottom + (room - height) / 2;
        return layerBottom - (tilesAt[1] + tiles.getHeight());
    }
    private void placeToast() {
        if (toastView != null && toastView.getParent() == toastLayer) toastView.setLayoutParams(toastParams());
    }
    private void hideToast(TextView view) {
        if (toastView != view) return;
        toastView = null;
        toastHandler.removeCallbacks(toastHide);
        view.animate().alpha(0f).setDuration(180).withEndAction(() -> toastLayer.removeView(view)).start();
    }
    private void hideToastNow() {
        if (toastView == null) return;
        toastHandler.removeCallbacks(toastHide);
        toastLayer.removeView(toastView);
        toastView = null;
    }
    // Five tiles, 46 by 38 with radius 8 and 8 between, icons only, centred. The current
    // page's tile is infoBg; the others rest on the page. The icons are 22: accent in the
    // current tab, textDim in the others, both at full strength, and the logo, which is
    // drawn in its own greys, at 1.0 in the current tab and 0.8 in the others. A remote's
    // focus is what the pointer's hover is: surface and grown to 1.08, and the current tile, which the
    // desktop leaves as it is, keeps its infoBg and only grows.
    private void nav() {
        Theme t = t();
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER);
        // 6 below the tiles, as on the desktop; the room above them is the status bar's and the overscan's.
        bar.setPadding(0, dp(9), 0, dp(6));
        // A tile that has the focus is 1.08 of its size, and the bar would cut that off at its padding.
        bar.setClipToPadding(false);
        settingsDot = null;
        for (int i = 0; i < tabNames.length; i++) {
            final int next = i;
            boolean current = i == page;
            FrameLayout item = new FrameLayout(this);
            ImageView icon = new ImageView(this); icon.setImageResource(tabIcons[i]);
            if (i == 0) icon.setAlpha(current ? 1f : 0.8f);
            else ui.tinted(icon, current ? t.accent : t.textDim);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
            item.addView(icon, iconLp);
            if (i == 3) {
                // Settings, while an update is waiting: a dot 7 across, 5 from the top and 8 from the right.
                View dot = new View(this);
                GradientDrawable round = new GradientDrawable();
                round.setShape(GradientDrawable.OVAL);
                round.setColor(t.warn);
                dot.setBackground(round);
                dot.setTag("update:dot");
                FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(7), dp(7), Gravity.TOP | Gravity.END);
                dotLp.topMargin = dp(5);
                dotLp.setMarginEnd(dp(8));
                item.addView(dot, dotLp);
                settingsDot = dot;
                refreshUpdateDot();
            }
            LinearLayout.LayoutParams itemLp = lp(46, 38);
            if (i > 0) itemLp.leftMargin = dp(8);
            bar.addView(item, itemLp);
            item.setContentDescription(tabNames[i]);
            ui.focus(item, "tab:" + i, Ui.Look.TILE, current ? Ui.F_CURRENT : 0).setOnClickListener(v -> { page = next; render(); });
        }
        pageRoot.addView(bar, lp(-1, -2));
    }
    private boolean updateWaiting() {
        AndroidUpdater.State update = AndroidUpdater.INSTANCE.getState();
        return update == AndroidUpdater.State.AVAILABLE || update == AndroidUpdater.State.READY;
    }
    private void refreshUpdateDot() {
        if (settingsDot != null) settingsDot.setVisibility(updateWaiting() ? View.VISIBLE : View.GONE);
    }

    // Home, as the desktop's HomePage draws it. The logo is the control: a click connects, cancels a connection that is
    // coming up or disconnects one that is up, and with no config to connect it opens the add menu on Configs.
    // Under it, while there is a session, one line: Connecting…, Disconnecting…, the time the connection has been up or,
    // while traffic is blocked, why, with Reconnect under it. Then the selector, which opens the config dropdown right here,
    // and +. At the foot, two tiles with the speeds. Keys: home:toggle (the logo), home:reconnect, home:config, home:add.
    // The logo's look follows the state (HeroMotion); the line under it and the speeds change once a second
    // without a render() (tickHome).
    private HeroMotion hero = new HeroMotion();
    private TextView homeStatus;
    private TextView homeDown;
    private TextView homeUp;
    private View homeSelectorRow;
    private View homeSpeedRow;
    // The last render() was of Home too, so the logo moves from where it was; otherwise it starts in its state.
    private boolean homeContinues;
    // After the app was left or came back, which the logo does not animate across.
    private boolean homeFresh = true;
    private boolean resumed;
    private final SpeedMeter speedMeter = new SpeedMeter();
    // Where the timer counts from when the service does not say (it always does, but a device test only sets the state).
    private long homeSeenUpAt;
    private long lastLogoClick;
    private int homePickerCount;

    private int pageWidthPx() {
        int width = host.getWidth() > 0 ? host.getWidth() - host.getPaddingLeft() - host.getPaddingRight() : getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(100), width);
    }

    private void home() {
        Theme t = t();
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        pageRoot.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        // The speed tiles, pinned 44 above the bottom, whatever the logo and the selector do.
        TextView[] down = new TextView[1];
        TextView[] up = new TextView[1];
        LinearLayout speeds = row();
        speeds.setGravity(Gravity.CENTER);
        speeds.addView(ui.speedTile("↓", t.success, down), lp(116, 44));
        ui.gapH(speeds, 12);
        speeds.addView(ui.speedTile("↑", t.textDim, up), lp(116, 44));
        FrameLayout.LayoutParams speedsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        speedsLp.bottomMargin = dp(44);
        stage.addView(speeds, speedsLp);
        // The speeds are of the time Home has been shown: a start is taken when it is shown, and the first tick after it gives a speed.
        if (vpnState != 2) speedMeter.reset();
        else if (!homeContinues) { speedMeter.reset(); sampleSpeeds(); }
        homeDown = down[0];
        homeUp = up[0];
        homeDown.setText(speedText(speedMeter.downBytesPerSecond()));
        homeUp.setText(speedText(speedMeter.upBytesPerSecond()));
        homeSpeedRow = speeds;

        // Above them, 22 clear, the logo, its line and the selector as one column, in the middle of what is left and 6 below it.
        FrameLayout area = new FrameLayout(this);
        area.setClipChildren(false);
        FrameLayout.LayoutParams areaLp = new FrameLayout.LayoutParams(-1, -1);
        areaLp.bottomMargin = dp(44 + 44 + 22);
        stage.addView(area, areaLp);
        LinearLayout column = column();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        columnLp.topMargin = dp(12);
        area.addView(column, columnLp);

        // The logo: 132 across, and it is all of the control, so the focus and a tap are on it and not around it.
        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        box.setContentDescription(isVpnActive() ? getString(R.string.text_disconnect_freetunnel) : getString(R.string.text_connect_freetunnel));
        ui.focus(box, "home:toggle", Ui.Look.HERO).setOnClickListener(v -> logoClicked());
        // A press, or OK on a remote, shrinks it to 0.96 and it comes back when that ends.
        box.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) hero.press(true);
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) hero.press(false);
            return false;
        });
        box.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) hero.press(true);
                else if (event.getAction() == KeyEvent.ACTION_UP) hero.press(false);
            }
            return false;
        });
        // With a remote's focus it is held at 1.05, the pulse's peak: a fill behind it would wash out its greys.
        box.setOnFocusChangeListener((v, hasFocus) -> {
            hero.focus(hasFocus);
            if (!hasFocus) hero.press(false);
        });
        LinearLayout.LayoutParams boxLp = lp(132, 132);
        boxLp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(box, boxLp);

        // The line, 6 under the logo, which takes the room it needs when a session starts and gives it back when it ends.
        HomeState.Line kind = HomeState.line(vpnState, stopping);
        FrameLayout line = new FrameLayout(this);
        line.setClipChildren(false);
        column.addView(line, lp(-1, 0));
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView status = ui.medium(statusText(kind), 15, kind == HomeState.Line.BLOCKED ? t.warn : t.textDim);
        status.setGravity(Gravity.CENTER);
        status.setMaxWidth(pageWidthPx() - dp(36));
        status.setTag("home:status");
        content.addView(status, lp(-2, -2));
        if (kind == HomeState.Line.BLOCKED) {
            // The service retries on its own; this reconnects now. The desktop has no such state, and nothing but the logo is
            // a button on Home: this is the accent link of Logs' "Turn on" (13), under the line. It is also the way in on
            // Android TV, which has no tile and makes notification actions hard to reach, so a remote lands on it first
            // (focusTarget). The connection coming up again is its own sign: the line turns to Connecting… and the logo pulses.
            TextView reconnect = ui.link(getString(R.string.vpn_reconnect), 13, Ui.LinkKind.ACCENT, "home:reconnect");
            reconnect.setOnClickListener(v -> FreeTunnelVpnService.reconnect(this));
            content.addView(reconnect, lp(-2, -2));
        }
        // As high as it needs whatever room the line has been given so far: while the line grows, it shows whole, as the desktop's does.
        content.measure(View.MeasureSpec.makeMeasureSpec(pageWidthPx(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(-1, content.getMeasuredHeight());
        contentLp.topMargin = dp(6);
        line.addView(content, contentLp);
        homeStatus = kind == HomeState.Line.TIMER ? status : null;
        int lineHeight = dp(6) + content.getMeasuredHeight();
        hero.attach(box, logo, line, status, dp(12), lineHeight, HomeState.sessionActive(vpnState, stopping),
                HomeState.alpha(vpnState, stopping), HomeState.pulsing(vpnState, stopping), homeContinues);

        ui.gap(column, 22);

        // The selector and +, side by side, 6 apart, as one row in the middle.
        LinearLayout selection = row();
        selection.setGravity(Gravity.CENTER);
        boolean any = !configs.isEmpty();
        String shown = any ? configs.get(Math.max(0, Math.min(activeIndex, configs.size() - 1))).name : getString(R.string.text_add_configuration);
        // The room the name has: the page less its 18 each side, the arrow and its 6, the + and its 6, and the selector's own padding.
        int room = pageWidthPx() - dp(36) - (any ? textWidth("▾", 15) + dp(6) + dp(6) + dp(22) : 0) - dp(12);
        LinearLayout selector = ui.selector(shown, any, room, "home:config");
        selector.setOnClickListener(v -> { if (configs.isEmpty()) addConfigOnConfigsPage(); else homePicker(); });
        selection.addView(selector, lp(-2, -2));
        if (any) {
            FrameLayout add = ui.smallIconButton(R.drawable.ic_add_home, 14, getString(R.string.text_add_configuration), "home:add");
            add.setOnClickListener(v -> addConfigOnConfigsPage());
            LinearLayout.LayoutParams addLp = lp(22, 22);
            addLp.leftMargin = dp(6);
            selection.addView(add, addLp);
        }
        LinearLayout.LayoutParams selectionLp = lp(-2, -2);
        selectionLp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(selection, selectionLp);
        homeSelectorRow = selection;
    }

    private String statusText(HomeState.Line kind) {
        switch (kind) {
            case CONNECTING: return getString(R.string.text_connecting);
            case DISCONNECTING: return getString(R.string.text_disconnecting);
            case TIMER: return SessionTime.format(SystemClock.elapsedRealtime() - sessionStart());
            case BLOCKED: return getString(R.string.vpn_traffic_blocked);
            default: return "";
        }
    }
    private String speedText(double bytesPerSecond) {
        return SpeedMeter.megabytes(bytesPerSecond) + getString(R.string.text_mb_per_s);
    }
    // SystemClock.elapsedRealtime() when the connection came up, from the service.
    private long sessionStart() {
        long start = FreeTunnelVpnService.sessionStart();
        if (start > 0) return start;
        if (homeSeenUpAt == 0) homeSeenUpAt = SystemClock.elapsedRealtime();
        return homeSeenUpAt;
    }

    // What the logo does: with no config and no session, the way to one, the add menu; otherwise it connects, cancels or disconnects.
    // A double tap counts once: people double-tap anything that looks like an icon, and it connected and at once cancelled.
    private void logoClicked() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogoClick < ViewConfiguration.getDoubleTapTimeout()) return;
        lastLogoClick = now;
        connect();
    }
    // Home's +, its "Add a config" and the logo with nothing to connect: the Configs page, with its menu open.
    private void addConfigOnConfigsPage() {
        page = 1;
        render();
        addMenu();
    }

    // Once a second while Home shows a connected session: the time under the logo and the two speeds. Only those views are
    // changed, never the page, so that nothing the user is doing on it is touched.
    private final Runnable homeTicker = new Runnable() {
        @Override public void run() {
            if (page != 0 || !resumed || vpnState != 2 || stopping) return;
            tickHome();
            scheduleHomeTick();
        }
    };
    private void scheduleHomeTick() {
        host.removeCallbacks(homeTicker);
        if (page != 0 || !resumed || vpnState != 2 || stopping) return;
        host.postDelayed(homeTicker, SessionTime.untilNextSecond(SystemClock.elapsedRealtime() - sessionStart()));
    }
    // The counters of this app, which carries the tunnel's traffic: the VPN excludes it, and its own connection to the server is the tunnel.
    // A device that keeps none gives TrafficStats.UNSUPPORTED, and the speeds stay 0.0.
    private void sampleSpeeds() {
        int uid = android.os.Process.myUid();
        speedMeter.sample(TrafficStats.getUidRxBytes(uid), TrafficStats.getUidTxBytes(uid), SystemClock.elapsedRealtime());
    }
    private void tickHome() {
        long now = SystemClock.elapsedRealtime();
        if (homeStatus != null) {
            String time = SessionTime.format(now - sessionStart());
            if (!time.contentEquals(homeStatus.getText())) homeStatus.setText(time);
        }
        sampleSpeeds();
        if (homeDown != null) homeDown.setText(speedText(speedMeter.downBytesPerSecond()));
        if (homeUp != null) homeUp.setText(speedText(speedMeter.upBytesPerSecond()));
    }

    // The config dropdown, right on Home (qml/pages/HomePage.qml, cfgPopup): under the selector, 6 below it and centred on it, as
    // wide as the widest name at 14 plus 30 (140 at least, the page less 16 at most), a row of 40 for each config, three showing and
    // the rest scrolling, the selected one in accent. A row selects that config (the one already selected does nothing),
    // reconnecting to it if a session is up, and closes it. Back, a tap anywhere else, and the selector, close it. The
    // first focus is on the selected row. Keys: home:pick:0, home:pick:1 and so on.
    private void homePicker() {
        if (configs.isEmpty() || overlay("homepicker") != null) return;
        Theme t = t();
        int selected = Math.max(0, Math.min(activeIndex, configs.size() - 1));
        LinearLayout frame = column();
        frame.setPadding(dp(5), dp(5), dp(5), dp(5));
        CappedScrollView scroll = new CappedScrollView(this);
        scroll.setMaxHeight(dp(3 * 40));
        LinearLayout list = column();
        final Overlay[] shown = new Overlay[1];
        int widest = 0;
        for (int i = 0; i < configs.size(); i++) {
            final int index = i;
            String name = configs.get(i).name;
            widest = Math.max(widest, textWidth(name, 14));
            int color = i == selected ? t.accent : t.text;
            TextView label = ui.label(name, 14, color);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(dp(10), 0, dp(10), 0);
            LinearLayout rowView = row();
            rowView.addView(label, new LinearLayout.LayoutParams(0, -1, 1));
            ui.focus(rowView, "home:pick:" + i, Ui.Look.MENU_ROW).setOnClickListener(v -> {
                closeOverlay(shown[0]);
                selectConfig(index);
            });
            list.addView(rowView, lp(-1, 40));
        }
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(scroll, lp(-1, -2));
        Overlay overlay = showOverlay("homepicker", OverlayLayer.Kind.POPUP, frame, "home:config", null);
        homePickerCount = configs.size();
        shown[0] = overlay;
        overlay.layer.setAnchor(l -> anchorRect("home:config", l), OverlayLayer.UNDER_CENTRE_OF_CONTROL, dp(6));
        overlay.width(Math.max(dp(140), widest + dp(30)));
        overlay.defaultKey = "home:pick:" + selected;
    }

    // What clicking a config does, here and on the Configs page: it becomes the one to connect to, and if a session is up it
    // reconnects to it. The one that is selected already is left alone.
    private void selectConfig(int index) {
        if (index < 0 || index >= configs.size() || index == activeIndex) return;
        activeIndex = index;
        saveConfigs();
        applyCurrentRules();
        render();
    }

    private void gapHorizontal(LinearLayout p, int size) { p.addView(new View(this), lp(size, 1)); }
    private void connect() {
        vpnState = FreeTunnelVpnService.state(this);
        if (isVpnActive()) {
            validationRequest++;
            // Disconnecting until the service says it is off.
            stopping = true;
            host.removeCallbacks(clearStopping);
            host.postDelayed(clearStopping, 8000);
            FreeTunnelVpnService.stop(this);
            render();
            return;
        }
        // Nothing to connect to: the way to something, as the desktop's logo is.
        if (configs.isEmpty()) { addConfigOnConfigsPage(); return; }
        Config config = activeConfig();
        String profile = activeProfileName();
        validateAsync(config.toml, profile, () -> {
            pendingConfig = config.toml;
            pendingProfile = profile;
            Intent prepare = VpnService.prepare(this);
            if (prepare == null) startVpnWithPendingConfig();
            else if (!startForResult(prepare, PREPARE_VPN)) {
                pendingConfig = null;
                toast(getString(R.string.text_vpn_consent_unavailable));
            }
        }, () -> {});
    }

    private boolean isVpnActive() {
        return vpnState == 1 || vpnState == 2 || vpnState == 3 || vpnState == 4 || vpnState == 5 || vpnState == 6;
    }

    private void startVpnWithPendingConfig() {
        if (pendingConfig == null) return;
        FreeTunnelVpnService.start(this, pendingConfig, pendingProfile);
        pendingConfig = null;
        pendingProfile = null;
        vpnState = 1;
        render();
        QuickTileService.refresh(this);
    }

    private void applyCurrentRules() {
        if (!isVpnActive()) return;
        if (configs.isEmpty()) {
            FreeTunnelVpnService.stop(this);
            vpnState = 0;
            return;
        }
        Config config = activeConfig();
        String profile = activeProfileName();
        validateAsync(config.toml, profile, () -> {
            if (FreeTunnelVpnService.state(this) == 0) return;
            FreeTunnelVpnService.start(this, config.toml, profile);
        }, () -> {});
    }

    // A config that passed: the text to save, what was left out of it, and the name
    // it gives its server, if it gives one.
    private interface Checked { void accept(RoutingConfig.Import checked); }

    private void validateAsync(String source, String profile, Runnable accepted, Runnable rejected) {
        validateAsync(source, profile, false, true, checked -> accepted.run(), rejected);
    }

    // With asImport, the text is first checked as a file to add (RoutingConfig.cleanImport),
    // which may refuse it, or take out a subscription or whatever in its certificate
    // is not a certificate. Here on the worker, since that parses the whole file once more.
    //
    // A check that is superseded is dropped, and says nothing: a newer check, or a change to the list of configs, makes what it
    // was asked for stale. That is for a check about the config in use (connecting, building the VPN again, the editor's Save),
    // which validationRequest numbers. A check of a file or a link to add is not: it is answered whatever else is going on,
    // since dropping it would lose a text the TV was to ask about, and nothing about it goes stale (the question is worked out
    // from the list as it is when the answer comes). Nor does it make another check stale: Replace builds the VPN again
    // while the next text waits behind it. No toast for the check itself: the desktop shows none, and the pulse of the logo or
    // the question that comes is the answer.
    private void validateAsync(String source, String profile, boolean asImport, boolean supersedable, Checked accepted, Runnable rejected) {
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_data_has_not_been_changed)); rejected.run(); return; }
        final long request = supersedable ? ++validationRequest : validationRequest;
        validationWorker.execute(() -> {
            try {
                RoutingConfig.Import checked = asImport ? RoutingConfig.cleanImport(source) : new RoutingConfig.Import(source, false);
                RoutingConfig.Plan plan = RoutingConfig.prepare(this, checked.getToml(), profile);
                NativeConfigValidator.INSTANCE.validate(getApplicationContext(), plan.getToml());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (supersedable && request != validationRequest) { rejected.run(); return; }
                    if (plan.getWarning() != null) toast(getString(plan.getWarning()));
                    accepted.accept(checked);
                });
            } catch (RoutingConfig.ImportRefused refused) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    rejected.run();
                    if (!supersedable || request == validationRequest) refuseImport(refused.messageResource);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    rejected.run();
                    if (!supersedable || request == validationRequest) toast(getString(R.string.config_validation_error, error.getMessage()));
                });
            }
        });
    }

    // The Configs page, as the desktop draws it (qml/pages/ConfigsPage.qml): at the top + and the speedometer, 40 by 32 and 16 apart
    // and in the middle, the speedometer only when there are configs; under them the rows (ConfigRowView), a click on which
    // selects that config; with none, "Add a config" in the middle of the page, which opens the add menu and is hidden while it is
    // open. On a phone a row is moved by pressing and holding it and dragging; a TV has no way to. Keys: configs:add, configs:ping,
    // configs:hint, and config:N:select, config:N:export, config:N:edit and config:N:remove for each row.
    private TextView configsHint;

    private void configs(LinearLayout body) {
        storageBanner(body);
        // The header's buttons are 40 by 32, 16 apart, resting on the page, 8 from the top of it.
        LinearLayout header = row(); header.setGravity(Gravity.CENTER);
        FrameLayout add = ui.iconButton(R.drawable.ic_add, 22, getString(R.string.text_add_configuration), "configs:add");
        add.setOnClickListener(v -> addMenu()); header.addView(add, lp(40, 32));
        if (!configs.isEmpty()) {
            gapHorizontal(header, 16);
            FrameLayout ping = ui.iconButton(R.drawable.ic_ping, 22, getString(R.string.text_check_server_tcp_latency), "configs:ping");
            ping.setOnClickListener(v -> pingConfigs()); header.addView(ping, lp(40, 32));
        }
        body.addView(header, lp(-1, 32));
        gap(body, 8);
        configsHint = null;
        if (configs.isEmpty()) {
            body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            // As wide as its words: the focus is around them, not across the page.
            TextView hint = ui.link(getString(R.string.text_add_configuration), 15, Ui.LinkKind.TEXT, "configs:hint");
            hint.setTypeface(Ui.MEDIUM);
            hint.setOnClickListener(v -> addMenu());
            // Under its own menu it would be cut in half.
            hint.setVisibility(overlay("addmenu") != null ? View.INVISIBLE : View.VISIBLE);
            configsHint = hint;
            LinearLayout.LayoutParams hintLp = lp(-2, 40);
            hintLp.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(hint, hintLp);
            body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            return;
        }
        // Answers of configs that are no more are not kept.
        pingResults.keySet().retainAll(configs);
        LinearLayout list = column();
        RowDrag drag = isTv() || storageFailed ? null
                : new RowDrag(list, dp(ConfigRowView.PITCH_DP), dp(2), this::moveConfig);
        for (int i = 0; i < configs.size(); i++) {
            final int index = i;
            final Config config = configs.get(i);
            boolean selected = i == activeIndex;
            // Worded by connected, as the desktop does; gone while it disconnects.
            CharSequence badge = null;
            if (selected && !stopping) {
                if (vpnState == 2) badge = getString(R.string.config_badge_connected);
                else if (isVpnActive()) badge = getString(R.string.config_badge_connecting);
            }
            ConfigRowView row = new ConfigRowView(this, ui, i, config.name, selected, pingResults.get(config), badge, vpnState == 2,
                    getString(R.string.text_export_config), getString(R.string.text_edit_configuration), getString(R.string.text_delete));
            row.setOnClickListener(v -> selectConfig(index));
            row.exportIcon.setOnClickListener(v -> exportMenu(config, index));
            row.editIcon.setOnClickListener(v -> editConfig(index));
            row.removeIcon.setOnClickListener(v -> confirmRemove(index));
            if (drag != null) drag.attach(row);
            list.addView(row, ConfigRowView.params(ui));
        }
        body.addView(list, lp(-1, -2));
    }
    // Moves a row to where it was dropped, and the selection stays on the config it was on.
    private void moveConfig(int from, int to) {
        if (storageFailed) return;
        activeIndex = Reorder.move(configs, from, to, activeIndex);
        saveConfigs();
        render();
    }

    // Every config at once, side by side on four threads: "…" until its probe answers, then the time or a dash.
    // Answers of an older press are dropped, as the desktop's are when its list is probed again.
    private void pingConfigs() {
        if (configs.isEmpty()) return;
        final int generation = ++pingGeneration;
        final String unit = getString(R.string.text_ms);
        List<Config> snapshot = new ArrayList<>(configs);
        for (Config config : snapshot) pingResults.put(config, PingProbe.PENDING);
        render();
        for (Config config : snapshot) {
            final String toml = config.toml;
            pingWorker.execute(() -> {
                String text = PingProbe.text(PingProbe.probe(firstAddress(toml), PingProbe.TIMEOUT_MS), unit);
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != pingGeneration) return;
                    pingResults.put(config, text);
                    if (page == 1) render();
                });
            });
        }
    }
    // The config's first address, as host:port, or null where it has none.
    private static String firstAddress(String toml) {
        try {
            org.tomlj.TomlArray addresses = RoutingConfig.validate(toml).getArray("endpoint.addresses");
            return addresses == null || addresses.size() == 0 ? null : addresses.getString(0);
        } catch (Exception e) { return null; }
    }

    // The export icon's choices, under it with no check mark: the config as a .toml file, through the system's file picker,
    // and as a tt:// link on the clipboard. Both carry the password, which the notice afterwards says.
    private void exportMenu(Config config, int index) {
        String[] options = {getString(R.string.export_toml), getString(R.string.export_deep_link)};
        showSelect("config:" + index + ":export", options, -1, which -> { if (which == 0) exportToml(config); else copyDeepLink(config); });
    }
    // A config's name may hold a "/" or a "\", which a file name cannot (ConfigsPage.exportFileName).
    private static String exportFileName(String name) {
        String flat = name.replaceAll("[/\\\\]", "-");
        return (flat.isEmpty() ? "config" : flat) + ".toml";
    }
    private void exportToml(Config config) {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, exportFileName(config.name));
        exporting = config;
        // Many TVs have no screen to save to.
        if (!startForResult(save, EXPORT_CONFIG)) { exporting = null; toast(getString(R.string.export_no_picker)); }
    }
    private void writeExport(Uri uri) {
        final Config config = exporting;
        exporting = null;
        if (config == null) return;
        // The list may have changed while the file picker was up: the config is found again, and if it is gone, that is said.
        if (!configs.contains(config)) { toast(getString(R.string.config_gone)); return; }
        final byte[] bytes = config.toml.getBytes(StandardCharsets.UTF_8);
        ioWorker.execute(() -> {
            boolean written;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("No stream");
                out.write(bytes);
                written = true;
            } catch (Exception e) { written = false; }
            final boolean ok = written;
            runOnUiThread(() -> { if (!isDestroyed()) toast(getString(ok ? R.string.config_exported : R.string.export_failed)); });
        });
    }
    private void copyDeepLink(Config config) {
        String link = null;
        try {
            DeepLinkImport.Link described = DeepLinkImport.linkOf(config.name, RoutingConfig.validate(config.toml));
            if (described != null) link = DeepLinkImport.encode(described);
        } catch (Exception e) { link = null; }
        if (link == null) { toast(getString(R.string.deep_link_failed)); return; }
        ClipData clip = ClipData.newPlainText("FreeTunnel link", link);
        // The link holds the password: a keyboard's clipboard history and the preview Android shows are asked to leave it out.
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        toast(getString(R.string.deep_link_copied));
    }
    // The Configs page's menu under its +: no title. A TV has no camera for a link's QR code and a remote
    // is no way to type one, so there a phone's way in comes first; then the desktop's items, in its
    // order and its words, and a separator before the last.
    private void addMenu() {
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_saved_data_has_been_preserved)); return; }
        List<MenuEntry> items = new ArrayList<>();
        if (isTv()) items.add(new MenuEntry(getString(R.string.text_receive_from_phone), "add:receive", this::receiveFromPhone));
        items.add(new MenuEntry(getString(R.string.text_paste_from_clipboard), "add:paste", this::pasteFromClipboard));
        items.add(new MenuEntry(getString(R.string.add_from_file), "add:file", this::pickFile));
        items.add(MenuEntry.SEPARATOR);
        items.add(new MenuEntry(getString(R.string.add_create_new), "add:create", () -> editConfig(-1)));
        showMenu("addmenu", "configs:add", items, this::refreshConfigsHint);
        refreshConfigsHint();
    }
    // The empty page's "Add a config" is hidden while its menu is open.
    private void refreshConfigsHint() {
        if (configsHint != null) configsHint.setVisibility(overlay("addmenu") != null ? View.INVISIBLE : View.VISIBLE);
    }
    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast(getString(R.string.text_clipboard_is_empty)); return; }
        CharSequence value = clip.getItemAt(0).coerceToText(this);
        importText(getString(R.string.text_from_clipboard), value == null ? "" : value.toString().trim(), null);
    }
    // Many TVs have no document picker. Some have one for GET_CONTENT only, which
    // onActivityResult reads the same way.
    private void pickFile() {
        for (String action : new String[]{Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT}) {
            if (startForResult(new Intent(action).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_CONFIG)) return;
        }
        toast(getString(isTv() ? R.string.text_no_file_picker_receive : R.string.text_no_file_picker));
    }
    // A link, or else a TOML file named fallbackName unless it names its server.
    // origin is the address it was sent from, null when it came from this device.
    private void importText(String fallbackName, String text, String origin) {
        importText(fallbackName, text, origin, null);
    }
    // Whoever asks for an import is told when it is answered, once: it was added, replaced, or cancelled,
    // or it was refused, which is true then, and a notice says why. Receive from phone takes its next text then.
    private interface Answered { void answered(boolean refused); }
    private static void answered(Answered done, boolean refused) { if (done != null) done.answered(refused); }

    private void importText(String fallbackName, String text, String origin, Answered done) {
        if (DeepLinkImport.looksLikeLink(text)) importDeepLink(text, origin, done);
        else importToml(fallbackName, text, origin, done);
    }
    private void importDeepLink(String link, String origin) { importDeepLink(link, origin, null); }
    private void importDeepLink(String link, String origin, Answered done) {
        final DeepLinkImport.Imported imported;
        try { imported = DeepLinkImport.parse(link); }
        catch (DeepLinkImport.ParseException e) { refuseImport(e.messageResource); answered(done, true); return; }

        // The text is the app's own, and the subscription and whatever in the
        // certificate is not a certificate were left out as the link was read.
        validateAsync(imported.toml, SplitProfiles.DEFAULT, false, false, checked -> showImportConfirmation(imported.name, false,
                new RoutingConfig.Import(checked.getToml(), imported.subscriptionNotKept, imported.certificateIgnored), origin, true, done),
                () -> answered(done, true));
    }
    // Named after its file or where the text came from, unless it is an endpoint's
    // export that names its server.
    private void importToml(String name, String toml, String origin) { importToml(name, toml, origin, null); }
    private void importToml(String name, String toml, String origin, Answered done) {
        validateAsync(stripBom(toml), SplitProfiles.DEFAULT, true, false, checked -> showImportConfirmation(
                checked.getName() != null ? checked.getName() : name, checked.getName() == null, checked, origin, false, done),
                () -> answered(done, true));
    }

    // Receive from phone: a phone on the same network opens the page the QR code or
    // the address leads to, and sends links or TOML files from there, pasted or as
    // files, as many as it likes (LinkReceiver). Each one goes through the same checks and
    // confirmation as any import, one after another in the order they came, and nothing is
    // added without the TV's yes (see processReceived).
    //
    // A card like the app picker's: ←, the title, the QR code on its own white tile (black on
    // white in both themes, so that a phone can read it), the address in monospace 16 to type on a
    // laptop, the hint, and a row with Close, which has the focus first, and, once something has
    // come, "Received: N" in textFaint at its left. The screen stays on while it is open: a
    // screensaver would stop the activity, and the receiver with it. The card and the port stay
    // open while the TV asks about what came, so that the phone can send the next, until Close,
    // Back, ←, the time, three wrong codes or leaving the app. What came and was not answered yet
    // is still asked about when the port closes, except when the app is left. Keys: receive:back
    // and receive:close.
    private void receiveFromPhone() {
        stopReceiver();
        InetAddress address = lanAddress();
        if (address == null) { toast(getString(R.string.text_receive_no_network)); return; }
        final LinkReceiver r;
        try { r = new LinkReceiver(address, LinkReceiver.newCode(new SecureRandom()), receivePages(), receivedTexts.waiting()); }
        catch (IOException e) { toast(getString(R.string.text_receive_failed)); return; }
        linkReceiver = r;
        receivedCount = 0;
        final int generation = receiveGeneration;
        Theme t = t();
        final Overlay[] shown = new Overlay[1];
        LinearLayout card = column();
        card.addView(ui.cardHeader(getString(R.string.text_receive_from_phone), "receive:back", getString(R.string.text_back),
                v -> closeOverlay(shown[0])), lp(-1, -2));
        LinearLayout box = column(); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(dp(18), dp(4), dp(18), dp(4));
        // Drawn at its own size, a whole number of pixels a module, so it is never blurred. The most
        // that fits: a TV only 540 dp high has the card's other lines to make room for.
        float density = getResources().getDisplayMetrics().density;
        int room = host.getHeight() > 0 ? Math.round((host.getHeight() - host.getPaddingTop() - host.getPaddingBottom()) / density) - 52 - 292 : 220;
        LinkReceiver.Qr qr = LinkReceiver.qr(r.url(), dp(Math.max(130, Math.min(220, room))));
        ImageView code = new ImageView(this);
        code.setImageBitmap(Bitmap.createBitmap(qr.pixels, qr.side, qr.side, Bitmap.Config.ARGB_8888));
        code.setContentDescription(r.url());
        // Its own tile: the code's quiet zone is white, whatever the theme.
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(ui.shape(Color.WHITE, 10));
        tile.setClipToOutline(true);
        tile.addView(code, new FrameLayout.LayoutParams(-2, -2));
        box.addView(tile, lp(-2, -2));
        gap(box, 10);
        // For typing on a laptop.
        TextView url = ui.mono(r.url(), 16, t.text);
        url.setGravity(Gravity.CENTER); box.addView(url, lp(-1, -2));
        gap(box, 10);
        box.addView(label(getString(R.string.text_receive_hint), 13, t.textDim), lp(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        // Nothing in it to take the focus: left focusable, it would be a stop with no sign of it.
        scroll.setFocusable(false);
        scroll.addView(box);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button close = ui.button(getString(R.string.text_close), Ui.Kind.NEUTRAL, 88, "receive:close");
        close.setOnClickListener(v -> closeOverlay(shown[0]));
        LinearLayout buttons = row(); buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(18), dp(6), dp(18), dp(12));
        // How many have come, once one has: in the row of the button, where it takes no more of the card's height.
        TextView counted = label("", 12, t.textFaint);
        counted.setTag("receive:count");
        counted.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        counted.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        buttons.addView(counted, new LinearLayout.LayoutParams(0, dp(32), 1));
        receivedLine = counted;
        buttons.addView(close, lp(-2, 32));
        card.addView(buttons, lp(-1, -2));
        // No screensaver while the phone is used instead of the remote.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Overlay overlay = showOverlay("receive", OverlayLayer.Kind.CARD, card, null, () -> {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (receivedLine == counted) receivedLine = null;
            if (linkReceiver == r) stopReceiver();
        });
        shown[0] = overlay;
        overlay.width(dp(460));
        // A remote starts on Close, not on the back arrow above the code.
        overlay.defaultKey = "receive:close";
        // These arrive on the receiver's thread. A text it took is the TV's to answer, whether or not its card is
        // still up, unless the app was left in between.
        r.start(new LinkReceiver.Listener() {
            @Override public void received(String text, String fileName, String sender) {
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != receiveGeneration) { receivedTexts.giveBack(); return; }
                    receivedTexts.add(text, fileName, sender);
                    if (linkReceiver == r) {
                        receivedCount++;
                        if (receivedLine != null) receivedLine.setText(getString(R.string.text_receive_received, receivedCount));
                    }
                    processReceived();
                });
            }
            @Override public void stopped(LinkReceiver.Stop reason) {
                runOnUiThread(() -> {
                    if (isDestroyed() || linkReceiver != r) return;
                    boolean somethingCame = receivedCount > 0;
                    stopReceiver();
                    toast(getString(reason == LinkReceiver.Stop.LOCKED_OUT ? R.string.text_receive_locked_out
                            : reason == LinkReceiver.Stop.TIMEOUT ? (somethingCame ? R.string.text_receive_timed_out_after_texts : R.string.text_receive_timed_out)
                            : R.string.text_receive_failed));
                });
            }
        });
    }
    // Closes the port and its card: its card's closing finds the port gone and leaves it be. What it took
    // stays in the queue.
    private void stopReceiver() {
        LinkReceiver r = linkReceiver;
        linkReceiver = null;
        if (r != null) r.stop();
        Overlay open = overlay("receive");
        if (open != null) closeOverlay(open);
    }

    // The next text it took, checked and asked about, when none is: importText, then validateAsync, then the
    // confirmation, then the next. Nothing is added without a yes. A text holds its place in the receiver's
    // count until it is answered, whatever the answer, which is what lets the phone send another when ten
    // waited. When one is refused, its notice stays up before the next one's check, and its question, can cover it.
    private void processReceived() {
        if (isDestroyed() || receivedTexts.busy() || receivedTexts.queued() == 0) return;
        if (receivedHold) {
            if (toastView != null && SystemClock.uptimeMillis() < receivedHoldUntil) {
                if (!receivedPollPending) {
                    receivedPollPending = true;
                    toastHandler.postDelayed(() -> { receivedPollPending = false; processReceived(); }, 250);
                }
                return;
            }
            receivedHold = false;
        }
        final ReceivedQueue.Item item = receivedTexts.next();
        // A TOML file is named after its file, as one picked here is; a link names itself.
        importText(item.fileName != null ? item.fileName : getString(R.string.text_from_another_device), item.text, item.sender, refused -> {
            if (!receivedTexts.answered(item)) return;
            if (refused) { receivedHold = true; receivedHoldUntil = SystemClock.uptimeMillis() + 10_000; }
            // After what answered it is done, not inside it.
            host.post(this::processReceived);
        });
    }
    // The app is left: what is waiting for its turn is dropped, and so is what the receiver handed over
    // that this thread has not seen. The one being asked about is not: its question is still up.
    private void dropReceived() {
        receiveGeneration++;
        receivedTexts.drop();
    }
    // The address a phone on the same Wi-Fi or Ethernet reaches this device at: the
    // active network's first, and never a VPN's. FreeTunnel is left out of its own VPN,
    // so the phone's connection comes in past it.
    @SuppressWarnings("deprecation") // getAllNetworks(): a one-off look needs no network callback.
    private InetAddress lanAddress() {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        List<Network> networks = new ArrayList<>();
        Network active = connectivity.getActiveNetwork();
        if (active != null) networks.add(active);
        for (Network network : connectivity.getAllNetworks()) if (!network.equals(active)) networks.add(network);
        List<InetAddress> addresses = new ArrayList<>();
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties link = connectivity.getLinkProperties(network);
            if (capabilities == null || link == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            for (LinkAddress linkAddress : link.getLinkAddresses()) addresses.add(linkAddress.getAddress());
        }
        return LinkReceiver.pickAddress(addresses);
    }
    // The phone's page in this device's language.
    private LinkReceiver.Pages receivePages() {
        return new LinkReceiver.Pages(getResources().getConfiguration().getLocales().get(0).toLanguageTag(), getString(R.string.receive_page_title),
                getString(R.string.receive_page_prompt), getString(R.string.receive_page_file), getString(R.string.receive_page_send),
                getString(R.string.receive_page_note), getString(R.string.receive_page_sent), getString(R.string.receive_page_retry),
                getString(R.string.receive_page_too_large), getString(R.string.receive_page_busy),
                getString(R.string.receive_page_wrong_code), getString(R.string.receive_page_locked_out));
    }
    // A refusal is the desktop's kind of notice, a toast, however long: up to 6 lines, 10 seconds.
    private void refuseImport(int messageResource) {
        toast(getString(messageResource));
    }
    // What an import left out, a line for each.
    private String importNotes(RoutingConfig.Import checked) {
        List<String> notes = new ArrayList<>();
        if (checked.getSubscriptionNotKept()) notes.add(getString(R.string.import_subscription_note));
        if (checked.getCertificateIgnored()) notes.add(getString(R.string.import_certificate_note));
        return TextUtils.join("\n", notes);
    }
    // The question before anything is added, as the desktop asks it (Backend::askToImport): whether it
    // is a link or a file, the server it would connect to, and what is wrong with it. The lines are
    // those, with the first, the second and the third in this order whatever they say:
    //   Add a VPN server from this link?               (or, for a name already in the list: “Name” already exists…)
    //   Server: host
    //   Sent from 192.168.1.5                           (Android only: where a phone sent it from)
    // then the rest. The question does not say the name it would be added under, as the desktop's does
    // not: the name comes with the link, and is the one thing whoever made it fully controls, so that a
    // decision made on the name alone can be made on a lie; the server is what it connects to.
    // origin is the address the text was sent from, shown so that a text someone
    // else on the network sent first can be told apart; null when it came from here. The name that is
    // shown (the existing one's, in a collision) and the server are shown as ShownText shows them, so that
    // whatever sent them, the lines after them stay in sight. namedAfterFile is true when name is the one the
    // text was imported under, its file's name or the app's words for where it came from, and false when it
    // is a link's or an export's own; link is true for a tt:// link. Cancel has the focus, and Import is red
    // and waits for 400 ms. A name already in the list is the desktop's collision, and the desktop's alone:
    // Replace puts it where that one is, Add copy adds it as another. The same server under another
    // name is no collision, as on the desktop.
    private void showImportConfirmation(String name, boolean namedAfterFile, RoutingConfig.Import checked, String origin, boolean link) {
        showImportConfirmation(name, namedAfterFile, checked, origin, link, null);
    }
    private void showImportConfirmation(String name, boolean namedAfterFile, RoutingConfig.Import checked, String origin, boolean link,
                                        Answered done) {
        String source = checked.getToml();
        org.tomlj.TomlTable parsed = RoutingConfig.validate(source);
        Config clash = null;
        for (Config config : configs) {
            if (config.name.equals(name)) { clash = config; break; }
        }
        StringBuilder message = new StringBuilder(clash == null
                ? getString(link ? R.string.import_question_link : R.string.import_question_file)
                : getString(R.string.import_exists, ShownText.of(clash.name)));
        message.append('\n').append(getString(R.string.import_server, ShownText.of(parsed.getString("endpoint.hostname"))));
        if (origin != null) message.append(getString(R.string.text_nsent_from_s, origin));
        // A file's extension is not counted: a configuration named after "Дом.toml"
        // would otherwise always be said to mix alphabets. Only a file's: a link's or
        // an export's name, or the server a link is named after, such as "раураӏ.com",
        // is counted whole.
        String word = namedAfterFile && name.matches("(?s).+\\.[0-9A-Za-z]{1,5}") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (word.codePoints().mapToObj(Character.UnicodeScript::of)
                .filter(script -> script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED
                        && script != Character.UnicodeScript.UNKNOWN).distinct().count() > 1)
            message.append('\n').append(getString(R.string.import_mixed_name));
        if (Boolean.TRUE.equals(parsed.getBoolean("endpoint.skip_verification")))
            message.append('\n').append(getString(link ? R.string.import_skips_link : R.string.import_skips_file));
        String notes = importNotes(checked);
        if (!notes.isEmpty()) message.append('\n').append(notes);
        final Config existing = clash;
        // Whichever way it is answered, Cancel, Back or a tap outside included, the asker is told.
        final Runnable declined = () -> answered(done, false);
        if (existing == null) {
            showConfirm(message, getString(R.string.import_confirm), null,
                    () -> { addImported(name, source); answered(done, false); }, null, declined);
        } else {
            showConfirm(message, getString(R.string.import_replace), getString(R.string.import_add_copy),
                    () -> { replaceImported(existing, name, source); answered(done, false); },
                    () -> { addImported(name, source); answered(done, false); }, declined);
        }
    }
    // After an import the page stays as it is, and the toast says what was added, as the desktop's does.
    private void addImported(String name, String source) {
        configs.add(new Config(name, source));
        if (configs.size() == 1) activeIndex = 0;
        if (!saveConfigs()) return;
        render();
        toast(getString(R.string.config_added, ShownText.of(name)));
    }
    // Replace: the config goes where the one it replaces was, and if that one is the one the VPN runs,
    // the VPN reconnects to this. If that one is gone by now, it is added.
    private void replaceImported(Config existing, String name, String source) {
        int at = configs.indexOf(existing);
        if (at < 0) { addImported(name, source); return; }
        // The config goes where the old one was, and uses the profile it used.
        configs.set(at, new Config(name, source, existing.profile));
        if (!saveConfigs()) return;
        if (at == activeIndex) applyCurrentRules();
        render();
        toast(getString(R.string.config_added, ShownText.of(name)));
    }
    private static String stripBom(String toml) {
        return toml.startsWith("\uFEFF") ? toml.substring(1) : toml;
    }
    // The editor: the desktop's card (qml/CreateConfigOverlay.qml), with ← and the title, a Name field, the
    // Split profile this config uses as a select box (where a config's profile is chosen, as on the desktop), the
    // TOML in a text area of its own, Show TOML and password as a toggle row, and Cancel and Save at the
    // bottom right. It opens on the name, and a remote never lands on the toggle by itself, first or after a
    // Save that is refused, where an OK would show the password to the room. The TOML holds the password,
    // so while the card is up the window is FLAG_SECURE. Back, ←, Cancel and a tap outside ask before they
    // drop what was typed. Keys: editor:back, editor:name, editor:toml, editor:show, editor:cancel and
    // editor:save; the select box is editor:profile.
    private void editConfig(int index) {
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_data_has_not_been_changed)); return; }
        if (overlay("editor") != null) return;
        final Config editing = index < 0 ? null : configs.get(index);
        final Overlay[] shown = new Overlay[1];
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(getString(R.string.editor_name_placeholder));
        name.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        EditText raw = new EditText(this);
        raw.setHint(getString(R.string.text_paste_complete_trusttunnel_toml));
        raw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // In landscape a keyboard can fill the screen with its own copy of the text. Ask it
        // not to go full screen, and not to copy the text if it does anyway: the copy has no
        // password mask and is outside FLAG_SECURE. Keyboards see ordinary text here, not a
        // password field, so also ask them not to learn from it: the TOML holds the password.
        raw.setImeOptions(raw.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        raw.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if (editing != null) { name.setText(editing.name); raw.setText(editing.toml); }
        // The profile the config uses; a new one uses Default.
        final String[] chosenProfile = {editing == null ? SplitProfiles.DEFAULT : splitModel().effective(editing.profile)};
        // What was there when it opened. Joined on a character no text holds, so that moving a boundary
        // between the two is a change.
        final String opened = name.getText() + "\u001f" + raw.getText() + "\u001f" + chosenProfile[0];
        ToggleView show = ui.toggle(false, false, "editor:show");
        show.setContentDescription(getString(R.string.text_show_toml_and_password));
        show.setListener((v, checked) -> raw.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance()));

        final boolean[] asking = {false};
        final Runnable tryClose = () -> {
            Overlay editor = shown[0];
            if (editor == null || editor.closed) return;
            if ((name.getText() + "\u001f" + raw.getText() + "\u001f" + chosenProfile[0]).equals(opened)) { closeOverlay(editor); return; }
            // A second Cancel, or a Back, while the question is up or waits is no second question.
            if (asking[0]) return;
            asking[0] = true;
            showConfirm(getString(R.string.editor_discard_question), getString(R.string.editor_discard), null,
                    () -> closeOverlay(editor), null, () -> asking[0] = false);
        };

        LinearLayout card = column();
        card.addView(ui.cardHeader(getString(index < 0 ? R.string.text_new_configuration : R.string.text_edit_configuration),
                "editor:back", getString(R.string.text_back), v -> tryClose.run()), lp(-1, -2));
        LinearLayout form = column();
        form.setPadding(dp(18), 0, dp(18), 0);
        // The toggle's row fills a little beyond the fields' edges when it has the focus.
        form.setClipToPadding(false);
        form.addView(ui.field(getString(R.string.editor_name), name, "editor:name"), lp(-1, -2));
        gap(form, 10);
        TextView[] profileValue = new TextView[1];
        form.addView(ui.selectField(getString(R.string.editor_split_profile), profileLabel(chosenProfile[0]), "editor:profile", profileValue, v -> {
            // The keyboard would cover the choices.
            hideKeyboard();
            List<String> names = splitModel().names();
            String[] labels = new String[names.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = profileLabel(names.get(i));
            showSelect("editor:profile", labels, names.indexOf(chosenProfile[0]), which -> {
                chosenProfile[0] = names.get(which);
                profileValue[0].setText(profileLabel(chosenProfile[0]));
            });
        }), lp(-1, -2));
        gap(form, 10);
        form.addView(ui.fieldArea(getString(R.string.editor_toml), raw, "editor:toml", 150), lp(-1, -2));
        gap(form, 10);
        form.addView(ui.editorToggleRow(getString(R.string.text_show_toml_and_password), show), ui.focusRowLp());
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        // The fields are its stops, not it.
        scroll.setFocusable(false);
        scroll.addView(form);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = ui.button(getString(R.string.text_cancel), Ui.Kind.SECONDARY, 88, "editor:cancel");
        cancel.setOnClickListener(v -> tryClose.run());
        Button save = ui.button(getString(R.string.text_save), Ui.Kind.PRIMARY, 88, "editor:save");
        LinearLayout buttons = row();
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        buttons.setPadding(dp(18), dp(6), dp(18), dp(12));
        buttons.addView(cancel, lp(-2, 32));
        ui.gapH(buttons, 8);
        buttons.addView(save, lp(-2, 32));
        card.addView(buttons, lp(-1, -2));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        Overlay overlay = showOverlay("editor", OverlayLayer.Kind.CARD, card, null, () -> {
            validationRequest++;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        shown[0] = overlay;
        overlay.width(dp(372));
        overlay.escape = tryClose;
        // A remote starts on the name: left to itself, on the first control, which could be the toggle.
        overlay.defaultKey = "editor:name";
        // One check at a time. Only a phone greys Save out meanwhile: disabling the view
        // that has a remote's focus moves the focus to the toggle as well.
        boolean[] checking = {false};
        save.setOnClickListener(v -> {
            if (checking[0]) return;
            String n = name.getText().toString().trim();
            String text = stripBom(raw.getText().toString().trim());
            if (n.isEmpty()) { toast(getString(R.string.text_enter_a_configuration_name)); return; }
            // A link pasted here is read as the link it is. On a TV, a phone's
            // keyboard, such as the Google TV app's, can paste one where a whole
            // file is a lot to get across.
            final DeepLinkImport.Imported link;
            try { link = DeepLinkImport.looksLikeLink(text) ? DeepLinkImport.parse(text) : null; }
            catch (DeepLinkImport.ParseException e) { refuseImport(e.messageResource); return; }
            checking[0] = true;
            if (save.isInTouchMode()) save.setEnabled(false);
            // Checked as an import: the hint asks for a whole TrustTunnel file, and
            // one pasted here, or saved by an earlier version, can carry a
            // subscription address, an auth key or a private key with its
            // certificate just as an imported one can. An endpoint's export is
            // turned into a whole file, and the name stays the one typed here,
            // as it does for a link, which is checked as importDeepLink() checks it.
            validateAsync(link != null ? link.toml : text, chosenProfile[0], link == null, true, result -> {
                RoutingConfig.Import checked = link == null ? result
                        : new RoutingConfig.Import(result.getToml(), link.subscriptionNotKept, link.certificateIgnored);
                if (overlay.closed) return;
                if (editing == null) configs.add(new Config(n, checked.getToml(), chosenProfile[0]));
                else {
                    int current = configs.indexOf(editing);
                    if (current < 0) { checking[0] = false; save.setEnabled(true); return; }
                    configs.set(current, new Config(n, checked.getToml(), chosenProfile[0]));
                }
                boolean saved = saveConfigs();
                closeOverlay(overlay);
                if (!saved) return;
                applyCurrentRules(); render();
                // What was left out of it, as a toast.
                String notes = importNotes(checked);
                if (!notes.isEmpty()) toast(notes);
            }, () -> { checking[0] = false; save.setEnabled(true); });
        });
    }
    // The name is cut in the middle to 36, as the desktop cuts it, and shown as ShownText shows names.
    private void confirmRemove(int index) {
        if (storageFailed) return;
        Config config = configs.get(index);
        showConfirm(getString(R.string.delete_config_question, ShownText.elideMiddle(ShownText.oneLine(config.name), 36)),
                getString(R.string.text_delete), () -> {
                    // Where it is now: the list may have changed while this was asked.
                    int at = configs.indexOf(config);
                    if (at < 0) return;
                    boolean activeRemoved = at == activeIndex;
                    configs.remove(at);
                    if (at < activeIndex) activeIndex--;
                    activeIndex = Math.max(0, Math.min(activeIndex, configs.size() - 1));
                    saveConfigs(); if (activeRemoved) applyCurrentRules(); render();
                });
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PREPARE_VPN) {
            if (resultCode == RESULT_OK) startVpnWithPendingConfig();
            else { pendingConfig = null; toast(getString(R.string.text_vpn_permission_was_not_granted)); }
            return;
        }
        if (requestCode == EXPORT_CONFIG) {
            Uri saved = resultCode == RESULT_OK && data != null ? data.getData() : null;
            if (saved == null) exporting = null;
            else writeExport(saved);
            return;
        }
        if (requestCode != PICK_CONFIG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData(); if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (input == null) return;
            byte[] bytes = new byte[8192]; int n; int total = 0;
            while ((n = input.read(bytes)) != -1) { total += n; if (total > 1024 * 1024) { toast(getString(R.string.text_file_is_too_large)); return; } out.write(bytes, 0, n); }
            String name = null;
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            } catch (Exception ignored) { }
            if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = getString(R.string.text_configuration);
            int slash = name.lastIndexOf('/'); if (slash >= 0) name = name.substring(slash + 1);
            importToml(name, out.toString(StandardCharsets.UTF_8.name()), null);
        } catch (Exception e) { toast(getString(R.string.text_could_not_open_file)); }
    }

    // The Split page (qml/pages/SplitPage.qml), top to bottom: the Split Tunneling switch; the Mode, as a value and its ▾; the
    // notices that apply; Profile and its chips, the edited one in accent, with a + that opens the field for a new name;
    // the rules header, with Recommended for Russia and Clear all, the domains as chips and the field to add them; and the
    // applications header, with Choose… and Clear all, and the applications as chips. The chips are the profile being edited
    // (SplitProfiles.edited), which is not the profile the selected config uses: that is chosen in the editor.
    // Keys: split:enabled, split:mode, profile:N, profile:N:remove, profile:add, profile:name, domains:russia, domains:clear,
    // domain:N, domains:input, apps:choose, apps:clear and app:N.
    private void split(LinearLayout body) {
        SplitProfiles model = splitModel();
        final String edited = model.edited();
        boolean enabled = prefs.getBoolean("split_enabled", false);
        boolean through = prefs.getBoolean("through", false);
        ToggleView splitSwitch = ui.toggle(enabled, false, "split:enabled");
        splitSwitch.setContentDescription(getString(R.string.text_split_tunnelling));
        splitSwitch.setListener((v, checked) -> {
            prefs.edit().putBoolean("split_enabled", checked).apply();
            applyCurrentRules();
            // The notice depends on it.
            render();
        });
        body.addView(ui.toggleRow(getString(R.string.text_split_tunnelling), null, splitSwitch), ui.focusRowLp());
        // Only the value and its ▾ are the control: the label is plain text.
        body.addView(ui.dropdown(getString(R.string.text_mode), through ? getString(R.string.text_through_vpn_62) : getString(R.string.text_bypass_vpn_61),
                "split:mode", v -> showSelect("split:mode",
                new String[]{getString(R.string.text_bypass_vpn_61), getString(R.string.text_through_vpn_62)}, through ? 1 : 0, which -> {
                    prefs.edit().putBoolean("through", which == 1).apply(); applyCurrentRules(); render();
                })), lp(-1, -2));
        for (CharSequence notice : splitNotices(model, enabled, through)) {
            gap(body, 6);
            body.addView(ui.notice(notice), lp(-1, -2));
        }
        gap(body, 6);
        body.addView(ui.sectionLabel(getString(R.string.split_profile)), lp(-1, -2));
        FlowLayout profiles = new FlowLayout(this, dp(6));
        List<String> names = model.names();
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            boolean current = name.equals(edited);
            LinearLayout chip = ui.chip(profileLabel(name), current, true, "profile:" + i, false);
            chip.setOnClickListener(v -> selectProfile(name));
            // Default is the one that cannot be deleted.
            if (!SplitProfiles.DEFAULT.equals(name)) {
                ImageView x = ui.chipX("profile:" + i + ":remove", current);
                x.setContentDescription(getString(R.string.text_delete) + " " + name);
                x.setOnClickListener(v -> confirmRemoveProfile(name));
                chip.addView(x, ui.chipXLp());
                ui.linkChipX(chip, x);
            }
            profiles.addView(chip);
        }
        TextView plus = ui.plusChip(getString(R.string.split_profile_add), "profile:add");
        plus.setOnClickListener(v -> openProfileName());
        profiles.addView(plus);
        LinearLayout.LayoutParams profilesLp = lp(-1, -2);
        profilesLp.topMargin = dp(2);
        body.addView(profiles, profilesLp);
        // The field for the name of a new profile: there all the time, and shown while it is wanted.
        EditText nameInput = ui.pageInput(getString(R.string.split_profile_name_hint), "profile:name");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(SplitProfiles.MAX_NAME)});
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameInput.setText(profileNameDraft);
        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { profileNameDraft = text.toString(); }
        });
        // Enter adds it; Escape, or going to another control, drops it.
        nameInput.setOnEditorActionListener((field, action, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            addProfile(nameInput.getText().toString());
            return true;
        });
        nameInput.setOnKeyListener((field, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_ESCAPE) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) closeProfileName();
            return true;
        });
        nameInput.setOnFocusChangeListener((field, hasFocus) -> { if (!hasFocus && !rendering) closeProfileName(); });
        nameInput.setVisibility(profileNameOpen ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams nameLp = lp(-1, 36);
        nameLp.topMargin = dp(6);
        body.addView(nameInput, nameLp);
        profileNameInput = nameInput;
        gap(body, 14);

        // Rules: the domains of the profile being edited.
        List<String> domainRules = model.domains(edited);
        LinearLayout domainHeader = row();
        TextView rulesTitle = ui.sectionLabel(through ? getString(R.string.text_rules_through_vpn) : getString(R.string.text_rules_bypass_vpn));
        rulesTitle.setSingleLine(true);
        rulesTitle.setEllipsize(TextUtils.TruncateAt.END);
        domainHeader.addView(rulesTitle, weighted());
        TextView recommended = ui.link(getString(R.string.split_recommended), 12, Ui.LinkKind.ACCENT, "domains:russia");
        recommended.setOnClickListener(v -> addRecommendedRussia());
        domainHeader.addView(recommended, lp(-2, -2));
        if (!domainRules.isEmpty()) {
            TextView clearDomains = ui.link(getString(R.string.split_clear_all), 12, Ui.LinkKind.DANGER, "domains:clear");
            clearDomains.setOnClickListener(v -> showConfirm(getString(R.string.split_clear_domains_question), getString(R.string.text_clear),
                    () -> editRules(m -> m.clearDomains(m.edited()))));
            domainHeader.addView(clearDomains, lp(-2, -2));
        }
        body.addView(domainHeader, lp(-1, -2));
        if (!domainRules.isEmpty()) {
            gap(body, 6);
            FlowLayout domains = new FlowLayout(this, dp(6));
            for (int i = 0; i < domainRules.size(); i++) {
                final int index = i;
                final String rule = domainRules.get(i);
                LinearLayout chip = ui.chip(rule, false, false, null, false);
                // A rule goes at once, without being asked about, as the desktop's does.
                ImageView x = ui.chipX("domain:" + i, false);
                x.setContentDescription(getString(R.string.text_delete) + " " + rule);
                x.setOnClickListener(v -> editRules(m -> m.removeDomain(m.edited(), index)));
                chip.addView(x, ui.chipXLp());
                domains.addView(chip);
            }
            body.addView(domains, lp(-1, -2));
        }
        EditText domainInput = ui.pageInput(getString(R.string.split_domains_hint), "domains:input");
        domainInput.setOnEditorActionListener((field, action, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (enterDomains(domainInput.getText().toString())) domainInput.setText("");
            return true;
        });
        LinearLayout.LayoutParams domainInputLp = lp(-1, 36);
        domainInputLp.topMargin = dp(6);
        body.addView(domainInput, domainInputLp);
        gap(body, 14);

        // Applications: those of the profile being edited, by the name the picker showed. No field for typing one in:
        // the picker is the way in.
        List<String> appRules = model.apps(edited);
        LinearLayout appHeader = row();
        TextView appsTitle = ui.sectionLabel(through ? getString(R.string.text_applications_through_vpn) : getString(R.string.text_applications_bypass_vpn));
        appsTitle.setSingleLine(true);
        appsTitle.setEllipsize(TextUtils.TruncateAt.END);
        appHeader.addView(appsTitle, weighted());
        TextView chooseLink = ui.link(getString(R.string.text_choose), 12, Ui.LinkKind.ACCENT, "apps:choose");
        chooseLink.setOnClickListener(v -> appPicker());
        appHeader.addView(chooseLink, lp(-2, -2));
        if (!appRules.isEmpty()) {
            TextView clearApps = ui.link(getString(R.string.split_clear_all), 12, Ui.LinkKind.DANGER, "apps:clear");
            clearApps.setOnClickListener(v -> showConfirm(getString(R.string.split_clear_apps_question), getString(R.string.text_clear),
                    () -> editRules(m -> m.clearApps(m.edited()))));
            appHeader.addView(clearApps, lp(-2, -2));
        }
        body.addView(appHeader, lp(-1, -2));
        if (!appRules.isEmpty()) {
            gap(body, 6);
            FlowLayout apps = new FlowLayout(this, dp(6));
            PackageManager pm = getPackageManager();
            for (int i = 0; i < appRules.size(); i++) {
                final int index = i;
                final String label = appLabel(pm, appRules.get(i));
                LinearLayout chip = ui.chip(label, false, false, null, false);
                ImageView x = ui.chipX("app:" + i, false);
                x.setContentDescription(getString(R.string.text_delete) + " " + label);
                x.setOnClickListener(v -> editRules(m -> m.removeApp(m.edited(), index)));
                chip.addView(x, ui.chipXLp());
                apps.addView(chip);
            }
            body.addView(apps, lp(-1, -2));
        }
    }

    // The name an application is listed by: the one the launcher shows, or its package where it is no longer installed, which is
    // all there is to know it by, and to take it out of the list by.
    private String appLabel(PackageManager pm, String pkg) {
        try { return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString(); }
        catch (PackageManager.NameNotFoundException gone) { return pkg; }
    }

    // The built-in profile is stored as "Default" and shown in the language of the app.
    private String profileLabel(String name) {
        return SplitProfiles.DEFAULT.equals(name) ? getString(R.string.split_profile_default) : name;
    }

    // What the page says is wrong or worth knowing, one notice each, in this order:
    //  - Through VPN would route nothing (the desktop's words, naming the config and the profile it uses), worked out by the
    //    code that builds the plan (RoutingConfig.prepare): its warning for no rules at all. The full tunnel stays on;
    //  - applications and domains both listed under Through VPN, which Android can not add together (VpnService takes the
    //    applications and the destinations as an intersection), so the full tunnel stays on then too: Android's own;
    //  - rules the selected config brings in with it, which count too; and rules an older preview let be saved that were dropped.
    private List<CharSequence> splitNotices(SplitProfiles model, boolean enabled, boolean through) {
        List<CharSequence> notices = new ArrayList<>();
        if (enabled && through) {
            if (configs.isEmpty()) {
                SplitStore.Rules rules = SplitStore.rules(splitPrefs, null, SplitProfiles.DEFAULT);
                if (rules.domains.isEmpty() && installedApps(rules.apps).isEmpty()) notices.add(getString(R.string.split_notice_nothing_no_configs));
            } else {
                Config active = activeConfig();
                String profile = model.effective(active.profile);
                SplitStore.Rules rules = SplitStore.rules(splitPrefs, null, profile);
                Integer warning = null;
                try { warning = RoutingConfig.prepare(active.toml, true, true, rules.domains, installedApps(rules.apps), true).getWarning(); }
                catch (Exception unusable) { /* a config that does not pass is said so where it is used */ }
                String config = ShownText.elideMiddle(ShownText.oneLine(active.name), 36);
                if (warning != null && warning == R.string.text_no_through_vpn_rules_full_vpn_is_enabled)
                    notices.add(getString(R.string.split_notice_nothing, config, profileLabel(profile)));
                else if (warning != null && warning == R.string.text_applications_and_addresses_are_selected_full_vpn_pro)
                    notices.add(getString(R.string.split_notice_both, config, profileLabel(profile)));
            }
        }
        if (!configs.isEmpty()) {
            try {
                org.tomlj.TomlArray imported = RoutingConfig.validate(activeConfig().toml).getArray("exclusions");
                if (imported != null && imported.size() > 0) {
                    List<String> shown = new ArrayList<>();
                    for (int i = 0; i < imported.size(); i++) shown.add(ShownText.of(imported.getString(i)));
                    notices.add(getString(R.string.imported_routing_rules, TextUtils.join(", ", shown)));
                }
            } catch (Exception ignored) { }
        }
        if (prefs.getBoolean(SplitStore.RULES_MIGRATED, false)) notices.add(getString(R.string.legacy_rules_removed));
        return notices;
    }

    // The packages of these that are installed here, other than this app, as the plan will use them.
    private List<String> installedApps(List<String> packages) {
        PackageManager pm = getPackageManager();
        List<String> installed = new ArrayList<>();
        for (String pkg : packages) {
            if (pkg.equals(getPackageName())) continue;
            try { pm.getApplicationInfo(pkg, 0); installed.add(pkg); }
            catch (PackageManager.NameNotFoundException gone) { /* left out of the plan, kept in the profile */ }
        }
        return installed;
    }

    // The profiles.

    private SplitProfiles splitModel() { return SplitStore.load(splitPrefs, RussiaList.seed(this)); }
    private boolean splitEdit(Consumer<SplitProfiles> change) { return SplitStore.edit(splitPrefs, RussiaList.seed(this), change); }
    private Config activeConfig() { return configs.get(Math.max(0, Math.min(activeIndex, configs.size() - 1))); }
    // The profile the selected config uses (Default for one that names a profile that is gone, and with no configs), which
    // the VPN is built with and the rules of which "Through VPN" is about.
    private String activeProfileName() {
        return configs.isEmpty() ? SplitProfiles.DEFAULT : splitModel().effective(activeConfig().profile);
    }
    // The old global lists, which 1.0.0 reads, are a copy of the lists of the profile the selected config uses.
    private void syncMirror() {
        String profile = activeProfileName();
        splitEdit(m -> m.setMirrored(profile));
    }

    private void selectProfile(String name) {
        // Which one the page shows. It changes nothing the VPN runs on, so nothing restarts.
        final boolean[] changed = {false};
        splitEdit(m -> changed[0] = m.select(name));
        if (changed[0]) render();
    }

    private void confirmRemoveProfile(String name) {
        showConfirm(getString(R.string.split_profile_delete_question, ShownText.elideMiddle(ShownText.oneLine(name), 36)),
                getString(R.string.text_delete), () -> removeProfile(name));
    }

    private void removeProfile(String name) {
        boolean usedByActive = name.equals(activeProfileName());
        final boolean[] removed = {false};
        splitEdit(m -> removed[0] = m.remove(name));
        if (!removed[0]) return;
        // The configs that used it use Default from now on.
        for (Config config : configs) config.profile = SplitProfiles.afterRemoval(config.profile, name);
        saveConfigs();
        if (usedByActive) applyCurrentRules();
        render();
    }

    private void openProfileName() {
        if (profileNameInput == null) return;
        profileNameOpen = true;
        profileNameDraft = "";
        profileNameInput.setText("");
        profileNameInput.setVisibility(View.VISIBLE);
        EditText input = profileNameInput;
        input.requestFocus();
        // A phone types at once; a TV opens its keyboard when OK is pressed on the field.
        if (!isTv()) input.post(() -> showKeyboard(input));
    }

    // Escape, or the focus gone elsewhere, or Back: nothing is added.
    private void closeProfileName() {
        if (!profileNameOpen) return;
        profileNameOpen = false;
        profileNameDraft = "";
        EditText input = profileNameInput;
        if (input == null) return;
        // A remote's focus goes to the + chip it was opened from, and not to wherever Android finds first when a field
        // that has the focus is hidden.
        View plus = host.findViewWithTag("profile:add");
        if (input.hasFocus() && plus != null && !getWindow().getDecorView().isInTouchMode()) plus.requestFocus();
        if (input.hasFocus()) hideKeyboard();
        input.setText("");
        input.setVisibility(View.GONE);
    }

    private void addProfile(String text) {
        final SplitProfiles.NameResult[] result = new SplitProfiles.NameResult[1];
        splitEdit(m -> result[0] = m.add(text));
        profileNameOpen = false;
        profileNameDraft = "";
        hideKeyboard();
        focusAfterRender = "profile:add";
        // Adding a name that differs only in case from one that is there is refused, and says so.
        if (result[0] != null && result[0].status == SplitProfiles.NameStatus.CASE_CLASH)
            toast(getString(R.string.split_profile_exists, ShownText.of(result[0].existing)));
        render();
    }

    // The rules.

    // A change to the profile being edited. If it changed anything, the VPN is built again when the profile is the one the
    // selected config uses (and the split switch is on, or nothing it is built from changed), and the page is drawn again;
    // editing another profile restarts nothing. Whether it changed anything.
    private boolean editRules(Consumer<SplitProfiles> change) {
        final String[] profile = new String[1];
        final boolean[] changed = {false};
        splitEdit(m -> {
            profile[0] = m.edited();
            String before = m.toJson();
            change.accept(m);
            changed[0] = !before.equals(m.toJson());
        });
        if (changed[0]) rulesChanged(profile[0]);
        return changed[0];
    }

    private void rulesChanged(String profile) {
        if (prefs.getBoolean("split_enabled", false) && profile.equals(activeProfileName())) applyCurrentRules();
        render();
    }

    private void addRecommendedRussia() {
        final List<String> list;
        try { list = RussiaList.read(this); }
        catch (IOException unreadable) { toast(getString(R.string.text_could_not_open_file)); return; }
        editRules(m -> m.addRecommended(m.edited(), list));
    }

    // What was typed in the domains field: the words that are rules are added, and the ones that are not are named. The field is
    // cleared if something was added, and keeps what was typed if nothing was, for it to be put right.
    private boolean enterDomains(String text) {
        final SplitProfiles.Entered[] entered = new SplitProfiles.Entered[1];
        final String[] profile = new String[1];
        splitEdit(m -> { profile[0] = m.edited(); entered[0] = m.addDomains(profile[0], text); });
        if (entered[0] == null) return false;
        if (!entered[0].refused.isEmpty()) toast(refusedRulesMessage(entered[0].refused));
        if (entered[0].added.isEmpty()) return false;
        // The field stays where the cursor is, for the next one.
        focusAfterRender = "domains:input";
        rulesChanged(profile[0]);
        return true;
    }

    // As the desktop says it (Backend refusedRulesMessage): which words, and, for the two reasons that deserve it, why.
    private String refusedRulesMessage(List<String> refused) {
        List<String> shown = new ArrayList<>();
        for (String word : refused) shown.add(ShownText.of(word));
        String list = TextUtils.join(", ", shown);
        switch (SplitProfiles.refusal(refused)) {
            case WILDCARD_ON_ADDRESS: return getString(R.string.split_refused_wildcard, list);
            case EVERY_ADDRESS: return getString(R.string.split_refused_every, list);
            default: return getString(R.string.split_refused, list);
        }
    }
    // The app picker (qml/AppPickerOverlay.qml): a card with ← and the title, a search box, and the installed
    // applications, a row each with the name over its package. One application to a visit: a tap adds it and
    // the card closes, as does Enter, which adds the first match. They are listed on a worker, and the card
    // says so meanwhile. On a phone the search box has the focus first, and the keyboard comes up; on a TV
    // the first row has it, once the list is in. No checkboxes, no icons and no Done, and no file to
    // choose instead, which Android has no such thing as. Keys: picker:back, picker:search, picker:0, ...
    private static final class AppEntry {
        final String label;
        final String pkg;
        AppEntry(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }

    private void appPicker() {
        if (overlay("picker") != null) return;
        new AppPicker().open();
    }

    private final class AppPicker {
        private final List<AppEntry> apps = new ArrayList<>();
        private boolean scanning = true;
        private boolean focusedOnce;
        private Overlay overlay;
        private EditText search;
        private LinearLayout list;

        void open() {
            LinearLayout card = column();
            // As tall as the desktop's card, 460, or as the window leaves room for.
            card.setMinimumHeight(dp(460));
            card.addView(ui.cardHeader(getString(R.string.picker_title), "picker:back", getString(R.string.text_back),
                    v -> closeOverlay(overlay)), lp(-1, -2));
            search = ui.pageInput(getString(R.string.picker_search), "picker:search");
            search.setImeOptions(EditorInfo.IME_ACTION_DONE);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable text) { fill(); }
            });
            // Enter adds the first match.
            search.setOnEditorActionListener((field, action, event) -> {
                List<AppEntry> matches = matches();
                if (!matches.isEmpty()) choose(matches.get(0));
                return true;
            });
            LinearLayout.LayoutParams searchLp = lp(-1, 34);
            searchLp.setMargins(dp(14), 0, dp(14), dp(10));
            card.addView(search, searchLp);
            list = column();
            list.setPadding(dp(14), 0, dp(14), dp(14));
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setVerticalScrollBarEnabled(false);
            // The rows are its stops, not it: with none, it would be one with no sign of the focus.
            scroll.setFocusable(false);
            scroll.addView(list);
            card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            overlay = showOverlay("picker", OverlayLayer.Kind.CARD, card, null, null);
            overlay.width(dp(420));
            fill();
            if (!isTv()) {
                overlay.defaultKey = "picker:search";
                search.post(() -> {
                    if (overlay.closed) return;
                    search.requestFocus();
                    showKeyboard(search);
                });
            }
            scanWorker.execute(() -> {
                List<AppEntry> found = installedApps();
                runOnUiThread(() -> {
                    if (isDestroyed() || overlay.closed) return;
                    apps.addAll(found);
                    scanning = false;
                    fill();
                });
            });
        }

        // Name or package, as it is typed.
        private List<AppEntry> matches() {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<AppEntry> matching = new ArrayList<>();
            for (AppEntry app : apps) {
                if (query.isEmpty() || app.label.toLowerCase(Locale.ROOT).contains(query) || app.pkg.toLowerCase(Locale.ROOT).contains(query))
                    matching.add(app);
            }
            return matching;
        }

        private void fill() {
            Theme t = t();
            list.removeAllViews();
            List<AppEntry> matching = matches();
            if (matching.isEmpty()) {
                TextView status = label(getString(scanning ? R.string.picker_scanning : apps.isEmpty() ? R.string.picker_none : R.string.picker_nothing),
                        12, t.textFaint);
                status.setGravity(Gravity.START);
                status.setTag("picker:status");
                list.addView(status, lp(-1, -2));
                return;
            }
            View first = null;
            for (int i = 0; i < matching.size(); i++) {
                AppEntry app = matching.get(i);
                LinearLayout row = column();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), 0, dp(10), 0);
                TextView name = label(app.label, 13, t.text);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                // The package, which tells two apps of one name apart, is cut at its left, where the end is what matters.
                TextView pkg = label(app.pkg, 11, t.textFaint);
                pkg.setSingleLine(true);
                pkg.setEllipsize(TextUtils.TruncateAt.START);
                row.addView(name, lp(-1, -2));
                row.addView(pkg, lp(-1, -2));
                ui.focus(row, "picker:" + i, Ui.Look.ROW).setOnClickListener(v -> choose(app));
                LinearLayout.LayoutParams rowLp = lp(-1, 42);
                rowLp.bottomMargin = dp(2);
                list.addView(row, rowLp);
                if (first == null) first = row;
            }
            // A TV's remote starts on the first row, when the list first arrives, or when the row it was on is gone.
            if (isTv() && first != null && !getWindow().getDecorView().isInTouchMode()) {
                View focused = getCurrentFocus();
                boolean unmoved = focused == null || "picker:back".equals(focused.getTag());
                if (unmoved || !focusedOnce) first.requestFocus();
                focusedOnce = true;
            }
        }

        // The application goes in the list of the profile being edited, of those the VPN does not carry, or carries alone;
        // and the card goes.
        private void choose(AppEntry app) {
            if (overlay.closed) return;
            closeOverlay(overlay);
            editRules(m -> m.addApp(m.edited(), app.pkg));
        }
    }

    // The apps the launcher shows, and a TV's, named, once each, in alphabetical order. This app's own is left out.
    // On the scan worker: loadLabel() for every one of them takes long on a TV.
    private List<AppEntry> installedApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        // Apps made for TVs may have a TV launcher entry only. "seen" drops those with both.
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        List<AppEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo app : found) {
            String pkg = app.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            entries.add(new AppEntry(app.loadLabel(pm).toString(), pkg));
        }
        entries.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        return entries;
    }
    private void showKeyboard(View view) {
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (input != null) input.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }
    private void hideKeyboard() {
        InputMethodManager input = getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    // The Settings page, as the desktop's is (qml/pages/SettingsPage.qml), top to bottom: General (Language and Theme as Dropdowns,
    // a line between the rows, and Connect on startup), Security (the storage banner, Kill switch, and Android's own Always-on
    // row), Excluded routes (chips, and a field), Logging, Maintenance (the update line) and the footer. Left out are what only
    // the desktop has: Launch at system startup, Hotkeys and the Windows-only ports row. Keys: settings:language, settings:theme,
    // switch:auto_connect, switch:killswitch, settings:vpn, routes:restore, routes:clear, route:N, routes:input, switch:logging,
    // switch:check_updates_on_launch, settings:update (with update:tick, update:arrow, update:dots and update:version beside it), and
    // in the footer settings:site, settings:core and settings:licenses.
    private void settings(LinearLayout body) {
        Theme t = t();
        // General.
        body.addView(ui.sectionLabel(getString(R.string.text_general)), lp(-1, -2));
        // English or Русский, each in itself; the one the app speaks now is the one shown, whatever was chosen.
        final String[] languageNames = {getString(R.string.language_english), getString(R.string.language_russian)};
        final int shownLanguage = AppLocale.RUSSIAN.equals(AppLocale.current(this)) ? 1 : 0;
        body.addView(ui.dropdown(getString(R.string.settings_language), languageNames[shownLanguage], "settings:language",
                v -> showSelect("settings:language", languageNames, shownLanguage, which -> {
                    if (which != shownLanguage) AppLocale.choose(this, prefs, AppLocale.LANGUAGES[which]);
                })), lp(-1, -2));
        body.addView(ui.sep(), lp(-1, 1));
        // System (the default), Light or Dark, as on the desktop.
        String[] modes = {Theme.SYSTEM, Theme.LIGHT_MODE, Theme.DARK_MODE};
        String[] names = {getString(R.string.text_theme_system), getString(R.string.text_light_78), getString(R.string.text_dark_79)};
        int shownMode = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(themeMode)) shownMode = i;
        final int shownModeIndex = shownMode;
        body.addView(ui.dropdown(getString(R.string.text_theme), names[shownMode], "settings:theme",
                v -> showSelect("settings:theme", names, shownModeIndex, which -> {
                    themeMode = modes[which]; prefs.edit().putString("theme_mode", themeMode).apply(); render();
                })), lp(-1, -2));
        body.addView(ui.sep(), lp(-1, 1));
        addSwitch(body, getString(R.string.settings_connect_on_startup), "", "auto_connect", false);

        // Security.
        section(body, getString(R.string.text_security));
        storageBanner(body);
        addSwitch(body, getString(R.string.settings_kill_switch), getString(R.string.settings_kill_switch_hint), "killswitch", true);
        // Most TVs have no Always-on VPN, and no screen for it. The manifest's queries
        // keep package visibility from hiding the screen on a phone.
        if (getPackageManager().resolveActivity(new Intent(Settings.ACTION_VPN_SETTINGS), 0) != null) {
            LinearLayout systemVpn = row();
            systemVpn.setMinimumHeight(dp(42));
            systemVpn.addView(label(getString(R.string.text_android_vpn_settings), 14, t.text), weighted());
            // The word is the control, not the row.
            TextView open = ui.link(getString(R.string.text_open), 13, Ui.LinkKind.ACCENT, "settings:vpn");
            // Some TVs have only a stub for it, which can still fail to open.
            open.setOnClickListener(v -> {
                try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
                catch (Exception e) { toast(getString(R.string.text_vpn_settings_are_unavailable)); }
            });
            LinearLayout.LayoutParams openLp = lp(-2, -2);
            // Its words end where the other rows' controls do, with the link's own padding beyond.
            openLp.rightMargin = -dp(6);
            systemVpn.addView(open, openLp);
            body.addView(systemVpn, lp(-1, -2));
            body.addView(ui.paragraph(getString(R.string.text_enable_always_on_vpn_and_block_connections_without_v),
                    12, t.textFaint, 0), lp(-1, -2));
            body.addView(ui.paragraph(getString(R.string.lockdown_app_warning), 12, t.textFaint, 0), lp(-1, -2));
        } else {
            body.addView(ui.paragraph(getString(R.string.text_no_always_on_here), 12, t.textFaint, 0), lp(-1, -2));
        }

        // Excluded routes.
        excludedRoutes(body);

        // Logging.
        section(body, getString(R.string.settings_logging));
        addSwitch(body, getString(R.string.text_enable_logging), "", "logging", true);

        // Maintenance.
        section(body, getString(R.string.settings_maintenance));
        maintenance(body);

        // The footer, centred: the app, the core it carries, and, Android's own, the licenses of what is in the APK.
        gap(body, 14);
        LinearLayout footer = row();
        footer.setGravity(Gravity.CENTER);
        TextView site = ui.link("FreeTunnel " + BuildConfig.VERSION_NAME, 12, Ui.LinkKind.FAINT, "settings:site");
        site.setOnClickListener(v -> openLink("https://github.com/" + UpdatePolicy.REPOSITORY));
        footer.addView(site, lp(-2, -2));
        footer.addView(label("  ·  ", 12, t.textFaint), lp(-2, -2));
        TextView core = ui.link(getString(R.string.settings_core) + BuildConfig.CORE_VERSION, 12, Ui.LinkKind.FAINT, "settings:core");
        core.setOnClickListener(v -> openLink("https://github.com/TrustTunnel/TrustTunnelClient"));
        footer.addView(core, lp(-2, -2));
        body.addView(footer, lp(-1, -2));
        LinearLayout licenses = row();
        licenses.setGravity(Gravity.CENTER);
        TextView openLicenses = ui.link(getString(R.string.text_open_source_licenses), 12, Ui.LinkKind.FAINT, "settings:licenses");
        openLicenses.setOnClickListener(v -> openSourceLicenses());
        licenses.addView(openLicenses, lp(-2, -2));
        body.addView(licenses, lp(-1, -2));
    }

    // A link out of the app: the browser's, where there is one. Most TVs have none, and say so.
    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException | SecurityException noBrowser) { toast(getString(R.string.text_no_browser)); }
    }

    // Excluded routes (Settings, qml/pages/SettingsPage.qml): subnets that bypass the tunnel at the routing level, whatever the split
    // rules say. The header has the title, which fills, "Restore defaults" (which asks first, as it replaces the list) and, with
    // routes, the danger "Clear all" (which asks as well); then the routes as chips, a ChipX takes one out at once; then a field, for
    // one or several, separated by spaces, commas or semicolons. A /0 is refused, and so is what is no subnet, by name. The
    // list is the saved one, or the defaults where none is saved (ExcludedRoutes), and a change builds the VPN again if it is up.
    // Keys: routes:restore, routes:clear, route:0, route:1, ... and routes:input.
    private List<String> excludedRoutes() {
        return ExcludedRoutes.load(prefs.getString(ExcludedRoutes.KEY, null), ExcludedRoutes.defaults(this));
    }

    private void saveExcludedRoutes(List<String> routes) {
        prefs.edit().putString(ExcludedRoutes.KEY, ExcludedRoutes.toJson(routes)).commit();
        // The plan of a VPN that is up is made again with them; one that is not up is made with them when it starts.
        applyCurrentRules();
        render();
    }

    private void excludedRoutes(LinearLayout body) {
        final List<String> routes = excludedRoutes();
        gap(body, 16);
        LinearLayout header = row();
        TextView title = ui.sectionLabel(getString(R.string.settings_excluded_routes));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, weighted());
        TextView restore = ui.link(getString(R.string.settings_restore_defaults), 12, Ui.LinkKind.ACCENT, "routes:restore");
        restore.setOnClickListener(v -> showConfirm(getString(R.string.settings_routes_restore_question), getString(R.string.import_replace), () -> {
            List<String> defaults = ExcludedRoutes.defaults(this);
            // Already the defaults: nothing to build again.
            if (!routes.equals(defaults)) saveExcludedRoutes(new ArrayList<>(defaults));
        }));
        LinearLayout.LayoutParams restoreLp = lp(-2, -2);
        header.addView(restore, restoreLp);
        if (!routes.isEmpty()) {
            TextView clear = ui.link(getString(R.string.split_clear_all), 12, Ui.LinkKind.DANGER, "routes:clear");
            clear.setOnClickListener(v -> showConfirm(getString(R.string.settings_routes_clear_question), getString(R.string.text_clear),
                    () -> saveExcludedRoutes(new ArrayList<>())));
            LinearLayout.LayoutParams clearLp = lp(-2, -2);
            clearLp.rightMargin = -dp(6);
            header.addView(clear, clearLp);
        } else {
            // It is the last, so its words end where the page's margin does.
            restoreLp.rightMargin = -dp(6);
        }
        body.addView(header, lp(-1, -2));
        if (!routes.isEmpty()) {
            gap(body, 6);
            FlowLayout chips = new FlowLayout(this, dp(6));
            for (int i = 0; i < routes.size(); i++) {
                final int index = i;
                final String route = routes.get(i);
                LinearLayout chip = ui.chip(route, false, false, null, false);
                // A route goes at once, without being asked about, as the desktop's does.
                ImageView x = ui.chipX("route:" + i, false);
                x.setContentDescription(getString(R.string.text_delete) + " " + route);
                x.setOnClickListener(v -> saveExcludedRoutes(ExcludedRoutes.removeAt(routes, index)));
                chip.addView(x, ui.chipXLp());
                chips.addView(chip);
            }
            body.addView(chips, lp(-1, -2));
        }
        EditText input = ui.pageInput(getString(R.string.settings_routes_hint), "routes:input");
        input.setOnEditorActionListener((field, action, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (enterExcludedRoutes(input.getText().toString())) input.setText("");
            return true;
        });
        LinearLayout.LayoutParams inputLp = lp(-1, 36);
        inputLp.topMargin = dp(6);
        body.addView(input, inputLp);
    }

    // What was typed in the routes field: the subnets are added and the words that are none are named, a /0 with the reason. The
    // field is cleared if something was added, and keeps what was typed if nothing was, for it to be put right.
    private boolean enterExcludedRoutes(String text) {
        ExcludedRoutes.Entered entered = ExcludedRoutes.add(excludedRoutes(), text);
        if (!entered.refused.isEmpty()) {
            List<String> shown = new ArrayList<>();
            for (String word : entered.refused) shown.add(ShownText.of(word));
            toast(getString(entered.refusedEveryAddress() ? R.string.settings_routes_refused_every : R.string.settings_routes_refused,
                    TextUtils.join(", ", shown)));
        }
        if (entered.added.isEmpty()) return false;
        // The field stays where the cursor is, for the next one.
        focusAfterRender = "routes:input";
        saveExcludedRoutes(entered.routes);
        return true;
    }

    // Maintenance (qml/pages/SettingsPage.qml): one row. At its left the update line, 14 in the text colour, which wraps (to six lines),
    // and is a link over its words alone: it is what to do next, as the icon beside it is, and a click does it. At its right the
    // picture for the state and the version (see UpdateLine, and refreshUpdateControls). Only in an official build, which is the
    // only one Android would let update itself; another says so. Android's own "Check for updates on launch" is above the row.
    private TextView updateTick;
    private FrameLayout updateSlot;
    private UpdateArrowView updateArrow;
    private BusyDotsView updateDots;
    private TextView updateVersion;
    // The pointer or the remote is on the line: the arrow beside it bends (a download) or leans (a retry), as the desktop's does under the pointer.
    private boolean updateHot;

    private void maintenance(LinearLayout body) {
        Theme t = t();
        updateAction = null; updateTick = null; updateSlot = null; updateArrow = null; updateDots = null; updateVersion = null;
        updateHot = false;
        if (!AndroidUpdater.INSTANCE.official(this)) {
            // Android would refuse an update signed with another key than this build's.
            body.addView(ui.paragraph(getString(R.string.update_unofficial), 12, t.textFaint, 0), lp(-1, -2));
            return;
        }
        addSwitch(body, getString(R.string.update_on_launch), "", "check_updates_on_launch", false);
        RelativeLayout line = new RelativeLayout(this);
        line.setMinimumHeight(dp(42));
        // The right: ✓ when it is current, the arrow or the dots, and the version.
        LinearLayout right = row();
        right.setId(View.generateViewId());
        updateTick = label("✓", 14, t.success);
        updateTick.setTag("update:tick");
        updateTick.setContentDescription(getString(R.string.update_latest));
        right.addView(updateTick, lp(-2, -2));
        updateSlot = new FrameLayout(this);
        // The pictures are the line's, which says what they say; TalkBack has nothing to add.
        updateArrow = new UpdateArrowView(this, t.text);
        updateArrow.setTag("update:arrow");
        updateArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateSlot.addView(updateArrow, new FrameLayout.LayoutParams(dp(UpdateArrowView.SIZE_DP), dp(UpdateArrowView.SIZE_DP)));
        updateDots = new BusyDotsView(this, t.text);
        updateDots.setTag("update:dots");
        updateDots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        updateSlot.addView(updateDots, new FrameLayout.LayoutParams(dp(UpdateArrowView.SIZE_DP), dp(UpdateArrowView.SIZE_DP)));
        LinearLayout.LayoutParams slotLp = lp(UpdateArrowView.SIZE_DP, UpdateArrowView.SIZE_DP);
        slotLp.leftMargin = dp(5);
        right.addView(updateSlot, slotLp);
        updateVersion = label(BuildConfig.VERSION_NAME, 13, t.textFaint);
        updateVersion.setTag("update:version");
        LinearLayout.LayoutParams versionLp = lp(-2, -2);
        versionLp.leftMargin = dp(5);
        right.addView(updateVersion, versionLp);
        RelativeLayout.LayoutParams rightLp = new RelativeLayout.LayoutParams(-2, -2);
        rightLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightLp.addRule(RelativeLayout.CENTER_VERTICAL);
        line.addView(right, rightLp);
        // The words, over the room the right leaves, and no wider than they are.
        updateAction = ui.link("", 14, Ui.LinkKind.TEXT, "settings:update");
        updateAction.setSingleLine(false);
        updateAction.setMaxLines(6);
        updateAction.setEllipsize(TextUtils.TruncateAt.END);
        updateAction.setOnClickListener(v -> doUpdateAction());
        updateAction.setOnFocusChangeListener((v, focused) -> setUpdateHot(focused || v.isHovered()));
        updateAction.setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_ENTER) setUpdateHot(true);
            else if (action == MotionEvent.ACTION_HOVER_EXIT) setUpdateHot(v.hasFocus());
            return false;
        });
        RelativeLayout.LayoutParams actionLp = new RelativeLayout.LayoutParams(-2, -2);
        actionLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        actionLp.addRule(RelativeLayout.LEFT_OF, right.getId());
        actionLp.addRule(RelativeLayout.CENTER_VERTICAL);
        // The words start where the other rows' do, with the link's own padding before them.
        actionLp.leftMargin = -dp(6);
        actionLp.rightMargin = dp(6);
        line.addView(updateAction, actionLp);
        body.addView(line, lp(-1, -2));
        refreshUpdateControls();
    }

    private void setUpdateHot(boolean hot) {
        if (updateHot == hot) return;
        updateHot = hot;
        applyUpdateArrow(true);
    }

    // What a click or OK on the update line does now (UpdateLine): check, download, cancel the download, or install.
    private UpdateLine updateLine() {
        AndroidUpdater.State update = AndroidUpdater.INSTANCE.getState();
        // Once Android has the session, Install update starts over if its dialog was lost.
        boolean reinstall = update == AndroidUpdater.State.INSTALLING && AndroidUpdater.INSTANCE.getCommitted();
        return UpdateLine.of(UpdateLine.Phase.valueOf(update.name()), reinstall, AndroidUpdater.INSTANCE.getFailedCheck());
    }

    private void doUpdateAction() {
        switch (updateLine().action) {
            case CHECK: AndroidUpdater.INSTANCE.check(this); break;
            case DOWNLOAD: AndroidUpdater.INSTANCE.download(this); break;
            case CANCEL: AndroidUpdater.INSTANCE.cancel(); break;
            case INSTALL: installUpdate(); break;
            default: break;
        }
    }
    // The third-party notices and the license texts, as app/build.gradle copies them
    // from the repository into the APK's assets, in one card: the files as rows, and a file's
    // text in a box like the log's, which a remote scrolls with the arrows because it has the
    // focus first. ← and Back go from the text to the list, and from the list out. Keys:
    // licenses:back, licenses:0, licenses:1, ... for the rows and licenses:text.
    private void openSourceLicenses() {
        if (overlay("licenses") != null) return;
        new Licenses().open();
    }

    private final class Licenses {
        private final List<String> files = new ArrayList<>();
        private Overlay overlay;
        private TextView title;
        private FrameLayout body;
        // The file whose text is up, or -1 while the list is.
        private int showing = -1;

        void open() {
            files.add("THIRD_PARTY_NOTICES.md");
            files.add("LICENSE");
            for (String group : new String[]{"licenses/common", "licenses/android"}) {
                List<String> texts = new ArrayList<>();
                try {
                    String[] names = getAssets().list(group);
                    if (names != null) for (String name : names) texts.add(group + "/" + name);
                } catch (IOException e) {
                    // Listed as far as it goes: a text that is missing says so when opened.
                }
                Collections.sort(texts, String.CASE_INSENSITIVE_ORDER);
                files.addAll(texts);
            }
            LinearLayout card = column();
            card.setMinimumHeight(dp(460));
            LinearLayout header = ui.cardHeader(getString(R.string.text_open_source_licenses), "licenses:back", getString(R.string.text_back),
                    v -> escape(overlay));
            title = (TextView) header.getChildAt(1);
            card.addView(header, lp(-1, -2));
            body = new FrameLayout(MainActivity.this);
            card.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
            overlay = showOverlay("licenses", OverlayLayer.Kind.CARD, card, null, null);
            overlay.width(dp(420));
            overlay.escape = () -> { if (showing >= 0) list(showing); else closeOverlay(overlay); };
            list(-1);
            overlay.defaultKey = "licenses:0";
        }

        // The files; with the focus on the one whose text was just up, if any.
        private void list(int focusRow) {
            Theme t = t();
            showing = -1;
            title.setText(getString(R.string.text_open_source_licenses));
            body.removeAllViews();
            LinearLayout rows = column();
            rows.setPadding(dp(14), 0, dp(14), dp(14));
            for (int i = 0; i < files.size(); i++) {
                final int index = i;
                TextView row = label(files.get(i), 13, t.text);
                row.setPadding(dp(10), 0, dp(10), 0);
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                ui.focus(row, "licenses:" + i, Ui.Look.ROW).setOnClickListener(v -> text(index));
                LinearLayout.LayoutParams rowLp = lp(-1, 42);
                rowLp.bottomMargin = dp(2);
                rows.addView(row, rowLp);
            }
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setFocusable(false);
            scroll.addView(rows);
            body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            if (focusRow >= 0 && !getWindow().getDecorView().isInTouchMode()) {
                View row = body.findViewWithTag("licenses:" + focusRow);
                if (row != null) row.requestFocus();
            }
        }

        // One of them. The box has the focus, so the arrows scroll it.
        private void text(int index) {
            Theme t = t();
            String asset = files.get(index);
            String contents;
            try (InputStream input = getAssets().open(asset); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) > 0; ) out.write(buffer, 0, n);
                contents = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                toast(getString(R.string.text_licenses_unavailable));
                return;
            }
            showing = index;
            title.setText(asset);
            body.removeAllViews();
            TextView text = ui.mono(contents, 11, t.text);
            text.setGravity(Gravity.TOP | Gravity.START);
            text.setPadding(dp(12), dp(12), dp(12), dp(12));
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(text);
            ui.focus(scroll, "licenses:text", Ui.Look.BOX);
            FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(-1, -1);
            boxLp.setMargins(dp(14), 0, dp(14), dp(14));
            body.addView(scroll, boxLp);
            if (!getWindow().getDecorView().isInTouchMode()) scroll.requestFocus();
        }
    }
    // Installing replaces the app, and Android stops the VPN with it, so say so
    // first. Without the install permission, install() opens its settings screen.
    private void installUpdate() {
        vpnState = FreeTunnelVpnService.state(this);
        if (getPackageManager().canRequestPackageInstalls() && isVpnActive()) {
            showConfirm(getString(R.string.update_disconnect_warning), getString(R.string.update_install),
                    () -> AndroidUpdater.INSTANCE.install(this, id -> toast(getString(id))));
        } else {
            AndroidUpdater.INSTANCE.install(this, id -> toast(getString(id)));
        }
    }
    private void refreshUpdateControls() {
        if (updateAction == null) return;
        UpdateLine line = updateLine();
        UpdatePolicy.Release release = AndroidUpdater.INSTANCE.getRelease();
        String version = release != null ? release.getVersion() : "";
        String text;
        switch (line.words) {
            case CHECKING: text = getString(R.string.update_checking); break;
            case AVAILABLE: text = getString(R.string.update_available, version); break;
            case DOWNLOADING: text = getString(R.string.update_downloading, AndroidUpdater.INSTANCE.getProgress()); break;
            case READY: text = getString(R.string.update_ready, version); break;
            case INSTALLING: text = getString(R.string.update_installing); break;
            case CHECK_FAILED:
                text = getString(R.string.update_check_failed, getString(updateFailureText(AndroidUpdater.INSTANCE.getFailure())));
                break;
            case FAILED: text = getString(updateFailureText(AndroidUpdater.INSTANCE.getFailure())); break;
            default: text = getString(R.string.update_check);
        }
        updateAction.setText(text);
        // Said aloud: the words, and then what a click does with them.
        int does = line.action == UpdateLine.Action.CHECK ? R.string.update_check : line.action == UpdateLine.Action.DOWNLOAD ? R.string.update_download
                : line.action == UpdateLine.Action.CANCEL ? R.string.update_cancel : line.action == UpdateLine.Action.INSTALL ? R.string.update_install : 0;
        updateAction.setContentDescription(does == 0 || getString(does).equals(text) ? text : text + ". " + getString(does));
        // Not setEnabled: disabling the view that has a remote's focus moves the focus to
        // the top of the window. Not clickable, it ignores taps and OK just the same.
        updateAction.setClickable(line.clickable());
        updateTick.setVisibility(line.tick ? View.VISIBLE : View.GONE);
        updateVersion.setVisibility(line.version ? View.VISIBLE : View.GONE);
        applyUpdateArrow(false);
    }

    // The picture beside the line: dots while it waits on an answer, ↓ for an offered download (it bends into ↻ under the pointer or a
    // remote's focus), a turning ↻ while one comes down, and ↻ after a failure (which leans, with the pointer or the focus on it).
    private void applyUpdateArrow(boolean animate) {
        if (updateArrow == null || updateDots == null || updateSlot == null) return;
        UpdateLine.Icon icon = updateLine().icon;
        boolean arrow = icon == UpdateLine.Icon.DOWNLOAD || icon == UpdateLine.Icon.SPINNER || icon == UpdateLine.Icon.RETRY;
        boolean dots = icon == UpdateLine.Icon.DOTS;
        updateArrow.setVisibility(arrow ? View.VISIBLE : View.GONE);
        updateDots.setVisibility(dots ? View.VISIBLE : View.GONE);
        updateSlot.setVisibility(arrow || dots ? View.VISIBLE : View.GONE);
        updateArrow.setSpinning(icon == UpdateLine.Icon.SPINNER);
        updateArrow.setBend(icon == UpdateLine.Icon.DOWNLOAD && !updateHot ? 0 : 1, animate);
        updateArrow.setTurn(icon == UpdateLine.Icon.RETRY && updateHot ? 45 : 0, animate);
    }
    private static int updateFailureText(UpdatePolicy.Failure failure) {
        if (failure == UpdatePolicy.Failure.RATE_LIMITED) return R.string.update_error_rate_limited;
        if (failure == UpdatePolicy.Failure.VERIFICATION) return R.string.update_error_verification;
        if (failure == UpdatePolicy.Failure.INSTALLER) return R.string.update_error_installer;
        return R.string.update_error_network;
    }
    // A row of a page: a label, with its subtitle under it, and the desktop's Toggle. Only the
    // toggle is the control.
    private void addSwitch(LinearLayout parent, String title, String subtitle, String key, boolean initial) {
        ToggleView sw = ui.toggle(prefs.getBoolean(key, initial), false, "switch:" + key);
        sw.setContentDescription(title);
        sw.setListener((v, enabled) -> {
            prefs.edit().putBoolean(key, enabled).apply();
            if (key.equals("killswitch")) applyCurrentRules();
        });
        parent.addView(ui.toggleRow(title, subtitle, sw), ui.focusRowLp());
    }
    // The Logs page (qml/pages/LogsPage.qml): at the top Clear (left) and Copy (right), links as wide as their words; with logging off,
    // "Logging is off." in warn and a "Turn on" link; the log in a box (surface, radius 8, monospace 11 in the text colour) that fills the
    // rest of the page, or, with logging on and nothing to show, a placeholder in the middle of it; and at the foot Auto-scroll with a small
    // toggle, which keeps the end of the log in view. The log is the file the VPN service writes (LogFile), each line shown as
    // "HH:mm:ss LEVEL message" (LogLines). While the page is shown the box follows the file once a second, by changing its text alone and
    // never by building the page again. On a TV the text is not selectable, so that the arrows scroll the box (a focus stop, which shows the
    // field's accent border) instead of moving a cursor in the text. There is no log path to open: the file is the app's own.
    // Keys: log:clear, log:copy, logs:enable, log:box, log:text and logs:autoscroll.
    private TextView logText;
    private ScrollView logBox;
    private TextView logPlaceholder;
    // On while the page is shown for the first time, as the desktop's is, and then as it is left.
    private boolean logAutoScroll = true;
    // The file as the page last showed it, to tell by a glance whether it changed; -1 until it has shown it.
    private long logStamp = -1;

    private File logFile() { return new File(getFilesDir(), LogFile.NAME); }

    private void logs(LinearLayout body) {
        Theme t = t();
        LinearLayout actions = row();
        actions.setMinimumHeight(dp(24));
        TextView clear = ui.link(getString(R.string.text_clear), 13, Ui.LinkKind.ACCENT, "log:clear");
        LinearLayout.LayoutParams clearLp = lp(-2, -2);
        clearLp.leftMargin = -dp(6);
        actions.addView(clear, clearLp);
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView copy = ui.link(getString(R.string.text_copy), 13, Ui.LinkKind.ACCENT, "log:copy");
        LinearLayout.LayoutParams copyLp = lp(-2, -2);
        copyLp.rightMargin = -dp(6);
        actions.addView(copy, copyLp);
        body.addView(actions, lp(-1, -2));
        clear.setOnClickListener(v -> {
            LogFile.clear(logFile());
            logStamp = LogFile.stamp(logFile());
            // At once, and the text alone: nothing else on the page changes.
            setLogText("");
        });
        copy.setOnClickListener(v -> copyLog());
        // Logging off: nothing new will be written, so the page says so instead of showing old lines as if they were current.
        if (!prefs.getBoolean("logging", true)) {
            gap(body, 8);
            LinearLayout off = row();
            TextView message = label(getString(R.string.logs_off), 12, t.warn);
            message.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            off.addView(message, weighted());
            TextView turnOn = ui.link(getString(R.string.logs_turn_on), 12, Ui.LinkKind.ACCENT, "logs:enable");
            turnOn.setOnClickListener(v -> { prefs.edit().putBoolean("logging", true).apply(); render(); });
            LinearLayout.LayoutParams turnOnLp = lp(-2, -2);
            turnOnLp.rightMargin = -dp(6);
            off.addView(turnOn, turnOnLp);
            body.addView(off, lp(-1, -2));
        }
        gap(body, 8);
        // The box is the scroll view: surface, radius 8, and the field's accent border with the focus. Its text is clipped
        // to 12 inside it.
        FrameLayout frame = new FrameLayout(this);
        logBox = new ScrollView(this);
        logBox.setVerticalScrollBarEnabled(false);
        logBox.setPadding(dp(12), dp(12), dp(12), dp(12));
        ui.focus(logBox, "log:box", Ui.Look.BOX);
        logText = ui.mono("", 11, t.text);
        logText.setTag("log:text");
        logText.setGravity(Gravity.TOP | Gravity.START);
        // On a TV, selectable text would take the remote's arrows as cursor moves: without it Down from Clear or Copy lands on the box,
        // and the arrows scroll it. Selected, the text is in the accent (see the desktop's selectionColor), light enough to read through.
        logText.setTextIsSelectable(!isTv());
        logText.setHighlightColor((t.accent & 0x00FFFFFF) | 0x66000000);
        logBox.addView(logText);
        frame.addView(logBox, new FrameLayout.LayoutParams(-1, -1));
        logPlaceholder = label(getString(R.string.logs_placeholder), 13, t.textFaint);
        logPlaceholder.setGravity(Gravity.CENTER);
        frame.addView(logPlaceholder, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        body.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        gap(body, 8);
        // The foot: Auto-scroll, at the right. The label and the toggle are one small row, which fills round them with the focus and
        // is no wider than they are.
        LinearLayout foot = row();
        foot.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        ToggleView auto = ui.toggle(logAutoScroll, true, "logs:autoscroll");
        auto.setContentDescription(getString(R.string.logs_auto_scroll));
        auto.setListener((v, on) -> { logAutoScroll = on; if (on) scrollLogToEnd(); });
        LinearLayout.LayoutParams autoLp = lp(-2, -2);
        // The track ends at the margin: the row's 6 of padding on its right, which the fill shows, go beyond it.
        autoLp.rightMargin = -dp(Ui.FOCUS_ROW_RIGHT_DP);
        foot.addView(ui.toggleGroup(getString(R.string.logs_auto_scroll), 12, t.textDim, auto), autoLp);
        body.addView(foot, lp(-1, -2));
        refreshLog(true);
    }

    // The log as the page shows it, or null if it cannot be read.
    private String shownLog() {
        try { return LogLines.shownText(LogFile.read(logFile()), LogLines.SHOWN_LINES); }
        catch (IOException | RuntimeException unreadable) { return null; }
    }

    // Shows the file again if it changed since the page last showed it (or whether it did, with force). Not while the user holds
    // a selection in it: the new text would wipe it.
    private void refreshLog(boolean force) {
        if (logText == null) return;
        long stamp = LogFile.stamp(logFile());
        if (!force && stamp == logStamp) return;
        if (!force && logText.hasSelection()) return;
        String shown = shownLog();
        logStamp = stamp;
        setLogText(shown == null ? getString(R.string.text_could_not_read_log) : shown);
    }

    private void setLogText(String text) {
        if (logText == null) return;
        if (!text.contentEquals(logText.getText())) logText.setText(text);
        logPlaceholder.setVisibility(text.isEmpty() && prefs.getBoolean("logging", true) ? View.VISIBLE : View.GONE);
        if (logAutoScroll) scrollLogToEnd();
    }

    // The end of the log, once the text is laid out. Not ScrollView.fullScroll, which moves the focus too.
    private void scrollLogToEnd() {
        final ScrollView box = logBox;
        if (box == null) return;
        box.post(() -> {
            View child = box.getChildAt(0);
            if (child == null) return;
            int room = box.getHeight() - box.getPaddingTop() - box.getPaddingBottom();
            box.scrollTo(0, Math.max(0, child.getHeight() - room));
        });
    }

    private void copyLog() {
        String shown = shownLog();
        if (shown == null) { toast(getString(R.string.text_could_not_read_log)); return; }
        if (shown.isEmpty()) { toast(getString(R.string.text_log_is_empty)); return; }
        ClipData clip = ClipData.newPlainText("FreeTunnel log", shown);
        // It lists where the connections went: a keyboard's clipboard history and the preview Android shows are asked to leave it out.
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        toast(getString(R.string.text_log_copied));
    }

    // Once a second while the page is shown: the box follows the file. The text alone changes, never the page.
    private final Runnable logTicker = new Runnable() {
        @Override public void run() {
            if (page != 4 || !resumed) return;
            refreshLog(false);
            host.postDelayed(this, 1000);
        }
    };
    private void scheduleLogTick() {
        host.removeCallbacks(logTicker);
        if (page == 4 && resumed) host.postDelayed(logTicker, 1000);
    }
    @Override protected void onDestroy() {
        toastHandler.removeCallbacksAndMessages(null);
        stopReceiver();
        dropReceived();
        validationRequest++;
        validationWorker.shutdown();
        scanWorker.shutdown();
        pingWorker.shutdownNow();
        ioWorker.shutdown();
        hero.release();
        super.onDestroy();
    }
    private void loadConfigs() {
        try {
            JSONArray array = new JSONArray(ConfigStore.read(prefs));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                configs.add(new Config(o.getString("name"), o.getString("toml"), SavedProfile.profileOf(o)));
            }
        } catch (Exception error) {
            storageFailed = true;
            configs.clear();
            rootStorageWarning();
        }
        activeIndex = Math.max(0, Math.min(activeIndex, configs.size() - 1));
    }
    private void rootStorageWarning() {
        storageProblem = getString(R.string.text_configurations_are_unavailable) + ". "
                + getString(R.string.text_android_could_not_read_storage_saved_data_is_unchang);
        toast(storageProblem);
    }
    // The desktop's credential-storage banner (SettingsPage): amber, at the top of a page while storage fails.
    private void storageBanner(LinearLayout body) {
        if (!storageFailed || storageProblem == null) return;
        TextView banner = ui.banner(storageProblem);
        banner.setTag("storage:banner");
        body.addView(banner, lp(-1, -2));
        gap(body, 10);
    }
    // True when they were saved. When they could not be, nothing is changed, and the user is told.
    private boolean saveConfigs() {
        validationRequest++;
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_data_has_not_been_changed)); return false; }
        JSONArray array = new JSONArray();
        for (Config config : configs) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", config.name); o.put("toml", config.toml);
                // Only a profile other than Default is written: a config saved before there were profiles has none, and
                // is Default, and so is one that names no profile.
                if (!SplitProfiles.DEFAULT.equals(config.profile)) o.put("profile", config.profile);
                array.put(o);
            } catch (Exception ignored) { }
        }
        if (!ConfigStore.write(prefs, array.toString())) {
            storageFailed = true;
            storageProblem = getString(R.string.text_could_not_save_configurations_data_on_disk_is_unchan);
            toast(storageProblem);
            configs.clear(); loadConfigs(); render(); return false;
        }
        prefs.edit().putInt("active", activeIndex).apply();
        syncMirror();
        return true;
    }
}
