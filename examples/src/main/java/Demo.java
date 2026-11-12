import java.nio.file.Files;
import java.nio.file.Path;
import nativecanvas.Canvas;

public final class Demo {
    private static final int WIDTH = 760, HEIGHT = 500;
    private static final int INK = 0xeaf2ffff;
    private static final Canvas.Gradient LINEAR = Canvas.linearGradient(32, 0, 350, 0)
            .addColorStop(0, 0x48e6c1ff).addColorStop(0.55, 0x428cffff).addColorStop(1, 0xbd5bffff);
    private static final Canvas.Gradient RADIAL = Canvas.radialGradient(0, 0, 4, 0, 0, 86)
            .addColorStop(0, 0xffe39aff).addColorStop(0.45, 0xf58285ff).addColorStop(1, 0x843dd500);

    public static void main(String[] args) throws Exception {
        boolean offscreen = args.length > 0 && "--png".equals(args[0]);
        boolean smoke = args.length > 0 && "--smoke".equals(args[0]);
        Path spriteFile = Files.createTempFile("nativecanvas-demo-", ".png");
        try {
            createSprite(spriteFile);
            try (Canvas canvas = offscreen ? Canvas.offscreen(WIDTH, HEIGHT)
                    : new Canvas("Native Canvas", WIDTH, HEIGHT, true);
                 Canvas.Image sprite = canvas.loadImage(spriteFile.toString())) {
                run(canvas, sprite, offscreen, smoke, args);
            }
        } finally {
            Files.deleteIfExists(spriteFile);
        }
    }

    private static void createSprite(Path path) {
        try (Canvas sprite = Canvas.offscreen(64, 64)) {
            sprite.setFillStyle(0x755cf2ff);
            sprite.fillRect(0, 0, 64, 64);
            sprite.setFillStyle(0x78ead0ff);
            sprite.fillRect(8, 8, 48, 48);
            sprite.clearRect(22, 22, 20, 20);
            sprite.writePng(path.toString());
        }
    }

    private static void run(Canvas canvas, Canvas.Image sprite, boolean offscreen,
                            boolean smoke, String[] args) throws InterruptedException {
        Canvas.InputEvent event = new Canvas.InputEvent();
        long start = System.nanoTime();
        Scene scene = new Scene();
        DemoStats stats = offscreen ? null : new DemoStats(canvas);
        if (!offscreen) canvas.setLiveResizeRedraw(() -> {
            scene.draw(canvas, sprite, elapsed(start));
            stats.draw(canvas);
        });
        do {
            while (!offscreen && canvas.pollEvent(event)) {
                switch (event.type()) {
                    case Canvas.InputEvent.CLOSE: return;
                    case Canvas.InputEvent.KEY_DOWN:
                        if (event.keyCode() == 27) return;
                        if (!event.repeat()) scene.alternate = !scene.alternate;
                        break;
                    case Canvas.InputEvent.POINTER_DOWN:
                    case Canvas.InputEvent.POINTER_UP:
                        scene.pressed = event.type() == Canvas.InputEvent.POINTER_DOWN;
                        scene.pointerX = event.x();
                        scene.pointerY = event.y();
                        break;
                    case Canvas.InputEvent.POINTER_MOVE:
                        scene.pointerX = event.x();
                        scene.pointerY = event.y();
                        break;
                    case Canvas.InputEvent.SCROLL:
                        scene.zoom = Math.max(0.6, Math.min(1.3, scene.zoom + event.deltaY() * 0.05));
                        break;
                    default: break;
                }
            }
            scene.draw(canvas, sprite, offscreen ? 0 : elapsed(start));
            if (offscreen) {
                canvas.writePng(args.length > 1 ? args[1] : "demo.png");
                return;
            }
            stats.draw(canvas);
            canvas.present();
            Thread.sleep(16);
        } while (!smoke || elapsed(start) < 1);
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1e9; }

    private static final class Scene {
        private double pointerX = -1, pointerY = -1, zoom = 1;
        private boolean pressed, alternate;

        void draw(Canvas canvas, Canvas.Image sprite, double time) {
            render(canvas, sprite, time, zoom, alternate);
            if (pointerX >= 0 && pointerY >= 0) {
                canvas.setFillStyle(pressed ? 0xf58285ff : INK);
                canvas.fillRect(pointerX - 3, pointerY - 3, 6, 6);
            }
        }
    }

    private static void render(Canvas c, Canvas.Image sprite, double time, double zoom, boolean alternate) {
        c.setFillStyle(0x111a2aff);
        c.fillRect(0, 0, c.width(), c.height());
        double scale = Math.min((double) c.width() / WIDTH, (double) c.height() / HEIGHT);
        c.save();
        c.translate((c.width() - WIDTH * scale) / 2, (c.height() - HEIGHT * scale) / 2);
        c.scale(scale, scale);
        label(c, "Canvas drawing", 32, 44, "Sans Bold 26px");
        label(c, "Scroll to scale · Press a key to change accent · Esc to close", 32, 68, "Sans 13px");

        label(c, "Linear fill · rectangular clip", 32, 110, "Sans Bold 15px");
        c.save();
        c.clipRect(32, 122, 328, 125);
        c.setFillStyle(LINEAR);
        c.fillRect(12, 122, 370, 125);
        c.setStrokeStyle(0x142449ff);
        c.setLineWidth(2);
        c.strokeRect(43, 132, 306, 104);
        c.restore();

        label(c, "Radial fill · path clip · transforms", 396, 110, "Sans Bold 15px");
        c.save();
        c.translate(560, 184);
        c.rotate(0.24 + Math.sin(time) * 0.32);
        c.scale(zoom, zoom);
        c.beginPath();
        c.moveTo(-136, -40);
        c.lineTo(108, -53);
        c.lineTo(136, 32);
        c.bezierCurveTo(65, 86, -52, 59, -136, 40);
        c.closePath();
        c.clip();
        c.setFillStyle(alternate ? 0x3b2859ff : 0x222d52ff);
        c.fill();
        c.setFillStyle(RADIAL);
        c.fillRect(-136, -80, 272, 160);
        c.restore();

        label(c, "Bézier path · gradient stroke · bounds", 32, 290, "Sans Bold 15px");
        c.beginPath();
        c.moveTo(48, 365);
        c.bezierCurveTo(105, 281, 238, 450, 336, 325);
        c.lineTo(336, 382);
        c.closePath();
        c.setLineWidth(4);
        Canvas.Bounds path = c.pathBounds();
        Canvas.Bounds fill = c.fillBounds();
        Canvas.Bounds stroke = c.strokeBounds();
        Canvas.Bounds clip = c.clipBounds();
        c.setFillStyle(0x253a60ff);
        c.fill();
        c.setStrokeStyle(LINEAR);
        c.stroke();
        c.setStrokeStyle(0x9bacc982);
        c.setLineWidth(1);
        c.strokeRect(stroke.x, stroke.y, stroke.width, stroke.height);
        label(c, "Widths  path " + Math.round(path.width) + "  ·  fill " + Math.round(fill.width)
                + "  ·  stroke " + Math.round(stroke.width) + "  ·  clip " + Math.round(clip.width),
                32, 411, "Sans 12px");

        label(c, "PNG image · alpha · clear", 396, 290, "Sans Bold 15px");
        c.save();
        c.clipRect(396, 302, 332, 108);
        c.setFillStyle(0x283a58ff);
        c.fillRect(396, 302, 332, 108);
        c.drawImage(sprite, 412, 318, sprite.width(), sprite.height());
        c.drawImage(sprite, 506, 308, 88, 88);
        c.drawImage(sprite, 628, 322, 74, 74);
        c.clearRect(655, 345, 18, 18);
        c.restore();

        c.setFont("Sans 21px");
        String text = "مرحبا  ·  Καλημέρα  ·  café";
        Canvas.TextMetrics metrics = c.measureText(text);
        double textX = (WIDTH - metrics.width) / 2;
        c.setFillStyle(INK);
        c.fillText(text, textX, 465);
        c.setStrokeStyle(0x9bacc982);
        c.strokeRect(textX + metrics.inkX, 465 + metrics.inkY, metrics.inkWidth, metrics.inkHeight);
        c.beginPath();
        c.restore();
    }

    private static void label(Canvas canvas, String text, double x, double y, String font) {
        canvas.setFont(font);
        canvas.setFillStyle(INK);
        canvas.fillText(text, x, y);
    }
}
