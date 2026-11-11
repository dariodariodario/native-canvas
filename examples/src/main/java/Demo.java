import nativecanvas.Canvas;

public final class Demo {
    public static void main(String[] args) throws InterruptedException {
        boolean offscreen = args.length > 0 && args[0].equals("--png");
        boolean smoke = args.length > 0 && args[0].equals("--smoke");
        try (Canvas canvas = offscreen ? Canvas.offscreen(640, 360)
                : new Canvas("Native Canvas", 640, 360)) {
            long start = System.nanoTime();
            Canvas.InputEvent event = new Canvas.InputEvent();
            boolean pointerPressed = false;
            boolean alternateColor = false;
            float pointerX = Float.NaN;
            float pointerY = Float.NaN;
            double scroll = 0;
            do {
                while (!offscreen && canvas.pollEvent(event)) {
                    switch (event.type()) {
                        case Canvas.InputEvent.CLOSE:
                            return;
                        case Canvas.InputEvent.KEY_DOWN:
                            if (event.keyCode() == 27) return;
                            if (!event.repeat()) alternateColor = !alternateColor;
                            break;
                        case Canvas.InputEvent.POINTER_DOWN:
                        case Canvas.InputEvent.POINTER_UP:
                            pointerPressed = event.type() == Canvas.InputEvent.POINTER_DOWN;
                            // Update the position from the button event too.
                        case Canvas.InputEvent.POINTER_MOVE:
                            pointerX = event.x();
                            pointerY = event.y();
                            break;
                        case Canvas.InputEvent.SCROLL:
                            scroll = Math.max(-30, Math.min(60, scroll + event.deltaY() * 4));
                            break;
                        default:
                            break;
                    }
                }
                double time = (System.nanoTime() - start) / 1e9;
                canvas.setFillStyle(0x172438ff);
                canvas.fillRect(0, 0, 640, 360);
                canvas.setFillStyle(alternateColor ? 0xffb559ff : 0x6ee7c7ff);
                canvas.fillRect(70 + 36 * Math.sin(time), 110, 120 + scroll, 120 + scroll);
                if (Float.isFinite(pointerX)) {
                    canvas.setFillStyle(pointerPressed ? 0xffb559ff : 0xffffffff);
                    canvas.fillRect(pointerX - 4, pointerY - 4, 8, 8);
                }
                canvas.setStrokeStyle(0xffb559ff);
                canvas.setLineWidth(4);
                canvas.beginPath();
                canvas.moveTo(220, 230);
                canvas.bezierCurveTo(270, 30, 350, 310, 470, 110);
                canvas.stroke();
                canvas.setFillStyle(0xffffffff);
                canvas.setFont("Sans Bold 24px");
                canvas.fillText("Cairo + Pango + SDL", 70, 294);
                canvas.setFont("Sans 18px");
                canvas.fillText("مرحبا · Καλημέρα · café", 70, 328);
                if (offscreen) {
                    canvas.writePng(args.length > 1 ? args[1] : "demo.png");
                    break;
                }
                canvas.present();
                Thread.sleep(16);
            } while (!smoke || System.nanoTime() - start < 1_000_000_000L);
        }
    }
}
