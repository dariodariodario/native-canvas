package nativecanvas.ui;

import java.util.Objects;
import nativecanvas.Canvas;

public final class Button extends Control {
    private final String text;
    private final Runnable action;

    public Button(String text, Runnable action) {
        this.text = Objects.requireNonNull(text);
        this.action = Objects.requireNonNull(action);
    }

    @Override public void activate() { action.run(); }

    @Override public double preferredWidth() { return 20 + textWidth(text); }

    @Override protected void paint(Canvas canvas) {
        super.paint(canvas);
        paintText(canvas, text, 10);
    }
}
