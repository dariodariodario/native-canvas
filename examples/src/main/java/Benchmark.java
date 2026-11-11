import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import nativecanvas.Canvas;

public final class Benchmark {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int WARMUP = 180;
    private static final int FRAMES = 3_000;

    public static void main(String[] args) throws Exception {
        boolean window = args.length > 0 && "--window".equals(args[0]);
        int frames = Integer.getInteger("benchmark.frames", FRAMES);
        if (frames < 1) throw new IllegalArgumentException("benchmark.frames must be positive");
        Path png = Files.createTempFile("nativecanvas-sprite-", ".png");
        try {
            try (Canvas sprite = Canvas.offscreen(64, 64)) {
                sprite.setFillStyle(0x63c7b9aa);
                sprite.fillRect(4, 4, 56, 56);
                sprite.setFillStyle(0xffbe6cff);
                sprite.fillRect(20, 20, 24, 24);
                sprite.writePng(png.toString());
            }
            try (Canvas canvas = window ? new Canvas("Native Canvas Benchmark", WIDTH, HEIGHT)
                    : Canvas.offscreen(WIDTH, HEIGHT);
                 Canvas.Image sprite = canvas.loadImage(png.toString())) {
                canvas.setFont("Sans 14px");
                for (int workload = 0; workload < 3; workload++) {
                    if (!run(canvas, sprite, window, workload, frames)) break;
                }
            }
        } finally {
            Files.deleteIfExists(png);
        }
    }

    private static boolean run(Canvas canvas, Canvas.Image sprite, boolean window, int workload, int frames) {
        long[] draw = new long[frames];
        long[] present = new long[frames];
        long[] total = new long[frames];
        int count = workload == 0 ? 4_096 : workload == 1 ? 1000 : 8_192;
        String name = workload == 0 ? "text" : workload == 1 ? "shapes" : "images";
        String[] labels = new String[256];
        for (int i = 0; i < labels.length; i++) labels[i] = "Item " + i + " · Ελληνικά";
        System.out.printf("%s (%,d draws/frame, %,d measured frames)%n", name, count, frames);
        for (int frame = -WARMUP; frame < frames; frame++) {
            if (window && !canvas.pollEvents()) return false;
            long start = System.nanoTime();
            canvas.setFillStyle(0x192535ff);
            canvas.fillRect(0, 0, WIDTH, HEIGHT);
            canvas.setFillStyle(0x75e4ca80);
            for (int i = 0; i < count; i++) {
                int x = Math.floorMod(i * 37 + (i / HEIGHT) * 13 + frame, WIDTH);
                int y = Math.floorMod(i * 29 + frame, HEIGHT);
                switch (workload) {
                    case 0: canvas.fillText(labels[i % labels.length], x, y); break;
                    case 1: canvas.fillRect(x, y, 12, 12); break;
                    case 2: canvas.drawImage(sprite, x, y, 48, 48); break;
                    default: throw new AssertionError(workload);
                }
            }
            canvas.flush();
            long flushed = System.nanoTime();
            if (window) canvas.present();
            long shown = System.nanoTime();
            if (frame >= 0) {
                draw[frame] = flushed - start;
                present[frame] = shown - flushed;
                total[frame] = shown - start;
            }
        }
        report("draw", draw);
        if (window) report("present", present);
        report("total", total);
        System.out.printf("  throughput %,.0f draws/s%n",
                (double) count * frames * 1e9 / Arrays.stream(total).sum());
        return true;
    }

    private static void report(String stage, long[] samples) {
        Arrays.sort(samples);
        System.out.printf("  %-8s p50 %6.3f ms   p95 %6.3f ms%n", stage,
                samples[samples.length / 2] / 1e6, samples[(int) Math.ceil(samples.length * 0.95) - 1] / 1e6);
    }
}
