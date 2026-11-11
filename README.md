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
