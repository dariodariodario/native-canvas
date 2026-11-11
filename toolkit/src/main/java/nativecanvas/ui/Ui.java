package nativecanvas.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nativecanvas.Canvas;

/** Owns layout, focus, and input dispatch for one widget tree. */
public final class Ui {
    private final Widget root;
    private final Canvas.InputEvent event = new Canvas.InputEvent();
    private Control focused, hovered, pressed;
    private boolean textInputEnabled;
    private boolean repaintRequested = true;
    private double pointerX = -1, pointerY = -1;

    public Ui(Widget root, int width, int height) {
        this.root = Objects.requireNonNull(root);
        if (root.parent != null || root.uiRoot || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid UI root or viewport");
        root.layout(0, 0, width, height);
        root.uiRoot = true;
    }

    public void layout() { resize((int) root.width(), (int) root.height()); }

    /** Relayouts the tree for a new logical viewport size. */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid viewport");
        root.layout(0, 0, width, height);
        pointerMoved(pointerX, pointerY);
    }

    public Control focusedControl() { return focused; }

    public void moveFocus(boolean backwards) {
        List<Control> controls = new ArrayList<>();
        collectControls(root, controls);
        if (controls.isEmpty()) return;
        int index = controls.indexOf(focused);
        if (index < 0 && backwards) index = 0;
        focus(controls.get(Math.floorMod(index + (backwards ? -1 : 1), controls.size())));
        for (Container parent = focused.parent; parent != null; parent = parent.parent)
            if (parent instanceof ScrollPane) ((ScrollPane) parent).reveal(focused);
    }

    private static void collectControls(Widget widget, List<Control> controls) {
        if (widget instanceof Control) controls.add((Control) widget);
        if (widget instanceof Container)
            for (Widget child : ((Container) widget).children()) collectControls(child, controls);
    }

    private void focus(Control control) {
        if (focused != null) focused.focused = false;
        focused = control;
        if (focused != null) focused.focused = true;
    }

    public void pointerMoved(double x, double y) {
        pointerX = x;
        pointerY = y;
        Widget hit = root.hit(x, y);
        Control next = hit instanceof Control ? (Control) hit : null;
        if (hovered == next) return;
        if (hovered != null) hovered.hovered = false;
        hovered = next;
        repaintRequested = true;
        if (hovered != null) hovered.hovered = true;
    }

    public void pointerDragged(double x, double y) {
        pointerMoved(x, y);
        if (pressed != null) {
            pressed.onPointerDragged(x - pressed.x(), y - pressed.y());
            repaintRequested = true;
        }
    }

    public void pointerPressed(double x, double y) {
        pointerMoved(x, y);
        if (pressed != null) pressed.pressed = false;
        if (focused != hovered || pressed != null || hovered != null) repaintRequested = true;
        focus(hovered);
        pressed = hovered;
        if (pressed != null) {
            pressed.pressed = true;
            pressed.onPointerPressed(x - pressed.x(), y - pressed.y());
        }
    }

    public void pointerReleased(double x, double y) {
        pointerMoved(x, y);
        Control target = pressed;
        pressed = null;
        if (target != null) {
            repaintRequested = true;
            target.pressed = false;
            target.onPointerReleased(x - target.x(), y - target.y());
            if (target == hovered) target.activate();
        }
    }

    public void scroll(double x, double y, double delta) {
        for (Widget widget = root.hit(x, y); widget != null; widget = widget.parent) {
            if (widget instanceof ScrollPane) {
                ScrollPane pane = (ScrollPane) widget;
                double oldScroll = pane.scrollPosition();
                pane.scrollBy(delta);
                if (oldScroll != pane.scrollPosition()) repaintRequested = true;
                break;
            }
        }
        pointerMoved(x, y);
    }

    /** Reads SDL input. Returns false on a window close request. */
    public boolean poll(Canvas canvas) {
        layout();
        syncTextInput(canvas, focused instanceof TextField);
        while (canvas.pollEvent(event)) {
            switch (event.type()) {
                case Canvas.InputEvent.CLOSE:
                    syncTextInput(canvas, false);
                    return false;
                case Canvas.InputEvent.RESIZE:
                    resize(event.width(), event.height());
                    repaintRequested = true;
                    break;
                case Canvas.InputEvent.EXPOSED:
                    repaintRequested = true;
                    break;
                case Canvas.InputEvent.TEXT_INPUT:
                    if (focused instanceof TextField) {
                        ((TextField) focused).insert(event.text());
                        repaintRequested = true;
                    }
                    break;
                case Canvas.InputEvent.POINTER_MOVE: pointerDragged(event.x(), event.y()); break;
                case Canvas.InputEvent.POINTER_DOWN:
                    if (event.button() == 1) pointerPressed(event.x(), event.y());
                    break;
                case Canvas.InputEvent.POINTER_UP:
                    if (event.button() == 1) pointerReleased(event.x(), event.y());
                    break;
                case Canvas.InputEvent.SCROLL: scroll(event.x(), event.y(), -event.deltaY() * 32); break;
                case Canvas.InputEvent.KEY_DOWN:
                    if (event.keyCode() == 9) {
                        moveFocus((event.modifiers() & 3) != 0);
                        repaintRequested = true;
                    }
                    else if (focused instanceof TextField) {
                        ((TextField) focused).keyDown(event.keyCode());
                        repaintRequested = true;
                    }
                    else if (focused != null && !event.repeat()
                            && (event.keyCode() == 13 || event.keyCode() == 32)) {
                        focused.activate();
                        repaintRequested = true;
                    }
                    break;
                default: break;
            }
            syncTextInput(canvas, focused instanceof TextField);
        }
        return true;
    }

    /**
     * Runs an event-driven window on its owner thread until close.
     * Draws the initial frame, coalesces input redraws, and waits while idle.
     */
    public void run(Canvas canvas) {
        resize(canvas.width(), canvas.height());
        canvas.setLiveResizeRedraw(() -> {
            resize(canvas.width(), canvas.height());
            draw(canvas);
        });
        try {
            paintFrame(canvas);
            while (true) {
                canvas.waitForEvents();
                if (!poll(canvas)) return;
                if (repaintRequested) paintFrame(canvas);
            }
        } finally {
            try {
                syncTextInput(canvas, false);
            } finally {
                canvas.setLiveResizeRedraw(null);
            }
        }
    }

    private void paintFrame(Canvas canvas) {
        draw(canvas);
        canvas.present();
        repaintRequested = false;
    }

    private void syncTextInput(Canvas canvas, boolean enabled) {
        if (enabled == textInputEnabled) return;
        canvas.setTextInputEnabled(enabled);
        textInputEnabled = enabled;
    }

    /** Draws the widget tree in the current canvas coordinates. */
    public void draw(Canvas canvas) {
        layout();
        canvas.save();
        canvas.setFillStyle(0x18212cff);
        canvas.fillRect(0, 0, root.width(), root.height());
        canvas.setFont("Sans 15px");
        root.paint(canvas);
        canvas.restore();
    }
}
