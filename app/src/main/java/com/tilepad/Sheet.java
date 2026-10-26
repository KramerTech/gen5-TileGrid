package com.tilepad;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/** One modal popup at a time: a dimmed scrim with a centered card. Tapping the scrim dismisses. */
final class Sheet {
    interface Listener { void onSheetChanged(); }

    private final Ui ui;
    private final FrameLayout host;
    private final Listener listener;
    private FrameLayout scrim;
    private Runnable onDismiss;
    private final List<View> targets = new ArrayList<>();

    Sheet(Ui ui, FrameLayout host, Listener listener) {
        this.ui = ui;
        this.host = host;
        this.listener = listener;
    }

    boolean isShowing() { return scrim != null; }

    /** Buttons the TUNE knob cycles through while the sheet is up. */
    List<View> targets() { return targets; }

    void setTargets(List<View> views) {
        targets.clear();
        targets.addAll(views);
        listener.onSheetChanged();
    }

    void show(View card, int widthDp, Runnable dismissed) {
        dismissNow();
        onDismiss = dismissed;
        scrim = new FrameLayout(ui.context);
        scrim.setBackgroundColor(Ui.SCRIM);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        card.setClickable(true); // swallow taps so only the scrim dismisses
        Ui.background(card, ui.outlined(0xff161a21, 28, 1, Ui.LINE));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
            widthDp > 0 ? ui.dp(widthDp) : -1, -2, Gravity.CENTER);
        if (widthDp <= 0) p.leftMargin = p.rightMargin = ui.dp(24);
        scrim.addView(card, p);
        host.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(140).start();
        card.setScaleX(0.96f); card.setScaleY(0.96f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
        targets.clear();
        listener.onSheetChanged();
    }

    void dismiss() {
        if (scrim == null) return;
        final FrameLayout closing = scrim;
        Runnable callback = onDismiss;
        scrim = null;
        onDismiss = null;
        targets.clear();
        closing.setClickable(false);
        closing.animate().alpha(0f).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() { host.removeView(closing); }
        }).start();
        listener.onSheetChanged();
        if (callback != null) callback.run();
    }

    private void dismissNow() {
        if (scrim == null) return;
        host.removeView(scrim);
        Runnable callback = onDismiss;
        scrim = null;
        onDismiss = null;
        if (callback != null) callback.run();
    }
}
