import nativecanvas.Canvas;
import nativecanvas.ui.*;

public final class ToolkitDemo {
    public static void main(String[] args) throws InterruptedException {
        boolean png = args.length > 0 && "--png".equals(args[0]);
        boolean smoke = args.length > 0 && "--smoke".equals(args[0]);
        try (Canvas canvas = png ? Canvas.offscreen(420, 360) : new Canvas("Native Canvas UI", 420, 360)) {
            Label status = new Label("Ready").setFont("Sans Bold", 18).setColor(0x7acffaff);
            Column options = new Column(0, 8);
            int[] clicks = {0};
            Row actions = new Row(0, 8)
                    .add(new Button("Increment", () -> status.setText("Clicks: " + ++clicks[0])), Track.FILL)
                    .add(new Button("Reset", () -> {
                        clicks[0] = 0;
                        status.setText("Clicks: 0");
                    }), Track.size(90));
            options.add(new TextField("", text -> status.setText("Name: " + text)));
            options.add(actions).add(new Label("Preferences"));
            Track[] rows = new Track[8];
            java.util.Arrays.fill(rows, Track.AUTO);
            Grid preferences = new Grid(new Track[]{Track.FILL, Track.FILL}, rows, 0, 8, 8);
            for (int i = 0; i < 16; i++) {
                String name = "Option " + (i + 1);
                preferences.add(i / 2, i % 2, new Checkbox(name, i == 0,
                        checked -> status.setText(name + (checked ? " enabled" : " disabled"))));
            }
            options.add(preferences);
            Column root = new Column(16, 8).add(status).add(new ScrollPane(options, 294));
            Ui ui = new Ui(root, 420, 360);
            if (png) {
                ui.draw(canvas);
                canvas.writePng(args.length > 1 ? args[1] : "toolkit.png");
                return;
            }
            do {
                if (!ui.poll(canvas)) break;
                ui.draw(canvas);
                canvas.present();
                if (!smoke) Thread.sleep(16);
            } while (!smoke);
        }
    }
}
