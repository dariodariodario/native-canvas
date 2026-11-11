# Native Canvas

Java 2D drawing with Cairo, Pango, and SDL3. Use a window for interactive
drawing or an offscreen canvas for rendering PNGs. The API follows familiar
Canvas 2D operations such as `fillRect`, `beginPath`, `save`, and `fillText`.

## Build on Apple Silicon

Open the root `pom.xml` as a Maven project in your IDE. Install JDK 21,
Maven, and the native build dependencies.

```sh
brew install cmake pkg-config cairo pango sdl3
mvn package
```

Maven invokes CMake to configure and compile the C code before packaging the
Java library. The root build packages both modules:

- `library/target/nativecanvas-0.1.0-SNAPSHOT.jar` contains the Java API.
- `library/target/native/libnativecanvas.dylib` is the Apple Silicon JNI library.
- `examples/target/nativecanvas-examples-0.1.0-SNAPSHOT.jar` contains the demo and benchmark.

Use `mvn install` to make `nativecanvas:nativecanvas:0.1.0-SNAPSHOT`
available to another local Maven project. Supply the directory containing
the JNI library with `-Djava.library.path=/path/to/native` when launching
that application. The native library links against the installed Homebrew
libraries.

The default JAR targets Java 8 bytecode and needs a runtime with JNI and
direct buffer support. Use `-Dmaven.compiler.release=21` to target Java 21
bytecode or `-Dnative.skip=true` to build just the Java JAR. The offscreen demo
also runs on a Java 21 image built with `jlink --add-modules java.base`.

### Run the examples

On Apple Silicon, run `mvn package` once to build the JNI library, then select
**Native Canvas Demo** or **Native Canvas Benchmark** in IntelliJ IDEA. The benchmark configuration opens an SDL window and measures presentation. Run Maven again after changing the C
code. For a custom Application configuration on Java 26, use these VM options:

```text
-XstartOnFirstThread --enable-native-access=ALL-UNNAMED -Djava.library.path="$PROJECT_DIR$/library/target/native"
```

In the window demo, move or click the pointer to see its position and button
state, scroll to resize the animated rectangle, or press a key to change its
color. Escape closes the window.

```sh
java -XstartOnFirstThread -Djava.library.path=library/target/native \
  -cp library/target/nativecanvas-0.1.0-SNAPSHOT.jar:examples/target/nativecanvas-examples-0.1.0-SNAPSHOT.jar Demo
java -Djava.library.path=library/target/native \
  -cp library/target/nativecanvas-0.1.0-SNAPSHOT.jar:examples/target/nativecanvas-examples-0.1.0-SNAPSHOT.jar Demo --png examples/target/demo.png
```

Run `Benchmark` with the same classpath to measure offscreen text, shape, and
image workloads. Each frame draws 4,096 text labels, 100,000 translucent
rectangles, or 8,192 scaled sprites, depending on the workload. Each workload
runs 180 warm-up frames and 3,000 measured frames. Set the VM option
`-Dbenchmark.frames=60` for a shorter run at the same draw counts. The draw
time includes command recording and Cairo rasterization, including automatic
flushes when the command buffer fills. Pass `--window` and add `-XstartOnFirstThread` to measure SDL
presentation as well.

## Use from Java

```java
try (Canvas canvas = Canvas.offscreen(640, 360)) {
    canvas.setFillStyle(0x162235ff);
    canvas.fillRect(0, 0, 640, 360);
    canvas.setFillStyle(0xffffffff);
    canvas.setFont("Sans Bold 24px");
    canvas.fillText("Hello, canvas", 24, 52);
    canvas.writePng("hello.png");
}
```

Import `nativecanvas.Canvas`. Colors are packed as `0xRRGGBBAA`. Text uses
Pango font descriptions and alphabetic baseline coordinates. Call
`measureText(text)` to get the logical width, height, baseline offset, and ink
bounds in canvas units before drawing. It submits queued commands so the
measurement uses the current font. Use
`loadImage` to load a PNG once and `drawImage` to reuse it. Query its source pixel size with `width()` and `height()`. Canvas and image
resources implement `AutoCloseable`.

Query geometry with `pathBounds()`, `fillBounds()`, `strokeBounds()`, and
`clipBounds()`. These submit queued commands and return `x`, `y`, `width`,
and `height` in current user coordinates. The queries preserve the current path
and drawing state. Fill and stroke bounds describe the geometry before clipping.

Use each canvas from the Java thread that created it. One standalone SDL window
can be open at a time. Create it on the main thread and pump events with
`pollEvents()`, which returns false when the window is asked to close.
To consume keyboard, pointer, scroll, and close events, reuse one
`Canvas.InputEvent` and loop on `pollEvent(event)` until it returns false.
Handle `InputEvent.CLOSE` to exit. Use one polling method per loop because both
consume the SDL event queue.
`present()` submits pending commands and uploads the Cairo backing store to
SDL. The backing store persists between frames.

The Java API records primitive operations in a reusable direct buffer. JNI
submits a batch when the buffer fills or when output is requested. Cairo
rasterizes on the CPU and SDL presents the texture. The portable CMake target
uses JNI headers and pkg-config. Apple Silicon macOS is the current build and
runtime target.

### Gradients, clips, and transforms

```java
Canvas.Gradient gradient = Canvas.linearGradient(0, 0, 180, 0)
        .addColorStop(0, 0x48e6c1ff)
        .addColorStop(1, 0xbd5bffff);
canvas.save();
canvas.translate(20, 30);
canvas.clipRect(0, 0, 180, 60);
canvas.setFillStyle(gradient);
canvas.fillRect(0, 0, 180, 60);
canvas.restore();
```

Use `Canvas.radialGradient(x0, y0, r0, x1, y1, r1)` for a two-circle gradient.
Fill and stroke styles accept both gradient types. Style assignment snapshots
the stops, and coordinates follow the drawing transform. Use `clip()` to clip
to the current path or `clipRect()` for a rectangle. Both intersect the
existing clip and preserve the path. `save()` and `restore()` scope the clip,
transform, and styles.

**Native Canvas Demo** shows gradients, path and rectangle clipping, translation,
rotation and scaling, geometry bounds, shaped text and ink bounds, and PNG
image scaling and transparency. It adapts to window resize and can export the
same drawing with `--png`.

## UI toolkit

The `toolkit` module builds `nativecanvas:nativecanvas-toolkit` and depends on
the canvas library. Compose a widget tree from `Label`, `Button`, `Checkbox`,
`Column`, `Row`, `Grid`, and `ScrollPane`. A `Ui` lays out the tree and dispatches input.
Tab and Shift+Tab move focus. Space and Enter activate the focused control.
The mouse wheel scrolls the pane under the pointer.

```java
Label status = new Label("Ready");
Column content = new Column(16, 8)
        .add(status)
        .add(new Button("Run", () -> status.setText("Running")))
        .add(new Checkbox("Show details", false,
                checked -> status.setText("Details: " + checked)));
Ui ui = new Ui(new ScrollPane(content, 360), 420, 360);
ui.run(canvas);
```

Import the widget classes from `nativecanvas.ui` and give `Ui` the logical
dimensions of the canvas. Build with `mvn package`, reimport the Maven project,
and select **Native Canvas Toolkit** in IntelliJ.

### Horizontal and grid layout

```java
Row actions = new Row(8, 6)
        .add(new Button("Save", save), Track.FILL)
        .add(new Button("Cancel", cancel), Track.size(90));

Grid form = new Grid(
        new Track[]{Track.AUTO, Track.fill(2)},
        new Track[]{Track.AUTO, Track.size(48)}, 8, 6, 6)
        .add(0, 0, new Label("Preferences"))
        .add(1, 1, actions);
```

`Track.AUTO` uses the preferred size. `Track.size(pixels)` sets a fixed size.
`Track.FILL` keeps the preferred size and shares extra space. Use
`Track.fill(weight)` to change that share. When space is tight, tracks keep
their minimum sizes and can overflow. Built-in controls estimate their
preferred text width. Grid indices are zero-based and each cell holds one
widget. Rows and grids can be nested inside other layouts.

## Tests

Run `mvn test` from the root. The `library` tests exercise the JNI canvas,
including rendering, text and path measurement, image compositing, lifecycle,
and SDL events with a headless video driver. The `toolkit` tests cover widget
ownership, layout, focus, pointer dispatch, scroll behavior, and rendered
clipping. Tests live in each module's `src/test` directory.

Run `mvn verify` to generate Java coverage reports in
`library/target/site/jacoco` and `toolkit/target/site/jacoco`. JNI integration
tests exercise native behavior, and the coverage reports measure Java code.

### Text fields and label styles

```java
Label name = new Label("Name").setFont("Sans Bold", 18).setColor(0x7acffaff);
TextField input = new TextField("", value -> name.setText("Name: " + value));
Column form = new Column(12, 8).add(name).add(input);
```

Click or Tab to focus the text field. It accepts committed Unicode text and
supports Left, Right, Home, End, Backspace, and Delete. It is single-line and
does not provide selection, clipboard shortcuts, or IME preedit display.
`setText` changes the value without firing the edit callback. Label font sizes
are in logical pixels and colors use `0xRRGGBBAA`.

### Paint example

Select **Native Canvas Paint** in IntelliJ after running `mvn package`, or run
from the project root on macOS:

```sh
java -XstartOnFirstThread --enable-native-access=ALL-UNNAMED \
  -Djava.library.path=library/target/native \
  -cp examples/target/classes:toolkit/target/classes:library/target/classes PaintDemo
```

Choose Brush, Eraser, Line, or Rectangle, then drag on the white drawing
area. Choose a color or cycle the stroke width with **Width**. **Undo**
removes the last mark, **Clear** empties the drawing, and **Save PNG** writes
`paint.png` in the working directory. The example replays its marks to
export the drawing without the toolbar.

### Resizable windows

Use `new Canvas(title, width, height, true)` to enable resizing. Poll events
before drawing. A `Canvas.InputEvent.RESIZE` reports the current logical
size, and `Canvas.width()` and `Canvas.height()` work with either polling API.
A backing-size change clears the backing pixels and resets Canvas drawing
state, including its path and save stack. Redraw the scene after resizing.

`Ui.poll(canvas)` relayouts the widget tree automatically on resize. Use
`Ui.resize(width, height)` when supplying your own viewport size. The Toolkit
Demo uses a grid with an `AUTO` heading and a `FILL` scroll pane.

For live resize, register a drawing callback with
`canvas.setLiveResizeRedraw(() -> { ui.resize(canvas.width(), canvas.height()); ui.draw(canvas); })`.
Canvas updates its backing before the callback and presents afterward. Keep
the callback to drawing and layout. The regular event loop continues to
handle input and queued resize events.


### Rendering loops

`Ui.run(canvas)` owns an event-driven window loop. It paints the initial
frame, waits for SDL events while idle, and redraws when input changes
visible state or the window is resized or exposed. It combines redraws
from one batch of queued events into a single frame. Both Toolkit Demo
and Paint use this loop. Paint keeps the marks in memory and replays them
when the UI needs repainting.

For a custom Canvas loop, use `pollEvent(event)` or `pollEvents()` and
`present()` to render continuously. To render on demand, call
`waitForEvents()` before draining the queue, then draw and present if
your scene changed or you receive an `EXPOSED` or `RESIZE` event.

### Aurora / Afterlight

Select **Native Canvas Aurora** in IntelliJ after running `mvn package`.
The animated scene draws aurora ribbons, twinkling stars, an orbital sunset,
and moving reflections from Canvas paths and gradients. It adapts to live
window resize. To export a still at a specific animation time in seconds:

```sh
java -Djava.library.path=library/target/native \
  -cp examples/target/classes:toolkit/target/classes:library/target/classes \
  AuroraDemo --png examples/target/aurora.png 4
```

### Paradox Engine

Select **Native Canvas Paradox** in IntelliJ after running `mvn package`.
The demo projects a rotating 3D torus knot into Canvas paths, with 36 twisting
strands and 7,200 flowing particles, eight inclined orbital bands, 24 rotating
satellites, and a deforming 22-layer energy core. Drag the scene to orbit. The toolkit sidebar controls particle count, speed,
zoom, surge, edge quality, and pause. Reset restores the defaults.

```sh
java -Djava.library.path=library/target/native \
  -cp examples/target/classes:toolkit/target/classes:library/target/classes \
  ParadoxDemo --png examples/target/paradox.png 3.8
```

### Paradox performance

Paradox uses `Canvas.Antialias.FAST` for its geometry. Use **Fast edge rendering** in the sidebar to toggle
Cairo's default edge quality. The fast mode trades some edge smoothness for
lower rasterization cost. Canvas itself defaults to `Antialias.DEFAULT`.
The setting participates in `save()` and `restore()`.

Measure the actual scene offscreen at 1280×800, with 30 warm-up frames and
100 measured frames:

```sh
java -Djava.library.path=library/target/native   -cp examples/target/classes:toolkit/target/classes:library/target/classes ParadoxDemo --benchmark default
java -Djava.library.path=library/target/native   -cp examples/target/classes:toolkit/target/classes:library/target/classes ParadoxDemo --benchmark fast
```

These timings include Java scene generation, command submission, and Cairo
rasterization. Window texture upload and presentation are outside this measurement.

### City of Light

Select **Native Canvas City** in IntelliJ. The procedural flight passes
hundreds of illuminated buildings with rooftop equipment, moving traffic,
facade gradients, and distance haze. The toolkit panel adjusts altitude,
flight speed, radial lens distortion, and pause.

The projection includes camera yaw, pitch, and roll, near-plane clipping,
and subdivided polygon edges for curved lens distortion. Scene geometry is
drawn with the existing Canvas API. Faces use painter-order depth sorting.

```sh
java -Djava.library.path=library/target/native \
  -cp examples/target/classes:toolkit/target/classes:library/target/classes \
  CityDemo --png examples/target/city.png 6
```

### Acceleration

SDL automatically selects an available presentation renderer. On the tested
Apple Silicon Mac this is Metal. Query `canvas.presentationRenderer()` to
see the selected backend. Offscreen canvases report `offscreen`.

Cairo rasterizes into CPU image surfaces before SDL uploads and presents
them. The installed Cairo 1.18 build has no OpenGL backend. Hardware
presentation does not imply GPU rasterization of Cairo paths.
