import java.util.Random;
import nativecanvas.Canvas;

/** Procedural animated poster, drawn entirely with Canvas paths and gradients. */
public final class AuroraDemo {
    private static final int WIDTH = 1100, HEIGHT = 700;
    private static final double TAU = 2 * Math.PI;
    private static final double SUN_X = 550, SUN_Y = 292, SUN_R = 145;
    private static final Canvas.Gradient SKY = Canvas.linearGradient(0, 0, 0, HEIGHT)
            .addColorStop(0, 0x050718ff).addColorStop(0.58, 0x11112aff).addColorStop(1, 0x060815ff);
    private static final Canvas.Gradient HALO = Canvas.radialGradient(SUN_X, SUN_Y, 86, SUN_X, SUN_Y, 385)
            .addColorStop(0, 0xc448a685).addColorStop(0.44, 0x652b9158).addColorStop(1, 0x29185700);
    private static final Canvas.Gradient SUN = Canvas.linearGradient(0, 145, 0, 437)
            .addColorStop(0, 0xffedb4ff).addColorStop(0.30, 0xffb175ff)
            .addColorStop(0.64, 0xff5f89ff).addColorStop(1, 0x9c42edff);
    private static final Canvas.Gradient RIDGE = Canvas.linearGradient(0, 382, 0, 553)
            .addColorStop(0, 0x272047ff).addColorStop(1, 0x0c1025ff);
    private static final Canvas.Gradient WATER = Canvas.linearGradient(0, 470, 0, HEIGHT)
            .addColorStop(0, 0x16132cff).addColorStop(1, 0x060918ff);
    private static final Canvas.Gradient RIBBON = Canvas.linearGradient(35, 0, WIDTH - 35, 0)
            .addColorStop(0, 0x45b6ff00).addColorStop(0.23, 0x54f4d6d0)
            .addColorStop(0.55, 0x65d9ffaa).addColorStop(0.78, 0xf282ecc0).addColorStop(1, 0xaf5cf500);
    private static final Canvas.Gradient RIBBON_GLOW = Canvas.linearGradient(0, 0, WIDTH, 0)
            .addColorStop(0, 0x44e8d800).addColorStop(0.30, 0x43ffcc42)
            .addColorStop(0.65, 0x615bff49).addColorStop(1, 0xe842ff00);
    private static final Canvas.Gradient WATER_LINE = Canvas.linearGradient(220, 0, 880, 0)
            .addColorStop(0, 0x864de000).addColorStop(0.32, 0xe454b42d)
            .addColorStop(0.50, 0xffa58f9c).addColorStop(0.68, 0xd968e02f).addColorStop(1, 0x864de000);

    private final double[][] stars = new double[250][4];

    private AuroraDemo() {
        Random random = new Random(92413);
        for (double[] star : stars) {
            star[0] = random.nextDouble() * WIDTH;
            star[1] = random.nextDouble() * 466;
            star[2] = random.nextDouble() * TAU;
            star[3] = random.nextDouble();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean png = args.length > 0 && "--png".equals(args[0]);
        boolean smoke = args.length > 0 && "--smoke".equals(args[0]);
        AuroraDemo scene = new AuroraDemo();
        try (Canvas canvas = png ? Canvas.offscreen(WIDTH, HEIGHT)
                : new Canvas("Aurora / Afterlight", WIDTH, HEIGHT, true)) {
            if (png) {
                double time = args.length > 2 ? Double.parseDouble(args[2]) : 4.0;
                scene.draw(canvas, time);
                canvas.writePng(args.length > 1 ? args[1] : "aurora.png");
                return;
            }
            long start = System.nanoTime();
            canvas.setLiveResizeRedraw(() -> scene.draw(canvas, elapsed(start)));
            while (canvas.pollEvents()) {
                scene.draw(canvas, elapsed(start));
                canvas.present();
                if (smoke && elapsed(start) >= 1) return;
                Thread.sleep(8);
            }
        }
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1e9; }

    private void draw(Canvas c, double t) {
        c.setFillStyle(0x050718ff);
        c.fillRect(0, 0, c.width(), c.height());
        double scale = Math.min((double)c.width() / WIDTH, (double)c.height() / HEIGHT);
        c.save();
        c.translate((c.width() - WIDTH * scale) / 2, (c.height() - HEIGHT * scale) / 2);
        c.scale(scale, scale);
        c.clipRect(0, 0, WIDTH, HEIGHT);
        c.setFillStyle(SKY);
        c.fillRect(0, 0, WIDTH, HEIGHT);
        c.setFillStyle(HALO);
        c.fillRect(0, 0, WIDTH, 515);
        drawStars(c, t);
        drawAurora(c, t);
        drawSun(c, t);
        drawOrbit(c, t);
        drawMountains(c);
        drawWater(c, t);
        c.beginPath();
        c.restore();
    }

    private void drawStars(Canvas c, double t) {
        for (double[] star : stars) {
            double twinkle = 0.5 + 0.5 * Math.sin(star[2] + t * (0.55 + star[3]));
            double size = 0.65 + star[3] * 1.45;
            c.setFillStyle(0xc5e7ff00 | (int)(37 + 138 * twinkle * star[3]));
            c.fillRect(star[0], star[1], size, size);
        }
        for (int i = 0; i < 9; i++) {
            double[] star = stars[i];
            double size = 2 + 3 * (0.5 + 0.5 * Math.sin(t + star[2]));
            c.setFillStyle(0x9be4ff49);
            c.fillRect(star[0] - size, star[1], 2 * size + 1, 1);
            c.fillRect(star[0], star[1] - size, 1, 2 * size + 1);
            c.setFillStyle(0xe2f4ffbf);
            c.fillRect(star[0], star[1], 1.4, 1.4);
        }
    }

    private static double ribbonY(double x, double t, double layer) {
        return 125 + 51 * Math.sin(x * 0.0078 + 0.48 * t + 0.55 * layer)
                + 26 * Math.sin(x * 0.014 - 0.35 * t + 0.17 * layer)
                - 0.075 * x + 10 * layer;
    }

    private static void drawAurora(Canvas c, double t) {
        c.setStrokeStyle(RIBBON_GLOW);
        for (int layer = 0; layer < 8; layer++) {
            ribbonPath(c, t, layer * 0.48);
            c.setLineWidth(14 - layer);
            c.stroke();
        }
        c.setStrokeStyle(RIBBON);
        c.setLineWidth(1.05);
        for (int layer = 0; layer < 11; layer++) {
            ribbonPath(c, t, layer * 0.33);
            c.stroke();
        }
    }

    private static void ribbonPath(Canvas c, double t, double layer) {
        c.beginPath();
        c.moveTo(0, ribbonY(0, t, layer));
        for (int x = 7; x <= WIDTH + 7; x += 7) c.lineTo(x, ribbonY(x, t, layer));
    }

    private static void ellipse(Canvas c, double x, double y, double rx, double ry) {
        double k = 0.552284749831;
        c.beginPath();
        c.moveTo(x + rx, y);
        c.bezierCurveTo(x + rx, y + k * ry, x + k * rx, y + ry, x, y + ry);
        c.bezierCurveTo(x - k * rx, y + ry, x - rx, y + k * ry, x - rx, y);
        c.bezierCurveTo(x - rx, y - k * ry, x - k * rx, y - ry, x, y - ry);
        c.bezierCurveTo(x + k * rx, y - ry, x + rx, y - k * ry, x + rx, y);
        c.closePath();
    }

    private static void drawSun(Canvas c, double t) {
        ellipse(c, SUN_X, SUN_Y, SUN_R, SUN_R);
        c.setFillStyle(SUN);
        c.fill();
        c.save();
        c.clip();
        c.setFillStyle(0x29174a73);
        for (int i = 0; i < 18; i++) {
            double p = i / 17.0;
            double y = SUN_Y - 17 + p * 169 + 2.5 * Math.sin(t * 1.3 + i * 0.63);
            c.fillRect(SUN_X - SUN_R, y, 2 * SUN_R, 0.65 + p * p * 9);
        }
        c.restore();
        c.setStrokeStyle(0xffd9bfa0);
        c.setLineWidth(1.2);
        c.stroke();
    }

    private static void drawOrbit(Canvas c, double t) {
        c.save();
        c.translate(SUN_X, SUN_Y);
        c.rotate(-0.24 + 0.045 * Math.sin(t * 0.37));
        for (int ring = 0; ring < 3; ring++) {
            double rx = 221 + ring * 13, ry = 42 + ring * 8;
            c.beginPath();
            c.moveTo(rx, 0);
            c.bezierCurveTo(rx, ry * 1.333, -rx, ry * 1.333, -rx, 0);
            c.setStrokeStyle(ring == 1 ? 0x92f7eeaf : 0x9bb9ff45);
            c.setLineWidth(ring == 1 ? 1.25 : 0.8);
            c.stroke();
            double phase = Math.PI * (0.5 + 0.5 * Math.sin(t * (0.55 + ring * 0.11) + ring * 2.1));
            double x = rx * Math.cos(phase), y = ry * Math.sin(phase);
            ellipse(c, x, y, 10, 10);
            c.setFillStyle(0x7bf5ee26);
            c.fill();
            ellipse(c, x, y, 2.7, 2.7);
            c.setFillStyle(0xe5ffffee);
            c.fill();
        }
        c.restore();
    }

    private static void drawMountains(Canvas c) {
        c.beginPath();
        c.moveTo(0, 461);
        c.lineTo(0, 387);
        c.lineTo(88, 410);
        c.lineTo(181, 357);
        c.lineTo(234, 407);
        c.lineTo(285, 378);
        c.lineTo(374, 435);
        c.lineTo(446, 420);
        c.lineTo(504, 459);
        c.lineTo(623, 442);
        c.lineTo(724, 394);
        c.lineTo(795, 413);
        c.lineTo(896, 353);
        c.lineTo(973, 405);
        c.lineTo(1043, 377);
        c.lineTo(WIDTH, 399);
        c.lineTo(WIDTH, 489);
        c.lineTo(0, 489);
        c.closePath();
        c.setFillStyle(RIDGE);
        c.fill();
        c.setStrokeStyle(0xa68cf258);
        c.setLineWidth(1);
        c.stroke();

        c.beginPath();
        c.moveTo(0, 446);
        c.lineTo(109, 429);
        c.lineTo(194, 466);
        c.lineTo(287, 448);
        c.lineTo(390, 480);
        c.lineTo(704, 480);
        c.lineTo(813, 458);
        c.lineTo(922, 470);
        c.lineTo(1011, 423);
        c.lineTo(WIDTH, 448);
        c.lineTo(WIDTH, 500);
        c.lineTo(0, 500);
        c.closePath();
        c.setFillStyle(0x0a0d20ff);
        c.fill();
    }

    private static void drawWater(Canvas c, double t) {
        c.setFillStyle(WATER);
        c.fillRect(0, 486, WIDTH, HEIGHT - 486);
        c.setStrokeStyle(WATER_LINE);
        for (int i = 0; i < 38; i++) {
            double depth = (i + (t * 0.65 % 1)) / 38;
            double y = 487 + depth * depth * 215;
            double half = 32 + depth * 380;
            double shift = 21 * Math.sin(i * 1.8 + t * 1.1);
            c.beginPath();
            c.moveTo(SUN_X - half + shift, y);
            c.lineTo(SUN_X + half + shift, y);
            c.setLineWidth(0.6 + depth * 2.1);
            c.stroke();
        }
        c.setFillStyle(0x777bae65);
        c.fillRect(32, 653, 36, 1);
        c.setFont("Sans Bold 14px");
        c.setFillStyle(0xe2e5f8d9);
        c.fillText("A U R O R A   /   A F T E R L I G H T", 32, 677);
    }
}
