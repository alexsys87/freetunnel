package org.freetunnel.android;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

/**
 * One overlay in the window: a backdrop that covers all of it, and a panel on it, placed
 * as the desktop places it. MainActivity.showOverlay is what makes one and adds it.
 *
 * <ul>
 * <li>CARD (the editor, the app picker, Receive from phone): centred across, at least 40
 * from the top, as wide as asked and 14 from the window's sides at most, 52 shorter than
 * the window at most.
 * <li>CONFIRM: centred both ways, 12 from the sides at most.
 * <li>POPUP (a dropdown, a menu, the select popup): under the control it belongs to,
 * 8 inside the window, flipped above when it does not fit below, and on the larger side,
 * scrolling, when it fits neither.
 * </ul>
 *
 * The panel is laid out after the page behind it, so that where the control is, after the
 * page is rebuilt, is known by then: requestLayout() puts a popup back under it.
 */
final class OverlayLayer extends ViewGroup {
    enum Kind { CARD, CONFIRM, POPUP }

    /** Where a popup goes against the control it belongs to. */
    static final int UNDER_RIGHT_EDGE = 0;
    static final int UNDER_CENTRE_OF_CONTROL = 1;
    static final int UNDER_CENTRE_OF_WINDOW = 2;

    /** The control's rectangle in this layer's own coordinates, or null where it is gone. */
    interface Anchor { Rect rect(View layer); }

    private final Kind kind;
    private final View backdrop;
    private final View panel;
    private final float density;
    private final Rect safe = new Rect();
    private int preferredWidth;
    private int placement = UNDER_RIGHT_EDGE;
    private int gap;
    private Anchor anchor;

    OverlayLayer(Context context, Kind kind, View backdrop, View panel) {
        super(context);
        this.kind = kind;
        this.backdrop = backdrop;
        this.panel = panel;
        this.density = context.getResources().getDisplayMetrics().density;
        addView(backdrop);
        addView(panel);
    }

    View backdrop() { return backdrop; }
    View panel() { return panel; }
    Kind kind() { return kind; }

    /** What the window keeps clear of at its edges: the status bar, and a TV's overscan. */
    void setSafe(int left, int top, int right, int bottom) {
        safe.set(left, top, right, bottom);
        requestLayout();
    }
    /** A card's width in pixels, or 0 for as wide as it holds. */
    void setPreferredWidth(int px) { preferredWidth = px; requestLayout(); }
    void setAnchor(Anchor anchor, int placement, int gapPx) {
        this.anchor = anchor;
        this.placement = placement;
        this.gap = gapPx;
        requestLayout();
    }

    private int dp(float n) { return Math.round(n * density); }

    private void measurePanel(int w, int h, int forcedHeight) {
        int availableWidth = w - safe.left - safe.right;
        int availableHeight = h - safe.top - safe.bottom;
        int side = dp(kind == Kind.CARD ? 14 : kind == Kind.CONFIRM ? 12 : 8);
        int maxWidth = Math.max(0, availableWidth - 2 * side);
        int maxHeight = Math.max(0, availableHeight - dp(kind == Kind.CARD ? 52 : kind == Kind.CONFIRM ? 24 : 16));
        int widthSpec = preferredWidth > 0
            ? MeasureSpec.makeMeasureSpec(Math.min(preferredWidth, maxWidth), MeasureSpec.EXACTLY)
            : MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
        int heightSpec = forcedHeight >= 0
            ? MeasureSpec.makeMeasureSpec(forcedHeight, MeasureSpec.EXACTLY)
            : MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        panel.measure(widthSpec, heightSpec);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        backdrop.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        measurePanel(w, h, -1);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        backdrop.layout(0, 0, w, h);
        int panelWidth = panel.getMeasuredWidth();
        int panelHeight = panel.getMeasuredHeight();
        int availableWidth = w - safe.left - safe.right;
        int availableHeight = h - safe.top - safe.bottom;
        int x = safe.left + (availableWidth - panelWidth) / 2;
        int y;
        if (kind == Kind.CARD) {
            y = safe.top + Math.max(dp(40), (availableHeight - panelHeight) / 2);
        } else if (kind == Kind.CONFIRM) {
            y = safe.top + (availableHeight - panelHeight) / 2;
        } else {
            Rect a = anchor == null ? null : anchor.rect(this);
            int margin = dp(8);
            if (a == null) {
                y = safe.top + (availableHeight - panelHeight) / 2;
            } else {
                if (placement == UNDER_RIGHT_EDGE) x = a.right - panelWidth;
                else if (placement == UNDER_CENTRE_OF_CONTROL) x = a.centerX() - panelWidth / 2;
                x = Math.max(safe.left + margin, Math.min(w - safe.right - margin - panelWidth, x));
                int below = (h - safe.bottom - margin) - (a.bottom + gap);
                int above = (a.top - gap) - (safe.top + margin);
                if (panelHeight <= below) {
                    y = a.bottom + gap;
                } else if (panelHeight <= above) {
                    y = a.top - gap - panelHeight;
                } else {
                    // Neither side has room for all of it: the larger one, and it scrolls.
                    boolean underneath = below >= above;
                    panelHeight = Math.max(0, underneath ? below : above);
                    measurePanel(w, h, panelHeight);
                    panelWidth = panel.getMeasuredWidth();
                    y = underneath ? a.bottom + gap : a.top - gap - panelHeight;
                }
            }
        }
        panel.layout(x, y, x + panelWidth, y + panelHeight);
    }
}
