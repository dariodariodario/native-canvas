package nativecanvas.ui;

import java.util.Objects;
import nativecanvas.Canvas;

public final class Label extends Widget {
    private String text;
    private String font = "Sans";
    private double fontSize = 15;
    private int color = 0xf3f6faff;

    public Label(String text) { setText(text); }
    public String text() { return text; }
    public void setText(String text) { this.text = Objects.requireNonNull(text); }
    /** Sets the Pango family/style and size in logical pixels. */
    public Label setFont(String familyAndStyle, double pixels) {
        if (!Double.isFinite(pixels) || pixels <= 0) throw new IllegalArgumentException("Invalid font size");
        font = Objects.requireNonNull(familyAndStyle);
        fontSize = pixels;
        return this;
    }

    public Label setColor(int rgba) {
        color = rgba;
        return this;
    }

    @Override public double preferredHeight(double width) { return 11 + fontSize; }

    @Override public double preferredWidth() { return 20 + textWidth(text) * (fontSize / 15); }

    @Override protected void paint(Canvas canvas) {
        canvas.save();
        canvas.setFont(font + " " + fontSize + "px");
        canvas.setFillStyle(color);
        canvas.fillText(text, x() + 10, y() + 4 + fontSize);
        canvas.restore();
    }
}
