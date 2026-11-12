package nativecanvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Encodes the wire protocol consumed by native/command_buffer.c. */
final class DrawCommandBuffer {
    static final int SET_FILL_COLOR = 1;
    static final int SET_STROKE_COLOR = 2;
    static final int FILL_RECT = 3;
    static final int STROKE_RECT = 4;
    static final int CLEAR_RECT = 5;
    static final int BEGIN_PATH = 6;
    static final int MOVE_TO = 7;
    static final int LINE_TO = 8;
    static final int CLOSE_PATH = 9;
    static final int FILL_PATH = 10;
    static final int STROKE_PATH = 11;
    static final int SAVE = 12;
    static final int RESTORE = 13;
    static final int TRANSLATE = 14;
    static final int SCALE = 15;
    static final int ROTATE = 16;
    static final int SET_LINE_WIDTH = 17;
    static final int ADD_RECT = 18;
    static final int CLIP_PATH = 19;
    static final int CUBIC_TO = 20;
    static final int SET_FONT = 21;
    static final int FILL_TEXT = 22;
    static final int IMAGE = 23;
    static final int CLIP_RECT = 24;
    static final int SET_FILL_LINEAR = 25;
    static final int SET_STROKE_LINEAR = 26;
    static final int SET_FILL_RADIAL = 27;
    static final int SET_STROKE_RADIAL = 28;
    static final int SET_ANTIALIAS = 29;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.nativeOrder());
    private final Runnable checkOpen;
    private final Runnable flush;

    DrawCommandBuffer(Runnable checkOpen, Runnable flush) {
        this.checkOpen = checkOpen;
        this.flush = flush;
    }

    int size() { return buffer.position(); }

    ByteBuffer take() {
        buffer.clear();
        return buffer;
    }

    void opcode(int op) { begin(op, 0); }

    void color(int op, int rgba) { begin(op, 4).putInt(rgba); }

    void scalar(int op, double value) { begin(op, 8).putDouble(value); }

    void point(int op, double x, double y) { begin(op, 16).putDouble(x).putDouble(y); }

    void rectangle(int op, double x, double y, double width, double height) {
        begin(op, 32).putDouble(x).putDouble(y).putDouble(width).putDouble(height);
    }

    void cubic(double x1, double y1, double x2, double y2, double x, double y) {
        begin(CUBIC_TO, 48).putDouble(x1).putDouble(y1).putDouble(x2).putDouble(y2)
                .putDouble(x).putDouble(y);
    }

    void gradient(int op, double[] coordinates, List<Canvas.Stop> stops) {
        int header = 4 + 8 * coordinates.length;
        if (stops.size() > (buffer.capacity() - 1 - header) / 12)
            throw new IllegalArgumentException("Too many gradient stops");
        ByteBuffer payload = begin(op, header + stops.size() * 12);
        for (double coordinate : coordinates) payload.putDouble(coordinate);
        payload.putInt(stops.size());
        for (Canvas.Stop stop : stops) payload.putDouble(stop.offset).putInt(stop.rgba);
    }

    void font(String description) { text(SET_FONT, description, 0); }

    void text(String value, double x, double y) {
        text(FILL_TEXT, value, 16).putDouble(x).putDouble(y);
    }

    void image(long image, double x, double y, double width, double height) {
        begin(IMAGE, 40).putLong(image).putDouble(x).putDouble(y).putDouble(width).putDouble(height);
    }

    private ByteBuffer text(int op, String value, int coordinateBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > buffer.capacity() - 5 - coordinateBytes)
            throw new IllegalArgumentException("String exceeds command buffer");
        return begin(op, 4 + bytes.length + coordinateBytes).putInt(bytes.length).put(bytes);
    }

    private ByteBuffer begin(int op, int payloadSize) {
        checkOpen.run();
        if (buffer.remaining() < payloadSize + 1) flush.run();
        return buffer.put((byte) op);
    }
}
