package org.freetunnel.android;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.graphics.drawable.StateListDrawable;
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
import android.text.InputType;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
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
import java.util.Map;
import java.util.Set;
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
    private TextView updateStatus;
    private TextView updateAction;
    // Receive from phone: the open port and its dialog, both closed by stopReceiver().
    private LinkReceiver linkReceiver;
    private AlertDialog receiveDialog;
    private long validationRequest;
    private final java.util.concurrent.ExecutorService validationWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
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
    // dialog is on the screen.
    @Override protected void onStop() {
        stopReceiver();
        super.onStop();
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
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
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
    // The system dialogs that remain until the in-app overlays replace them take the app's
    // theme, light or dark, so that what is put in them reads in either. Their buttons and
    // check boxes show the focus with dialogFocus.
    private AlertDialog.Builder builder() {
        return new AlertDialog.Builder(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }
    // The focus of a control in a system dialog: a ring over it, in the text's colour. The
    // only ring left: everything of the app's own shows the desktop's focus (Ui.focus).
    private void dialogFocus(View view) {
        int ring = dark ? Color.WHITE : t().text;
        GradientDrawable stroke = ui.shape(Theme.withAlpha(ring, 0x1A), 10);
        stroke.setStroke(dp(2), ring);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, stroke);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        view.setForeground(states);
    }
    // Every dialog is shown through here, so that its buttons get a ring.
    private void show(AlertDialog.Builder builder) { show(builder.create()); }
    private void show(AlertDialog dialog) {
        dialog.show();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) dialogFocus(button);
        }
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
                setHostPadding(sides, bars.top + ends, sides, bars.bottom + ends);
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
        overlays.remove(overlay);
        retag(overlay.layer);
        overlay.layer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        updateOverlayState();
        returnFocus(overlay);
        overlay.layer.animate().alpha(0f).setDuration(130).withEndAction(() -> overlayLayer.removeView(overlay.layer)).start();
        if (overlay.onClose != null) overlay.onClose.run();
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
    // asked. Confirmations queue: one asked for while another is up waits its turn, and
    // never takes its place. Cancel has the focus first. Keys: confirm:message,
    // confirm:cancel, confirm:alt and confirm:ok.
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
        if (request.alternate != null) {
            ui.gapH(buttons, 8);
            Button alternate = ui.button(request.alternate, Ui.Kind.NEUTRAL, 76, "confirm:alt");
            alternate.setOnClickListener(v -> {
                closeOverlay(shown[0]);
                if (request.onAlternate != null) request.onAlternate.run();
            });
            buttons.addView(alternate, lp(-2, 32));
        }
        ui.gapH(buttons, 8);
        final long armedAt = SystemClock.uptimeMillis() + CONFIRM_ARMED_MS;
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
    // accessibility. The system's Toast is used only where no window of ours can show one
    // (the tile, the VPN consent screen) and, until the dialogs it would be behind are
    // overlays too, by toast().
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
        bar.setPadding(0, dp(9), 0, dp(4));
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
        pageRoot.addView(bar, lp(-1, 56));
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
    private void addMenu() {
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_saved_data_has_been_preserved)); return; }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        // A TV has no camera for a link's QR code, and a remote is no way to type one.
        if (isTv()) { labels.add(getString(R.string.text_receive_from_phone)); actions.add(this::receiveFromPhone); }
        labels.add(getString(R.string.text_import_toml_file)); actions.add(this::pickFile);
        labels.add(getString(R.string.text_paste_from_clipboard)); actions.add(this::pasteFromClipboard);
        labels.add(getString(R.string.text_create_manually)); actions.add(() -> editConfig(-1));
        show(builder().setTitle(getString(R.string.text_add_configuration))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()));
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
        if (DeepLinkImport.looksLikeLink(text)) importDeepLink(text, origin);
        else importToml(fallbackName, text, origin);
    }
    private void importDeepLink(String link, String origin) {
        final DeepLinkImport.Imported imported;
        try { imported = DeepLinkImport.parse(link); }
        catch (DeepLinkImport.ParseException e) { refuseImport(e.messageResource); return; }

        // The text is the app's own, and the subscription and whatever in the
        // certificate is not a certificate were left out as the link was read.
        validateAsync(imported.toml, false, checked -> showImportConfirmation(imported.name, false,
                new RoutingConfig.Import(checked.getToml(), imported.subscriptionNotKept, imported.certificateIgnored), origin), () -> {});
    }
    // Named after its file or where the text came from, unless it is an endpoint's
    // export that names its server.
    private void importToml(String name, String toml, String origin) {
        validateAsync(stripBom(toml), true, checked -> showImportConfirmation(
                checked.getName() != null ? checked.getName() : name, checked.getName() == null, checked, origin), () -> {});
    }

    // Receive from phone: a phone on the same network opens the page the QR code or
    // the address leads to, and sends a link or a TOML file from there, pasted or as
    // a file (LinkReceiver). What arrives goes through the same checks and
    // confirmation as any import.
    private void receiveFromPhone() {
        stopReceiver();
        InetAddress address = lanAddress();
        if (address == null) { toast(getString(R.string.text_receive_no_network)); return; }
        final LinkReceiver r;
        try { r = new LinkReceiver(address, LinkReceiver.newCode(new SecureRandom()), receivePages()); }
        catch (IOException e) { toast(getString(R.string.text_receive_failed)); return; }
        linkReceiver = r;
        LinearLayout box = column(); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        // Drawn at its own size, a whole number of pixels a module, so it is never blurred.
        LinkReceiver.Qr qr = LinkReceiver.qr(r.url(), dp(220));
        ImageView code = new ImageView(this);
        code.setImageBitmap(Bitmap.createBitmap(qr.pixels, qr.side, qr.side, Bitmap.Config.ARGB_8888));
        code.setContentDescription(r.url());
        box.addView(code, lp(-2, -2));
        gap(box, 10);
        // For typing on a laptop.
        TextView url = ui.mono(r.url(), 16, t().text);
        url.setGravity(Gravity.CENTER); box.addView(url, lp(-1, -2));
        gap(box, 10);
        box.addView(label(getString(R.string.text_receive_hint), 13, t().textDim), lp(-1, -2));
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = builder().setTitle(getString(R.string.text_receive_from_phone))
                .setView(scroll).setNegativeButton(getString(R.string.text_cancel), null).create();
        dialog.setOnDismissListener(d -> { if (linkReceiver == r) stopReceiver(); });
        receiveDialog = dialog;
        show(dialog);
        // No screensaver while the phone is used instead of the remote: it would stop the activity, and the receiver with it.
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // A remote starts on Cancel. Left to itself, the focus would go to the scroll
        // view around the code, which has no ring and does nothing on OK. As in the
        // editor, Cancel is the view focused by default rather than asked about touch
        // mode here, before the dialog is attached.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setFocusedByDefault(true);
        // These arrive on the receiver's thread, and count only for the receiver still open.
        r.start(new LinkReceiver.Listener() {
            @Override public void received(String text, String fileName, String sender) {
                runOnUiThread(() -> {
                    if (isDestroyed() || linkReceiver != r) return;
                    stopReceiver();
                    // A TOML file is named after its file, as one picked here is; a link names itself.
                    importText(fileName != null ? fileName : getString(R.string.text_from_another_device), text, sender);
                });
            }
            @Override public void stopped(LinkReceiver.Stop reason) {
                runOnUiThread(() -> {
                    if (isDestroyed() || linkReceiver != r) return;
                    stopReceiver();
                    if (reason == LinkReceiver.Stop.LOCKED_OUT)
                        show(builder().setTitle(getString(R.string.text_receive_from_phone))
                                .setMessage(getString(R.string.text_receive_locked_out)).setPositiveButton(getString(R.string.text_ok), null));
                    else toast(getString(reason == LinkReceiver.Stop.TIMEOUT ? R.string.text_receive_timed_out : R.string.text_receive_failed));
                });
            }
        });
    }
    private void stopReceiver() {
        if (linkReceiver != null) { linkReceiver.stop(); linkReceiver = null; }
        if (receiveDialog != null) { AlertDialog dialog = receiveDialog; receiveDialog = null; dialog.dismiss(); }
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
                getString(R.string.receive_page_too_large), getString(R.string.receive_page_wrong_code),
                getString(R.string.receive_page_locked_out));
    }
    // These two refusals are about what this version cannot do yet, and say what to
    // do instead. That is more than the two lines a toast shows, so they get a dialog.
    private void refuseImport(int messageResource) {
        if (messageResource == R.string.text_subscription_links_are_not_supported_yet
                || messageResource == R.string.text_this_server_needs_a_newer_trusttunnel_core)
            show(builder().setTitle(getString(R.string.text_import_trusttunnel))
                    .setMessage(getString(messageResource)).setPositiveButton(getString(R.string.text_ok), null));
        else toast(getString(messageResource));
    }
    // What an import left out, a line for each, each line starting with a line break.
    private String importNotes(RoutingConfig.Import checked) {
        String notes = "";
        if (checked.getSubscriptionNotKept()) notes += getString(R.string.text_nsubscriptions_are_not_supported_yet_the_server_wont);
        if (checked.getCertificateIgnored()) notes += getString(R.string.text_nextra_data_in_the_certificate_was_ignored);
        return notes;
    }
    // origin is the address the text was sent from, shown so that a text someone
    // else on the network sent first can be told apart; null when it came from here.
    // The name and the server are shown as ShownText shows them, so that whatever
    // sent them, the lines after them stay in sight. namedAfterFile is true when name
    // is the one the text was imported under, its file's name or the app's words for
    // where it came from, and false when it is a link's or an export's own.
    private void showImportConfirmation(String name, boolean namedAfterFile, RoutingConfig.Import checked, String origin) {
        String source = checked.getToml();
        org.tomlj.TomlTable parsed = RoutingConfig.validate(source);
        String message = getString(R.string.text_add_configuration_36) + ShownText.of(name) + getString(R.string.text_nserver)
                + ShownText.of(parsed.getString("endpoint.hostname"));
        if (origin != null) message += getString(R.string.text_nsent_from_s, origin);
        if (Boolean.TRUE.equals(parsed.getBoolean("endpoint.skip_verification")))
            message += getString(R.string.text_nserver_certificate_verification_is_disabled);
        message += importNotes(checked);
        for (Config config : configs) {
            if (config.name.equals(name) || config.toml.equals(source)) {
                message += getString(R.string.text_nthis_configuration_or_name_already_exists_a_separat); break;
            }
        }
        // A file's extension is not counted: a configuration named after "Дом.toml"
        // would otherwise always be said to mix alphabets. Only a file's: a link's or
        // an export's name, or the server a link is named after, such as "раураӏ.com",
        // is counted whole.
        String word = namedAfterFile && name.matches("(?s).+\\.[0-9A-Za-z]{1,5}") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (word.codePoints().mapToObj(Character.UnicodeScript::of)
                .filter(script -> script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED
                        && script != Character.UnicodeScript.UNKNOWN).distinct().count() > 1)
            message += getString(R.string.text_nthe_name_mixes_similar_characters_from_different_al);
        show(builder().setTitle(getString(R.string.text_import_trusttunnel)).setMessage(message)
                .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_add), (dialog, which) -> {
                    configs.add(new Config(name, source));
                    if (configs.size() == 1) activeIndex = 0;
                    saveConfigs(); page = 1; render();
                }));
    }
    private static String stripBom(String toml) {
        return toml.startsWith("\uFEFF") ? toml.substring(1) : toml;
    }
    private void editConfig(int index) {
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_data_has_not_been_changed)); return; }
        final Config editing = index < 0 ? null : configs.get(index);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout form = column(); form.setPadding(dp(20), dp(5), dp(20), 0);
        EditText name = new EditText(this); name.setSingleLine(true); name.setTextColor(t().text);
        name.setHint(getString(R.string.text_configuration_name)); name.setHintTextColor(t().textDim);
        EditText raw = new EditText(this); raw.setTextColor(t().text); raw.setHintTextColor(t().textDim);
        raw.setHint(getString(R.string.text_paste_complete_trusttunnel_toml)); raw.setMinLines(8); raw.setGravity(Gravity.TOP);
        // The TOML is monospace, as the log is.
        raw.setTypeface(Typeface.MONOSPACE); raw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        raw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // In landscape a keyboard can fill the screen with its own copy of the text. Ask it
        // not to go full screen, and not to copy the text if it does anyway: the copy has no
        // password mask and is outside FLAG_SECURE. Keyboards see ordinary text here, not a
        // password field, so also ask them not to learn from it: the TOML holds the password.
        raw.setImeOptions(raw.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        raw.setTransformationMethod(PasswordTransformationMethod.getInstance());
        CheckBox show = new CheckBox(this); show.setText(getString(R.string.text_show_toml_and_password)); show.setTextColor(t().text);
        dialogFocus(show);
        show.setOnCheckedChangeListener((b, checked) -> raw.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance()));
        form.addView(show, lp(-1, 48));
        if (index >= 0) { name.setText(configs.get(index).name); raw.setText(configs.get(index).toml); }
        form.addView(name, lp(-1, 54)); form.addView(raw, lp(-1, 230));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog editor = builder().setTitle(index < 0 ? getString(R.string.text_new_configuration) : getString(R.string.text_edit_configuration))
                .setView(scroll).setNegativeButton(getString(R.string.text_cancel), null)
                .setPositiveButton(getString(R.string.text_save), null).create();
        editor.setOnDismissListener(d -> {
            validationRequest++;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        show(editor);
        editor.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // A dialog shown to a remote focuses its first control, here the check box that
        // shows the TOML and password, where the first OK would show them to the room.
        // Android gives a window its first focus only out of touch mode, and then to the
        // view focused by default. Asking the dialog here whether it is in touch mode is
        // too early: until its first frame it is not attached and answers for no window.
        name.setFocusedByDefault(true);
        Button save = editor.getButton(AlertDialog.BUTTON_POSITIVE);
        // One check at a time. Only a phone greys Save out meanwhile: disabling the view
        // that has a remote's focus moves the focus to that check box as well.
        boolean[] checking = {false};
        save.setOnClickListener(v -> {
                    if (checking[0]) return;
                    String n = name.getText().toString().trim();
                    String t = stripBom(raw.getText().toString().trim());
                    if (n.isEmpty()) { toast(getString(R.string.text_enter_a_configuration_name)); return; }
                    // A link pasted here is read as the link it is. On a TV, a phone's
                    // keyboard, such as the Google TV app's, can paste one where a whole
                    // file is a lot to get across.
                    final DeepLinkImport.Imported link;
                    try { link = DeepLinkImport.looksLikeLink(t) ? DeepLinkImport.parse(t) : null; }
                    catch (DeepLinkImport.ParseException e) { refuseImport(e.messageResource); return; }
                    checking[0] = true;
                    if (save.isInTouchMode()) save.setEnabled(false);
                    // Checked as an import: the hint asks for a whole TrustTunnel file, and
                    // one pasted here, or saved by an earlier version, can carry a
                    // subscription address, an auth key or a private key with its
                    // certificate just as an imported one can. An endpoint's export is
                    // turned into a whole file, and the name stays the one typed here,
                    // as it does for a link, which is checked as importDeepLink() checks it.
                    validateAsync(link != null ? link.toml : t, link == null, result -> {
                        RoutingConfig.Import checked = link == null ? result
                                : new RoutingConfig.Import(result.getToml(), link.subscriptionNotKept, link.certificateIgnored);
                        if (!editor.isShowing()) return;
                        if (editing == null) configs.add(new Config(n, checked.getToml()));
                        else {
                            int current = configs.indexOf(editing);
                            if (current < 0) return;
                            configs.set(current, new Config(n, checked.getToml()));
                        }
                        saveConfigs(); editor.dismiss(); applyCurrentRules(); render();
                        String notes = importNotes(checked).trim();
                        if (!notes.isEmpty()) show(builder().setMessage(notes)
                                .setPositiveButton(getString(R.string.text_ok), null));
                    }, () -> { checking[0] = false; save.setEnabled(true); });
                });
    }
    private void confirmRemove(int index) {
        if (storageFailed) return;
        show(builder().setTitle(getString(R.string.text_delete_configuration)).setMessage(configs.get(index).name)
                .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_delete), (d, w) -> {
                    boolean activeRemoved = index == activeIndex;
                    configs.remove(index);
                    if (index < activeIndex) activeIndex--;
                    activeIndex = Math.max(0, Math.min(activeIndex, configs.size() - 1));
                    saveConfigs(); if (activeRemoved) applyCurrentRules(); render();
                }));
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
                "split:mode", v -> show(builder().setTitle(getString(R.string.text_mode))
                .setItems(new String[]{getString(R.string.text_bypass_vpn_61), getString(R.string.text_through_vpn_62)}, (dialog, which) -> {
                    prefs.edit().putBoolean("through", which == 1).apply(); applyCurrentRules(); render();
                }))), lp(-1, -2));
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
    private void appPicker() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(launcher, 0));
        // Apps made for TVs may have a TV launcher entry only. "seen" drops those with both.
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.loadLabel(pm).toString(), b.loadLabel(pm).toString()));
        Set<String> chosen = new HashSet<>(prefs.getStringSet("apps", Collections.emptySet()));
        LinearLayout list = column(); list.setPadding(dp(16), 0, dp(16), 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo app : found) {
            String pkg = app.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            CheckBox box = new CheckBox(this); box.setText(app.loadLabel(pm)); box.setTextColor(t().text);
            box.setButtonTintList(android.content.res.ColorStateList.valueOf(t().success));
            box.setChecked(chosen.contains(pkg)); box.setPadding(dp(4), dp(6), dp(4), dp(6)); dialogFocus(box);
            box.setOnCheckedChangeListener((b, checked) -> { if (checked) chosen.add(pkg); else chosen.remove(pkg); });
            list.addView(box, lp(-1, 52));
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(list);
        show(builder().setTitle(getString(R.string.text_choose_applications)).setView(scroll)
                .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_done), (d, w) -> {
                    prefs.edit().putStringSet("apps", chosen).apply(); applyCurrentRules(); render();
                }));
    }

    private void settings(LinearLayout body) {
        Theme t = t();
        section(body, getString(R.string.text_general));
        // System (the default), Light or Dark, as on the desktop.
        String[] modes = {Theme.SYSTEM, Theme.LIGHT_MODE, Theme.DARK_MODE};
        String[] names = {getString(R.string.text_theme_system), getString(R.string.text_light_78), getString(R.string.text_dark_79)};
        int shownMode = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(themeMode)) shownMode = i;
        body.addView(ui.dropdown(getString(R.string.text_theme), names[shownMode], "settings:theme",
                v -> show(builder().setTitle(getString(R.string.text_theme)).setItems(names, (d, which) -> {
                    themeMode = modes[which]; prefs.edit().putString("theme_mode", themeMode).apply(); render();
                }))), lp(-1, -2));
        body.addView(ui.sep(), lp(-1, 1));
        addSwitch(body, getString(R.string.text_connect_on_launch), "", "auto_connect", false);
        section(body, getString(R.string.text_security));
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
    // from the repository into the APK's assets.
    private void openSourceLicenses() {
        List<String> files = new ArrayList<>();
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
        show(builder().setTitle(getString(R.string.text_open_source_licenses))
                .setItems(files.toArray(new String[0]), (d, which) -> showLicenseText(files.get(which)))
                .setNegativeButton(getString(R.string.text_done), null));
    }
    // One of them. A remote scrolls it with the arrows: the scroll view takes the
    // focus, and the frame around it, which takes on the scroll view's states, shows
    // the ring while it has it. Back returns to the list.
    private void showLicenseText(String asset) {
        String text;
        try (InputStream input = getAssets().open(asset); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) > 0; ) out.write(buffer, 0, n);
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            toast(getString(R.string.text_licenses_unavailable));
            return;
        }
        TextView body = label(text, 12, t().text);
        body.setGravity(Gravity.START);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        scroll.setFocusedByDefault(true);
        FrameLayout frame = new FrameLayout(this);
        frame.setAddStatesFromChildren(true);
        frame.addView(scroll);
        dialogFocus(frame);
        show(builder().setTitle(asset).setView(frame)
                .setPositiveButton(getString(R.string.text_done), null)
                .setOnCancelListener(d -> openSourceLicenses()));
    }
    // Installing replaces the app, and Android stops the VPN with it, so say so
    // first. Without the install permission, install() opens its settings screen.
    private void installUpdate() {
        vpnState = FreeTunnelVpnService.state(this);
        if (getPackageManager().canRequestPackageInstalls() && isVpnActive()) {
            show(builder().setTitle(getString(R.string.update_install))
                    .setMessage(getString(R.string.update_disconnect_warning))
                    .setNegativeButton(getString(R.string.text_cancel), null)
                    .setPositiveButton(getString(R.string.update_install), (d, w) -> AndroidUpdater.INSTANCE.install(this)));
        } else {
            AndroidUpdater.INSTANCE.install(this);
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
        validationRequest++;
        validationWorker.shutdown();
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
        show(builder().setTitle(getString(R.string.text_configurations_are_unavailable))
                .setMessage(getString(R.string.text_android_could_not_read_storage_saved_data_is_unchang))
                .setPositiveButton(getString(R.string.text_ok), null));
    }
    private void saveConfigs() {
        validationRequest++;
        if (storageFailed) { toast(getString(R.string.text_storage_is_unavailable_data_has_not_been_changed)); return; }
        JSONArray array = new JSONArray();
        for (Config config : configs) {
            JSONObject o = new JSONObject();
            try { o.put("name", config.name); o.put("toml", config.toml); array.put(o); } catch (Exception ignored) { }
        }
        if (!ConfigStore.write(prefs, array.toString())) {
            storageFailed = true; toast(getString(R.string.text_could_not_save_configurations_data_on_disk_is_unchan));
            configs.clear(); loadConfigs(); render(); return;
        }
        prefs.edit().putInt("active", activeIndex).apply();
    }
}
