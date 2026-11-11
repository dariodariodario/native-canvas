package nativecanvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Canvas-style drawing into a persistent backing store. Use on the window thread. */
public final class Canvas implements AutoCloseable {
    static { System.loadLibrary("nativecanvas"); }

    private final Thread owner = Thread.currentThread();
    private final ByteBuffer commands = ByteBuffer.allocateDirect(256 * 1024)
            .order(ByteOrder.nativeOrder());
    private final Set<Image> images = new HashSet<>();
    private long handle;
    private int width, height;
    private Runnable liveResizeRedraw;
    private boolean redrawingDuringResize;

    public Canvas(String title, int width, int height) {
        this(title, width, height, false);
    }

    /** Opens a window with optional user resizing. */
    public Canvas(String title, int width, int height, boolean resizable) {
        this(utf8(title), width, height, resizable);
    }

    public static Canvas offscreen(int width, int height) {
        return new Canvas((byte[]) null, width, height, false);
    }

    private Canvas(byte[] title, int width, int height, boolean resizable) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid canvas size");
        handle = open(title, width, height, resizable);
        updateSize(logicalSize(handle));
    }

    /** Current logical width, updated while polling window events. */
    public int width() { return width; }

    /** Current logical height, updated while polling window events. */
    public int height() { return height; }

    /** Requests a new logical window size. Poll events to apply it to the backing store. */
    public void setWindowSize(int width, int height) {
        checkOutsideResize();
        checkOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid canvas size");
        requestWindowSize(handle, width, height);
    }

    /**
     * Registers a drawing callback for live resize. Canvas resizes and presents around it.
     * Pass null to remove it. The callback must only draw, without polling or closing.
     */
    public void setLiveResizeRedraw(Runnable redraw) {
        checkOpen();
        checkOutsideResize();
        watchResize(handle, redraw == null ? null : this);
        liveResizeRedraw = redraw;
    }

    private void redrawDuringResize() {
        redrawingDuringResize = true;
        try {
            applyResize();
            liveResizeRedraw.run();
            present();
        } finally {
            redrawingDuringResize = false;
        }
    }

    private void checkOutsideResize() {
        if (redrawingDuringResize) throw new IllegalStateException("Operation during live resize redraw");
    }

    private void updateSize(long size) {
        width = (int) (size >>> 32);
        height = (int) size;
    }

    private void applyResize() {
        flush();
        updateSize(resizeBacking(handle));
    }

    private static byte[] utf8(String value) {
        if (value.indexOf(0) >= 0) throw new IllegalArgumentException("Embedded NUL");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong canvas thread");
    }

    private void checkOpen() {
        checkThread();
        if (handle == 0) throw new IllegalStateException("Canvas is closed");
    }

    private void command(int opcode, int bytes) {
        checkOpen();
        if (commands.remaining() < bytes + 1) flush();
        commands.put((byte) opcode);
    }

    private void point(int opcode, double x, double y) {
        command(opcode, 16);
        commands.putDouble(x).putDouble(y);
    }

    private void box(int opcode, double x, double y, double w, double h) {
        command(opcode, 32);
        commands.putDouble(x).putDouble(y).putDouble(w).putDouble(h);
    }

    /** Colors use 0xRRGGBBAA. */
    public void setFillStyle(int rgba) { command(1, 4); commands.putInt(rgba); }
    public void setStrokeStyle(int rgba) { command(2, 4); commands.putInt(rgba); }
    public static Gradient linearGradient(double x0, double y0, double x1, double y1) {
        return new Gradient(false, x0, y0, x1, y1);
    }

    public static Gradient radialGradient(double x0, double y0, double r0,
                                          double x1, double y1, double r1) {
        if (r0 < 0 || r1 < 0) throw new IllegalArgumentException("Negative gradient radius");
        return new Gradient(true, x0, y0, r0, x1, y1, r1);
    }

    public static final class Gradient {
        private final boolean radial;
        private final double[] coordinates;
        private final List<Stop> stops = new ArrayList<>();

        private Gradient(boolean radial, double... coordinates) {
            for (double coordinate : coordinates)
                if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Invalid gradient coordinate");
            this.radial = radial;
            this.coordinates = coordinates;
        }

        /** Adds a stop with an offset from 0 to 1 and a color in 0xRRGGBBAA. */
        public Gradient addColorStop(double offset, int rgba) {
            if (!(offset >= 0 && offset <= 1)) throw new IllegalArgumentException("Invalid stop offset");
            stops.add(new Stop(offset, rgba));
            return this;
        }
    }

    private static final class Stop {
        final double offset;
        final int rgba;

        Stop(double offset, int rgba) {
            this.offset = offset;
            this.rgba = rgba;
        }
    }

    /** Snapshots the gradient stops. Coordinates are interpreted in drawing user space. */
    public void setFillStyle(Gradient gradient) { gradientStyle(gradient, false); }

    /** Snapshots the gradient stops. Coordinates are interpreted in drawing user space. */
    public void setStrokeStyle(Gradient gradient) { gradientStyle(gradient, true); }

    private void gradientStyle(Gradient gradient, boolean stroke) {
        int header = 4 + 8 * gradient.coordinates.length;
        int count = gradient.stops.size();
        if (count > (commands.capacity() - 1 - header) / 12)
            throw new IllegalArgumentException("Too many gradient stops");
        command(25 + (gradient.radial ? 2 : 0) + (stroke ? 1 : 0), header + count * 12);
        for (double coordinate : gradient.coordinates) commands.putDouble(coordinate);
        commands.putInt(count);
        for (Stop stop : gradient.stops) commands.putDouble(stop.offset).putInt(stop.rgba);
    }

    public void fillRect(double x, double y, double w, double h) { box(3, x, y, w, h); }
    public void strokeRect(double x, double y, double w, double h) { box(4, x, y, w, h); }
    public void clearRect(double x, double y, double w, double h) { box(5, x, y, w, h); }
    public void beginPath() { command(6, 0); }
    public void moveTo(double x, double y) { point(7, x, y); }
    public void lineTo(double x, double y) { point(8, x, y); }
    public void closePath() { command(9, 0); }
    public void fill() { command(10, 0); }
    public void stroke() { command(11, 0); }
    public void save() { command(12, 0); }
    public void restore() { command(13, 0); }
    public void translate(double x, double y) { point(14, x, y); }
    public void scale(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x == 0 || y == 0) return;
        point(15, x, y);
    }
    public void rotate(double radians) { command(16, 8); commands.putDouble(radians); }
    public void setLineWidth(double width) {
        if (width > 0 && Double.isFinite(width)) { command(17, 8); commands.putDouble(width); }
    }
    public void rect(double x, double y, double w, double h) { box(18, x, y, w, h); }
    public void clip() { command(19, 0); }

    /** Intersects the clip with a rectangle while preserving the current path. */
    public void clipRect(double x, double y, double width, double height) {
        box(24, x, y, width, height);
    }
    public void bezierCurveTo(double x1, double y1, double x2, double y2, double x, double y) {
        command(20, 48);
        commands.putDouble(x1).putDouble(y1).putDouble(x2).putDouble(y2).putDouble(x).putDouble(y);
    }

    private void stringCommand(int opcode, String value, int extraBytes) {
        byte[] bytes = utf8(value);
        if (bytes.length > commands.capacity() - 5 - extraBytes)
            throw new IllegalArgumentException("String exceeds command buffer");
        command(opcode, 4 + bytes.length + extraBytes);
        commands.putInt(bytes.length).put(bytes);
    }

    /** Sets a Pango font description, for example "Sans Bold 18px". */
    public void setFont(String description) { stringCommand(21, description, 0); }

    /** Draws text at the specified alphabetic baseline. */
    public void fillText(String text, double x, double y) {
        stringCommand(22, text, 16);
        commands.putDouble(x).putDouble(y);
    }

    /** Geometric bounds of the current path in current user coordinates. */
    public Bounds pathBounds() { return bounds(0); }

    /** Bounds of the area that fill() would cover, before clipping. */
    public Bounds fillBounds() { return bounds(1); }

    /** Bounds of the area that stroke() would cover, before clipping. */
    public Bounds strokeBounds() { return bounds(2); }

    /** Bounds of the current clip in current user coordinates. */
    public Bounds clipBounds() { return bounds(3); }

    private Bounds bounds(int kind) {
        flush();
        double[] result = new double[4];
        extents(handle, kind, result);
        return new Bounds(result[0], result[1], result[2], result[3]);
    }

    public static final class Bounds {
        public final double x, y, width, height;

        private Bounds(double left, double top, double right, double bottom) {
            x = left;
            y = top;
            width = right - left;
            height = bottom - top;
        }
    }

    /** Measures text in canvas units using the current font, without drawing it. */
    public TextMetrics measureText(String text) {
        byte[] bytes = utf8(text);
        flush();
        double[] metrics = new double[7];
        measure(handle, bytes, metrics);
        return new TextMetrics(metrics);
    }

    public static final class TextMetrics {
        public final double width;
        public final double height;
        public final double baseline;
        /** Ink bounds relative to the x coordinate and baseline passed to fillText. */
        public final double inkX, inkY, inkWidth, inkHeight;

        private TextMetrics(double[] metrics) {
            width = metrics[0];
            height = metrics[1];
            baseline = metrics[2];
            inkX = metrics[3];
            inkY = metrics[4];
            inkWidth = metrics[5];
            inkHeight = metrics[6];
        }
    }

    /** Loads a PNG for repeated drawing on this canvas. */
    public Image loadImage(String filename) {
        checkOpen();
        int[] size = new int[2];
        long imageHandle = loadPng(utf8(filename), size);
        Image image = new Image(imageHandle, size[0], size[1]);
        images.add(image);
        return image;
    }

    public void drawImage(Image image, double x, double y, double width, double height) {
        checkOpen();
        if (!images.contains(image)) throw new IllegalArgumentException("Image is closed or belongs to another canvas");
        command(23, 40);
        commands.putLong(image.handle).putDouble(x).putDouble(y).putDouble(width).putDouble(height);
    }

    public final class Image implements AutoCloseable {
        private long handle;
        private final int width, height;

        private Image(long handle, int width, int height) {
            this.handle = handle;
            this.width = width;
            this.height = height;
        }

        public int width() { return width; }
        public int height() { return height; }

        @Override public void close() {
            checkThread();
            if (handle == 0) return;
            flush();
            releaseImage();
            images.remove(this);
        }

        private void releaseImage() {
            destroyImage(handle);
            handle = 0;
        }
    }

    /** Submits pending commands and presents the window. */
    public void present() {
        flush();
        show(handle);
    }

    public void writePng(String filename) {
        flush();
        png(handle, utf8(filename));
    }

    /** Submits pending commands without presenting. */
    public void flush() {
        checkOpen();
        int length = commands.position();
        if (length == 0) return;
        commands.clear();
        draw(handle, commands, length);
    }

    /** Returns false after the user requests that the window close. */
    public boolean pollEvents() {
        checkOutsideResize();
        checkOpen();
        int flags = poll(handle);
        if ((flags & 2) != 0) applyResize();
        return (flags & 1) != 0;
    }

    /** Waits until the window has a pending event, leaving it queued for polling. */
    public void waitForEvents() {
        checkOutsideResize();
        checkOpen();
        waitForWindowEvent(handle);
    }

    /** Reads the next supported SDL event into a caller-owned, reusable object. */
    public boolean pollEvent(InputEvent event) {
        checkOutsideResize();
        checkOpen();
        event.type = nextEvent(handle, event.data);
        if (event.type == InputEvent.RESIZE) {
            applyResize();
            event.data.putInt(0, width).putInt(4, height);
        }
        event.text = event.type == InputEvent.TEXT_INPUT
                ? new String(inputText(handle), StandardCharsets.UTF_8) : "";
        return event.type != 0;
    }

    /** Enables or disables committed text events for this window. */
    public void setTextInputEnabled(boolean enabled) {
        checkOpen();
        textInputEnabled(handle, enabled);
    }

    public static final class InputEvent {
        public static final int CLOSE = 1;
        public static final int KEY_DOWN = 2;
        public static final int KEY_UP = 3;
        public static final int POINTER_MOVE = 4;
        public static final int POINTER_DOWN = 5;
        public static final int POINTER_UP = 6;
        public static final int SCROLL = 7;
        public static final int TEXT_INPUT = 8;
        /** Logical or backing-pixel size changed. Redraw the canvas after this event. */
        public static final int RESIZE = 9;
        /** The window was exposed and its contents should be redrawn. */
        public static final int EXPOSED = 10;

        private final ByteBuffer data = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        private int type;
        private String text = "";

        public String text() { return text; }
        public int width() { return data.getInt(0); }
        public int height() { return data.getInt(4); }

        public int type() { return type; }
        /** SDL virtual key code. */
        public int keyCode() { return data.getInt(0); }
        /** SDL physical key code. */
        public int scanCode() { return data.getInt(4); }
        public int modifiers() { return data.getInt(8); }
        public boolean repeat() { return data.getInt(12) != 0; }
        public int button() { return data.getInt(0); }
        public int clicks() { return data.getInt(4); }
        /** Pointer position in window coordinates. */
        public float x() { return data.getFloat(16); }
        public float y() { return data.getFloat(20); }
        /** Relative movement or scroll amount. */
        public float deltaX() { return data.getFloat(24); }
        public float deltaY() { return data.getFloat(28); }
    }

    @Override public void close() {
        checkOutsideResize();
        checkThread();
        if (handle != 0) {
            for (Image image : images) image.releaseImage();
            images.clear();
            destroy(handle);
            handle = 0;
        }
    }

    private static native long open(byte[] title, int width, int height, boolean resizable);
    private static native long logicalSize(long handle);
    private static native long resizeBacking(long handle);
    private static native void watchResize(long handle, Canvas canvas);
    private static native void requestWindowSize(long handle, int width, int height);
    private static native void draw(long handle, ByteBuffer commands, int length);
    private static native void show(long handle);
    private static native void waitForWindowEvent(long handle);
    private static native int poll(long handle);
    private static native byte[] inputText(long handle);
    private static native void textInputEnabled(long handle, boolean enabled);
    private static native int nextEvent(long handle, ByteBuffer event);
    private static native void extents(long handle, int kind, double[] bounds);
    private static native void measure(long handle, byte[] text, double[] metrics);
    private static native long loadPng(byte[] filename, int[] size);
    private static native void destroyImage(long image);
    private static native void png(long handle, byte[] filename);
    private static native void destroy(long handle);
}
