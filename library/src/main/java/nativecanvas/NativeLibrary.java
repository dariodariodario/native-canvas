package nativecanvas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class NativeLibrary {
    private NativeLibrary() {}

    static void load() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform = os.contains("mac") ? "macos" : os.contains("win") ? "windows" : "linux";
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("arm64")) arch = "aarch64";
        if (arch.equals("amd64")) arch = "x86_64";
        String root = "/native/" + platform + "-" + arch + "/";
        InputStream manifest = NativeLibrary.class.getResourceAsStream(root + "libraries.txt");
        if (manifest == null) {
            System.loadLibrary("nativecanvas");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(manifest, StandardCharsets.UTF_8))) {
            Path directory = Files.createTempDirectory("nativecanvas-");
            directory.toFile().deleteOnExit();
            for (String name; (name = reader.readLine()) != null;) {
                if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".."))
                    throw new IOException("Invalid bundled library name");
                Path library = directory.resolve(name);
                try (InputStream bytes = NativeLibrary.class.getResourceAsStream(root + name)) {
                    if (bytes == null) throw new IOException("Missing bundled library " + name);
                    Files.copy(bytes, library);
                }
                library.toFile().deleteOnExit();
            }
            System.load(directory.resolve(System.mapLibraryName("nativecanvas")).toAbsolutePath().toString());
        } catch (IOException error) {
            UnsatisfiedLinkError failure = new UnsatisfiedLinkError("Cannot extract nativecanvas for " + platform + "-" + arch);
            failure.initCause(error);
            throw failure;
        }
    }
}
