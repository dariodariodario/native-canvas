#define SDL_MAIN_HANDLED
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <jni.h>
#include "backend.h"
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    SDL_Window *window;
    const CanvasBackend *backend;
    void *target;
    int width, height;
    int logical_width, logical_height;
    bool quit, resize_pending;
    char *input_text;
    JavaVM *vm;
    jobject redraw_canvas;
    jmethodID redraw_method;
    bool redrawing;
} Canvas;

static Canvas *window_canvas;

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static char *string(JNIEnv *env, jbyteArray bytes) {
    jsize size = (*env)->GetArrayLength(env, bytes);
    char *result = malloc((size_t)size + 1);
    if (!result) {
        fail(env, "Cannot allocate string");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)result);
    result[size] = 0;
    return result;
}

static bool SDLCALL watch_live_resize(void *userdata, SDL_Event *event) {
    if (event->type != SDL_EVENT_WINDOW_EXPOSED || event->window.data1 != 1
            || !SDL_IsMainThread()) return true;
    Canvas *c = userdata;
    if (c->redrawing || event->window.windowID != SDL_GetWindowID(c->window)) return true;
    JNIEnv *env;
    if ((*c->vm)->GetEnv(c->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK
            || (*env)->ExceptionCheck(env)) return true;
    c->redrawing = true;
    (*env)->CallVoidMethod(env, c->redraw_canvas, c->redraw_method);
    c->redrawing = false;
    return true;
}

static void remove_resize_watch(JNIEnv *env, Canvas *c) {
    if (!c->redraw_canvas) return;
    SDL_RemoveEventWatch(watch_live_resize, c);
    (*env)->DeleteGlobalRef(env, c->redraw_canvas);
    c->redraw_canvas = NULL;
}

static void release(Canvas *c) {
    if (!c) return;
    SDL_free(c->input_text);
    if (c->target) c->backend->destroy(c->target);
    if (c->window) SDL_DestroyWindow(c->window);
    if (c == window_canvas) {
        window_canvas = NULL;
        SDL_QuitSubSystem(SDL_INIT_VIDEO);
    }
    free(c);
}

static bool configure_target(JNIEnv *env, Canvas *c, int width, int height, int pixels_w, int pixels_h) {
    RenderSize size = {pixels_w, pixels_h, (double)pixels_w / width, (double)pixels_h / height};
    const char *error = c->target
        ? c->backend->resize(c->window, size, &c->target)
        : c->backend->create(c->window, size, &c->target);
    if (error) {
        fail(env, error);
        return false;
    }
    c->width = pixels_w;
    c->height = pixels_h;
    c->logical_width = width;
    c->logical_height = height;
    return true;
}

JNIEXPORT jstring JNICALL Java_nativecanvas_Canvas_rendererName
  (JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    const char *name = c->backend->renderer(c->target);
    if (!name) { fail(env, SDL_GetError()); return NULL; }
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_open
  (JNIEnv *env, jclass type, jbyteArray title, jint width, jint height, jboolean resizable, jstring backend_name) {
    Canvas *c = calloc(1, sizeof(*c));
    if (!c) { fail(env, "Cannot allocate canvas"); return 0; }
    const char *name = (*env)->GetStringUTFChars(env, backend_name, NULL);
    if (!name) goto error;
    c->backend = canvas_backend(name);
    (*env)->ReleaseStringUTFChars(env, backend_name, name);
    if (!c->backend) { fail(env, "Rendering backend is unavailable in this build"); goto error; }
    if (title) {
        if (!SDL_IsMainThread() || window_canvas) {
            fail(env, "Open one SDL window on the main thread (use -XstartOnFirstThread on macOS)");
            goto error;
        }
        SDL_SetMainReady();
        if (!SDL_InitSubSystem(SDL_INIT_VIDEO)) goto sdl_error;
        window_canvas = c;
        char *name = string(env, title);
        if (!name) goto error;
        SDL_WindowFlags flags = SDL_WINDOW_HIGH_PIXEL_DENSITY | c->backend->window_flags;
        if (resizable) flags |= SDL_WINDOW_RESIZABLE;
        c->window = SDL_CreateWindow(name, width, height, flags);
        free(name);
        if (!c->window) goto sdl_error;
        if (!SDL_GetWindowSize(c->window, &width, &height)) goto sdl_error;
    }
    int pixels_w = width, pixels_h = height;
    if (c->window && !SDL_GetWindowSizeInPixels(c->window, &pixels_w, &pixels_h)) goto sdl_error;
    if (!configure_target(env, c, width, height, pixels_w, pixels_h)) goto error;
    return (jlong)(intptr_t)c;

sdl_error:
    fail(env, SDL_GetError());
error:
    release(c);
    return 0;
}

static uint32_t read_uint(const unsigned char **p) {
    uint32_t value;
    memcpy(&value, *p, sizeof(value));
    *p += sizeof(value);
    return value;
}

static double read_double(const unsigned char **p) {
    double value;
    memcpy(&value, *p, sizeof(value));
    *p += sizeof(value);
    return value;
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_draw
  (JNIEnv *env, jclass type, jlong handle, jobject buffer, jint length) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    const unsigned char *p = (*env)->GetDirectBufferAddress(env, buffer);
    if (!p || length < 0 || length > (*env)->GetDirectBufferCapacity(env, buffer)) {
        fail(env, "Invalid command buffer");
        return;
    }
    const unsigned char *end = p + length;
    static const unsigned char arity[] = {
        0, 0, 0, 4, 4, 4, 0, 2, 2, 0, 0, 0, 0, 0, 2, 2, 1, 1, 4, 0, 6, 0, 2, 4, 4, 4, 4, 6, 6, 1
    };
    while (p < end) {
        unsigned op = *p++;
        if (op == 0 || op >= sizeof(arity)) goto malformed;
        const char *value = NULL;
        uint32_t size = 0, rgba = 0;
        if (op == 1 || op == 2 || op == 21 || op == 22) {
            if (end - p < 4) goto malformed;
            uint32_t word = read_uint(&p);
            if (op <= 2) rgba = word;
            else {
                size = word;
                if ((size_t)(end - p) < size) goto malformed;
                value = (const char *)p;
                p += size;
            }
        }
        void *image = NULL;
        if (op == 23) {
            if (end - p < 8) goto malformed;
            uint64_t address;
            memcpy(&address, p, sizeof(address));
            p += sizeof(address);
            image = (void *)(uintptr_t)address;
        }
        if ((size_t)(end - p) < arity[op] * sizeof(double)) goto malformed;
        double v[6];
        bool finite = true;
        for (unsigned i = 0; i < arity[op]; ++i) {
            v[i] = read_double(&p);
            finite = finite && isfinite(v[i]);
        }
        DrawCommand command = {.op = op, .rgba = rgba, .text = value, .text_size = size, .image = image};
        memcpy(command.args, v, arity[op] * sizeof(double));
        if (op >= 25 && op <= 28) {
            if (end - p < 4) goto malformed;
            command.stop_count = read_uint(&p);
            if (command.stop_count > (size_t)(end - p) / 12) goto malformed;
            command.stops = p;
            p += (size_t)command.stop_count * 12;
        }
        if (!finite) continue;
        const char *error = c->backend->draw(c->target, &command);
        if (error) { fail(env, error); return; }
    }
    if (c->backend->finish_batch) {
        const char *error = c->backend->finish_batch(c->target);
        if (error) fail(env, error);
    }
    return;
malformed:
    fail(env, "Malformed drawing command");
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_show(JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    const char *error = c->backend->present(c->target);
    if (error) fail(env, error);
}

static jlong logical_size(const Canvas *c) {
    return ((jlong)c->logical_width << 32) | (uint32_t)c->logical_height;
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_logicalSize
  (JNIEnv *env, jclass type, jlong handle) {
    return logical_size((Canvas *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_resizeBacking
  (JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    int width, height, pixels_w, pixels_h;
    if (!SDL_GetWindowSize(c->window, &width, &height)
            || !SDL_GetWindowSizeInPixels(c->window, &pixels_w, &pixels_h)) {
        fail(env, SDL_GetError());
        return 0;
    }
    if (width <= 0 || height <= 0 || pixels_w <= 0 || pixels_h <= 0)
        return logical_size(c);
    if (width == c->logical_width && height == c->logical_height
            && pixels_w == c->width && pixels_h == c->height)
        return logical_size(c);
    if (!configure_target(env, c, width, height, pixels_w, pixels_h)) return 0;
    return logical_size(c);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_requestWindowSize
  (JNIEnv *env, jclass type, jlong handle, jint width, jint height) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    if (!SDL_SetWindowSize(c->window, width, height)) fail(env, SDL_GetError());
}

static int next_event(Canvas *c, void *output) {
    SDL_free(c->input_text);
    c->input_text = NULL;
    SDL_Event event;
    while (SDL_PollEvent(&event)) {
        uint32_t fields[4] = {0};
        float coordinates[4] = {0};
        int type;
        switch (event.type) {
            case SDL_EVENT_QUIT:
            case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
                c->quit = true;
                type = 1;
                break;
            case SDL_EVENT_KEY_DOWN:
            case SDL_EVENT_KEY_UP:
                type = event.type == SDL_EVENT_KEY_DOWN ? 2 : 3;
                fields[0] = event.key.key;
                fields[1] = event.key.scancode;
                fields[2] = event.key.mod;
                fields[3] = event.key.repeat;
                break;
            case SDL_EVENT_WINDOW_EXPOSED:
                if (event.window.data1 == 1) continue;
                type = 10;
                break;
            case SDL_EVENT_WINDOW_RESIZED:
            case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
                if (!output) c->resize_pending = true;
                type = 9;
                break;
            case SDL_EVENT_TEXT_INPUT:
                if (!output) continue;
                c->input_text = SDL_strdup(event.text.text);
                type = 8;
                break;
            case SDL_EVENT_MOUSE_MOTION:
                type = 4;
                coordinates[0] = event.motion.x;
                coordinates[1] = event.motion.y;
                coordinates[2] = event.motion.xrel;
                coordinates[3] = event.motion.yrel;
                break;
            case SDL_EVENT_MOUSE_BUTTON_DOWN:
            case SDL_EVENT_MOUSE_BUTTON_UP:
                type = event.type == SDL_EVENT_MOUSE_BUTTON_DOWN ? 5 : 6;
                fields[0] = event.button.button;
                fields[1] = event.button.clicks;
                coordinates[0] = event.button.x;
                coordinates[1] = event.button.y;
                break;
            case SDL_EVENT_MOUSE_WHEEL: {
                type = 7;
                float direction = event.wheel.direction == SDL_MOUSEWHEEL_FLIPPED ? -1 : 1;
                coordinates[0] = event.wheel.mouse_x;
                coordinates[1] = event.wheel.mouse_y;
                coordinates[2] = direction * event.wheel.x;
                coordinates[3] = direction * event.wheel.y;
                break;
            }
            default:
                continue;
        }
        if (output) {
            memcpy(output, fields, sizeof(fields));
            memcpy((unsigned char *)output + sizeof(fields), coordinates, sizeof(coordinates));
            return type;
        }
    }
    return 0;
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_waitForWindowEvent
  (JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    if (!c->quit && !SDL_WaitEvent(NULL) && !(*env)->ExceptionCheck(env))
        fail(env, SDL_GetError());
}

JNIEXPORT jint JNICALL Java_nativecanvas_Canvas_poll(JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return JNI_FALSE; }
    next_event(c, NULL);
    int flags = (c->quit ? 0 : 1) | (c->resize_pending ? 2 : 0);
    c->resize_pending = false;
    return flags;
}

JNIEXPORT jint JNICALL Java_nativecanvas_Canvas_nextEvent
  (JNIEnv *env, jclass type, jlong handle, jobject buffer) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return 0; }
    void *output = (*env)->GetDirectBufferAddress(env, buffer);
    if (!output || (*env)->GetDirectBufferCapacity(env, buffer) < 32) {
        fail(env, "Invalid event buffer");
        return 0;
    }
    return next_event(c, output);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_textInputEnabled
  (JNIEnv *env, jclass type, jlong handle, jboolean enabled) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    bool ok = enabled ? SDL_StartTextInput(c->window) : SDL_StopTextInput(c->window);
    if (!ok) fail(env, SDL_GetError());
}

JNIEXPORT jbyteArray JNICALL Java_nativecanvas_Canvas_inputText
  (JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    jsize length = c->input_text ? (jsize)strlen(c->input_text) : 0;
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes && length) (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)c->input_text);
    return bytes;
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_watchResize
  (JNIEnv *env, jclass type, jlong handle, jobject canvas) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    remove_resize_watch(env, c);
    if (!canvas) return;
    jclass canvas_class = (*env)->GetObjectClass(env, canvas);
    c->redraw_method = (*env)->GetMethodID(env, canvas_class, "redrawDuringResize", "()V");
    if (!c->redraw_method) return;
    if ((*env)->GetJavaVM(env, &c->vm) != JNI_OK) {
        fail(env, "Cannot find the Java VM for resize callbacks");
        return;
    }
    c->redraw_canvas = (*env)->NewGlobalRef(env, canvas);
    if (!c->redraw_canvas) return;
    if (!SDL_AddEventWatch(watch_live_resize, c)) {
        (*env)->DeleteGlobalRef(env, c->redraw_canvas);
        c->redraw_canvas = NULL;
        fail(env, SDL_GetError());
    }
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_destroy(JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    remove_resize_watch(env, c);
    release(c);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_png
  (JNIEnv *env, jclass type, jlong handle, jbyteArray filename) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    char *path = string(env, filename);
    if (!path) return;
    const char *error = c->backend->write_png(c->target, path);
    free(path);
    if (error) fail(env, error);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_extents
  (JNIEnv *env, jclass type, jlong handle, jint kind, jdoubleArray output) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (kind < 0 || kind > 3) { fail(env, "Invalid bounds kind"); return; }
    jdouble bounds[4];
    const char *error = c->backend->extents(c->target, kind, bounds);
    if (error) fail(env, error);
    else (*env)->SetDoubleArrayRegion(env, output, 0, 4, bounds);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_measure
  (JNIEnv *env, jclass type, jlong handle, jbyteArray bytes, jdoubleArray output) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    char *text = string(env, bytes);
    if (!text) return;
    jdouble metrics[7];
    c->backend->measure(c->target, text, (*env)->GetArrayLength(env, bytes), metrics);
    free(text);
    (*env)->SetDoubleArrayRegion(env, output, 0, 7, metrics);
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_loadPng
  (JNIEnv *env, jclass type, jlong handle, jbyteArray filename, jintArray output) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    char *path = string(env, filename);
    if (!path) return 0;
    jint size[2];
    void *image = NULL;
    const char *error = c->backend->load_image(path, size, &image);
    free(path);
    if (error) fail(env, error);
    else (*env)->SetIntArrayRegion(env, output, 0, 2, size);
    return (jlong)(intptr_t)image;
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_destroyImage
  (JNIEnv *env, jclass type, jlong handle, jlong image) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    c->backend->destroy_image((void *)(intptr_t)image);
}
