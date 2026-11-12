import java.util.function.DoubleConsumer;
import nativecanvas.Canvas;
import nativecanvas.ui.Button;
import nativecanvas.ui.Row;
import nativecanvas.ui.Track;
import nativecanvas.ui.Ui;

/** Hosts the animated scene and toolkit controls in a resizable window. */
final class AnimatedToolkitDemo {
    private AnimatedToolkitDemo() {}

    static void run(Canvas canvas, Ui ui, DoubleConsumer advance, boolean smoke) {
        DemoStats stats = new DemoStats(canvas);
        canvas.setLiveResizeRedraw(() -> {
            ui.resize(canvas.width(), canvas.height());
            draw(canvas, ui, stats);
        });
        try {
            long previous = System.nanoTime();
            long start = previous;
            while (ui.poll(canvas)) {
                long now = System.nanoTime();
                advance.accept((now - previous) / 1e9);
                previous = now;
                draw(canvas, ui, stats);
                canvas.present();
                if (smoke && now - start >= 1_000_000_000L) return;
            }
        } finally {
            canvas.setLiveResizeRedraw(null);
        }
    }

    private static void draw(Canvas canvas, Ui ui, DemoStats stats) {
        ui.draw(canvas);
        stats.draw(canvas);
    }

    static Row adjustmentButtons(Runnable decrease, Runnable increase, Runnable refresh) {
        return new Row(0, 8)
                .add(new Button("−", () -> { decrease.run(); refresh.run(); }), Track.FILL)
                .add(new Button("+", () -> { increase.run(); refresh.run(); }), Track.FILL);
    }
}
