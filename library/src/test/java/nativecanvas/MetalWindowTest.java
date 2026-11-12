package nativecanvas;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MetalWindowTest {
    @TempDir Path temp;

    @Test void presentsAndRebuildsTheMetalTargetAfterResize() throws Exception {
        assumeFalse(Boolean.getBoolean("native.skip"));
        assumeTrue("cocoa".equals(System.getenv("SDL_VIDEO_DRIVER")));
        assumeTrue(Canvas.isBackendAvailable(Canvas.Backend.SKIA));

        try (Canvas canvas = new Canvas("Metal integration test", 160, 120, true, Canvas.Backend.SKIA)) {
            assertEquals("skia-metal", canvas.presentationRenderer());
            canvas.fillRect(0, 0, 160, 120);
            canvas.present();

            canvas.setWindowSize(240, 180);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (canvas.width() != 240 || canvas.height() != 180) {
                assertTrue(System.nanoTime() < deadline, "Resize event did not arrive");
                assertTrue(canvas.pollEvents());
                Thread.sleep(5);
            }
            canvas.setFillStyle(0xff0000ff);
            canvas.fillRect(0, 0, 240, 180);
            canvas.present();

            Path png = temp.resolve("metal.png");
            canvas.writePng(png.toString());
            BufferedImage image = ImageIO.read(png.toFile());
            assertEquals(0xffff0000, image.getRGB(image.getWidth() - 1, image.getHeight() - 1));
        }
    }
}
