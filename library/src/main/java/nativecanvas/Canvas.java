package nativecanvas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static nativecanvas.DrawCommandBuffer.*;

/** Canvas-style drawing into a persistent backing store. Use on the window thread. */
public final class Canvas implements AutoCloseable {
    static { NativeLibrary.load(); }

    private final Thread owner = Thread.currentThread();
    private final DrawCommandBuffer commands = new DrawCommandBuffer(this::checkOpen, this::flush);
    private final Set<Image> images = new HashSet<>();
    public enum Backend { CAIRO, SKIA }

    private final Backend backend;
    private long handle;
    private int width, height;
    private Runnable liveResizeRedraw;
    private boolean redrawingDuringResize;

    public Canvas(String title, int width, int height) {
        this(title, width, height, false);
    }

    /** Opens a window with optional user resizing. */
    public Canvas(String title, int width, int height, boolean resizable) {
        this(title, width, height, resizable, defaultBackend());
    }

    public Canvas(String title, int width, int height, boolean resizable, Backend backend) {
        this(utf8(title), width, height, resizable, backend);
    }

    public static Canvas offscreen(int width, int height) {
        return offscreen(width, height, defaultBackend());
    }

    public static Canvas offscreen(int width, int height, Backend backend) {
        return new Canvas((byte[]) null, width, height, false, backend);
    }

    private static Backend defaultBackend() {
        String requested = System.getProperty("nativecanvas.backend");
        if (requested != null) return Backend.valueOf(requested.toUpperCase(java.util.Locale.ROOT));
        return isBackendAvailable(Backend.SKIA) ? Backend.SKIA : Backend.CAIRO;
    }

    /** Returns whether the JNI library was built with this backend. */
    public static boolean isBackendAvailable(Backend backend) {
        return hasBackend(backendName(backend));
    }

    private static String backendName(Backend backend) {
        return backend.name().toLowerCase(java.util.Locale.ROOT);
    }

    public Backend backend() { return backend; }

    private Canvas(byte[] title, int width, int height, boolean resizable, Backend backend) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid canvas size");
        this.backend = java.util.Objects.requireNonNull(backend);
        handle = open(title, width, height, resizable, backendName(backend));
        updateSize(logicalSize(handle));
    }

    /** Rendering target: skia-metal, skia-raster, an SDL renderer, or offscreen (Cairo). */
    public String presentationRenderer() {
        checkOpen();
        return rendererName(handle);
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

    /** Colors use 0xRRGGBBAA. */
    public void setFillStyle(int rgba) { commands.color(SET_FILL_COLOR, rgba); }
    public void setStrokeStyle(int rgba) { commands.color(SET_STROKE_COLOR, rgba); }
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

    static final class Stop {
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
        int op = gradient.radial
                ? (stroke ? SET_STROKE_RADIAL : SET_FILL_RADIAL)
                : (stroke ? SET_STROKE_LINEAR : SET_FILL_LINEAR);
        commands.gradient(op, gradient.coordinates, gradient.stops);
    }

    public void fillRect(double x, double y, double w, double h) { commands.rectangle(FILL_RECT, x, y, w, h); }
    public void strokeRect(double x, double y, double w, double h) { commands.rectangle(STROKE_RECT, x, y, w, h); }
    public void clearRect(double x, double y, double w, double h) { commands.rectangle(CLEAR_RECT, x, y, w, h); }
    public void beginPath() { commands.opcode(BEGIN_PATH); }
    public void moveTo(double x, double y) { commands.point(MOVE_TO, x, y); }
    public void lineTo(double x, double y) { commands.point(LINE_TO, x, y); }
    public void closePath() { commands.opcode(CLOSE_PATH); }
    public void fill() { commands.opcode(FILL_PATH); }
    public void stroke() { commands.opcode(STROKE_PATH); }
    public void save() { commands.opcode(SAVE); }
    public void restore() { commands.opcode(RESTORE); }
    public void translate(double x, double y) { commands.point(TRANSLATE, x, y); }
    public void scale(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || x == 0 || y == 0) return;
        commands.point(SCALE, x, y);
    }
    public void rotate(double radians) { commands.scalar(ROTATE, radians); }
    public enum Antialias { DEFAULT, FAST }

    /** Selects geometric edge quality. FAST prioritizes rasterization throughput. */
    public void setAntialias(Antialias quality) {
        java.util.Objects.requireNonNull(quality);
        commands.scalar(SET_ANTIALIAS, quality == Antialias.FAST ? 1 : 0);
    }

    public void setLineWidth(double width) {
        if (width > 0 && Double.isFinite(width)) commands.scalar(SET_LINE_WIDTH, width);
    }
    public void rect(double x, double y, double w, double h) { commands.rectangle(ADD_RECT, x, y, w, h); }
    public void clip() { commands.opcode(CLIP_PATH); }

    /** Intersects the clip with a rectangle while preserving the current path. */
    public void clipRect(double x, double y, double width, double height) {
        commands.rectangle(CLIP_RECT, x, y, width, height);
    }
    public void bezierCurveTo(double x1, double y1, double x2, double y2, double x, double y) {
        commands.cubic(x1, y1, x2, y2, x, y);
    }

    /** Sets a font description, for example "Sans Bold 18px". */
    public void setFont(String description) { commands.font(description); }

    /** Draws text at the specified alphabetic baseline. */
    public void fillText(String text, double x, double y) {
        commands.text(text, x, y);
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

    /** Returns the shaped horizontal caret position for a UTF-16 index in a single line. */
    public double textCaretX(String text, int index) {
        if (index < 0 || index > text.length()
                || (index > 0 && index < text.length() && Character.isSurrogatePair(
                        text.charAt(index - 1), text.charAt(index))))
            throw new IllegalArgumentException("Invalid text caret index");
        byte[] bytes = utf8(text);
        int byteOffset = index == text.length() ? bytes.length : utf8(text.substring(0, index)).length;
        flush();
        return caretPosition(handle, bytes, byteOffset, index);
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
        long imageHandle = loadPng(handle, utf8(filename), size);
        Image image = new Image(imageHandle, size[0], size[1]);
        images.add(image);
        return image;
    }

    public void drawImage(Image image, double x, double y, double width, double height) {
        checkOpen();
        if (!images.contains(image)) throw new IllegalArgumentException("Image is closed or belongs to another canvas");
        commands.image(image.handle, x, y, width, height);
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
            destroyImage(Canvas.this.handle, handle);
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
        int length = commands.size();
        if (length == 0) return;
        draw(handle, commands.take(), length);
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

    private static native boolean hasBackend(String backendName);
    private static native String rendererName(long handle);
    private static native long open(byte[] title, int width, int height, boolean resizable, String backendName);
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
    private static native double caretPosition(long canvas, byte[] text, int byteOffset, int utf16Offset);
    private static native void measure(long handle, byte[] text, double[] metrics);
    private static native long loadPng(long canvas, byte[] filename, int[] size);
    private static native void destroyImage(long canvas, long image);
    private static native void png(long handle, byte[] filename);
    private static native void destroy(long handle);
}
