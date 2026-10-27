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
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import android.text.method.PasswordTransformationMethod;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android adaptation of the FreeTunnel desktop interface. */
public final class MainActivity extends Activity {
    private static final int PICK_CONFIG = 21;
    private static final int PREPARE_VPN = 22;
    private static final String PREFS = "freetunnel";
    private String[] tabNames;
    // The Home tab is the logo, in its own greys: the app never tints it.
    private final int[] tabIcons = {R.drawable.ic_logo, R.drawable.ic_connection, R.drawable.ic_network,
            R.drawable.ic_settings, R.drawable.ic_log};
    private SharedPreferences prefs;
    private final List<Config> configs = new ArrayList<>();
    private final Map<Config, String> pingResults = new IdentityHashMap<>();
    private boolean pingRunning;
    private int activeIndex;
    private int page;
    // The theme setting, "system" (the default), "light" or "dark", and the theme in
    // use: for "system" it follows the device's night mode (see Theme.isDark).
    private String themeMode = Theme.SYSTEM;
    private boolean dark = true;
    // A remote or a keyboard drives the screen: a TV, or a window that is out of touch
    // mode. Then the controls that rest in a strong fill rest in a neutral one, so that
    // the focus is the one strong fill on the screen (Ui.update).
    private boolean remote;
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
    private String pendingConfig;
    private boolean receiverRegistered;
    private boolean storageFailed;
    // What to tell the user while storage is failed: the amber banner on Configs and Settings, and a toast.
    private String storageProblem;
    private TextView updateStatus;
    private TextView updateAction;
    // Receive from phone: the open port and its card, both closed by stopReceiver().
    private LinkReceiver linkReceiver;
    // The texts it took that the TV has not answered yet: the one being checked or asked about and those
    // waiting for their turn, one at a time, since a check bumps validationRequest, which would cancel one
    // already running. The receiver counts them too, in the queue's count, and turns a send away with the
    // busy page at LinkReceiver.MAX_WAITING. They outlive the port: what was taken is the TV's to answer.
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
            if (page == 0) render();
            QuickTileService.refresh(context);
        }
    };

    private static final class Config {
        String name;
        String toml;
        Config(String name, String toml) { this.name = name; this.toml = toml; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        loadConfigs();
        render();
        // A phone that starts taking keys, or a keyboard put away, changes what rests in a
        // strong fill (see remote). Not rebuilt in the middle of the touch that caused it.
        getWindow().getDecorView().getViewTreeObserver().addOnTouchModeChangeListener(inTouchMode ->
            host.post(() -> { if (remote != isRemote()) render(); }));
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
        FreeTunnelVpnService.clearStaleAlert(this);
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(FreeTunnelVpnService.ACTION_STATE);
            ContextCompat.registerReceiver(this, vpnReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        AndroidUpdater.INSTANCE.observe(() -> { refreshUpdateDot(); if (page == 3) refreshUpdateControls(); });
        AndroidUpdater.INSTANCE.recoverInstall(this);
        vpnState = FreeTunnelVpnService.state(this);
        // Settings is only refreshed, not rebuilt, so it keeps its scroll position.
        if (page == 0) render();
        else if (page == 3) refreshUpdateControls();
    }

    @Override protected void onPause() {
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
    private LinearLayout card() {
        LinearLayout c = column(); c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(ui.shape(t().surface, 14)); return c;
    }
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
    // A remote, or a keyboard on a phone: the window is out of touch mode. See remote.
    // Before the window is attached it has no touch mode to ask, and answers as if it were
    // out of it: a phone is taken to be touched until the window says it is not.
    private boolean isRemote() {
        View decor = getWindow().getDecorView();
        return isTv() || decor.isAttachedToWindow() && !decor.isInTouchMode();
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
        int generation = ++renderGeneration;
        dark = Theme.isDark(themeMode, nightMode());
        remote = isRemote();
        ui.update(dark, remote);
        Theme t = t();
        getWindow().setStatusBarColor(t.bg); getWindow().setNavigationBarColor(t.bg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // The window itself too, so that nothing of the system theme's dark shows before a frame, or at an edge.
        getWindow().setBackgroundDrawable(new ColorDrawable(t.bg));
        host.setBackgroundColor(t.bg);
        pageRoot.removeAllViews();
        nav();
        pageScroll = null;
        if (page == 0) home();
        else {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(false);
            // On a TV the cards would otherwise stretch across the whole screen.
            int sides = isTv() ? dp(48) : 0;
            int side = isTv() ? Math.max(dp(18), (getResources().getDisplayMetrics().widthPixels - 2 * sides - dp(640)) / 2) : dp(18);
            LinearLayout body = column(); body.setPadding(side, dp(8), side, dp(26)); scroll.addView(body);
            pageRoot.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            pageScroll = scroll;
            switch (page) {
                case 1: configs(body); break;
                case 2: split(body); break;
                case 3: settings(body); break;
                default: logs(body); break;
            }
        }
        renderedPage = page;
        restoreFocus(generation, restoreKey, restoreScrollY);
        reanchorOverlays();
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
    private static final Pattern NUMBERED_KEY = Pattern.compile("([a-z]+):(\\d{1,5})(:[a-z]+)?");
    // The control with this key. When it is gone, such as after a deletion, the nearest
    // one before it: config:2:edit, then config:1:edit and config:0:edit. Otherwise the
    // page's own: Reconnect while traffic is blocked, else Connect, on Home, and the
    // page's tab elsewhere.
    private View focusTarget(String key) {
        View found = key == null ? null : host.findViewWithTag(key);
        Matcher numbered = NUMBERED_KEY.matcher(key == null ? "" : key);
        if (found == null && numbered.matches()) {
            String rest = numbered.group(3) == null ? "" : numbered.group(3);
            for (int n = Integer.parseInt(numbered.group(2)) - 1; n >= 0 && found == null; n--)
                found = host.findViewWithTag(numbered.group(1) + ":" + n + rest);
        }
        if (found == null && page == 0) found = pageRoot.findViewWithTag("home:reconnect");
        if (found == null) found = pageRoot.findViewWithTag(page == 0 ? "home:toggle" : "tab:" + page);
        return found;
    }
    // Back closes what is on top first: an overlay. Without tabs to tap, Back on a TV then
    // goes to Home, and leaves the app from there. Home's own control gets the focus: left
    // on the tab it was on, the next OK would go straight back to that page.
    // Still delivered at targetSdk 35; targetSdk 36 needs an OnBackInvokedCallback.
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        Overlay top = topOverlay();
        if (top != null) { escape(top); return; }
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
        returnFocus(overlay);
        if (typing) hideKeyboard();
        // The fade ends in taking the view away. Not left to the animation alone: with the activity stopped, as
        // Receive from phone's card is when the app is left, it may never run, and the card would be there,
        // invisible and in the way, when the app comes back.
        Runnable remove = () -> overlayLayer.removeView(overlay.layer);
        overlay.layer.animate().alpha(0f).setDuration(130).withEndAction(remove).start();
        host.postDelayed(remove, 400);
        if (overlay.onClose != null) overlay.onClose.run();
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
    private void returnFocus(Overlay closed) {
        if (getWindow().getDecorView().isInTouchMode()) return;
        View target = closed.openerKey == null ? null : host.findViewWithTag(closed.openerKey);
        Overlay top = topOverlay();
        if (target == null && top != null) { focusOverlay(top); return; }
        if (target == null) target = focusTarget(closed.openerKey);
        if (target != null) target.requestFocus();
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
            TextView name = ui.follow(ui.label(options[i], 14, color), color, color, t.accentText);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            rowView.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            if (i == current) rowView.addView(ui.follow(ui.label("✓", 14, t.accent), t.accent, t.accent, t.accentText), lp(-2, -1));
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
            TextView text = ui.follow(ui.label(item.label, 14, t.text), t.text, t.text, t.accentText);
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
        Overlay overlay = showOverlay(name, OverlayLayer.Kind.POPUP, list, anchorKey, null);
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
        return p;
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
    // focus is the tile in accent, grown to 1.08, the current one too.
    private void nav() {
        Theme t = t();
        LinearLayout bar = row(); bar.setGravity(Gravity.CENTER);
        // 6 below the tiles, as on the desktop; the room above them is the status bar's and the overscan's.
        bar.setPadding(0, dp(9), 0, dp(6));
        settingsDot = null;
        for (int i = 0; i < tabNames.length; i++) {
            final int next = i;
            boolean current = i == page;
            FrameLayout item = new FrameLayout(this);
            ImageView icon = new ImageView(this); icon.setImageResource(tabIcons[i]);
            if (i == 0) icon.setAlpha(current ? 1f : 0.8f);
            else ui.followTint(icon, current ? t.accent : t.textDim, current ? t.accent : t.textDim, t.accentText);
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

    private void home() {
        Theme t = t();
        FrameLayout stage = new FrameLayout(this);
        pageRoot.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout hero = column(); hero.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_logo); logo.setAlpha(0.5f);
        hero.addView(logo, lp(132, 132));
        logo.setContentDescription(isVpnActive() ? getString(R.string.text_disconnect_freetunnel) : getString(R.string.text_connect_freetunnel));
        // On a TV it would be one more stop on the way down to the same Connect.
        if (!isTv()) ui.focus(logo, "home:logo", Ui.Look.TILE).setOnClickListener(v -> connect());
        gap(hero, 22);
        String name = configs.isEmpty() ? getString(R.string.text_add_configuration) : configs.get(Math.min(activeIndex, configs.size() - 1)).name + "  ▾";
        // As wide as its words, so that the focus is around them and not across the screen.
        TextView selected = ui.link(name, 15, Ui.LinkKind.TEXT, "home:config");
        selected.setTypeface(Ui.MEDIUM);
        selected.setOnClickListener(v -> {
            page = 1; render(); if (configs.isEmpty()) addMenu();
        });
        hero.addView(selected, lp(-2, 32));
        TextView status = label(connectionCaption(), 13, vpnState == 2 ? t.success : t.textDim);
        status.setGravity(Gravity.CENTER);
        hero.addView(status, lp(-1, 30));
        if (vpnState == 6) {
            // The service retries on its own; this reconnects now. It is also the
            // way in on Android TV, which has no tile and makes notification
            // actions hard to reach, so a remote lands on it first (focusTarget).
            Button reconnect = ui.button(getString(R.string.vpn_reconnect), Ui.Kind.PRIMARY, 76, "home:reconnect");
            reconnect.setOnClickListener(v -> {
                FreeTunnelVpnService.reconnect(this);
                toast(getString(R.string.text_recovering_connection));
            });
            hero.addView(reconnect, lp(178, 44));
            gap(hero, 10);
        }
        Button toggle = ui.button(isVpnActive() ? getString(R.string.text_disconnect) : getString(R.string.text_connect),
                isVpnActive() && vpnState != 6 ? Ui.Kind.PRIMARY : Ui.Kind.NEUTRAL, 76, "home:toggle");
        toggle.setContentDescription(isVpnActive() ? getString(R.string.text_disconnect_vpn) : getString(R.string.text_connect_vpn));
        toggle.setOnClickListener(v -> connect());
        hero.addView(toggle, lp(178, 44));
        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        heroLp.bottomMargin = dp(34);
        stage.addView(hero, heroLp);
    }
    private void gapHorizontal(LinearLayout p, int size) { p.addView(new View(this), lp(size, 1)); }
    private void connect() {
        vpnState = FreeTunnelVpnService.state(this);
        if (isVpnActive()) {
            validationRequest++;
            FreeTunnelVpnService.stop(this);
            return;
        }
        if (configs.isEmpty()) { page = 1; render(); toast(getString(R.string.text_add_a_configuration_first)); return; }
        Config config = configs.get(Math.max(0, Math.min(activeIndex, configs.size() - 1)));
        validateAsync(config.toml, () -> {
            pendingConfig = config.toml;
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

    private String connectionCaption() {
        switch (vpnState) {
            case 1: return getString(R.string.text_connecting);
            case 2: return getString(R.string.text_connected);
            case 6: return getString(R.string.vpn_traffic_blocked);
            case 3: case 4: case 5: return getString(R.string.text_recovering_connection);
            default: return getString(R.string.text_disconnected);
        }
    }

    private void startVpnWithPendingConfig() {
        if (pendingConfig == null) return;
        FreeTunnelVpnService.start(this, pendingConfig);
        pendingConfig = null;
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
        Config config = configs.get(Math.max(0, Math.min(activeIndex, configs.size() - 1)));
        validateAsync(config.toml, () -> {
            if (FreeTunnelVpnService.state(this) == 0) return;
            FreeTunnelVpnService.start(this, config.toml);
            toast(getString(R.string.text_applying_routing_settings));
        }, () -> {});
    }

    // A config that passed: the text to save, what was left out of it, and the name
    // it gives its server, if it gives one.
    private interface Checked { void accept(RoutingConfig.Import checked); }

    private void validateAsync(String source, Runnable accepted, Runnable rejected) {
        validateAsync(source, false, checked -> accepted.run(), rejected);
    }

    // With asImport, the text is first checked as a file to add (RoutingConfig.cleanImport),
    // which may refuse it, or take out a subscription or whatever in its certificate
    // is not a certificate. Here on the worker, since that parses the whole file once more.
    private void validateAsync(String source, boolean asImport, Checked accepted, Runnable rejected) {
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_data_has_not_been_changed)); rejected.run(); return; }
        final long request = ++validationRequest;
        toast(getString(R.string.validating_config));
        validationWorker.execute(() -> {
            try {
                RoutingConfig.Import checked = asImport ? RoutingConfig.cleanImport(source) : new RoutingConfig.Import(source, false);
                RoutingConfig.Plan plan = RoutingConfig.prepare(this, checked.getToml());
                NativeConfigValidator.INSTANCE.validate(getApplicationContext(), plan.getToml());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (request != validationRequest) { rejected.run(); return; }
                    if (plan.getWarning() != null) toast(getString(plan.getWarning()));
                    accepted.accept(checked);
                });
            } catch (RoutingConfig.ImportRefused refused) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    rejected.run();
                    if (request == validationRequest) refuseImport(refused.messageResource);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    rejected.run();
                    if (request == validationRequest) toast(getString(R.string.config_validation_error, error.getMessage()));
                });
            }
        });
    }

    private void configs(LinearLayout body) {
        Theme t = t();
        storageBanner(body);
        // The header's buttons are 40 by 32, 16 apart, resting on the page.
        LinearLayout header = row(); header.setGravity(Gravity.CENTER);
        FrameLayout add = ui.iconButton(R.drawable.ic_add, 22, getString(R.string.text_add_configuration), "configs:add");
        add.setOnClickListener(v -> addMenu()); header.addView(add, lp(40, 32));
        gapHorizontal(header, 16);
        FrameLayout ping = ui.iconButton(R.drawable.ic_ping, 22, getString(R.string.text_check_server_tcp_latency), "configs:ping");
        ping.setOnClickListener(v -> pingConfigs()); header.addView(ping, lp(40, 32));
        body.addView(header, lp(-1, 44));
        if (configs.isEmpty()) {
            body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            // As wide as its words: the focus is around them, not across the page.
            TextView hint = ui.link(getString(R.string.text_add_configuration), 15, Ui.LinkKind.TEXT, "configs:hint");
            hint.setTypeface(Ui.MEDIUM);
            hint.setOnClickListener(v -> addMenu());
            LinearLayout.LayoutParams hintLp = lp(-2, 40);
            hintLp.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(hint, hintLp);
            body.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        }
        for (int i = 0; i < configs.size(); i++) {
            final int index = i; Config config = configs.get(i); gap(body, 12);
            LinearLayout c = card(); LinearLayout top = row();
            TextView name = ui.medium(config.name, 16, t.text); top.addView(name, new LinearLayout.LayoutParams(0, dp(35), 1));
            if (i == activeIndex) top.addView(label(getString(R.string.text_selected), 12, t.success), lp(-2, 35));
            c.addView(top, lp(-1, 36));
            LinearLayout meta = row();
            meta.addView(label("TrustTunnel  ·  TOML", 12, t.textDim), new LinearLayout.LayoutParams(0, dp(25), 1));
            String pingText = pingResults.get(config);
            if (pingText != null) meta.addView(label(pingText, 12, t.textDim), lp(-2, 25));
            c.addView(meta, lp(-1, 25));
            LinearLayout actions = row();
            Button select = ui.button(getString(R.string.text_select), Ui.Kind.NEUTRAL, 76, "config:" + i + ":select"); select.setOnClickListener(v -> { activeIndex = index; saveConfigs(); applyCurrentRules(); render(); });
            actions.addView(select, new LinearLayout.LayoutParams(0, dp(38), 1)); gapHorizontal(actions, 8);
            Button edit = ui.button(getString(R.string.text_edit), Ui.Kind.NEUTRAL, 76, "config:" + i + ":edit"); edit.setOnClickListener(v -> editConfig(index));
            actions.addView(edit, new LinearLayout.LayoutParams(0, dp(38), 1)); gapHorizontal(actions, 8);
            Button remove = ui.button("✕", Ui.Kind.NEUTRAL, 0, "config:" + i + ":remove"); remove.setOnClickListener(v -> confirmRemove(index));
            remove.setPadding(0, 0, 0, 0);
            actions.addView(remove, lp(42, 38)); c.addView(actions, lp(-1, 38));
            body.addView(c, lp(-1, -2));
        }
    }
    private void pingConfigs() {
        if (configs.isEmpty()) { toast(getString(R.string.text_add_a_configuration_first)); return; }
        if (pingRunning) return;
        pingRunning = true;
        List<Config> snapshot = new ArrayList<>(configs);
        for (Config config : snapshot) pingResults.put(config, "…");
        render();
        new Thread(() -> {
            for (Config config : snapshot) {
                String result = tcpPing(config.toml);
                runOnUiThread(() -> {
                    pingResults.put(config, result);
                    if (page == 1) render();
                });
            }
            runOnUiThread(() -> { pingRunning = false; if (page == 1) render(); });
        }, "freetunnel-ping").start();
    }
    private String tcpPing(String toml) {
        String target;
        try { target = RoutingConfig.validate(toml).getArray("endpoint.addresses").getString(0); }
        catch (Exception e) { return getString(R.string.text_no_address); }
        int separator = target.lastIndexOf(':');
        if (separator <= 0) return getString(R.string.text_no_port);
        String host = target.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            int port = Integer.parseInt(target.substring(separator + 1));
            if (port < 1 || port > 65535) return getString(R.string.text_no_port);
            long start = SystemClock.elapsedRealtime();
            // Times a TCP handshake to the server and sends nothing over it, so
            // there is nothing to encrypt.
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), 2500); } // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            return (SystemClock.elapsedRealtime() - start) + getString(R.string.text_ms);
        } catch (Exception e) { return getString(R.string.text_unreachable); }
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
        showMenu("addmenu", "configs:add", items);
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
        validateAsync(imported.toml, false, checked -> showImportConfirmation(imported.name, false,
                new RoutingConfig.Import(checked.getToml(), imported.subscriptionNotKept, imported.certificateIgnored), origin, true, done),
                () -> answered(done, true));
    }
    // Named after its file or where the text came from, unless it is an endpoint's
    // export that names its server.
    private void importToml(String name, String toml, String origin) { importToml(name, toml, origin, null); }
    private void importToml(String name, String toml, String origin, Answered done) {
        validateAsync(stripBom(toml), true, checked -> showImportConfirmation(
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
    // waited. When one is refused, its notice stays up before the next one's "Validating…" takes its place.
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
        return new LinkReceiver.Pages(java.util.Locale.getDefault().toLanguageTag(), getString(R.string.receive_page_title),
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
    //   Add a VPN server from this link? “Name”        (or, for a name already in the list: “Name” already exists…)
    //   Server: host
    //   Sent from 192.168.1.5                           (Android only: where a phone sent it from)
    // then the rest. origin is the address the text was sent from, shown so that a text someone
    // else on the network sent first can be told apart; null when it came from here. The name and the
    // server are shown as ShownText shows them, so that whatever sent them, the lines after them stay
    // in sight. namedAfterFile is true when name is the one the text was imported under, its file's name
    // or the app's words for where it came from, and false when it is a link's or an export's own; link
    // is true for a tt:// link. Cancel has the focus, and Import is red and waits for 400 ms. A name
    // already in the list, or the same file, is the desktop's collision: Replace puts it where that one
    // is, Add copy adds it as another.
    private void showImportConfirmation(String name, boolean namedAfterFile, RoutingConfig.Import checked, String origin, boolean link) {
        showImportConfirmation(name, namedAfterFile, checked, origin, link, null);
    }
    private void showImportConfirmation(String name, boolean namedAfterFile, RoutingConfig.Import checked, String origin, boolean link,
                                        Answered done) {
        String source = checked.getToml();
        org.tomlj.TomlTable parsed = RoutingConfig.validate(source);
        Config clash = null;
        for (Config config : configs) {
            if (config.name.equals(name) || config.toml.equals(source)) { clash = config; break; }
        }
        StringBuilder message = new StringBuilder(clash == null
                ? getString(link ? R.string.import_question_link : R.string.import_question_file) + " “" + ShownText.of(name) + "”"
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
        configs.set(at, new Config(name, source));
        if (!saveConfigs()) return;
        if (at == activeIndex) applyCurrentRules();
        render();
        toast(getString(R.string.config_added, ShownText.of(name)));
    }
    private static String stripBom(String toml) {
        return toml.startsWith("\uFEFF") ? toml.substring(1) : toml;
    }
    // The editor: the desktop's card (qml/CreateConfigOverlay.qml), with ← and the title, a Name field, the
    // TOML in a text area of its own, Show TOML and password as a toggle row, and Cancel and Save at the
    // bottom right. It opens on the name, and a remote never lands on the toggle by itself, first or after a
    // Save that is refused, where an OK would show the password to the room. The TOML holds the password,
    // so while the card is up the window is FLAG_SECURE. Back, ←, Cancel and a tap outside ask before they
    // drop what was typed. Keys: editor:back, editor:name, editor:toml, editor:show, editor:cancel and
    // editor:save.
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
        // What was there when it opened. Joined on a character no text holds, so that moving a boundary
        // between the two is a change.
        final String opened = name.getText() + "\u001f" + raw.getText();
        ToggleView show = ui.toggle(false, false, "editor:show");
        show.setContentDescription(getString(R.string.text_show_toml_and_password));
        show.setListener((v, checked) -> raw.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance()));

        final boolean[] asking = {false};
        final Runnable tryClose = () -> {
            Overlay editor = shown[0];
            if (editor == null || editor.closed) return;
            if ((name.getText() + "\u001f" + raw.getText()).equals(opened)) { closeOverlay(editor); return; }
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
        form.addView(ui.field(getString(R.string.editor_name), name, "editor:name"), lp(-1, -2));
        gap(form, 10);
        form.addView(ui.fieldArea(getString(R.string.editor_toml), raw, "editor:toml", 150), lp(-1, -2));
        gap(form, 10);
        form.addView(ui.editorToggleRow(getString(R.string.text_show_toml_and_password), show), lp(-1, -2));
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
            validateAsync(link != null ? link.toml : text, link == null, result -> {
                RoutingConfig.Import checked = link == null ? result
                        : new RoutingConfig.Import(result.getToml(), link.subscriptionNotKept, link.certificateIgnored);
                if (overlay.closed) return;
                if (editing == null) configs.add(new Config(n, checked.getToml()));
                else {
                    int current = configs.indexOf(editing);
                    if (current < 0) { checking[0] = false; save.setEnabled(true); return; }
                    configs.set(current, new Config(n, checked.getToml()));
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

    private void split(LinearLayout body) {
        Theme t = t();
        ToggleView splitSwitch = ui.toggle(prefs.getBoolean("split_enabled", false), false, "split:enabled");
        splitSwitch.setContentDescription(getString(R.string.text_split_tunnelling));
        splitSwitch.setListener((v, checked) -> {
            prefs.edit().putBoolean("split_enabled", checked).apply();
            applyCurrentRules();
        });
        body.addView(ui.toggleRow(getString(R.string.text_split_tunnelling), null, splitSwitch), lp(-1, -2));
        boolean through = prefs.getBoolean("through", false);
        // Only the value and its ▾ are the control: the label is plain text.
        body.addView(ui.dropdown(getString(R.string.text_mode), through ? getString(R.string.text_through_vpn_62) : getString(R.string.text_bypass_vpn_61),
                "split:mode", v -> showSelect("split:mode",
                new String[]{getString(R.string.text_bypass_vpn_61), getString(R.string.text_through_vpn_62)}, through ? 1 : 0, which -> {
                    prefs.edit().putBoolean("through", which == 1).apply(); applyCurrentRules(); render();
                })), lp(-1, -2));
        TextView explanation = ui.paragraph(getString(R.string.text_for_through_vpn_choose_applications_or_addresses_sel), 12, t.textDim, 0);
        body.addView(explanation, lp(-1, -2));
        for (Config config : configs) {
            if (configs.indexOf(config) != activeIndex) continue;
            try {
                org.tomlj.TomlArray imported = RoutingConfig.validate(config.toml).getArray("exclusions");
                if (imported != null && imported.size() > 0) body.addView(label(getString(R.string.imported_routing_rules, imported.toList().toString()),
                        12, t.textDim), lp(-1, -2));
            } catch (Exception ignored) { }
        }
        if (prefs.getBoolean("rules_migrated", false)) {
            body.addView(label(getString(R.string.legacy_rules_removed), 12, t.textDim), lp(-1, -2));
        }
        gap(body, 14);
        LinearLayout domainHeader = row();
        domainHeader.addView(label(through ? getString(R.string.text_rules_through_vpn) : getString(R.string.text_rules_bypass_vpn), 12, t.textFaint),
                weighted());
        TextView clearDomains = ui.link(getString(R.string.text_clear), 12, Ui.LinkKind.ACCENT, "domains:clear");
        clearDomains.setOnClickListener(v -> { prefs.edit().remove("domains").apply(); applyCurrentRules(); render(); });
        domainHeader.addView(clearDomains, lp(-2, -2)); body.addView(domainHeader, lp(-1, -2));
        gap(body, 6);
        Set<String> rules = new HashSet<>(prefs.getStringSet("domains", Collections.emptySet()));
        LinearLayout domains = column();
        List<String> shownRules = sorted(rules);
        for (int i = 0; i < shownRules.size(); i++) {
            String rule = shownRules.get(i);
            LinearLayout chip = ui.chip(rule, false, false, null, true);
            ImageView del = ui.chipX("domain:" + i, false);
            del.setContentDescription(getString(R.string.text_delete));
            del.setOnClickListener(v -> { rules.remove(rule); prefs.edit().putStringSet("domains", rules).apply(); applyCurrentRules(); render(); });
            chip.addView(del, ui.chipXLp());
            domains.addView(chip, lp(-1, 28)); gap(domains, 6);
        }
        body.addView(domains, lp(-1, -2));
        EditText domainInput = ui.pageInput(getString(R.string.text_domains_or_ips_space_comma), "domains:input");
        domainInput.setOnEditorActionListener((field, action, event) -> {
            addDomains(domainInput.getText().toString()); domainInput.setText(""); return true;
        });
        body.addView(domainInput, lp(-1, 36));
        gap(body, 14);
        LinearLayout appHeader = row();
        appHeader.addView(label(through ? getString(R.string.text_applications_through_vpn) : getString(R.string.text_applications_bypass_vpn), 12, t.textFaint),
                weighted());
        TextView chooseLink = ui.link(getString(R.string.text_choose), 12, Ui.LinkKind.ACCENT, "apps:choose");
        chooseLink.setOnClickListener(v -> appPicker());
        appHeader.addView(chooseLink, lp(-2, -2)); body.addView(appHeader, lp(-1, -2));
        gap(body, 6);
        LinearLayout apps = column();
        Set<String> selected = prefs.getStringSet("apps", Collections.emptySet());
        PackageManager pm = getPackageManager();
        int appRows = 0;
        for (String pkg : sorted(selected)) {
            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                LinearLayout appRow = ui.chip(appInfo.loadLabel(pm).toString(), false, false, null, true);
                ImageView appIcon = new ImageView(this); appIcon.setImageDrawable(appInfo.loadIcon(pm));
                appRow.addView(appIcon, 0, lp(20, 20));
                appRow.addView(new View(this), 1, lp(8, 1));
                ImageView remove = ui.chipX("app:" + appRows++, false);
                remove.setContentDescription(getString(R.string.text_delete));
                remove.setOnClickListener(v -> {
                    Set<String> next = new HashSet<>(prefs.getStringSet("apps", Collections.emptySet()));
                    next.remove(pkg); prefs.edit().putStringSet("apps", next).apply(); applyCurrentRules(); render();
                });
                appRow.addView(remove, ui.chipXLp()); apps.addView(appRow, lp(-1, 28)); gap(apps, 6);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        body.addView(apps, lp(-1, -2));
        TextView addApp = label(getString(R.string.text_choose_installed_applications), 13, t.textFaint);
        addApp.setPadding(dp(12), 0, dp(12), 0);
        ui.focus(addApp, "apps:add", Ui.Look.FIELD).setOnClickListener(v -> appPicker());
        body.addView(addApp, lp(-1, 36));
    }
    private void addDomains(String inputText) {
        Set<String> rules = new HashSet<>(prefs.getStringSet("domains", Collections.emptySet()));
        try {
            for (String rule : SplitRules.INSTANCE.normalize(inputText, false)) rules.add(rule);
        } catch (IllegalArgumentException e) { toast(getString(R.string.invalid_routing_rule)); return; }
        prefs.edit().putStringSet("domains", rules).apply(); applyCurrentRules(); render();
    }
    private List<String> sorted(Set<String> set) { List<String> list = new ArrayList<>(set); Collections.sort(list); return list; }
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
                TextView name = ui.follow(label(app.label, 13, t.text), t.text, t.text, t.accentText);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                // The package, which tells two apps of one name apart, is cut at its left, where the end is what matters.
                TextView pkg = ui.follow(label(app.pkg, 11, t.textFaint), t.textFaint, t.textFaint, t.accentText);
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

        // The application goes in the list of those the VPN does not carry, or carries alone; and the card goes.
        private void choose(AppEntry app) {
            if (overlay.closed) return;
            Set<String> next = new HashSet<>(prefs.getStringSet("apps", Collections.emptySet()));
            next.add(app.pkg);
            closeOverlay(overlay);
            prefs.edit().putStringSet("apps", next).apply();
            applyCurrentRules();
            render();
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

    private void settings(LinearLayout body) {
        Theme t = t();
        section(body, getString(R.string.text_general));
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
        addSwitch(body, getString(R.string.text_connect_on_launch), "", "auto_connect", false);
        section(body, getString(R.string.text_security));
        storageBanner(body);
        addSwitch(body, "Kill switch", getString(R.string.text_blocks_traffic_while_reconnecting), "killswitch", true);
        // Most TVs have no Always-on VPN, and no screen for it. The manifest's queries
        // keep package visibility from hiding the screen on a phone.
        if (getPackageManager().resolveActivity(new Intent(Settings.ACTION_VPN_SETTINGS), 0) != null) {
            LinearLayout systemVpn = row();
            systemVpn.setMinimumHeight(dp(48));
            systemVpn.addView(label(getString(R.string.text_android_vpn_settings), 14, t.text), weighted());
            // The word is the control, not the row.
            TextView open = ui.link(getString(R.string.text_open), 13, Ui.LinkKind.ACCENT, "settings:vpn");
            // Some TVs have only a stub for it, which can still fail to open.
            open.setOnClickListener(v -> {
                try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
                catch (Exception e) { toast(getString(R.string.text_vpn_settings_are_unavailable)); }
            });
            systemVpn.addView(open, lp(-2, -2));
            body.addView(systemVpn, lp(-1, -2));
            body.addView(ui.paragraph(getString(R.string.text_enable_always_on_vpn_and_block_connections_without_v),
                    12, t.textFaint, 0), lp(-1, -2));
            body.addView(ui.paragraph(getString(R.string.lockdown_app_warning), 12, t.textFaint, 0), lp(-1, -2));
        } else {
            body.addView(ui.paragraph(getString(R.string.text_no_always_on_here), 12, t.textFaint, 0), lp(-1, -2));
        }
        section(body, getString(R.string.text_log));
        addSwitch(body, getString(R.string.text_enable_logging), "", "logging", true);
        section(body, getString(R.string.update_title));
        if (!AndroidUpdater.INSTANCE.official(this)) {
            // Android would refuse an update signed with another key than this build's.
            updateStatus = null; updateAction = null;
            body.addView(ui.paragraph(getString(R.string.update_unofficial), 12, t.textFaint, 0), lp(-1, -2));
        } else {
            addSwitch(body, getString(R.string.update_on_launch), "", "check_updates_on_launch", false);
            updateStatus = ui.paragraph("", 12, t.textDim, 0);
            body.addView(updateStatus, lp(-1, -2));
            // As wide as its words.
            updateAction = ui.link("", 14, Ui.LinkKind.ACCENT, "settings:update");
            updateAction.setOnClickListener(v -> {
                AndroidUpdater.State update = AndroidUpdater.INSTANCE.getState();
                if (update == AndroidUpdater.State.AVAILABLE) AndroidUpdater.INSTANCE.download(this);
                else if (update == AndroidUpdater.State.DOWNLOADING) AndroidUpdater.INSTANCE.cancel();
                else if (update == AndroidUpdater.State.READY || update == AndroidUpdater.State.INSTALLING) installUpdate();
                else AndroidUpdater.INSTANCE.check(this);
            });
            body.addView(updateAction, lp(-2, -2));
            refreshUpdateControls();
        }
        section(body, getString(R.string.text_about));
        body.addView(ui.paragraph(getString(R.string.text_freetunnel_for_android), 14, t.text, 42), lp(-1, -2));
        body.addView(ui.paragraph(getString(R.string.text_version) + BuildConfig.VERSION_NAME, 12, t.textDim, 0), lp(-1, -2));
        LinearLayout licenses = row();
        licenses.setMinimumHeight(dp(48));
        licenses.addView(label(getString(R.string.text_open_source_licenses), 14, t.text), weighted());
        TextView openLicenses = ui.link(getString(R.string.text_open), 13, Ui.LinkKind.ACCENT, "settings:licenses");
        openLicenses.setOnClickListener(v -> openSourceLicenses());
        licenses.addView(openLicenses, lp(-2, -2));
        body.addView(licenses, lp(-1, -2));
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
                row.setTextColor(Ui.colors(t.text, t.text, t.accentText));
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
        if (updateStatus == null || updateAction == null) return;
        AndroidUpdater.State update = AndroidUpdater.INSTANCE.getState();
        UpdatePolicy.Release release = AndroidUpdater.INSTANCE.getRelease();
        String version = release != null ? release.getVersion() : "";
        // Once Android has the session, Install update starts over if its dialog was lost.
        boolean reinstall = update == AndroidUpdater.State.INSTALLING && AndroidUpdater.INSTANCE.getCommitted();
        String text;
        switch (update) {
            case CHECKING: text = getString(R.string.update_checking); break;
            case CURRENT: text = getString(R.string.update_current); break;
            case AVAILABLE: text = getString(R.string.update_available, version); break;
            case DOWNLOADING: text = getString(R.string.update_downloading, version, AndroidUpdater.INSTANCE.getProgress()); break;
            case READY: text = getString(R.string.update_ready, version); break;
            case INSTALLING: text = getString(R.string.update_installing); break;
            case ERROR: text = getString(updateFailureText(AndroidUpdater.INSTANCE.getFailure())); break;
            default: text = getString(R.string.update_hint);
        }
        updateStatus.setText(text);
        int action = R.string.update_check;
        if (update == AndroidUpdater.State.AVAILABLE) action = R.string.update_download;
        else if (update == AndroidUpdater.State.DOWNLOADING) action = R.string.update_cancel;
        else if (update == AndroidUpdater.State.READY || reinstall) action = R.string.update_install;
        updateAction.setText(getString(action));
        // Not setEnabled: disabling the view that has a remote's focus moves the focus to
        // the top of the window. Not clickable, it ignores taps and OK just the same.
        updateAction.setClickable(update != AndroidUpdater.State.CHECKING
                && (update != AndroidUpdater.State.INSTALLING || reinstall));
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
        parent.addView(ui.toggleRow(title, subtitle, sw), lp(-1, -2));
    }
    private void logs(LinearLayout body) {
        Theme t = t();
        // Clear and Copy are as wide as their words, with room between them.
        LinearLayout actions = row();
        TextView clear = ui.link(getString(R.string.text_clear), 13, Ui.LinkKind.ACCENT, "log:clear");
        actions.addView(clear, lp(-2, -2));
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView copy = ui.link(getString(R.string.text_copy), 13, Ui.LinkKind.ACCENT, "log:copy"); actions.addView(copy, lp(-2, -2));
        clear.setOnClickListener(v -> {
            try { new java.io.File(getFilesDir(), "freetunnel.log").delete(); } catch (Exception ignored) { }
            render();
        });
        copy.setOnClickListener(v -> {
            try {
                java.io.File file = new java.io.File(getFilesDir(), "freetunnel.log");
                String contents = file.exists() ? new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
                ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                manager.setPrimaryClip(ClipData.newPlainText("FreeTunnel log", contents));
                toast(contents.isEmpty() ? getString(R.string.text_log_is_empty) : getString(R.string.text_log_copied));
            } catch (Exception e) { toast(getString(R.string.text_could_not_read_log)); }
        });
        body.addView(actions, lp(-1, 34)); gap(body, 8);
        FrameLayout panel = new FrameLayout(this); panel.setBackground(ui.shape(t.surface, 8));
        String logText = getString(R.string.text_connect_vpn_to_see_the_log);
        java.io.File logFile = new java.io.File(getFilesDir(), "freetunnel.log");
        if (logFile.exists()) {
            try { logText = new String(java.nio.file.Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8).trim(); }
            catch (Exception ignored) { logText = getString(R.string.text_could_not_read_log); }
        }
        // The log is monospace, 11 in the text colour; the placeholder is 13 in textFaint.
        TextView placeholder = logFile.exists() ? ui.mono(logText, 11, t.text) : label(logText, 13, t.textFaint);
        placeholder.setGravity(Gravity.TOP | Gravity.START);
        placeholder.setPadding(dp(12), dp(12), dp(12), dp(12));
        // On a TV, selectable text would take the remote's arrows as cursor moves. Without
        // it, Down from Clear or Copy scrolls through the log.
        placeholder.setTextIsSelectable(!isTv());
        panel.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        body.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    @Override protected void onDestroy() {
        toastHandler.removeCallbacksAndMessages(null);
        stopReceiver();
        dropReceived();
        validationRequest++;
        validationWorker.shutdown();
        scanWorker.shutdown();
        super.onDestroy();
    }
    private void loadConfigs() {
        try {
            JSONArray array = new JSONArray(ConfigStore.read(prefs));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i); configs.add(new Config(o.getString("name"), o.getString("toml")));
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
            try { o.put("name", config.name); o.put("toml", config.toml); array.put(o); } catch (Exception ignored) { }
        }
        if (!ConfigStore.write(prefs, array.toString())) {
            storageFailed = true;
            storageProblem = getString(R.string.text_could_not_save_configurations_data_on_disk_is_unchan);
            toast(storageProblem);
            configs.clear(); loadConfigs(); render(); return false;
        }
        prefs.edit().putInt("active", activeIndex).apply();
        return true;
    }
}
