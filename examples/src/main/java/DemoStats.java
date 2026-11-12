import java.util.Locale;
import nativecanvas.Canvas;

final class DemoStats {
    private final String renderer;
    private final boolean onDemand;
    private long sampleStart = System.nanoTime();
    private int frames;
    private String fps = "0.0 FPS";
    private String displayedText;
    private double width;

    DemoStats(Canvas canvas) {
        this(canvas, false);
    }

    DemoStats(Canvas canvas, boolean onDemand) {
        renderer = renderer(canvas);
        this.onDemand = onDemand;
    }

    static String renderer(Canvas canvas) {
        String target = canvas.presentationRenderer();
        boolean gpu = target.equals("skia-metal");
        String backend = canvas.backend() == Canvas.Backend.SKIA ? "Skia" : "Cairo";
        return backend + " · " + (gpu ? "GPU" : "CPU") + " drawing · " + target;
    }

    void draw(Canvas canvas) {
        long now = System.nanoTime();
        frames++;
        long elapsed = now - sampleStart;
        if (elapsed >= 500_000_000L) {
            fps = String.format(Locale.ROOT, "%.1f FPS", frames * 1e9 / elapsed);
            frames = 0;
            sampleStart = now;
        }
        String text = fps + (onDemand ? " (on demand)" : "") + "   |   " + renderer;
        canvas.save();
        canvas.setFont("Sans Bold 12px");
        if (!text.equals(displayedText)) {
            width = canvas.measureText(text).width + 20;
            displayedText = text;
        }
        double x = Math.max(4, canvas.width() - width - 8);
        canvas.setFillStyle(0x101723e8);
        canvas.fillRect(x, 8, width, 24);
        canvas.setFillStyle(0xeaf4ffff);
        canvas.fillText(text, x + 10, 25);
        canvas.restore();
    }
}
