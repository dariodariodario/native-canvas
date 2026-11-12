package nativecanvas;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CanvasTest {
    @TempDir Path temp;

    @BeforeEach void requireNativeLibrary() {
        assumeFalse(Boolean.getBoolean("native.skip"));
    }

    @Test void defaultsToAnAvailableBackend() {
        String previous = System.clearProperty("nativecanvas.backend");
        try (Canvas canvas = Canvas.offscreen(1, 1)) {
            Canvas.Backend expected = Canvas.isBackendAvailable(Canvas.Backend.SKIA)
                    ? Canvas.Backend.SKIA : Canvas.Backend.CAIRO;
            assertEquals(expected, canvas.backend());
        } finally {
            if (previous != null) System.setProperty("nativecanvas.backend", previous);
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class)
    void shapedCaretFollowsRightToLeftText(Canvas.Backend backend) {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(32, 32, backend)) {
            canvas.setFont("Sans 18px");
            assertTrue(canvas.textCaretX("אבג", 0) > canvas.textCaretX("אבג", 3));
            assertTrue(canvas.textCaretX("abc", 0) < canvas.textCaretX("abc", 3));
        }
    }

    @Test void skiaClipBoundsRetainUserSpaceGeometry() {
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.SKIA));
        try (Canvas canvas = Canvas.offscreen(80, 80, Canvas.Backend.SKIA)) {
            canvas.translate(30, 30);
            canvas.rotate(Math.PI / 4);
            canvas.save();
            canvas.clipRect(0, 0, 10, 10);
            Canvas.Bounds clip = canvas.clipBounds();
            assertEquals(10, clip.width, 0.001);
            assertEquals(10, clip.height, 0.001);
            canvas.restore();
            assertTrue(canvas.clipBounds().width > 10);
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class)
    void signedPathRectanglesPreserveWinding(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(32, 32, backend)) {
            canvas.rect(0, 0, 30, 30);
            canvas.rect(20, 10, -10, 10);
            canvas.fill();
            BufferedImage result = pixels(canvas);
            assertEquals(0xff000000, result.getRGB(5, 5));
            assertEquals(0, result.getRGB(15, 15));
        }
    }

    @Test void underflowingCairoScaleLeavesTheContextUsable() throws Exception {
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.CAIRO));
        try (Canvas canvas = Canvas.offscreen(2, 2, Canvas.Backend.CAIRO)) {
            canvas.save();
            canvas.scale(1e-200, 1e-200);
            canvas.restore();
            canvas.fillRect(0, 0, 2, 2);
            assertEquals(0xff000000, pixels(canvas).getRGB(1, 1));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class)
    void pathsRetainCoordinatesAcrossTransformChanges(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(40, 20, backend)) {
            canvas.translate(5, 0);
            canvas.beginPath();
            canvas.rect(0, 0, 5, 5);
            canvas.translate(10, 0);
            canvas.rect(0, 0, 5, 5);
            canvas.fill();
            canvas.translate(10, 0);
            canvas.fill();
            BufferedImage image = pixels(canvas);
            assertEquals(0xff000000, image.getRGB(7, 2));
            assertEquals(0xff000000, image.getRGB(17, 2));
            assertEquals(0, image.getRGB(27, 2));
        }
    }

    @Test void cachedTextPreservesPaintAndFontChanges() throws Exception {
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.SKIA));
        try (Canvas canvas = Canvas.offscreen(80, 40, Canvas.Backend.SKIA)) {
            canvas.setFont("Sans 24px");
            canvas.setFillStyle(0xff0000ff);
            canvas.fillText("M", 2, 30);
            canvas.setFillStyle(0x0000ffff);
            canvas.fillText("M", 40, 30);
            BufferedImage image = pixels(canvas);
            boolean red = false, blue = false;
            for (int pixel : image.getRGB(0, 0, 80, 40, null, 0, 80)) {
                red |= pixel == 0xffff0000;
                blue |= pixel == 0xff0000ff;
            }
            assertTrue(red && blue);
            double large = canvas.measureText("M").width;
            canvas.setFont("Sans 12px");
            assertTrue(canvas.measureText("M").width < large);
        }
    }

    private BufferedImage pixels(Canvas canvas) throws Exception {
        Path png = temp.resolve("result.png");
        canvas.writePng(png.toString());
        return ImageIO.read(png.toFile());
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void paintsAndClearsPixelsAcrossAutomaticBatches(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(24, 16, backend)) {
            canvas.setFillStyle(0xff0000ff);
            for (int i = 0; i < 20_000; i++) canvas.fillRect(0, 0, 24, 16);
            canvas.clearRect(0, 0, 4, 4);
            canvas.setStrokeStyle(0x00ff00ff);
            canvas.setLineWidth(2);
            canvas.strokeRect(8, 4, 8, 8);
            BufferedImage image = pixels(canvas);
            assertEquals(0, image.getRGB(1, 1));
            assertEquals(0xffff0000, image.getRGB(5, 5));
            assertEquals(0xff00ff00, image.getRGB(8, 8));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void savesTransformStyleAndClipWithoutDiscardingPath(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(32, 32, backend)) {
            canvas.rect(2, 2, 10, 10);
            canvas.setLineWidth(4);
            assertEquals(10, canvas.pathBounds().width);
            assertEquals(10, canvas.fillBounds().width);
            assertEquals(14, canvas.strokeBounds().width);
            canvas.setFillStyle(0x0000ffff);
            canvas.save();
            canvas.clip();
            assertEquals(10, canvas.clipBounds().width);
            canvas.translate(5, 5);
            canvas.scale(2, 2);
            canvas.rotate(Math.PI / 2);
            canvas.setFillStyle(0xff0000ff);
            canvas.fillRect(0, 0, 10, 10);
            canvas.restore();
            assertEquals(32, canvas.clipBounds().width);
            assertEquals(10, canvas.pathBounds().width);
            canvas.fill();
            BufferedImage image = pixels(canvas);
            assertEquals(0xff0000ff, image.getRGB(4, 4));
            assertEquals(0, image.getRGB(14, 14));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void buildsAndStrokesCurvedPaths(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(30, 30, backend)) {
            canvas.beginPath();
            canvas.moveTo(2, 3);
            canvas.lineTo(18, 3);
            canvas.bezierCurveTo(24, 3, 24, 17, 18, 17);
            canvas.closePath();
            assertTrue(canvas.pathBounds().width > 16);
            canvas.setStrokeStyle(0xffffffff);
            canvas.stroke();
            assertNotEquals(0, pixels(canvas).getRGB(10, 3));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void measuresQueuedFontAndRendersUnicodeText(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(200, 60, backend)) {
            canvas.setFont("Sans 12px");
            double small = canvas.measureText("Καλημέρα").width;
            canvas.setFont("Sans 24px");
            Canvas.TextMetrics metrics = canvas.measureText("Καλημέρα");
            assertTrue(metrics.width > small);
            assertTrue(metrics.height > 0 && metrics.baseline > 0 && metrics.inkWidth > 0);
            assertEquals(0, pixels(canvas).getRGB(2, 2));
            canvas.setFillStyle(0xffffffff);
            canvas.fillText("Καλημέρα", 4, 40);
            BufferedImage image = pixels(canvas);
            boolean painted = false;
            for (int pixel : image.getRGB(0, 0, 200, 60, null, 0, 200))
                painted |= (pixel >>> 24) != 0;
            assertTrue(painted);
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void loadsScalesAndCompositesPngAndEnforcesImageOwnership(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        Path sprite = temp.resolve("sprite.png");
        try (Canvas source = Canvas.offscreen(4, 4, backend);
             Canvas target = Canvas.offscreen(16, 16, backend)) {
            source.setFillStyle(0xff000080);
            source.fillRect(0, 0, 4, 4);
            source.writePng(sprite.toString());
            target.setFillStyle(0x0000ffff);
            target.fillRect(0, 0, 16, 16);
            try (Canvas.Image image = target.loadImage(sprite.toString())) {
                assertEquals(4, image.width());
                assertEquals(4, image.height());
                assertThrows(IllegalArgumentException.class, () -> source.drawImage(image, 0, 0, 4, 4));
                target.drawImage(image, 4, 4, 8, 8);
            }
            BufferedImage image = pixels(target);
            assertEquals(0xff80007f, image.getRGB(4, 4));
            assertEquals(0xff0000ff, image.getRGB(12, 4));
            assertThrows(IllegalStateException.class, () -> target.loadImage(temp.resolve("missing").toString()));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void enforcesCanvasLifecycleAndThreadOwnership(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        assertThrows(IllegalArgumentException.class, () -> Canvas.offscreen(0, 1, backend));
        try (Canvas canvas = Canvas.offscreen(2, 2, backend)) {
            FutureTask<Void> task = new FutureTask<>(() -> { canvas.flush(); return null; });
            new Thread(task).start();
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, task::get).getCause());
            assertThrows(IllegalStateException.class, canvas::present);
            assertThrows(IllegalStateException.class, canvas::pollEvents);
            canvas.close();
            assertThrows(IllegalStateException.class, canvas::flush);
        }
    }

    @Test void presentsAndPollsAWindowUsingSdlDummyVideo() {
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.CAIRO));
        try (Canvas canvas = new Canvas("Test", 64, 48, false, Canvas.Backend.CAIRO)) {
            assertEquals("software", canvas.presentationRenderer());
            canvas.fillRect(0, 0, 64, 48);
            canvas.present();
            assertTrue(canvas.pollEvents());
            assertFalse(canvas.pollEvent(new Canvas.InputEvent()));
        }
    }
    @ParameterizedTest @EnumSource(Canvas.Backend.class) void rectangleClipIntersectsExistingClipAndKeepsPath(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(20, 20, backend)) {
            canvas.rect(1, 2, 3, 4);
            canvas.clipRect(4, 4, 10, 10);
            canvas.save();
            canvas.clipRect(8, 2, 10, 8);
            Canvas.Bounds clip = canvas.clipBounds();
            assertAll(() -> assertEquals(8, clip.x), () -> assertEquals(4, clip.y),
                    () -> assertEquals(6, clip.width), () -> assertEquals(6, clip.height));
            canvas.fillRect(0, 0, 20, 20);
            canvas.restore();
            assertEquals(10, canvas.clipBounds().width);
            assertEquals(3, canvas.pathBounds().width);
            BufferedImage image = pixels(canvas);
            assertEquals(0xff000000, image.getRGB(8, 4));
            assertEquals(0, image.getRGB(7, 4));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void restoresFontAndIgnoresRestoreUnderflow(Canvas.Backend backend) {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(20, 20, backend)) {
            assertEquals(backend == Canvas.Backend.SKIA ? "skia-raster" : "offscreen", canvas.presentationRenderer());
            canvas.restore();
            canvas.setFont("Sans 12px");
            double original = canvas.measureText("ABC").width;
            canvas.save();
            canvas.setFont("Sans 24px");
            assertTrue(canvas.measureText("ABC").width > original);
            canvas.restore();
            assertEquals(original, canvas.measureText("ABC").width);
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void imageCloseIsIdempotentAndClosedImagesCannotBeQueued(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(4, 4, backend)) {
            Path png = temp.resolve("image.png");
            canvas.writePng(png.toString());
            Canvas.Image image = canvas.loadImage(png.toString());
            image.close();
            image.close();
            assertThrows(IllegalArgumentException.class, () -> canvas.drawImage(image, 0, 0, 4, 4));
            Canvas.Image owned = canvas.loadImage(png.toString());
            canvas.close();
            assertDoesNotThrow(owned::close);
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void immediateDrawingPreservesCurrentPathAndInvalidTransformsDoNotPoisonIt(Canvas.Backend backend) {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas canvas = Canvas.offscreen(20, 20, backend)) {
            canvas.rect(2, 3, 4, 5);
            canvas.scale(0, 1);
            canvas.rotate(Double.NaN);
            canvas.setLineWidth(-1);
            canvas.fillRect(0, 0, 2, 2);
            canvas.strokeRect(0, 0, 2, 2);
            canvas.clearRect(0, 0, 2, 2);
            canvas.fillText("A", 0, 10);
            Canvas.Bounds path = canvas.pathBounds();
            assertAll(() -> assertEquals(2, path.x), () -> assertEquals(3, path.y),
                    () -> assertEquals(4, path.width), () -> assertEquals(5, path.height));
            assertEquals(5, canvas.strokeBounds().width);
        }
    }

    private static native void enqueueInput();

    @Test void decodesSdlInputAndTracksCloseRequests() {
        System.loadLibrary("nativecanvas_test");
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.CAIRO));
        try (Canvas canvas = new Canvas("Input test", 64, 48, false, Canvas.Backend.CAIRO)) {
            assertTrue(canvas.pollEvents());
            enqueueInput();
            Canvas.InputEvent event = new Canvas.InputEvent();
            expect(canvas, event, Canvas.InputEvent.KEY_DOWN);
            assertEquals('a', event.keyCode());
            assertEquals(4, event.scanCode());
            assertEquals(1, event.modifiers());
            assertTrue(event.repeat());
            expect(canvas, event, Canvas.InputEvent.KEY_UP);
            assertFalse(event.repeat());
            expect(canvas, event, Canvas.InputEvent.POINTER_MOVE);
            assertEquals(31.5f, event.x());
            assertEquals(12f, event.y());
            assertEquals(-2f, event.deltaX());
            expect(canvas, event, Canvas.InputEvent.POINTER_DOWN);
            assertEquals(1, event.button());
            assertEquals(2, event.clicks());
            expect(canvas, event, Canvas.InputEvent.POINTER_UP);
            expect(canvas, event, Canvas.InputEvent.SCROLL);
            assertEquals(2f, event.deltaY());
            assertEquals(31.5f, event.x());
            expect(canvas, event, Canvas.InputEvent.CLOSE);
            assertFalse(canvas.pollEvent(event));
            assertFalse(canvas.pollEvents());
        }
    }

    private static void expect(Canvas canvas, Canvas.InputEvent event, int type) {
        assertTrue(canvas.pollEvent(event));
        assertEquals(type, event.type());
    }
    @Test void resizesWindowBackingThroughBothPollingApis() throws Exception {
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.CAIRO));
        try (Canvas canvas = new Canvas("Resize test", 64, 48, true, Canvas.Backend.CAIRO)) {
            assertEquals(64, canvas.width());
            assertEquals(48, canvas.height());
            canvas.setFillStyle(0xff0000ff);
            canvas.fillRect(0, 0, 64, 48);
            canvas.setWindowSize(96, 72);
            Canvas.InputEvent event = new Canvas.InputEvent();
            boolean resized = false;
            while (canvas.pollEvent(event)) {
                if (event.type() != Canvas.InputEvent.RESIZE) continue;
                assertEquals(96, event.width());
                assertEquals(72, event.height());
                resized = true;
            }
            assertTrue(resized);
            assertEquals(96, canvas.width());
            assertEquals(72, canvas.height());
            assertEquals(0, pixels(canvas).getRGB(5, 5));
            canvas.fillRect(90, 66, 6, 6);
            canvas.present();
            BufferedImage larger = pixels(canvas);
            assertEquals(96, larger.getWidth());
            assertEquals(72, larger.getHeight());
            assertEquals(0xff000000, larger.getRGB(95, 71));
            canvas.setWindowSize(32, 24);
            assertTrue(canvas.pollEvents());
            assertEquals(32, canvas.width());
            assertEquals(24, pixels(canvas).getHeight());
        }
    }
    @ParameterizedTest @EnumSource(Canvas.Backend.class) void linearGradientSnapshotsStopsAndSurvivesSaveRestore(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        Canvas.Gradient gradient = Canvas.linearGradient(0, 0, 20, 0)
                .addColorStop(1, 0x0000ffff).addColorStop(0, 0xff0000ff);
        try (Canvas canvas = Canvas.offscreen(20, 20, backend)) {
            canvas.setFillStyle(gradient);
            gradient.addColorStop(0.5, 0x00ff00ff);
            canvas.save();
            canvas.setFillStyle(0xffffffff);
            canvas.fillRect(0, 0, 20, 4);
            canvas.restore();
            canvas.fillRect(0, 4, 20, 8);
            canvas.setStrokeStyle(gradient);
            canvas.setLineWidth(2);
            canvas.strokeRect(2, 14, 16, 4);
            BufferedImage image = pixels(canvas);
            assertEquals(0xffffffff, image.getRGB(10, 1));
            assertEquals(0, (image.getRGB(10, 6) >>> 8) & 255);
            assertTrue((image.getRGB(1, 6) & 255) < (image.getRGB(18, 6) & 255));
            assertTrue(((image.getRGB(10, 14) >>> 8) & 255) > 220);
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void radialFillAndStrokeFollowTransformsAndClipping(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        Canvas.Gradient gradient = Canvas.radialGradient(0, 0, 1, 0, 0, 10)
                .addColorStop(0, 0xffffffff).addColorStop(1, 0xffffff00);
        try (Canvas canvas = Canvas.offscreen(40, 30, backend)) {
            canvas.translate(20, 15);
            canvas.scale(2, 1);
            canvas.clipRect(-5, -10, 15, 20);
            canvas.setFillStyle(gradient);
            canvas.fillRect(-10, -10, 20, 10);
            canvas.setStrokeStyle(gradient);
            canvas.setLineWidth(2);
            canvas.strokeRect(-10, 3, 20, 6);
            BufferedImage image = pixels(canvas);
            assertEquals(255, image.getRGB(20, 14) >>> 24);
            assertTrue((image.getRGB(35, 14) >>> 24) < 100);
            assertEquals(0, image.getRGB(9, 14));
            assertTrue((image.getRGB(20, 18) >>> 24) > (image.getRGB(36, 18) >>> 24));
        }
    }

    @ParameterizedTest @EnumSource(Canvas.Backend.class) void gradientValidatesArgumentsAndEmptyGradientIsTransparent(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        assertThrows(IllegalArgumentException.class, () -> Canvas.linearGradient(0, 0, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Canvas.radialGradient(0, 0, -1, 0, 0, 2));
        Canvas.Gradient empty = Canvas.linearGradient(0, 0, 5, 5);
        assertThrows(IllegalArgumentException.class, () -> empty.addColorStop(1.1, 0));
        assertThrows(IllegalArgumentException.class, () -> empty.addColorStop(Double.NaN, 0));
        try (Canvas canvas = Canvas.offscreen(5, 5, backend)) {
            canvas.setFillStyle(empty);
            canvas.fillRect(0, 0, 5, 5);
            assertEquals(0, pixels(canvas).getRGB(2, 2));
        }
    }
    private static native void exposeDuringResize();

    @Test void redrawsDuringExposeBeforePollingAndCanRemoveCallback() throws Exception {
        System.loadLibrary("nativecanvas_test");
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.CAIRO));
        try (Canvas canvas = new Canvas("Live resize", 64, 48, true, Canvas.Backend.CAIRO)) {
            int[] calls = {0};
            canvas.setLiveResizeRedraw(() -> {
                calls[0]++;
                assertEquals(96, canvas.width());
                assertEquals(72, canvas.height());
                assertThrows(IllegalStateException.class, canvas::pollEvents);
                canvas.setFillStyle(0x123456ff);
                canvas.fillRect(0, 0, canvas.width(), canvas.height());
            });
            canvas.setWindowSize(96, 72);
            int before = calls[0];
            exposeDuringResize();
            assertEquals(before + 1, calls[0]);
            assertEquals(0xff123456, pixels(canvas).getRGB(95, 71));
            canvas.setLiveResizeRedraw(null);
            exposeDuringResize();
            assertEquals(before + 1, calls[0]);
        }
    }
    @ParameterizedTest @EnumSource(Canvas.Backend.class) void restoresAntialiasQualityWithDrawingState(Canvas.Backend backend) throws Exception {
        assumeTrue(Canvas.isBackendAvailable(backend));
        try (Canvas reference = Canvas.offscreen(32, 32, backend); Canvas canvas = Canvas.offscreen(32, 32, backend)) {
            canvas.save();
            canvas.setAntialias(Canvas.Antialias.FAST);
            canvas.fillRect(1.25, 1.25, 8.5, 8.5);
            canvas.restore();
            canvas.clearRect(0, 0, 32, 32);
            for (Canvas target : new Canvas[]{reference, canvas}) {
                target.moveTo(2.1, 3.2);
                target.lineTo(27.7, 24.3);
                target.setLineWidth(1.3);
                target.stroke();
            }
            BufferedImage expected = pixels(reference);
            BufferedImage actual = pixels(canvas);
            assertArrayEquals(expected.getRGB(0, 0, 32, 32, null, 0, 32),
                    actual.getRGB(0, 0, 32, 32, null, 0, 32));
            assertThrows(NullPointerException.class, () -> canvas.setAntialias(null));
            canvas.setAntialias(Canvas.Antialias.DEFAULT);
            canvas.flush();
        }
    }
}
