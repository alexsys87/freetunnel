package org.freetunnel.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

/**
 * How Home's logo moves, as the desktop's does (qml/pages/HomePage.qml), and what it keeps across render(), which
 * makes every view anew while an animation goes on:
 *
 * <ul>
 * <li>its opacity follows the state in 220 ms: 0.5 off, 0.7 while connecting or disconnecting, 1.0 connected;
 * <li>with no session it sits 12 lower, and when one starts it rises over 280 ms, easing out, while the line under it
 * (the status and, when blocked, Reconnect) takes the room it needs over the same 280 ms, so that the selector
 * below does not jump;
 * <li>while connecting or disconnecting it pulses, to 1.05 and then between 0.97 and 1.05, 750 ms each way and
 * sine-eased, and eases back to 1.0 in 120 ms when it stops;
 * <li>a press scales it to 0.96 in 120 ms, easing out, and it comes back the same way.
 * </ul>
 *
 * <p>Each value is held here and applied to whatever views were last given to {@link #attach}, so a render() that
 * replaces the views carries on from where the value was, and a state that has not changed starts nothing again.
 */
final class HeroMotion {
    /** The size and place the pulse goes through: up to 1.05 first, then between 0.97 and 1.05. */
    private static final float PULSE_HIGH = 1.05f;
    private static final float PULSE_LOW = 0.97f;
    private static final float PRESSED = 0.96f;

    private interface Apply { void set(float value); }

    /** One animated value: where it is, where it is going, and what it moves. */
    private static final class Prop {
        float value;
        float target;
        ValueAnimator animator;
        Apply apply = v -> { };

        Prop(float value) { this.value = value; this.target = value; }

        void bind(Apply next) { apply = next; next.set(value); }

        void snap(float to) {
            cancel();
            value = to;
            target = to;
            apply.set(to);
        }

        void cancel() {
            if (animator != null) { animator.removeAllListeners(); animator.cancel(); animator = null; }
        }

        void to(float to, long durationMs, TimeInterpolator interpolator) {
            // Already there, or already on its way: not started again by a render() that changed nothing.
            if (to == target && (animator != null || value == to)) return;
            cancel();
            target = to;
            if (durationMs <= 0 || value == to) { snap(to); return; }
            ValueAnimator a = ValueAnimator.ofFloat(value, to);
            a.setDuration(durationMs);
            a.setInterpolator(interpolator);
            a.addUpdateListener(x -> { value = (Float) x.getAnimatedValue(); apply.set(value); });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (animator == a) { animator = null; value = target; apply.set(value); }
                }
            });
            animator = a;
            a.start();
        }
    }

    private final Prop alpha = new Prop(HomeState.OFF);
    private final Prop rise = new Prop(0f);
    private final Prop room = new Prop(0f);
    private final Prop statusAlpha = new Prop(0f);
    private final Prop press = new Prop(1f);
    private final Prop pulse = new Prop(1f);
    private ValueAnimator loop;
    private boolean pulsing;

    private View box;

    /** The views are gone (another page, or the window): nothing may go on animating them. */
    void release() {
        for (Prop p : new Prop[]{alpha, rise, room, statusAlpha, press, pulse}) { p.cancel(); p.apply = v -> { }; }
        stopLoop();
        box = null;
    }

    /**
     * Shows Home's logo for a state, in these views.
     *
     * @param box the 132 by 132 control, which rises, pulses and shrinks under a press
     * @param logo the picture in it, whose opacity is the state's
     * @param line the room under the logo for the status and Reconnect, as a view whose height is set here
     * @param status the status line, which fades in
     * @param riseOffsetPx how far down the logo sits with no session
     * @param lineHeightPx the room the line takes while there is a session: 0 where there is none
     * @param animate false for a page that has just been built, which starts in its state; true for a render() of
     * the page that is already showing, which moves from where it was
     */
    void attach(View box, ImageView logo, View line, View status, float riseOffsetPx, int lineHeightPx,
                boolean sessionActive, float alphaTarget, boolean pulseNow, boolean animate) {
        this.box = box;
        alpha.bind(v -> logo.setAlpha(v));
        rise.bind(v -> box.setTranslationY((1f - v) * riseOffsetPx));
        room.bind(v -> {
            ViewGroup.LayoutParams lp = line.getLayoutParams();
            int height = Math.round(v);
            if (lp.height != height) { lp.height = height; line.setLayoutParams(lp); }
        });
        statusAlpha.bind(v -> status.setAlpha(v));
        press.bind(v -> scale());
        pulse.bind(v -> scale());
        long fade = animate ? 220 : 0;
        long glide = animate ? 280 : 0;
        DecelerateInterpolator outCubic = new DecelerateInterpolator(1.5f);
        alpha.to(alphaTarget, fade, new LinearInterpolator());
        rise.to(sessionActive ? 1f : 0f, glide, outCubic);
        room.to(sessionActive ? lineHeightPx : 0f, glide, outCubic);
        statusAlpha.to(sessionActive ? 1f : 0f, fade, new LinearInterpolator());
        pulseTo(pulseNow);
    }

    private void scale() {
        if (box == null) return;
        float s = pulse.value * press.value;
        box.setScaleX(s);
        box.setScaleY(s);
    }

    /** A press on the logo, or the OK of a remote on it: 0.96, and back to 1 when it ends. */
    void press(boolean down) {
        press.to(down ? PRESSED : 1f, 120, new DecelerateInterpolator(1.5f));
    }

    // The pulse is one up to 1.05 and then to 0.97 and back for as long as it goes on; stopped anywhere in its swing,
    // it eases back to 1 in 120 ms.
    private void pulseTo(boolean on) {
        if (on == pulsing) return;
        pulsing = on;
        if (on) {
            pulse.cancel();
            ValueAnimator first = ValueAnimator.ofFloat(pulse.value, PULSE_HIGH);
            first.setDuration(750);
            first.setInterpolator(new AccelerateDecelerateInterpolator());
            first.addUpdateListener(x -> { pulse.value = pulse.target = (Float) x.getAnimatedValue(); pulse.apply.set(pulse.value); });
            first.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(Animator animation) { if (!cancelled && pulsing && loop == first) startLoop(); }
            });
            loop = first;
            first.start();
        } else {
            stopLoop();
            pulse.to(1f, 120, new DecelerateInterpolator(1.5f));
        }
    }

    private void startLoop() {
        ValueAnimator swing = ValueAnimator.ofFloat(PULSE_HIGH, PULSE_LOW);
        swing.setDuration(750);
        swing.setInterpolator(new AccelerateDecelerateInterpolator());
        swing.setRepeatCount(ValueAnimator.INFINITE);
        swing.setRepeatMode(ValueAnimator.REVERSE);
        swing.addUpdateListener(x -> { pulse.value = pulse.target = (Float) x.getAnimatedValue(); pulse.apply.set(pulse.value); });
        loop = swing;
        swing.start();
    }

    private void stopLoop() {
        pulsing = false;
        if (loop != null) { loop.removeAllListeners(); loop.cancel(); loop = null; }
    }

    /** Whether the logo is pulsing now (for tests). */
    boolean isPulsing() { return pulsing; }
    float alphaNow() { return alpha.value; }
    float riseNow() { return rise.value; }
    int roomNow() { return Math.round(room.value); }
    float scaleNow() { return pulse.value * press.value; }
}
