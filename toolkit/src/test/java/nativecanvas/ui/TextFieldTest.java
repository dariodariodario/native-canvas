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

class TextFieldTest {
    private static native void enqueueText();

    @Test void editsUnicodeAtCaretAndNotifiesOnlyOnChanges() {
        List<String> changes = new ArrayList<>();
        TextField field = new TextField("A😀B", changes::add);
        field.keyDown(0x40000050);
        field.keyDown(8);
        field.insert("é\n");
        field.keyDown(0x4000004a);
        field.keyDown(127);
        field.keyDown(0x4000004f);
        field.insert("中");
        field.keyDown(0x4000004d);
        field.insert("!");
        assertEquals("é中B!", field.text());
        assertEquals(Arrays.asList("AB", "AéB", "éB", "é中B", "é中B!"), changes);
        field.setText("x\r\ny\u2028z");
        assertEquals("xyz", field.text());
        assertEquals(5, changes.size());
    }

    @Test void receivesCommittedSdlTextAndStopsEditingWhenFocusMoves() {
        assumeFalse(Boolean.getBoolean("native.skip"));
        System.loadLibrary("nativecanvas_test");
        TextField field = new TextField("", value -> {});
        Button button = new Button("Next", () -> {});
        Ui ui = new Ui(new Column(0, 0).add(field).add(button), 200, 100);
        try (Canvas canvas = new Canvas("Text input test", 200, 100, false, Canvas.Backend.CAIRO)) {
            ui.moveFocus(false);
            assertTrue(ui.poll(canvas));
            enqueueText();
            assertTrue(ui.poll(canvas));
            assertEquals("é", field.text());
            assertSame(button, ui.focusedControl());
            enqueueText();
            assertTrue(ui.poll(canvas));
            assertEquals("é", field.text());
        }
    }

    @Test void styledLabelChangesLayoutAndPaintsItsColor(@TempDir Path temp) throws Exception {
        assumeFalse(Boolean.getBoolean("native.skip"));
        Label label = new Label("Hello").setFont("Sans Bold", 24).setColor(0xff0000ff);
        assertEquals(35, label.preferredHeight(200));
        assertEquals(100, label.preferredWidth());
        assertThrows(IllegalArgumentException.class, () -> label.setFont("Sans", 0));
        Ui ui = new Ui(new Column(0, 0).add(label), 200, 50);
        try (Canvas canvas = Canvas.offscreen(200, 50)) {
            ui.draw(canvas);
            Path png = temp.resolve("label.png");
            canvas.writePng(png.toString());
            assertTrue(Arrays.stream(ImageIO.read(png.toFile()).getRGB(0, 0, 200, 50, null, 0, 200))
                    .anyMatch(pixel -> pixel == 0xffff0000));
        }
    }
}
