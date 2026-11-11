import java.util.ArrayList;
import java.util.List;
import nativecanvas.Canvas;
import nativecanvas.ui.Button;
import nativecanvas.ui.Column;
import nativecanvas.ui.Control;
import nativecanvas.ui.Label;
import nativecanvas.ui.Row;
import nativecanvas.ui.Track;
import nativecanvas.ui.Ui;

public final class PaintDemo {
    private static final int WIDTH = 840, HEIGHT = 620;

    private enum Tool { BRUSH, ERASER, LINE, RECTANGLE }

    public static void main(String[] args) {
        boolean smoke = args.length > 0 && "--smoke".equals(args[0]);
        Label status = new Label("Brush · Black · 4 px").setFont("Sans Bold", 16);
        Drawing drawing = new Drawing();
        Row tools = new Row(0, 6);
        for (Tool tool : Tool.values()) {
            String name = tool.name().substring(0, 1) + tool.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            tools.add(new Button(name, () -> {
                drawing.tool = tool;
                status.setText(name);
            }), Track.FILL);
        }
        tools.add(new Button("Undo", drawing::undo), Track.FILL)
                .add(new Button("Clear", drawing::clear), Track.FILL);
        Row colors = new Row(0, 6);
        addColor(colors, drawing, status, "Black", 0x172332ff);
        addColor(colors, drawing, status, "Red", 0xc12b45ff);
        addColor(colors, drawing, status, "Blue", 0x2456b4ff);
        addColor(colors, drawing, status, "Green", 0x23764fff);
        colors.add(new Button("Width", () -> {
            drawing.width = drawing.width == 16 ? 2 : drawing.width * 2;
            status.setText("Width: " + drawing.width + " px");
        }), Track.FILL);
        colors.add(new Button("Save PNG", () -> {
            try {
                drawing.export("paint.png");
                status.setText("Saved paint.png");
            } catch (IllegalStateException error) {
                status.setText("Save failed: " + error.getMessage());
            }
        }), Track.FILL);
        Column root = new Column(12, 6).add(tools).add(colors).add(status).add(drawing);
        Ui ui = new Ui(root, WIDTH, HEIGHT);

        if (args.length > 0 && "--png".equals(args[0])) {
            ui.pointerPressed(40, 180);
            ui.pointerDragged(110, 240);
            ui.pointerReleased(180, 220);
            drawing.export(args.length > 1 ? args[1] : "paint.png");
            return;
        }
        try (Canvas canvas = new Canvas("Native Canvas Paint", WIDTH, HEIGHT)) {
            if (smoke) {
                ui.poll(canvas);
                ui.draw(canvas);
                canvas.present();
            } else {
                ui.run(canvas);
            }
        }
    }

    private static void addColor(Row row, Drawing drawing, Label status, String name, int rgba) {
        row.add(new Button(name, () -> {
            drawing.color = rgba;
            if (drawing.tool == Tool.ERASER) drawing.tool = Tool.BRUSH;
            status.setText("Color: " + name);
        }), Track.FILL);
    }

    private static final class Drawing extends Control {
        private final List<Mark> marks = new ArrayList<>();
        private Mark pending;
        private Tool tool = Tool.BRUSH;
        private int color = 0x172332ff;
        private int width = 4;

        @Override public double preferredHeight(double availableWidth) { return 470; }
        @Override public void activate() {}

        @Override protected void onPointerPressed(double x, double y) {
            pending = new Mark(tool, color, width, point(x, y));
        }

        @Override protected void onPointerDragged(double x, double y) {
            if (pending != null) pending.move(point(x, y));
        }

        @Override protected void onPointerReleased(double x, double y) {
            if (pending == null) return;
            pending.move(point(x, y));
            marks.add(pending);
            pending = null;
        }

        private Point point(double x, double y) {
            return new Point(Math.max(0, Math.min(width(), x)), Math.max(0, Math.min(height(), y)));
        }

        void undo() {
            if (!marks.isEmpty()) marks.remove(marks.size() - 1);
        }

        void clear() { marks.clear(); }

        @Override protected void paint(Canvas canvas) {
            canvas.save();
            canvas.clipRect(x(), y(), width(), height());
            canvas.translate(x(), y());
            paintPaper(canvas);
            if (pending != null) pending.paint(canvas);
            canvas.restore();
        }

        private void paintPaper(Canvas canvas) {
            canvas.setFillStyle(0xffffffff);
            canvas.fillRect(0, 0, width(), height());
            for (Mark mark : marks) mark.paint(canvas);
        }

        void export(String filename) {
            try (Canvas image = Canvas.offscreen((int) width(), (int) height())) {
                paintPaper(image);
                image.writePng(filename);
            }
        }
    }

    private static final class Mark {
        private final List<Point> points = new ArrayList<>();
        private final Tool tool;
        private final int color, width;
        private Point end;

        Mark(Tool tool, int color, int width, Point start) {
            this.tool = tool;
            this.color = tool == Tool.ERASER ? 0xffffffff : color;
            this.width = tool == Tool.ERASER ? width * 3 : width;
            points.add(start);
            end = start;
        }

        void move(Point point) {
            if (end.x == point.x && end.y == point.y) return;
            end = point;
            if (tool == Tool.BRUSH || tool == Tool.ERASER) points.add(point);
        }

        void paint(Canvas canvas) {
            Point start = points.get(0);
            canvas.setStrokeStyle(color);
            canvas.setLineWidth(width);
            if (tool == Tool.RECTANGLE) {
                canvas.strokeRect(Math.min(start.x, end.x), Math.min(start.y, end.y),
                        Math.abs(end.x - start.x), Math.abs(end.y - start.y));
            } else if (start == end) {
                canvas.setFillStyle(color);
                canvas.fillRect(start.x - width / 2.0, start.y - width / 2.0, width, width);
            } else {
                canvas.beginPath();
                canvas.moveTo(start.x, start.y);
                if (tool == Tool.LINE) canvas.lineTo(end.x, end.y);
                else for (int i = 1; i < points.size(); i++) {
                    Point point = points.get(i);
                    canvas.lineTo(point.x, point.y);
                }
                canvas.stroke();
                canvas.beginPath();
            }
        }
    }

    private static final class Point {
        final double x, y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
