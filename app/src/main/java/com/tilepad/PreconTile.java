package com.tilepad;

/** One-button battery preconditioning; all state comes from the service's snapshot. */
final class PreconTile extends StatusTile {
    PreconTile(Ui ui, TileSpec spec, EditListener edits) {
        super(ui, spec, edits, Glyph.Kind.BATTERY_HEAT, "Precondition");
        bind(PreconditioningSnapshot.checking());
    }

    void bind(PreconditioningSnapshot s) {
        int color = Ui.tone(PreconPresenter.tone(s));
        glyph.setColor(color);
        spinner.setColor(color);
        spinner.setSpinning(PreconPresenter.busy(s));
        headline.setText(PreconPresenter.headline(s));
        boolean off = (s.state == PreconditioningSnapshot.State.IDLE
            || s.state == PreconditioningSnapshot.State.ENDED) && s.indicator != 1;
        headline.setTextColor(off ? Ui.TEXT : color);
        show(detail, PreconPresenter.detail(s));
        show(meta, PreconPresenter.hint(s));
        String indicator = PreconPresenter.indicator(s);
        setPill(indicator, s.indicator == 1 ? Ui.GREEN : Ui.MUTED);
        setContentDescription("Battery preconditioning, " + s.title() + ". " + s.detail);
    }
}
