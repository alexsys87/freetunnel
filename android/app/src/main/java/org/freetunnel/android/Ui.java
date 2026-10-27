package org.freetunnel.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The desktop's controls, built from its measurements (qml/Main.qml, qml/Toggle.qml,
 * qml/Field.qml, qml/components/*, qml/pages/*), and how a remote's focus shows on them.
 *
 * <p>The desktop has no focus ring: where the pointer is, a control takes its hover
 * look. A remote has no pointer, and from across a room the desktop's hover fills
 * (one step from the page) cannot be seen, so the focus takes the strongest fill the
 * desktop has, the selected chip's: the accent, with its content in accentText. A
 * destructive control focuses as the desktop's confirm button does, in danger with
 * white. A text field keeps its own focus, an accent border. Never a stroke around
 * a control, never a foreground: every control's focus is its background, in its own
 * shape and radius, around what it holds. See {@link #focus}.
 */
final class Ui {
    /** What a control's rest, hover and focus look like. */
    enum Look { TILE, HERO, ROW, MENU_ROW, PILL, LINK, ICON, CHIP, BUTTON, PRIMARY, DANGER, FIELD, SELECT, BOX, TOGGLE }
    /** The four buttons the desktop has. */
    enum Kind { PRIMARY, SECONDARY, NEUTRAL, DESTRUCTIVE }
    /** The colours of link: accent, text colour, danger, and textFaint (the footer's). */
    enum LinkKind { ACCENT, TEXT, DANGER, FAINT }

    /** The tab or chip that is the current one, which rests in its selected look. */
    static final int F_CURRENT = 1;
    /** A secondary button, which rests on the page with a border, as the editor's Cancel does. */
    static final int F_SECONDARY = 2;
    /** A control that goes danger: the close icons, and a danger link. */
    static final int F_DANGER = 4;
    /** Fully round, such as a ChipX or a toggle's backdrop. */
    static final int F_ROUND = 8;
    /**
     * A row that holds controls of its own, such as a config row with its icons: while one of them has
     * the focus the row is activated (setActivated), and rests on surface, as the desktop's row does
     * while the pointer is over one of its icons.
     */
    static final int F_HOLDS_FOCUS = 16;
    /** A 1 px border in the border colour at rest, such as the + chip's. Gone with the focus, which fills it. */
    static final int F_BORDER = 32;

    /** The desktop animates every hover colour for 120 ms. */
    static final int FADE_MS = 120;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Context context;
    private final float density;
    private Theme t = Theme.DARK;
    private boolean remote;

    Ui(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    /**
     * The theme the next controls are drawn in, and whether a remote or keyboard drives
     * the screen. Then, and only then, a control that rests in a strong fill (a primary
     * or a destructive button, the selected chip) rests in a neutral one, so that the
     * focus is the one strong fill on the screen.
     */
    void update(boolean dark, boolean remote) {
        this.t = Theme.of(dark);
        this.remote = remote;
    }
    Theme t() { return t; }
    boolean remote() { return remote; }

    // Measures and containers.

    int dp(float n) { return Math.round(n * density); }
    /** Sizes as a LinearLayout wants them: dp, or -1 and -2 for match and wrap. */
    LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    LinearLayout column() { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); return l; }
    LinearLayout row() { LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    void gap(LinearLayout parent, int size) { parent.addView(new View(context), lp(1, size)); }
    void gapH(LinearLayout parent, int size) { parent.addView(new View(context), lp(size, 1)); }

    GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }
    GradientDrawable outlined(int fill, int stroke, float radiusDp) {
        GradientDrawable d = shape(fill, radiusDp);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    // Text.

    TextView label(CharSequence value, float sp, int color) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }
    /** The desktop's Medium weight, 500: never bold. */
    TextView medium(CharSequence value, float sp, int color) {
        TextView v = label(value, sp, color);
        v.setTypeface(MEDIUM);
        return v;
    }
    TextView mono(CharSequence value, float sp, int color) {
        TextView v = label(value, sp, color);
        v.setTypeface(Typeface.MONOSPACE);
        return v;
    }
    /** SectionLabel: textFaint, 12, as it is typed, with 4 under it. */
    TextView sectionLabel(CharSequence value) {
        TextView v = label(value, 12, t.textFaint);
        v.setPadding(0, 0, 0, dp(4));
        return v;
    }
    /** A line of wrapped text: it takes the height its lines need, and at least this. */
    TextView paragraph(CharSequence value, float sp, int color, int minHeightDp) {
        TextView v = label(value, sp, color);
        v.setMinHeight(dp(minHeightDp));
        return v;
    }
    /** Sep: one line in the border colour, the full width. */
    View sep() {
        View line = new View(context);
        line.setBackgroundColor(t.border);
        return line;
    }
    /** SectionLabel's own spacing: 16 between sections. */
    void section(LinearLayout parent, CharSequence title) {
        gap(parent, 16);
        parent.addView(sectionLabel(title), lp(-1, -2));
    }

    // Colours that change with the control's state.

    /** rest, while pressed or hovered, and while it has the focus. */
    static ColorStateList colors(int rest, int hot, int focused) {
        return new ColorStateList(new int[][]{
            {android.R.attr.state_focused}, {android.R.attr.state_pressed}, {android.R.attr.state_hovered}, {}},
            new int[]{focused, hot, hot, rest});
    }
    /**
     * As above, and a colour for while the view is selected, which a row sets on its icons while the row itself has
     * the focus: they sit on its accent then, as the row's own words do.
     */
    static ColorStateList colors(int rest, int hot, int focused, int selected) {
        return new ColorStateList(new int[][]{
            {android.R.attr.state_focused}, {android.R.attr.state_selected}, {android.R.attr.state_pressed}, {android.R.attr.state_hovered}, {}},
            new int[]{focused, selected, hot, hot, rest});
    }
    /** What a control's content turns to while it has the focus: accentText, or white on danger. */
    int onFocus(Look look, int flags) { return (flags & F_DANGER) != 0 || look == Look.DANGER ? t.onDanger : t.accentText; }

    /** A child of a focusable control takes its states, and its text changes colour with them. */
    <T extends TextView> T follow(T child, int rest, int hot, int focused) {
        child.setTextColor(colors(rest, hot, focused));
        child.setDuplicateParentStateEnabled(true);
        return child;
    }
    /** The same for an icon, whose tint is its colour. */
    <T extends ImageView> T followTint(T child, int rest, int hot, int focused) {
        child.setImageTintList(colors(rest, hot, focused));
        child.setDuplicateParentStateEnabled(true);
        return child;
    }

    // Focus.

    /**
     * Makes the view a stop for a remote, with a key that names it across render(), which
     * rebuilds every view, and gives it the desktop's look for this kind of control: its
     * rest look, its hover look while pressed or hovered, and its focus look. All three
     * are the view's background, a StateListDrawable that fades in 120 ms as the
     * desktop's colours do; the view has no foreground, and Android's own highlight is
     * off. A phone in touch mode never focuses a control, so it shows none of it; text
     * fields focus on a tap, and show the desktop's field focus then too.
     */
    <T extends View> T focus(T view, String key, Look look) { return focus(view, key, look, 0); }
    <T extends View> T focus(T view, String key, Look look, int flags) {
        view.setFocusable(true);
        view.setTag(key);
        view.setDefaultFocusHighlightEnabled(false);
        view.setBackground(background(look, flags));
        if (look == Look.TILE) scaleOnFocus(view);
        return view;
    }

    /** The background of a control with this look; see {@link #focus}. */
    StateListDrawable background(Look look, int flags) {
        boolean current = (flags & F_CURRENT) != 0;
        boolean danger = (flags & F_DANGER) != 0;
        int rest = Color.TRANSPARENT;
        int hot = Color.TRANSPARENT;
        int focused = danger || look == Look.DANGER ? t.danger : t.accent;
        int stroke = 0;
        int focusStroke = 0;
        // The border under the pointer, when it is not the one at rest.
        int hotStroke = 0;
        boolean hotStrokeSet = false;
        float radius = 8;
        switch (look) {
            case TILE:
                // The current tab is infoBg, and hovering it changes nothing; the others
                // rest on the page and take surface.
                rest = current ? t.infoBg : Color.TRANSPARENT;
                hot = current ? t.infoBg : t.surface;
                break;
            case HERO:
                // The logo as a control: the nav tile's proportions (8 on 38), at 132. It has no hover look of
                // its own on the desktop, only its press, which the logo does by scaling.
                radius = 28;
                break;
            case ROW: hot = t.surface; break;
            case MENU_ROW: hot = t.surface; radius = 6; break;
            case PILL: hot = t.surface; break;
            case LINK: radius = 6; break;
            case ICON: hot = t.surface; radius = 6; break;
            case CHIP:
                radius = 13;
                // Selected: accent, as on the desktop. With a remote, infoBg: only the
                // focus is accent then.
                rest = current ? (remote ? t.infoBg : t.accent) : t.surface;
                hot = current ? rest : t.surfaceHover;
                break;
            case BUTTON:
                if ((flags & F_SECONDARY) != 0) { rest = t.bg; stroke = t.border; hot = t.surface; }
                else { rest = t.surface; hot = t.surfaceHover; }
                break;
            case PRIMARY:
                rest = remote ? t.surface : t.accent;
                hot = t.primaryHover;
                break;
            case DANGER:
                rest = remote ? t.surface : t.danger;
                hot = t.destructiveHover;
                break;
            case FIELD:
                // The desktop's field: inputBg with an inputBorder outline that turns
                // accent while it has the focus. With a remote the fill also moves to
                // surface, which reads from further away.
                rest = t.inputBg;
                hot = t.inputBg;
                stroke = t.inputBorder;
                focused = remote ? t.surface : t.inputBg;
                focusStroke = t.accent;
                break;
            case SELECT:
                // A select box (the editor's): a field that opens a choice. As a field, and its border turns accent under the
                // pointer, as the desktop's does.
                rest = t.inputBg;
                hot = t.inputBg;
                stroke = t.inputBorder;
                hotStroke = t.accent;
                hotStrokeSet = true;
                focused = remote ? t.surface : t.inputBg;
                focusStroke = t.accent;
                break;
            case BOX:
                // A box of text that a remote scrolls, such as a license or the log: the
                // desktop's surface, radius 8; with the focus, the field's accent border.
                rest = t.surface;
                hot = t.surface;
                focused = t.surface;
                focusStroke = t.accent;
                break;
            case TOGGLE:
                radius = 99;
                break;
            default:
                break;
        }
        if ((flags & F_ROUND) != 0) radius = 99;
        if ((flags & F_BORDER) != 0) stroke = t.border;
        if (!hotStrokeSet) hotStroke = stroke;
        StateListDrawable states = new StateListDrawable();
        // The focus comes first: OK held on a focused control presses it, and it keeps its focus look.
        states.addState(new int[]{android.R.attr.state_focused}, box(focused, radius, focusStroke));
        if ((flags & F_HOLDS_FOCUS) != 0) states.addState(new int[]{android.R.attr.state_activated}, box(t.surface, radius, stroke));
        states.addState(new int[]{android.R.attr.state_pressed}, box(hot, radius, hotStroke));
        states.addState(new int[]{android.R.attr.state_hovered}, box(hot, radius, hotStroke));
        states.addState(new int[]{}, box(rest, radius, stroke));
        states.setEnterFadeDuration(FADE_MS);
        states.setExitFadeDuration(FADE_MS);
        return states;
    }
    private GradientDrawable box(int fill, float radiusDp, int stroke) {
        GradientDrawable d = shape(fill, radiusDp);
        if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    // The nav tiles grow to 1.08 while they have the focus, as the desktop's tiles do under the pointer.
    private void scaleOnFocus(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> v.animate().scaleX(hasFocus ? 1.08f : 1f).scaleY(hasFocus ? 1.08f : 1f)
            .setDuration(FADE_MS).setInterpolator(new DecelerateInterpolator(1.5f)).start());
    }

    // Controls.

    /**
     * The desktop's buttons: 32 high, radius 8, a label 14 and the label's width plus
     * 26, at least minWidth (88 in the editor, 76 in a dialog). None of them shows a
     * pressed look beyond its hover.
     */
    Button button(CharSequence caption, Kind kind, int minWidthDp, String key) {
        Button b = new Button(context);
        b.setAllCaps(false);
        b.setText(caption);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT);
        b.setLetterSpacing(0);
        b.setStateListAnimator(null);
        b.setMinWidth(dp(minWidthDp));
        b.setMinimumWidth(dp(minWidthDp));
        b.setMinHeight(dp(32));
        b.setMinimumHeight(dp(32));
        b.setPadding(dp(13), 0, dp(13), 0);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        Look look;
        int flags = 0;
        switch (kind) {
            case PRIMARY:
                look = Look.PRIMARY;
                b.setTextColor(colors(remote ? t.text : t.accentText, t.accentText, t.accentText));
                break;
            case DESTRUCTIVE:
                look = Look.DANGER;
                b.setTextColor(colors(remote ? t.danger : t.onDanger, t.onDanger, t.onDanger));
                break;
            case SECONDARY:
                look = Look.BUTTON;
                flags = F_SECONDARY;
                b.setTextColor(colors(t.text, t.text, t.accentText));
                break;
            default:
                look = Look.BUTTON;
                b.setTextColor(colors(t.text, t.text, t.accentText));
                break;
        }
        return key == null ? styled(b, look, flags) : focus(b, key, look, flags);
    }
    // A control that is no focus stop: its looks without a key.
    private <T extends View> T styled(T view, Look look, int flags) {
        view.setBackground(background(look, flags));
        return view;
    }

    /**
     * A link: words, wrap_content, so that it acts over them and a little around them.
     * Accent, which goes to the text colour and underlined under the pointer; a text link
     * the other way about, and a faint one (the footer's) too; a danger link to its lighter. With the focus: the accent
     * (danger) around them, and the words in accentText (white).
     */
    TextView link(CharSequence value, float sp, LinkKind kind, String key) {
        LinkText v = new LinkText(context);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setPadding(dp(6), dp(4), dp(6), dp(4));
        int flags = 0;
        switch (kind) {
            case DANGER:
                flags = F_DANGER;
                v.setTextColor(colors(t.danger, t.dangerLinkHover, t.onDanger));
                break;
            case TEXT:
                v.setTextColor(colors(t.text, t.accent, t.accentText));
                break;
            case FAINT:
                v.setTextColor(colors(t.textFaint, t.accent, t.accentText));
                break;
            default:
                v.setTextColor(colors(t.accent, t.text, t.accentText));
                break;
        }
        return key == null ? styled(v, Look.LINK, flags) : focus(v, key, Look.LINK, flags);
    }

    /** A link that underlines while it is pressed or hovered: the desktop's. */
    static final class LinkText extends TextView {
        LinkText(Context c) { super(c); }
        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            boolean hot = false;
            for (int state : getDrawableState()) if (state == android.R.attr.state_pressed || state == android.R.attr.state_hovered) hot = true;
            int flags = getPaintFlags();
            int next = hot ? flags | Paint.UNDERLINE_TEXT_FLAG : flags & ~Paint.UNDERLINE_TEXT_FLAG;
            if (next != flags) setPaintFlags(next);
        }
    }

    /** The desktop's Dropdown: the label does nothing; only the value and its ▾ open the choice. */
    LinearLayout dropdown(CharSequence name, CharSequence value, String key, View.OnClickListener open) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(42));
        TextView title = label(name, 14, t.text);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        gapH(r, 8);
        LinearLayout group = row();
        group.setPadding(dp(6), dp(4), dp(6), dp(4));
        TextView shown = follow(label(value, 14, t.textDim), t.textDim, t.text, t.accentText);
        shown.setSingleLine(true);
        shown.setEllipsize(TextUtils.TruncateAt.END);
        shown.setMaxWidth(dp(106));
        group.addView(shown, lp(-2, -2));
        gapH(group, 4);
        group.addView(follow(label("▾", 16, t.textDim), t.textDim, t.text, t.accentText), lp(-2, -2));
        focus(group, key, Look.PILL).setOnClickListener(open);
        // The value and its ▾ end where the page's margin does, as the desktop's do: the pill's own 6 go beyond it.
        LinearLayout.LayoutParams groupLp = lp(-2, -2);
        groupLp.rightMargin = -dp(6);
        r.addView(group, groupLp);
        return r;
    }

    /** A page's text input: 36 high, radius 8, 13. The desktop's field look. */
    EditText pageInput(CharSequence hint, String key) {
        EditText e = new EditText(context);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setTextColor(t.text);
        e.setHintTextColor(t.textFaint);
        e.setHint(hint);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setGravity(Gravity.CENTER_VERTICAL);
        return focus(e, key, Look.FIELD);
    }

    /** Field: a label, 13 in textDim, 4 above a 34 high box, radius 8, 14. */
    LinearLayout field(CharSequence name, EditText input, String key) {
        LinearLayout box = column();
        box.addView(label(name, 13, t.textDim), lp(-1, -2));
        gap(box, 4);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTextColor(t.text);
        input.setHintTextColor(t.textFaint);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setGravity(Gravity.CENTER_VERTICAL);
        focus(input, key, Look.FIELD);
        box.addView(input, lp(-1, 34));
        return box;
    }

    /**
     * The desktop's text area (the editor's certificate): a Field's label and look, and a box
     * of several lines in monospace 12, at least this high and growing with what is typed.
     */
    LinearLayout fieldArea(CharSequence name, EditText input, String key, int minHeightDp) {
        LinearLayout box = column();
        box.addView(label(name, 13, t.textDim), lp(-1, -2));
        gap(box, 4);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setTextColor(t.text);
        input.setHintTextColor(t.textFaint);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinHeight(dp(minHeightDp));
        focus(input, key, Look.FIELD);
        box.addView(input, lp(-1, -2));
        return box;
    }

    /**
     * The editor's select box (qml/Field.qml's look, CreateConfigOverlay's Protocol and Split profile): a label, 13 in textDim, over a
     * box 34 high, radius 8, on inputBg, with the value (14, the text colour) 10 in from the left and ▾ (16, textDim) 10 from the right. The
     * box is the control; its border turns accent under the pointer, as a field's does with the focus. Where the
     * value is shown is put in {@code valueOut}, for the caller to change it when a choice is made.
     */
    LinearLayout selectField(CharSequence name, CharSequence value, String key, TextView[] valueOut, View.OnClickListener open) {
        LinearLayout field = column();
        field.addView(label(name, 13, t.textDim), lp(-1, -2));
        gap(field, 4);
        LinearLayout box = row();
        box.setPadding(dp(10), 0, dp(10), 0);
        TextView shown = label(value, 14, t.text);
        shown.setSingleLine(true);
        shown.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(shown, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        box.addView(label("▾", 16, t.textDim), lp(-2, -2));
        focus(box, key, Look.SELECT).setOnClickListener(open);
        field.addView(box, lp(-1, 34));
        if (valueOut != null) valueOut[0] = shown;
        return field;
    }

    /** The editor's toggle row: its label, 13 in textDim, and the toggle, 32 high. */
    LinearLayout editorToggleRow(CharSequence title, ToggleView toggle) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(32));
        r.addView(label(title, 13, t.textDim), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        gapH(r, 8);
        r.addView(toggle, lp(-2, -2));
        return r;
    }

    /** A card's ←, 20 in textDim and the text colour under the pointer, in a cell 6 larger all round. */
    TextView backArrow(String key, CharSequence description, View.OnClickListener back) {
        TextView arrow = label("←", 20, t.textDim);
        arrow.setTextColor(colors(t.textDim, t.text, t.accentText));
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(8), dp(1), dp(8), dp(1));
        arrow.setContentDescription(description);
        arrow.setOnClickListener(back);
        return focus(arrow, key, Look.ICON);
    }

    /**
     * A card's header, 48 high: the ← 14 from the left, and 12 on from it the title, 15 in
     * Medium. The title is the header's second child, for a card that changes it.
     */
    LinearLayout cardHeader(CharSequence title, String backKey, CharSequence backDescription, View.OnClickListener back) {
        LinearLayout bar = row();
        bar.setMinimumHeight(dp(48));
        bar.setPadding(dp(6), 0, dp(14), 0);
        bar.addView(backArrow(backKey, backDescription, back), lp(-2, -2));
        TextView name = medium(title, 15, t.text);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.leftMargin = dp(4);
        bar.addView(name, p);
        return bar;
    }

    /** A button of the header, 40 by 32, radius 8, with an icon of this size in accent. */
    FrameLayout iconButton(int drawable, int sizeDp, CharSequence description, String key) {
        FrameLayout box = new FrameLayout(context);
        box.setContentDescription(description);
        ImageView icon = followTint(new ImageView(context), t.accent, t.accent, t.accentText);
        icon.setImageResource(drawable);
        box.addView(icon, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER));
        return focus(box, key, Look.PILL);
    }

    /** A small icon button, such as Home's + (22 by 22, radius 6), with an icon of this size in accent. */
    FrameLayout smallIconButton(int drawable, int iconDp, CharSequence description, String key) {
        FrameLayout box = new FrameLayout(context);
        box.setContentDescription(description);
        ImageView icon = followTint(new ImageView(context), t.accent, t.accent, t.accentText);
        icon.setImageResource(drawable);
        box.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        return focus(box, key, Look.ICON);
    }

    /**
     * A chip, 28 high, radius 13, 13, 11 in from the left; selectable ones are focus stops.
     * It is as wide as its label, or fills its row, with the label taking the room and the
     * ChipX at the end, if {@code fill}.
     */
    LinearLayout chip(CharSequence value, boolean selected, boolean selectable, String key, boolean fill) {
        LinearLayout c = new ChipRow(context);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(11), 0, dp(11), 0);
        boolean accentFill = selected && !remote;
        int rest = selected ? (remote ? t.accent : t.accentText) : t.text;
        CharSequence shown = selected && remote ? value + " ✓" : value;
        TextView name = label(shown, 13, rest);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (selectable) {
            follow(name, rest, rest, t.accentText);
            focus(c, key, Look.CHIP, selected ? F_CURRENT : 0);
        } else {
            c.setBackground(shape(accentFill ? t.accent : t.surface, 13));
        }
        c.addView(name, fill ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1) : lp(-2, -1));
        c.setMinimumHeight(dp(28));
        return c;
    }
    /**
     * A chip's row. Its label gives way to the ChipX at its end: a name too long for the row is cut with an ellipsis, as the
     * desktop cuts it (the label is no wider than the chip less 39), and the ChipX is still there to be pressed. A plain
     * LinearLayout gives the first child all the room it asks for and the last what is left, which may be none.
     */
    static final class ChipRow extends LinearLayout {
        ChipRow(Context context) { super(context); }

        @Override protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
                                                         int parentHeightMeasureSpec, int heightUsed) {
            int count = getChildCount();
            if (count > 1 && child == getChildAt(0) && getChildAt(count - 1).getLayoutParams() instanceof MarginLayoutParams) {
                MarginLayoutParams x = (MarginLayoutParams) getChildAt(count - 1).getLayoutParams();
                widthUsed += Math.max(0, x.width) + x.leftMargin + x.rightMargin;
            }
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed);
        }
    }

    /**
     * A remote reaches a ChipX from its chip with Right, and the chip from it with Left: it is inside the chip, where Android's
     * search for the next control to the right finds none.
     */
    void linkChipX(View chip, View x) {
        if (chip.getId() == View.NO_ID) chip.setId(View.generateViewId());
        if (x.getId() == View.NO_ID) x.setId(View.generateViewId());
        chip.setNextFocusRightId(x.getId());
        x.setNextFocusLeftId(chip.getId());
        // The chip's focus is the accent, and what it holds sits on it.
        chip.setOnFocusChangeListener((v, hasFocus) -> x.setSelected(hasFocus));
    }

    /**
     * The + chip that makes a new one (Split's profiles): 34 by 28, radius 13, on surface with a border, a + of 17 in accent.
     * With the focus the accent fills it, border and all.
     */
    TextView plusChip(CharSequence description, String key) {
        TextView v = label("+", 17, t.accent);
        v.setTextColor(colors(t.accent, t.accent, t.accentText));
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setMinWidth(dp(34));
        v.setMinimumWidth(dp(34));
        v.setMinHeight(dp(28));
        v.setMinimumHeight(dp(28));
        v.setContentDescription(description);
        return focus(v, key, Look.CHIP, F_BORDER);
    }

    /**
     * ChipX: the close icon, 14 in a cell 22 wide, danger under the pointer; with a remote
     * a danger circle with a white cross. A chip is 28 high, and its ChipX sits 3 from the label.
     */
    ImageView chipX(String key, boolean overAccent) {
        ImageView x = new ImageView(context);
        x.setImageResource(R.drawable.ic_close);
        x.setPadding(dp(4), dp(4), dp(4), dp(4));
        // Selected, as the chip it is in is while it has the focus (see linkChipX): the chip is then the accent, and the cross on it accentText.
        x.setImageTintList(colors(overAccent ? t.accentText : t.textDim, t.danger, t.onDanger, t.accentText));
        return focus(x, key, Look.ICON, F_DANGER | F_ROUND);
    }
    /** Where a ChipX sits in its chip: 3 from the label, and the chip's 11 of padding less 8 beyond it. */
    LinearLayout.LayoutParams chipXLp() {
        LinearLayout.LayoutParams p = lp(22, 22);
        p.leftMargin = dp(3);
        p.rightMargin = dp(-8);
        return p;
    }

    /** NoticeBox: infoBg, radius 8, 12 in warn, wrapped, 10 in from the sides and 8 above and below. */
    TextView notice(CharSequence value) {
        TextView v = label(value, 12, t.warn);
        v.setGravity(Gravity.START);
        v.setBackground(shape(t.infoBg, 8));
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        return v;
    }
    /** The warning that credentials cannot be stored: amber, a border, radius 8, 13, 10 round. */
    TextView banner(CharSequence value) {
        TextView v = label(value, 13, t.text);
        v.setGravity(Gravity.START);
        GradientDrawable d = shape(t.bannerFill, 8);
        d.setStroke(Math.max(1, dp(1)), t.bannerBorder);
        v.setBackground(d);
        v.setPadding(dp(10), dp(10), dp(10), dp(10));
        return v;
    }

    /** The desktop's Toggle with a row's label: 42 high, more if a subtitle wraps. */
    LinearLayout toggleRow(CharSequence title, CharSequence subtitle, ToggleView toggle) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(42));
        LinearLayout copy = column();
        copy.addView(label(title, 14, t.text), lp(-1, -2));
        if (subtitle != null && subtitle.length() > 0) copy.addView(label(subtitle, 12, t.textFaint), lp(-1, -2));
        r.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        gapH(r, 8);
        // The track ends where the page's margin does, as the desktop's does: the 4 its focus fills go beyond it.
        LinearLayout.LayoutParams toggleLp = lp(-2, -2);
        toggleLp.rightMargin = -dp(ToggleView.PAD_DP);
        r.addView(toggle, toggleLp);
        return r;
    }
    /** A toggle: 38 by 22 (34 by 20 small), with the focus around it, as a focus stop keyed {@code key}. */
    ToggleView toggle(boolean checked, boolean small, String key) {
        ToggleView v = new ToggleView(context, t, small);
        v.setChecked(checked);
        return focus(v, key, Look.TOGGLE);
    }

    // Home.

    /**
     * Home's config selector: the active config's name, 15 in Medium and elided at the right to the room
     * there is, and 6 later the ▾ (15), as one control that is as wide as they are. Both are the text colour, and
     * accent under the pointer, with the name underlined; with the focus, the accent around them and accentText
     * in them. Without configs it is "Add a config" alone, with no ▾.
     */
    LinearLayout selector(CharSequence name, boolean arrow, int maxNameWidthPx, String key) {
        LinearLayout box = row();
        box.setPadding(dp(6), dp(4), dp(6), dp(4));
        LinkText label = new LinkText(context);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(MEDIUM);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        if (maxNameWidthPx > 0) label.setMaxWidth(maxNameWidthPx);
        label.setTextColor(colors(t.text, t.accent, t.accentText));
        label.setDuplicateParentStateEnabled(true);
        box.addView(label, lp(-2, -2));
        if (arrow) {
            TextView triangle = follow(label("▾", 15, t.text), t.text, t.accent, t.accentText);
            LinearLayout.LayoutParams p = lp(-2, -2);
            p.leftMargin = dp(6);
            box.addView(triangle, p);
        }
        return focus(box, key, Look.LINK);
    }

    /** One of the two speed tiles at the foot of Home: 116 by 44, radius 8, on the tile colour. Not a control. */
    LinearLayout speedTile(String arrow, int arrowColor, TextView[] valueOut) {
        LinearLayout tile = row();
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(shape(t.tile, 8));
        tile.addView(label(arrow, 14, arrowColor), lp(-2, -2));
        TextView value = medium("", 15, t.text);
        LinearLayout.LayoutParams p = lp(-2, -2);
        p.leftMargin = dp(5);
        tile.addView(value, p);
        valueOut[0] = value;
        return tile;
    }

    // The Configs page's rows.

    /**
     * A pill of text on infoBg: radius 10, 20 high, 8 in from each side, 11 in Medium. A config row's
     * badge says connected (in success) or connecting… (in textDim). It follows its row's focus: accentText on
     * the translucent accentText that infoBg is on an accent row.
     */
    TextView badge(CharSequence value, int color) {
        TextView v = medium(value, 11, color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), 0, dp(8), 0);
        v.setSingleLine(true);
        follow(v, color, color, t.accentText);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(Theme.withAlpha(t.accentText, 0x29), 10));
        states.addState(new int[]{}, shape(t.infoBg, 10));
        v.setBackground(states);
        v.setDuplicateParentStateEnabled(true);
        return v;
    }

    /**
     * A config row's icon: a cell 26 wide and as high as it is given, the icon centred in it, textDim, and the
     * text colour (danger for the close icon) under the pointer. It is a focus stop of its own: with the
     * focus, an accent cell with the icon in accentText (danger and white for close). While the row it is in
     * has the focus, the row sets the cell selected, and the icon is accentText on the row's accent.
     */
    FrameLayout rowIcon(int drawable, int iconDp, CharSequence description, String key, boolean close) {
        FrameLayout cell = new FrameLayout(context);
        cell.setContentDescription(description);
        ImageView icon = new ImageView(context);
        icon.setImageResource(drawable);
        icon.setImageTintList(colors(t.textDim, close ? t.danger : t.text, close ? t.onDanger : t.accentText, t.accentText));
        icon.setDuplicateParentStateEnabled(true);
        cell.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        return focus(cell, key, Look.ICON, close ? F_DANGER : 0);
    }
}
