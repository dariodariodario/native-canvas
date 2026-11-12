import java.util.*;
import nativecanvas.Canvas;
import nativecanvas.ui.*;

/** Procedural city flyover with perspective projection and radial lens distortion. */
public final class CityDemo {
    private static final int WIDTH = 1280, HEIGHT = 800;
    private static final double BLOCK = 125;
    private static final Canvas.Gradient SKY = Canvas.linearGradient(0, 0, 0, HEIGHT)
            .addColorStop(0, 0x080e2aff).addColorStop(0.43, 0x5d4272ff)
            .addColorStop(0.62, 0xf7ac8aff).addColorStop(1, 0x172b40ff);
    private double time, speed = 1, lens = 0.16, altitude = 235;
    private boolean paused;
    private int viewportWidth = WIDTH, viewportHeight = HEIGHT;
    private double cameraX, cameraZ, yaw, pitch, roll;
    private final List<Face> faces = new ArrayList<>();
    private static final int[] LIGHTS = {0xffd39b, 0x68e6ed, 0x7e9fff, 0xf59cc9};

    public static void main(String[] args) throws Exception {
        CityDemo city = new CityDemo();
        boolean png = args.length > 0 && "--png".equals(args[0]);
        boolean smoke = args.length > 0 && "--smoke".equals(args[0]);
        try (Canvas canvas = png ? Canvas.offscreen(WIDTH, HEIGHT)
                : new Canvas("City of Light", WIDTH, HEIGHT, true)) {
            if (png) {
                city.time = args.length > 2 ? Double.parseDouble(args[2]) : 6;
                if (!Double.isFinite(city.time) || city.time < 0) throw new IllegalArgumentException("Invalid time");
                city.render(canvas, WIDTH, HEIGHT);
                canvas.writePng(args.length > 1 ? args[1] : "city.png");
                return;
            }
            Label altitude = new Label(""), lens = new Label(""), speed = new Label("");
            Runnable refresh = () -> {
                altitude.setText("Altitude: " + (int)city.altitude);
                lens.setText(String.format(Locale.ROOT, "Lens distortion: %.2f", city.lens));
                speed.setText(String.format(Locale.ROOT, "Flight speed: %.2fx", city.speed));
            };
            refresh.run();
            Column panel = new Column(14, 8)
                    .add(new Label("CITY OF LIGHT").setFont("Sans Bold", 18).setColor(0xffcda2ff))
                    .add(new Checkbox("Pause flight", false, value -> city.paused = value))
                    .add(altitude).add(AnimatedToolkitDemo.adjustmentButtons(() -> city.altitude = Math.max(110, city.altitude - 25),
                            () -> city.altitude = Math.min(650, city.altitude + 25), refresh))
                    .add(lens).add(AnimatedToolkitDemo.adjustmentButtons(() -> city.lens = Math.max(-0.2, city.lens - 0.04),
                            () -> city.lens = Math.min(0.5, city.lens + 0.04), refresh))
                    .add(speed).add(AnimatedToolkitDemo.adjustmentButtons(() -> city.speed = Math.max(0.25, city.speed - 0.25),
                            () -> city.speed = Math.min(4, city.speed + 0.25), refresh));
            Widget scene = new Widget() {
                @Override public double preferredHeight(double width) { return 0; }
                @Override protected void paint(Canvas c) {
                    if (width() < 1 || height() < 1) return;
                    c.save();
                    c.clipRect(x(), y(), width(), height());
                    c.translate(x(), y());
                    city.render(c, (int)width(), (int)height());
                    c.restore();
                }
            };
            Ui ui = new Ui(new Grid(new Track[]{Track.FILL, Track.size(250)}, new Track[]{Track.FILL}, 0, 0, 0)
                    .add(0, 0, scene).add(0, 1, new ScrollPane(panel, 1)), WIDTH, HEIGHT);
            AnimatedToolkitDemo.run(canvas, ui, elapsed -> {
                if (!city.paused) city.time += elapsed * city.speed;
            }, 8, smoke);
        }
    }

    private void render(Canvas c, int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        cameraX = 62.5 + 30 * Math.sin(time * 0.12);
        cameraZ = time * 45;
        yaw = 0.11 * Math.sin(time * 0.09);
        pitch = 0.30 + 0.04 * Math.sin(time * 0.14);
        roll = 0.025 * Math.sin(time * 0.18);
        c.save();
        c.scale(width / (double)WIDTH, height / (double)HEIGHT);
        c.setFillStyle(SKY);
        c.fillRect(0, 0, WIDTH, HEIGHT);
        Canvas.Gradient sun = Canvas.radialGradient(880, 270, 5, 880, 270, 300)
                .addColorStop(0, 0xffe9baff).addColorStop(0.14, 0xffd1a2c0)
                .addColorStop(0.5, 0xee91803b).addColorStop(1, 0xe9877000);
        c.setFillStyle(sun);
        c.fillRect(500, 0, 780, 650);
        c.restore();

        ground(c);
        faces.clear();
        int centerZ = (int)Math.floor(cameraZ / BLOCK);
        int centerX = (int)Math.floor(cameraX / BLOCK);
        for (int z = centerZ - 2; z < centerZ + 28; z++) {
            for (int x = centerX - 15; x <= centerX + 15; x++) {
                long seed = hash(x, z);
                double h = 22 + ((seed >>> 8) & 127);
                if ((seed & 15) == 0) h += 95;
                double bx = x * BLOCK + 13, bz = z * BLOCK + 13;
                double bw = 68 + ((seed >>> 16) & 15), bd = 67 + ((seed >>> 21) & 15);
                Building b = new Building(bx, bz, bw, bd, h, seed);
                addFace(b, 0);
                if (cameraX < bx) addFace(b, 1);
                if (cameraX > bx + bw) addFace(b, 2);
                if (cameraZ < bz) addFace(b, 3);
                if (cameraZ > bz + bd) addFace(b, 4);
            }
        }
        faces.sort((a,b) -> Double.compare(b.distance, a.distance));
        for (Face face : faces) paintFace(c, face);
        c.beginPath();
        c.setFillStyle(0xf0dfceff);
        c.setFont("Sans Bold 23px");
        c.fillText("CITY OF LIGHT", 28, 45);
        c.setFont("Sans 12px");
        c.setFillStyle(0xf2d4bbbc);
        c.fillText("PROCEDURAL FLIGHT  /  RADIAL LENS", 29, 66);
    }

    private static long hash(int x, int z) {
        long n = x * 341873128712L + z * 132897987541L + 8127;
        n = (n ^ (n >>> 27)) * 0x3c79ac492ba7b653L;
        return n ^ (n >>> 33);
    }

    private double[] camera(double x, double y, double z) {
        double dx = x - cameraX, dy = y - altitude, dz = z - cameraZ;
        double rx = dx * Math.cos(yaw) - dz * Math.sin(yaw);
        double rz = dx * Math.sin(yaw) + dz * Math.cos(yaw);
        return new double[]{rx, dy * Math.cos(pitch) + rz * Math.sin(pitch),
                -dy * Math.sin(pitch) + rz * Math.cos(pitch)};
    }

    private double[] screen(double[] p) {
        double x = p[0] / p[2], y = -p[1] / p[2];
        double r2 = x * x + y * y;
        // Bounded radial distortion keeps the projection stable near screen edges.
        double factor = 1 + lens * r2 / (1 + r2);
        x *= factor;
        y *= factor;
        double rx = x * Math.cos(roll) - y * Math.sin(roll);
        double ry = x * Math.sin(roll) + y * Math.cos(roll);
        double focal = Math.min(viewportWidth * 0.83, viewportHeight * 1.10);
        return new double[]{viewportWidth / 2.0 + rx * focal, viewportHeight * 0.47 + ry * focal};
    }

    private boolean polygon(Canvas c, double[][] world) {
        List<double[]> input = new ArrayList<>();
        for (double[] point : world) input.add(camera(point[0], point[1], point[2]));
        List<double[]> clipped = new ArrayList<>();
        double[] previous = input.get(input.size() - 1);
        for (double[] point : input) {
            if ((previous[2] >= 8) != (point[2] >= 8)) {
                double t = (8 - previous[2]) / (point[2] - previous[2]);
                clipped.add(new double[]{previous[0] + t * (point[0] - previous[0]),
                        previous[1] + t * (point[1] - previous[1]), 8});
            }
            if (point[2] >= 8) clipped.add(point);
            previous = point;
        }
        if (clipped.size() < 3) return false;
        c.beginPath();
        previous = clipped.get(clipped.size() - 1);
        boolean first = true;
        // Subdivide edges so lens distortion bends architecture, including roads.
        for (double[] point : clipped) {
            for (int i = 1; i <= 4; i++) {
                double t = i / 4.0;
                double[] projected = screen(new double[]{previous[0] + t * (point[0] - previous[0]),
                        previous[1] + t * (point[1] - previous[1]), previous[2] + t * (point[2] - previous[2])});
                if (first) { c.moveTo(projected[0], projected[1]); first = false; }
                else c.lineTo(projected[0], projected[1]);
            }
            previous = point;
        }
        c.closePath();
        return true;
    }

    private void ground(Canvas c) {
        c.setFillStyle(0x121d2bff);
        if (polygon(c, new double[][]{{-10000,0,cameraZ-1000},{10000,0,cameraZ-1000},
                {10000,0,cameraZ+6000},{-10000,0,cameraZ+6000}})) c.fill();
        int base = (int)Math.floor(cameraZ / BLOCK);
        for (int i = -13; i <= 13; i++) {
            double x = i * BLOCK + 112;
            c.setFillStyle(0xf3ac6860);
            if (polygon(c,new double[][]{{x,0.1,cameraZ-150},{x+0.7,0.1,cameraZ-150},
                    {x+0.7,0.1,cameraZ+3500},{x,0.1,cameraZ+3500}})) c.fill();
            for (int j = base - 1; j < base + 27; j++) {
                double z = j * BLOCK + 112;
                c.setFillStyle(0x79c6dd45);
                if (polygon(c,new double[][]{{x-110,0.1,z},{x+15,0.1,z},{x+15,0.1,z+0.7},{x-110,0.1,z+0.7}})) c.fill();
                double travel = (time * (i % 2 == 0 ? 23 : -19) + j * 31) % BLOCK;
                if (travel < 0) travel += BLOCK;
                double carZ = j * BLOCK + travel;
                c.setFillStyle(i % 2 == 0 ? 0xffddbaff : 0xff6284ff);
                if (polygon(c,new double[][]{{x-4,1,carZ},{x-2,1,carZ},{x-2,1,carZ+7},{x-4,1,carZ+7}})) c.fill();
            }
        }
    }

    private void addFace(Building b, int side) {
        double cx = b.x + b.w / 2, cz = b.z + b.d / 2;
        if (side == 0 && altitude < b.h) return;
        double distance = camera(cx, side == 0 ? b.h : b.h/2, cz)[2];
        if (distance < -150 || distance > 3900) return;
        faces.add(new Face(b, side, distance));
    }

    private void paintFace(Canvas c, Face f) {
        Building b = f.building;
        double[][] corners = face(b, f.side, 0, 0, 1, 1);
        if (!polygon(c, corners)) return;
        double fog = Math.min(0.94, Math.max(0, f.distance / 3500));
        int base = f.side == 0 ? 0x40506a : (f.side == 1 || f.side == 3 ? 0x283d54 : 0x15283d);
        int low = blend(base, 0xad8595, fog);
        int high = blend(f.side == 0 ? 0x667285 : 0x486079, 0xad8595, fog);
        double[] top = screen(camera(b.x+b.w/2,b.h,b.z+b.d/2));
        double[] bottom = screen(camera(b.x+b.w/2,0,b.z+b.d/2));
        c.setFillStyle(Canvas.linearGradient(0,top[1],0,bottom[1]+1)
                .addColorStop(0,(high<<8)|255).addColorStop(1,(low<<8)|255));
        c.fill();
        c.setLineWidth(0.6);
        c.setStrokeStyle((blend(0x9eabc1,0xad8595,fog)<<8)|90);
        c.stroke();
        if (f.side == 0) {
            if (f.distance < 1500) {
                c.setFillStyle((blend(0x111f34,0xad8595,fog)<<8)|255);
                if (polygon(c,face(b,0,0.20,0.20,0.65,0.65))) c.fill();
                c.setFillStyle((LIGHTS[(int)(b.seed&3)]<<8)|180);
                if (polygon(c,face(b,0,0.12,0.08,0.88,0.10))) c.fill();
                for (int vent = 0; vent < 3; vent++) {
                    double offset = 0.25 + vent * 0.13;
                    c.setFillStyle((blend(0x8a94a4, 0xad8595, fog) << 8) | 255);
                    if (polygon(c, face(b, 0, offset, 0.72, offset + 0.08, 0.86))) c.fill();
                }
            }
            return;
        }
        if (f.distance > 2300) return;
        int floors = Math.max(2,(int)(b.h/10));
        int columns = f.distance > 1400 ? 3 : 6;
        int light = blend(LIGHTS[(int)(b.seed&3)],0xad8595,fog);
        for (int row=0;row<floors;row++) {
            for (int col=0;col<columns;col++) {
                if (((b.seed >>> ((row*7+col*3)%56)) & 3) == 0) continue;
                double u0=(col+0.22)/columns, u1=(col+0.68)/columns;
                double v0=(row+0.25)/floors, v1=(row+0.62)/floors;
                c.setFillStyle((light<<8)|(f.distance<1000?220:130));
                if(polygon(c,face(b,f.side,u0,v0,u1,v1))) c.fill();
            }
        }
    }

    private static double[][] face(Building b,int side,double u0,double v0,double u1,double v1) {
        if(side==0) return new double[][]{{b.x+u0*b.w,b.h,b.z+v0*b.d},{b.x+u1*b.w,b.h,b.z+v0*b.d},
                {b.x+u1*b.w,b.h,b.z+v1*b.d},{b.x+u0*b.w,b.h,b.z+v1*b.d}};
        double x0=side==1?b.x:side==2?b.x+b.w:b.x+u0*b.w;
        double x1=side==1?b.x:side==2?b.x+b.w:b.x+u1*b.w;
        double z0=side==3?b.z:side==4?b.z+b.d:b.z+u0*b.d;
        double z1=side==3?b.z:side==4?b.z+b.d:b.z+u1*b.d;
        return new double[][]{{x0,v0*b.h,z0},{x1,v0*b.h,z1},{x1,v1*b.h,z1},{x0,v1*b.h,z0}};
    }

    private static int blend(int a,int b,double t) {
        int r=(int)(((a>>16)&255)*(1-t)+((b>>16)&255)*t);
        int g=(int)(((a>>8)&255)*(1-t)+((b>>8)&255)*t);
        int blue=(int)((a&255)*(1-t)+(b&255)*t);
        return (r<<16)|(g<<8)|blue;
    }

    private static final class Building {
        final double x,z,w,d,h;
        final long seed;
        Building(double x,double z,double w,double d,double h,long seed) {
            this.x=x;this.z=z;this.w=w;this.d=d;this.h=h;this.seed=seed;
        }
    }

    private static final class Face {
        final Building building;
        final int side;
        final double distance;
        Face(Building building,int side,double distance) {
            this.building=building;this.side=side;this.distance=distance;
        }
    }
}
