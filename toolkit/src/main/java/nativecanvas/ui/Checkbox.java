package nativecanvas.ui;

import java.util.Objects;
import java.util.function.Consumer;
import nativecanvas.Canvas;

public final class Checkbox extends Control {
    private final String text;
    private final Consumer<Boolean> onChange;
    private boolean checked;

    public Checkbox(String text, boolean checked, Consumer<Boolean> onChange) {
        this.text = Objects.requireNonNull(text);
        this.checked = checked;
        this.onChange = Objects.requireNonNull(onChange);
    }

    public boolean isChecked() { return checked; }

    /** Updates the value without firing the user action callback. */
    public void setChecked(boolean checked) { this.checked = checked; }

    @Override public void activate() {
        checked = !checked;
        onChange.accept(checked);
    }

    @Override public double preferredWidth() { return 46 + textWidth(text); }

    @Override protected void paint(Canvas canvas) {
        super.paint(canvas);
        canvas.setFillStyle(checked ? 0x7acffaff : 0x18212cff);
        canvas.fillRect(x() + 10, y() + 11, 16, 16);
        canvas.setStrokeStyle(0x7acffaff);
        canvas.setLineWidth(1);
        canvas.strokeRect(x() + 10.5, y() + 11.5, 15, 15);
        paintText(canvas, text, 36);
    }
}
