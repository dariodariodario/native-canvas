import java.util.Random;
import nativecanvas.Canvas;
import nativecanvas.ui.*;

/** A projected torus knot with a flowing particle field and a reactive camera. */
public final class ParadoxDemo {
    private static final int WIDTH = 1280, HEIGHT = 800;
    private static final double TAU = Math.PI * 2;
    private static final int STEPS = 720, STRANDS = 36, PARTICLES = 7200;
    private static final double[][] KNOT = new double[STEPS][3];
    private static final double[][] NORMAL = new double[STEPS][3];
    private static final double[][] BINORMAL = new double[STEPS][3];
    private static final Canvas.Gradient BACKDROP = Canvas.radialGradient(640, 405, 0, 640, 405, 760)
            .addColorStop(0, 0x211846ff).addColorStop(0.45, 0x101127ff).addColorStop(1, 0x03050eff);
    private static final Canvas.Gradient BLOOM = Canvas.radialGradient(640, 410, 0, 640, 410, 550)
            .addColorStop(0, 0x5a36c354).addColorStop(0.5, 0x1834a628).addColorStop(1, 0x070b3400);
    private static final int[] PALETTE = {0x52ffe1, 0x619cff, 0xb76aff, 0xff5cb6, 0xffbc74};

    static {
        for (int i = 0; i < STEPS; i++) {
            double t = TAU * i / STEPS;
            double r = 190 + 87 * Math.cos(3 * t);
            KNOT[i] = new double[]{r * Math.cos(2 * t), r * Math.sin(2 * t), 87 * Math.sin(3 * t)};
        }
        for (int i = 0; i < STEPS; i++) {
            double[] a = KNOT[(i + STEPS - 1) % STEPS], b = KNOT[(i + 1) % STEPS];
            double[] tangent = unit(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            double[] n = unit(tangent[1], -tangent[0], 0);
            NORMAL[i] = n;
            BINORMAL[i] = new double[]{tangent[1] * n[2] - tangent[2] * n[1],
                    tangent[2] * n[0] - tangent[0] * n[2], tangent[0] * n[1] - tangent[1] * n[0]};
        }
    }

    private final double[][] particles = new double[PARTICLES][4];
    private final double[][] stars = new double[330][3];
    private final double[][] xs = new double[STRANDS][STEPS], ys = new double[STRANDS][STEPS];
    private final int[][] depths = new int[STRANDS][STEPS];
    private double yaw, pitch, zoom = 1, sx, sy, z;
    private double sinYaw, cosYaw, sinPitch, cosPitch, spinSin, spinCos;
    private boolean pulse;
    private boolean fast = true;
    private int particleCount = PARTICLES;
    private double speed = 1;
    private boolean paused;

    private ParadoxDemo() {
        Random random = new Random(7072);
        for (double[] p : particles) {
            p[0] = random.nextDouble() * STEPS;
            p[1] = random.nextDouble() * TAU;
            p[2] = random.nextDouble();
            p[3] = random.nextDouble();
        }
        for (double[] star : stars) {
            star[0] = random.nextDouble() * WIDTH;
            star[1] = random.nextDouble() * HEIGHT;
            star[2] = random.nextDouble();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean png = args.length > 0 && "--png".equals(args[0]);
        boolean smoke = args.length > 0 && "--smoke".equals(args[0]);
        ParadoxDemo demo = new ParadoxDemo();
        try (Canvas canvas = png || (args.length > 0 && "--benchmark".equals(args[0])) ? Canvas.offscreen(WIDTH, HEIGHT)
                : new Canvas("Paradox Engine", WIDTH, HEIGHT, true)) {
            if (args.length > 0 && "--benchmark".equals(args[0])) {
                demo.fast = args.length < 2 || !"default".equals(args[1]);
                long[] frames = new long[100];
                for (int i = -30; i < frames.length; i++) {
                    long startFrame = System.nanoTime();
                    demo.draw(canvas, 3.8 + (i + 30) / 60.0);
                    canvas.flush();
                    if (i >= 0) frames[i] = System.nanoTime() - startFrame;
                }
                java.util.Arrays.sort(frames);
                System.out.printf("%s: p50 %.3f ms, p95 %.3f ms%n",
                        demo.fast ? "fast" : "default", frames[50] / 1e6, frames[94] / 1e6);
                return;
            }
            if (png) {
                double time = args.length > 2 ? Double.parseDouble(args[2]) : 3.8;
                if (!Double.isFinite(time) || time < 0) throw new IllegalArgumentException("Invalid animation time");
                demo.draw(canvas, time);
                canvas.writePng(args.length > 1 ? args[1] : "paradox.png");
                return;
            }
            Controls controls = new Controls(demo, canvas.width(), canvas.height());
            long start = System.nanoTime();
            long previous = start;
            canvas.setLiveResizeRedraw(() -> {
                controls.ui.resize(canvas.width(), canvas.height());
                controls.ui.draw(canvas);
            });
            while (controls.ui.poll(canvas)) {
                long now = System.nanoTime();
                if (!demo.paused) controls.time += (now - previous) / 1e9 * demo.speed;
                previous = now;
                controls.ui.draw(canvas);
                canvas.present();
                if (smoke && seconds(start) >= 1) return;
                Thread.sleep(4);
            }
        }
    }

    private static final class Controls {
        private final Ui ui;
        private double time;

        Controls(ParadoxDemo demo, int width, int height) {
            Label density = new Label("");
            Label speed = new Label("");
            Label zoom = new Label("");
            Runnable refresh = () -> {
                density.setText("Particles: " + demo.particleCount);
                speed.setText(String.format(java.util.Locale.ROOT, "Speed: %.2fx", demo.speed));
                zoom.setText(String.format(java.util.Locale.ROOT, "Zoom: %.2fx", demo.zoom));
            };
            refresh.run();
            Checkbox pause = new Checkbox("Pause animation", false, value -> demo.paused = value);
            Checkbox surge = new Checkbox("Energy surge", demo.pulse, value -> demo.pulse = value);
            Checkbox fast = new Checkbox("Fast edge rendering", demo.fast, value -> demo.fast = value);
            Column panel = new Column(14, 8)
                    .add(new Label("ENGINE CONTROLS").setFont("Sans Bold", 17).setColor(0x73e8dfff))
                    .add(pause).add(surge).add(fast)
                    .add(density).add(adjustments(
                            () -> demo.particleCount = Math.max(0, demo.particleCount - 600),
                            () -> demo.particleCount = Math.min(PARTICLES, demo.particleCount + 600), refresh))
                    .add(speed).add(adjustments(
                            () -> demo.speed = Math.max(0.25, demo.speed - 0.25),
                            () -> demo.speed = Math.min(4, demo.speed + 0.25), refresh))
                    .add(zoom).add(adjustments(
                            () -> demo.zoom = Math.max(0.5, demo.zoom - 0.1),
                            () -> demo.zoom = Math.min(2, demo.zoom + 0.1), refresh))
                    .add(new Button("Reset", () -> {
                        demo.particleCount = PARTICLES;
                        demo.speed = demo.zoom = 1;
                        demo.yaw = demo.pitch = 0;
                        demo.paused = demo.pulse = false;
                        demo.fast = true;
                        pause.setChecked(false);
                        surge.setChecked(false);
                        fast.setChecked(true);
                        refresh.run();
                    }));
            Control viewport = new Control() {
                private double lastX, lastY;
                @Override public double preferredHeight(double width) { return 0; }
                @Override public void activate() {}
                @Override protected void onPointerPressed(double x, double y) { lastX = x; lastY = y; }
                @Override protected void onPointerDragged(double x, double y) {
                    demo.yaw += (x - lastX) * 0.006;
                    demo.pitch += (y - lastY) * 0.006;
                    lastX = x;
                    lastY = y;
                }
                @Override protected void paint(Canvas c) {
                    if (width() <= 0 || height() <= 0) return;
                    c.save();
                    c.clipRect(x(), y(), width(), height());
                    c.translate(x(), y());
                    demo.draw(c, time, width(), height(), true);
                    c.restore();
                }
            };
            Grid root = new Grid(new Track[]{Track.FILL, Track.size(280)},
                    new Track[]{Track.FILL}, 0, 0, 0)
                    .add(0, 0, viewport).add(0, 1, new ScrollPane(panel, 1));
            ui = new Ui(root, width, height);
        }

        private static Row adjustments(Runnable decrease, Runnable increase, Runnable refresh) {
            return new Row(0, 8)
                    .add(new Button("−", () -> { decrease.run(); refresh.run(); }), Track.FILL)
                    .add(new Button("+", () -> { increase.run(); refresh.run(); }), Track.FILL);
        }
    }

    private static double seconds(long start) { return (System.nanoTime() - start) / 1e9; }

    private static double[] unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }

    private void draw(Canvas c, double time) {
        draw(c, time, c.width(), c.height(), false);
    }

    private void draw(Canvas c, double time, double width, double height, boolean withControls) {
        c.setFillStyle(0x03050eff);
        c.fillRect(0, 0, width, height);
        double scale = Math.min((double)width / WIDTH, (double)height / HEIGHT);
        c.save();
        c.setAntialias(fast ? Canvas.Antialias.FAST : Canvas.Antialias.DEFAULT);
        c.translate((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2);
        c.scale(scale, scale);
        c.clipRect(0, 0, WIDTH, HEIGHT);
        c.setFillStyle(BACKDROP);
        c.fillRect(0, 0, WIDTH, HEIGHT);
        c.setFillStyle(BLOOM);
        c.fillRect(0, 0, WIDTH, HEIGHT);
        backdrop(c, time);

        double a = yaw + 0.36 * Math.sin(time * 0.42);
        double b = pitch + 0.41 + 0.21 * Math.sin(time * 0.57);
        sinYaw = Math.sin(a);
        cosYaw = Math.cos(a);
        sinPitch = Math.sin(b);
        cosPitch = Math.cos(b);
        spinSin = Math.sin(time * 0.22);
        spinCos = Math.cos(time * 0.22);

        celestialMachinery(c, time, false);
        energyCore(c, time);
        projectLattice(time);
        for (int layer = 0; layer < 4; layer++) {
            drawLattice(c, layer);
            c.beginPath();
            drawParticles(c, time, layer);
        }
        celestialMachinery(c, time, true);
        c.beginPath();
        c.setFillStyle(0xe0e8ffff);
        c.setFont("Sans Bold 27px");
        c.fillText("PARADOX ENGINE", 38, 73);
        c.setFont("Sans 12px");
        c.setFillStyle(0x91a7d3ff);
        c.fillText("TORUS KNOT   /   36 STRANDS   /   " + particleCount + " PARTICLES", 40, 96);
        c.fillText(withControls ? "DRAG TO ORBIT" : "PARADOX ENGINE", 40, 763);
        c.restore();
    }

    private void energyCore(Canvas c, double time) {
        for (int shell = 0; shell < 22; shell++) {
            double radius = 18 + shell * 3.1;
            c.beginPath();
            for (int j = 0; j <= 120; j++) {
                double angle = TAU * j / 120;
                double ripple = radius * (1 + 0.15 * Math.sin(angle * 7 + time * 1.7 + shell * 0.29)
                        + 0.08 * Math.cos(angle * 11 - time * 0.9));
                double x = 640 + ripple * Math.cos(angle);
                double y = 409 + ripple * Math.sin(angle);
                if (j == 0) c.moveTo(x, y);
                else c.lineTo(x, y);
            }
            c.setStrokeStyle((PALETTE[shell % PALETTE.length] << 8) | (170 - shell * 5));
            c.setLineWidth(shell % 5 == 0 ? 2 : 0.7);
            c.stroke();
        }
        c.beginPath();
        for (int ray = 0; ray < 64; ray++) {
            double angle = TAU * ray / 64 + time * 0.09;
            double radius = 125 + 55 * Math.sin(ray * 2.3 + time);
            c.moveTo(640 + 87 * Math.cos(angle), 409 + 87 * Math.sin(angle));
            c.lineTo(640 + radius * Math.cos(angle), 409 + radius * Math.sin(angle));
        }
        c.setStrokeStyle(0x83eaff36);
        c.setLineWidth(0.65);
        c.stroke();
    }

    private void celestialMachinery(Canvas c, double time, boolean front) {
        // Inclined orbital bands are depth-split around the central knot.
        for (int orbit = 0; orbit < 8; orbit++) {
            double radius = 303 + orbit * 17;
            double tilt = orbit * 0.43 + time * (orbit % 2 == 0 ? 0.045 : -0.055);
            double ct = Math.cos(tilt), st = Math.sin(tilt);
            int rgb = PALETTE[orbit % PALETTE.length] << 8;
            c.beginPath();
            boolean joined = false;
            for (int j = 0; j <= 360; j++) {
                double angle = TAU * j / 360;
                double depth = Math.sin(angle);
                if ((depth > 0) != front || (j / 13 + orbit) % 7 == 0) {
                    joined = false;
                    continue;
                }
                double x = radius * Math.cos(angle);
                double y = radius * 0.24 * Math.sin(angle);
                double px = 640 + x * ct - y * st;
                double py = 409 + x * st + y * ct;
                if (!joined) c.moveTo(px, py);
                else c.lineTo(px, py);
                joined = true;
            }
            c.setStrokeStyle(rgb | (front ? 90 : 34));
            c.setLineWidth(orbit % 3 == 0 ? 1.5 : 0.55);
            c.stroke();
            c.beginPath();
            for (int tick = 0; tick < 100; tick++) {
                double angle = TAU * tick / 100;
                if ((Math.sin(angle) > 0) != front) continue;
                double length = tick % 5 == 0 ? 8 : 3;
                double x = radius * Math.cos(angle), y = radius * 0.24 * Math.sin(angle);
                c.moveTo(640 + x * ct - y * st, 409 + x * st + y * ct);
                x = (radius + length) * Math.cos(angle);
                y = (radius + length) * 0.24 * Math.sin(angle);
                c.lineTo(640 + x * ct - y * st, 409 + x * st + y * ct);
            }
            c.setStrokeStyle(rgb | (front ? 110 : 42));
            c.setLineWidth(0.75);
            c.stroke();
            for (int satellite = 0; satellite < 3; satellite++) {
                double angle = time * (0.18 + orbit * 0.025) + satellite * TAU / 3 + orbit;
                if ((Math.sin(angle) > 0) != front) continue;
                double x = radius * Math.cos(angle), y = radius * 0.24 * Math.sin(angle);
                double px = 640 + x * ct - y * st, py = 409 + x * st + y * ct;
                c.save();
                c.translate(px, py);
                c.rotate(time * 0.8 + orbit);
                c.setStrokeStyle(rgb | 210);
                c.setLineWidth(1);
                c.strokeRect(-6, -6, 12, 12);
                c.rotate(Math.PI / 4);
                c.strokeRect(-10, -10, 20, 20);
                c.setFillStyle(0xe6ffffed);
                c.fillRect(-2, -2, 4, 4);
                c.restore();
            }
        }
        c.beginPath();
    }

    private void backdrop(Canvas c, double time) {
        for (double[] star : stars) {
            int alpha = (int)(25 + star[2] * (82 + 45 * Math.sin(time + star[0])));
            c.setFillStyle(0xb9dcff00 | alpha);
            double size = 0.7 + star[2] * 1.4;
            c.fillRect(star[0], star[1], size, size);
        }
        c.setLineWidth(0.8);
        for (int ring = 0; ring < 6; ring++) {
            double radius = 235 + ring * 42;
            c.beginPath();
            for (int i = 0; i <= 160; i++) {
                double a = TAU * i / 160;
                double wobble = radius + 5 * Math.sin(a * 5 - time + ring);
                double x = 640 + wobble * Math.cos(a) * 1.27;
                double y = 407 + wobble * Math.sin(a) * 0.78;
                if (i == 0) c.moveTo(x, y);
                else c.lineTo(x, y);
            }
            c.setStrokeStyle(ring % 2 == 0 ? 0x5266b937 : 0xa864cf25);
            c.stroke();
        }
        c.beginPath();
        for (int i = -12; i <= 12; i++) {
            c.moveTo(640 + i * 29, 593);
            c.lineTo(640 + i * 118, HEIGHT);
        }
        for (int i = 1; i <= 9; i++) {
            double y = 593 + i * i * 2.6;
            c.moveTo(0, y);
            c.lineTo(WIDTH, y);
        }
        c.setStrokeStyle(0x7b62ea20);
        c.stroke();
    }

    private void project(int step, double angle, double radius) {
        double u = Math.cos(angle) * radius, v = Math.sin(angle) * radius;
        double[] p = KNOT[step], n = NORMAL[step], b = BINORMAL[step];
        double x = p[0] + u * n[0] + v * b[0];
        double y = p[1] + u * n[1] + v * b[1];
        double pz = p[2] + u * n[2] + v * b[2];
        double x0 = x * spinCos - y * spinSin;
        double y0 = x * spinSin + y * spinCos;
        double x1 = x0 * cosYaw + pz * sinYaw;
        double z1 = -x0 * sinYaw + pz * cosYaw;
        double y1 = y0 * cosPitch - z1 * sinPitch;
        z = y0 * sinPitch + z1 * cosPitch;
        double perspective = 680 / (680 - z) * zoom;
        sx = 640 + x1 * perspective;
        sy = 409 + y1 * perspective;
    }

    private int depth() { return Math.max(0, Math.min(3, (int)((z + 210) / 105))); }

    private void projectLattice(double time) {
        for (int strand = 0; strand < STRANDS; strand++) {
            for (int i = 0; i < STEPS; i++) {
                double t = TAU * i / STEPS;
                double angle = TAU * strand / STRANDS + 4 * t + time * 0.62;
                double radius = 32 + 9 * Math.sin(6 * t - time * 2) + (pulse ? 13 : 0);
                project(i, angle, radius);
                xs[strand][i] = sx;
                ys[strand][i] = sy;
                depths[strand][i] = depth();
            }
        }
    }

    private void drawLattice(Canvas c, int layer) {
        for (int strand = 0; strand < STRANDS; strand++) {
            c.beginPath();
            boolean joined = false;
            for (int i = 0; i < STEPS; i += 3) {
                if (depths[strand][i] != layer) {
                    joined = false;
                    continue;
                }
                int next = (i + 3) % STEPS;
                if (!joined) c.moveTo(xs[strand][i], ys[strand][i]);
                c.lineTo(xs[strand][next], ys[strand][next]);
                joined = true;
            }
            int color = PALETTE[strand * PALETTE.length / STRANDS] << 8;
            if (strand % 4 == 0) {
                c.setStrokeStyle(color | (16 + layer * 6));
                c.setLineWidth(8 + layer);
                c.stroke();
            }
            c.setStrokeStyle(color | (62 + layer * 42));
            c.setLineWidth(0.65 + layer * 0.3);
            c.stroke();
        }
    }

    private void drawParticles(Canvas c, double time, int layer) {
        for (int index = 0; index < particleCount; index++) {
            double[] p = particles[index];
            int step = (int)((p[0] + time * (26 + p[2] * 46)) % STEPS);
            double angle = p[1] + time * (0.35 + p[3] * 0.8);
            double radius = 39 + p[2] * p[2] * (pulse ? 175 : 95);
            project(step, angle, radius);
            if (depth() != layer) continue;
            int color = PALETTE[Math.min(4, (int)(p[1] * 5 / TAU))] << 8;
            double size = 0.65 + p[3] * (0.95 + layer * 0.5);
            if (p[3] > 0.985) {
                c.setFillStyle(color | 30);
                c.fillRect(sx - 5, sy - 5, 11, 11);
                c.setFillStyle(0xf4ffffeb);
                c.fillRect(sx - 4, sy, 9, 1);
                c.fillRect(sx, sy - 4, 1, 9);
            } else {
                c.setFillStyle(color | (45 + layer * 33 + (int)(p[3] * 56)));
                c.fillRect(sx, sy, size, size);
            }
        }
    }
}
