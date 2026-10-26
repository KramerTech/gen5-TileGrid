package com.tilepad;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Popup body: a status icon (or spinner), title, message, and a row of buttons. */
final class StatusCard extends LinearLayout {
    static final class Action {
        final String label;
        final int fill;
        final Runnable run;
        Action(String label, int fill, Runnable run) { this.label = label; this.fill = fill; this.run = run; }
    }

    private final Ui ui;
    private final FrameLayout badge;
    private final SpinnerView spinner;
    private final ImageView glyphView;
    private Glyph.Kind shownKind;
    private int shownColor;
    private final TextView title, body, meta;
    private final LinearLayout buttons;
    private String buttonKey = "";

    StatusCard(Ui ui) {
        super(ui.context);
        this.ui = ui;
        setOrientation(VERTICAL);
        setPadding(ui.dp(32), ui.dp(28), ui.dp(28), ui.dp(24));

        LinearLayout top = ui.row();
        badge = new FrameLayout(ui.context);
        spinner = new SpinnerView(ui.context, ui.dp(4));
        badge.addView(spinner, new FrameLayout.LayoutParams(-1, -1));
        glyphView = new ImageView(ui.context);
        badge.addView(glyphView, new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER));
        top.addView(badge, new LayoutParams(ui.dp(76), ui.dp(76)));

        LinearLayout text = ui.column();
        title = ui.text("", 26, Ui.TEXT, true);
        body = ui.text("", 18, Ui.MUTED, false);
        body.setMaxLines(4);
        body.setLineSpacing(0, 1.15f);
        meta = ui.text("", 16, Ui.MUTED, false);
        text.addView(title);
        LayoutParams bp = new LayoutParams(-1, -2); bp.topMargin = ui.dp(10);
        text.addView(body, bp);
        LayoutParams mp = new LayoutParams(-1, -2); mp.topMargin = ui.dp(10);
        text.addView(meta, mp);
        LayoutParams tp = new LayoutParams(0, -2, 1f);
        tp.leftMargin = ui.dp(24);
        tp.gravity = Gravity.CENTER_VERTICAL;
        top.addView(text, tp);
        addView(top, new LayoutParams(-1, -2));

        buttons = ui.row();
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LayoutParams rp = new LayoutParams(-1, ui.dp(60));
        rp.topMargin = ui.dp(26);
        addView(buttons, rp);
    }

    void set(Glyph.Kind kind, int color, boolean spinning, String titleText, String bodyText, String metaText) {
        if (kind != shownKind || color != shownColor) {
            glyphView.setImageDrawable(kind == null ? null : new Glyph(kind, color, ui.dp(40)));
            shownKind = kind;
            shownColor = color;
        }
        spinner.setColor(color);
        spinner.setSpinning(spinning);
        Ui.background(badge, spinning ? null : ui.circle(Ui.alpha(color, 0.16f)));
        title.setText(titleText);
        body.setText(bodyText == null ? "" : bodyText);
        body.setVisibility(bodyText == null || bodyText.isEmpty() ? GONE : VISIBLE);
        meta.setText(metaText == null ? "" : metaText);
        meta.setVisibility(metaText == null || metaText.isEmpty() ? GONE : VISIBLE);
    }

    /**
     * Replaces the buttons only when their labels change, so a popup refreshed every second
     * keeps its views (and the TUNE highlight) stable. Returns the button views.
     */
    List<View> setActions(Action... actions) {
        StringBuilder key = new StringBuilder();
        for (Action a : actions) key.append(a.label).append('|').append(a.fill).append(';');
        if (!key.toString().equals(buttonKey)) {
            buttonKey = key.toString();
            buttons.removeAllViews();
            for (final Action a : actions) {
                int pressed = a.fill == Ui.SURFACE ? Ui.SURFACE_PRESSED : Ui.alpha(a.fill, 0.75f);
                LinearLayout b = ui.button(a.label, null, a.fill, pressed, Ui.TEXT);
                b.setTag(a);
                LayoutParams p = new LayoutParams(-2, -1);
                p.leftMargin = ui.dp(14);
                buttons.addView(b, p);
            }
        }
        List<View> views = new ArrayList<>();
        for (int i = 0; i < buttons.getChildCount(); i++) {
            final View b = buttons.getChildAt(i);
            final Action a = actions[i];
            b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { a.run.run(); } });
            views.add(b);
        }
        return views;
    }
}
