package nativecanvas.ui;

import java.util.Objects;
import java.util.function.Consumer;
import nativecanvas.Canvas;

/** Single-line editable text. Editing moves and deletes whole Unicode code points. */
public final class TextField extends Control {
    private String text;
    private int caret;
    private final Consumer<String> onChange;

    public TextField(String text, Consumer<String> onChange) {
        setText(text);
        this.onChange = Objects.requireNonNull(onChange);
    }

    public String text() { return text; }

    /** Replaces the value and moves the caret to the end without firing onChange. */
    public void setText(String text) {
        this.text = singleLine(text);
        caret = this.text.length();
    }

    private static String singleLine(String text) {
        StringBuilder result = new StringBuilder();
        Objects.requireNonNull(text).codePoints()
                .filter(cp -> !Character.isISOControl(cp) && cp != 0x2028 && cp != 0x2029)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    @Override public double preferredWidth() { return 160; }

    @Override public void activate() {}

    void insert(String value) {
        String input = singleLine(value);
        if (!input.isEmpty()) replace(caret, caret, input);
    }

    private void replace(int start, int end, String value) {
        text = text.substring(0, start) + value + text.substring(end);
        caret = start + value.length();
        onChange.accept(text);
    }

    void keyDown(int key) {
        switch (key) {
            case 8: // Backspace
                if (caret > 0) replace(text.offsetByCodePoints(caret, -1), caret, "");
                break;
            case 127: // Delete
                if (caret < text.length()) replace(caret, text.offsetByCodePoints(caret, 1), "");
                break;
            case 0x40000050: // Left
                if (caret > 0) caret = text.offsetByCodePoints(caret, -1);
                break;
            case 0x4000004f: // Right
                if (caret < text.length()) caret = text.offsetByCodePoints(caret, 1);
                break;
            case 0x4000004a: caret = 0; break; // Home
            case 0x4000004d: caret = text.length(); break; // End
            default: break;
        }
    }

    @Override protected void paint(Canvas canvas) {
        super.paint(canvas);
        if (width() <= 20 || height() <= 6) return;
        canvas.save();
        canvas.clipRect(x() + 8, y() + 3, width() - 16, height() - 6);
        double caretWidth = focused ? canvas.textCaretX(text, caret) : 0;
        double start = x() + 10 - Math.max(0, caretWidth - (width() - 21));
        canvas.setFillStyle(0xf3f6faff);
        canvas.fillText(text, start, y() + 24);
        if (focused) canvas.fillRect(start + caretWidth, y() + 9, 1, 19);
        canvas.restore();
    }
}
