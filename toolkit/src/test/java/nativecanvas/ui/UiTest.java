package nativecanvas.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import nativecanvas.Canvas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class UiTest {
    @Test void computesNestedColumnBoundsAndOwnsChildren() {
        Label label = new Label("Title");
        Button button = new Button("Action", () -> {});
        Column nested = new Column(4, 0).add(button);
        Column root = new Column(10, 6).add(label).add(nested);
        new Ui(root, 200, 120);
        assertEquals(98, root.preferredHeight(200));
        assertEquals(10, label.x());
        assertEquals(10, label.y());
        assertEquals(180, label.width());
        assertEquals(14, button.x());
        assertEquals(46, button.y());
        assertEquals(172, button.width());
        assertThrows(IllegalArgumentException.class, () -> new Column(0, 0).add(button));
        assertThrows(IllegalArgumentException.class, () -> nested.add(root));
        assertThrows(IllegalArgumentException.class, () -> new Ui(root, 200, 120));
        assertThrows(IllegalArgumentException.class, () -> new Column(0, 0).add(root));
        assertThrows(UnsupportedOperationException.class, () -> root.children().clear());
    }

    @Test void activatesOnlyWhenPointerReleasesOnPressedControl() {
        int[] clicks = {0};
        Button button = new Button("Action", () -> clicks[0]++);
        Ui ui = new Ui(new Column(4, 0).add(button), 100, 80);
        ui.pointerPressed(10, 10);
        assertSame(button, ui.focusedControl());
        ui.pointerReleased(10, 70);
        assertEquals(0, clicks[0]);
        ui.pointerPressed(10, 10);
        ui.pointerReleased(10, 10);
        assertEquals(1, clicks[0]);
        ui.pointerReleased(10, 10);
        assertEquals(1, clicks[0]);
    }

    @Test void checkboxTogglesAndNotifiesOnActivation() {
        List<Boolean> values = new ArrayList<>();
        Checkbox checkbox = new Checkbox("Option", false, values::add);
        checkbox.activate();
        assertTrue(checkbox.isChecked());
        checkbox.activate();
        assertFalse(checkbox.isChecked());
        checkbox.setChecked(true);
        assertTrue(checkbox.isChecked());
        assertEquals(Arrays.asList(true, false), values);
    }

    @Test void focusWrapsAndScrollsClippedControlIntoView() {
        Button first = new Button("First", () -> {});
        Checkbox last = new Checkbox("Last", false, checked -> {});
        ScrollPane pane = new ScrollPane(new Column(0, 4).add(first).add(new Label("Divider")).add(last), 50);
        Ui ui = new Ui(pane, 120, 50);
        ui.moveFocus(false);
        assertSame(first, ui.focusedControl());
        ui.moveFocus(false);
        assertSame(last, ui.focusedControl());
        assertTrue(pane.scrollPosition() > 0);
        assertEquals(50, last.y() + last.height());
        ui.moveFocus(false);
        assertSame(first, ui.focusedControl());
        assertEquals(0, pane.scrollPosition());
        ui.moveFocus(true);
        assertSame(last, ui.focusedControl());
    }

    @Test void routesScrollAndClampsToViewport() {
        int[] calls = {0};
        Button first = new Button("First", () -> calls[0]++);
        Button last = new Button("Last", () -> calls[0] += 10);
        ScrollPane pane = new ScrollPane(new Column(0, 0).add(first).add(last), 38);
        Ui ui = new Ui(pane, 100, 38);
        ui.scroll(150, 10, 1000);
        assertEquals(0, pane.scrollPosition());
        ui.scroll(10, 10, 1000);
        assertEquals(38, pane.scrollPosition());
        ui.pointerPressed(10, 10);
        ui.pointerReleased(10, 10);
        assertEquals(10, calls[0]);
        ui.scroll(10, 10, -1000);
        assertEquals(0, pane.scrollPosition());
    }

    @Test void paintsAndClipsControls(@TempDir Path temp) throws Exception {
        assumeFalse(Boolean.getBoolean("native.skip"));
        ScrollPane pane = new ScrollPane(new Column(0, 0)
                .add(new Button("Visible", () -> {}))
                .add(new Checkbox("Clipped", true, checked -> {})), 30);
        Ui ui = new Ui(new Column(4, 0).add(pane), 140, 100);
        try (Canvas canvas = Canvas.offscreen(140, 100)) {
            canvas.rect(7, 8, 9, 10);
            ui.draw(canvas);
            assertEquals(9, canvas.pathBounds().width);
            Path png = temp.resolve("ui.png");
            canvas.writePng(png.toString());
            assertEquals(0xff26364a, ImageIO.read(png.toFile()).getRGB(6, 6));
            assertEquals(0xff18212c, ImageIO.read(png.toFile()).getRGB(6, 35));
        }
    }
    private static native void enqueueInput();

    @Test void dispatchesSdlPointerKeyboardScrollAndClose() {
        assumeFalse(Boolean.getBoolean("native.skip"));
        System.loadLibrary("nativecanvas_test");
        int[] clicks = {0};
        Button button = new Button("Action", () -> clicks[0]++);
        Checkbox checkbox = new Checkbox("Option", false, checked -> {});
        ScrollPane pane = new ScrollPane(new Column(4, 0).add(button).add(checkbox)
                .add(new Label("More")), 80);
        Ui ui = new Ui(pane, 120, 80);
        try (Canvas canvas = new Canvas("Toolkit input test", 120, 80)) {
            assertTrue(ui.poll(canvas));
            enqueueInput();
            assertFalse(ui.poll(canvas));
            assertEquals(1, clicks[0]);
            assertSame(checkbox, ui.focusedControl());
            assertTrue(checkbox.isChecked());
            assertEquals(30, pane.scrollPosition());
        }
    }

    @Test void paintsFocusedAndCheckedControlsAndReflectsScroll(@TempDir Path temp) throws Exception {
        assumeFalse(Boolean.getBoolean("native.skip"));
        Checkbox checkbox = new Checkbox("Option", true, checked -> {});
        ScrollPane pane = new ScrollPane(new Column(4, 4).add(checkbox).add(new Label("More")), 50);
        Ui ui = new Ui(pane, 120, 50);
        ui.moveFocus(false);
        try (Canvas canvas = Canvas.offscreen(120, 50)) {
            ui.draw(canvas);
            Path png = temp.resolve("focused.png");
            canvas.writePng(png.toString());
            assertEquals(0xff7acffa, ImageIO.read(png.toFile()).getRGB(18, 18));
            ui.scroll(20, 20, 20);
            ui.draw(canvas);
            canvas.writePng(png.toString());
            assertEquals(0xff18212c, ImageIO.read(png.toFile()).getRGB(100, 40));
        }
    }
    @Test void capturedControlReceivesLocalDragAndReleaseOutsideItsBounds() {
        List<String> events = new ArrayList<>();
        Control control = new Control() {
            @Override public void activate() { events.add("activate"); }
            @Override protected void onPointerPressed(double x, double y) {
                events.add("down " + x + " " + y);
            }
            @Override protected void onPointerDragged(double x, double y) {
                events.add("drag " + x + " " + y);
            }
            @Override protected void onPointerReleased(double x, double y) {
                events.add("up " + x + " " + y);
            }
        };
        Ui ui = new Ui(new Column(8, 0).add(control), 100, 100);
        ui.pointerDragged(10, 10);
        ui.pointerPressed(10, 12);
        ui.pointerDragged(110, 80);
        ui.pointerReleased(120, 90);
        ui.pointerDragged(10, 10);
        assertEquals(Arrays.asList("down 2.0 4.0", "drag 102.0 72.0", "up 112.0 82.0"), events);
    }
    @Test void relayoutsFillTracksAndClampsScrollOnWindowResize() {
        assumeFalse(Boolean.getBoolean("native.skip"));
        int[] clicks = {0};
        Button button = new Button("Last", () -> clicks[0]++);
        Column content = new Column(0, 0).add(new Label("One")).add(new Label("Two")).add(button);
        ScrollPane pane = new ScrollPane(content, 1);
        Grid root = new Grid(new Track[]{Track.FILL}, new Track[]{Track.AUTO, Track.FILL}, 4, 0, 4)
                .add(0, 0, new Label("Header")).add(1, 0, pane);
        try (Canvas canvas = new Canvas("Resize toolkit", 140, 80, true)) {
            Ui ui = new Ui(root, canvas.width(), canvas.height());
            ui.scroll(15, 50, 1000);
            assertTrue(pane.scrollPosition() > 0);
            assertEquals(42, pane.height());
            canvas.setWindowSize(240, 160);
            assertTrue(ui.poll(canvas));
            assertEquals(232, pane.width());
            assertEquals(122, pane.height());
            assertEquals(0, pane.scrollPosition());
            ui.pointerPressed(200, 100);
            ui.pointerReleased(200, 100);
            assertEquals(1, clicks[0]);
            ui.draw(canvas);
            canvas.present();
        }
    }
    private static native void queueRepaintOrClose(boolean close);

    @Test void eventLoopCoalescesEventsAndStopsOnClose() {
        assumeFalse(Boolean.getBoolean("native.skip"));
        System.loadLibrary("nativecanvas_test");
        int[] paints = {0};
        Widget widget = new Widget() {
            @Override public double preferredHeight(double width) { return 20; }

            @Override protected void paint(Canvas canvas) {
                int frame = ++paints[0];
                if (frame == 1) {
                    queueRepaintOrClose(false);
                    queueRepaintOrClose(false);
                } else if (frame == 2) {
                    queueRepaintOrClose(true);
                } else {
                    fail("Unexpected frame " + frame);
                }
            }
        };
        try (Canvas canvas = new Canvas("Event loop test", 100, 80)) {
            assertTrue(canvas.pollEvents());
            new Ui(widget, 100, 80).run(canvas);
            assertEquals(2, paints[0]);
        }
    }
}
