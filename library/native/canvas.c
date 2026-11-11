#define SDL_MAIN_HANDLED
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <cairo.h>
#include <pango/pangocairo.h>
#include <jni.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct Style {
    uint32_t fill, stroke;
    cairo_pattern_t *fill_pattern, *stroke_pattern;
    PangoFontDescription *font;
    struct Style *previous;
} Style;

typedef struct {
    SDL_Window *window;
    SDL_Renderer *renderer;
    SDL_Texture *texture;
    cairo_surface_t *surface;
    cairo_t *cr;
    PangoLayout *layout;
    Style style;
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

static void release_style(Style *style) {
    if (style->font) pango_font_description_free(style->font);
    if (style->fill_pattern) cairo_pattern_destroy(style->fill_pattern);
    if (style->stroke_pattern) cairo_pattern_destroy(style->stroke_pattern);
}

static void release_backing(Canvas *c) {
    if (c->layout) g_object_unref(c->layout);
    release_style(&c->style);
    while (c->style.previous) {
        Style *saved = c->style.previous;
        c->style.previous = saved->previous;
        release_style(saved);
        free(saved);
    }
    if (c->cr) cairo_destroy(c->cr);
    if (c->surface) cairo_surface_destroy(c->surface);
    if (c->texture) SDL_DestroyTexture(c->texture);
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
    g_free(c->input_text);
    release_backing(c);
    if (c->renderer) SDL_DestroyRenderer(c->renderer);
    if (c->window) SDL_DestroyWindow(c->window);
    if (c == window_canvas) {
        window_canvas = NULL;
        SDL_QuitSubSystem(SDL_INIT_VIDEO);
    }
    free(c);
}

static bool create_backing(JNIEnv *env, Canvas *c, int width, int height) {
    c->logical_width = c->width = width;
    c->logical_height = c->height = height;
    if (c->window) {
        if (!SDL_GetWindowSizeInPixels(c->window, &c->width, &c->height)) {
            fail(env, SDL_GetError());
            return false;
        }
        c->texture = SDL_CreateTexture(c->renderer, SDL_PIXELFORMAT_ARGB8888,
                                      SDL_TEXTUREACCESS_STREAMING, c->width, c->height);
        if (!c->texture || !SDL_SetTextureBlendMode(c->texture, SDL_BLENDMODE_NONE)) {
            fail(env, SDL_GetError());
            return false;
        }
    }
    c->surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, c->width, c->height);
    cairo_surface_set_device_scale(c->surface, (double)c->width / width, (double)c->height / height);
    c->cr = cairo_create(c->surface);
    cairo_status_t status = cairo_status(c->cr);
    if (status != CAIRO_STATUS_SUCCESS) {
        fail(env, cairo_status_to_string(status));
        return false;
    }
    c->style.fill = c->style.stroke = 0x000000ff;
    c->style.font = pango_font_description_from_string("Sans 10px");
    c->layout = pango_cairo_create_layout(c->cr);
    cairo_set_line_width(c->cr, 1);
    return true;
}

JNIEXPORT jstring JNICALL Java_nativecanvas_Canvas_rendererName
  (JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    const char *name = c->renderer ? SDL_GetRendererName(c->renderer) : "offscreen";
    if (!name) { fail(env, SDL_GetError()); return NULL; }
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_open
  (JNIEnv *env, jclass type, jbyteArray title, jint width, jint height, jboolean resizable) {
    Canvas *c = calloc(1, sizeof(*c));
    if (!c) { fail(env, "Cannot allocate canvas"); return 0; }
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
        SDL_WindowFlags flags = SDL_WINDOW_HIGH_PIXEL_DENSITY;
        if (resizable) flags |= SDL_WINDOW_RESIZABLE;
        c->window = SDL_CreateWindow(name, width, height, flags);
        free(name);
        if (!c->window) goto sdl_error;
        c->renderer = SDL_CreateRenderer(c->window, NULL);
        if (!c->renderer || !SDL_GetWindowSize(c->window, &width, &height)) goto sdl_error;
    }
    if (!create_backing(env, c, width, height)) goto error;
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

static void color(cairo_t *cr, uint32_t rgba) {
    cairo_set_source_rgba(cr, (rgba >> 24) / 255.0, ((rgba >> 16) & 255) / 255.0,
                         ((rgba >> 8) & 255) / 255.0, (rgba & 255) / 255.0);
}

static void source(Canvas *c, bool stroke) {
    cairo_pattern_t *pattern = stroke ? c->style.stroke_pattern : c->style.fill_pattern;
    if (pattern) cairo_set_source(c->cr, pattern);
    else color(c->cr, stroke ? c->style.stroke : c->style.fill);
}

static void replace_pattern(Style *style, bool stroke, cairo_pattern_t *pattern) {
    cairo_pattern_t **slot = stroke ? &style->stroke_pattern : &style->fill_pattern;
    if (*slot) cairo_pattern_destroy(*slot);
    *slot = pattern;
}

static void rect(Canvas *c, int op, const double *v) {
    cairo_t *cr = c->cr;
    if (op == 18) {
        cairo_rectangle(cr, v[0], v[1], v[2], v[3]);
        return;
    }
    cairo_path_t *path = cairo_copy_path(cr);
    cairo_new_path(cr);
    cairo_rectangle(cr, v[0], v[1], v[2], v[3]);
    if (op == 5) {
        cairo_save(cr);
        cairo_set_operator(cr, CAIRO_OPERATOR_CLEAR);
        cairo_fill(cr);
        cairo_restore(cr);
    } else if (op == 24) {
        cairo_clip(cr);
    } else if (op == 4) {
        source(c, true);
        cairo_stroke(cr);
    } else {
        source(c, false);
        cairo_fill(cr);
    }
    cairo_append_path(cr, path);
    cairo_path_destroy(path);
}

static void layout_text(Canvas *c, const char *text, int size) {
    pango_layout_set_font_description(c->layout, c->style.font);
    pango_layout_set_text(c->layout, text, size);
    pango_cairo_update_layout(c->cr, c->layout);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_draw
  (JNIEnv *env, jclass type, jlong handle, jobject buffer, jint length) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    cairo_t *cr = c->cr;
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
        cairo_surface_t *image = NULL;
        if (op == 23) {
            if (end - p < 8) goto malformed;
            uint64_t address;
            memcpy(&address, p, sizeof(address));
            p += sizeof(address);
            image = (cairo_surface_t *)(uintptr_t)address;
        }
        if ((size_t)(end - p) < arity[op] * sizeof(double)) goto malformed;
        double v[6];
        bool finite = true;
        for (unsigned i = 0; i < arity[op]; ++i) {
            v[i] = read_double(&p);
            finite = finite && isfinite(v[i]);
        }
        if (!finite) continue;
        switch (op) {
            case 1: replace_pattern(&c->style, false, NULL); c->style.fill = rgba; break;
            case 2: replace_pattern(&c->style, true, NULL); c->style.stroke = rgba; break;
            case 3: case 4: case 5: case 18: case 24: rect(c, op, v); break;
            case 6: cairo_new_path(cr); break;
            case 7: cairo_move_to(cr, v[0], v[1]); break;
            case 8: cairo_line_to(cr, v[0], v[1]); break;
            case 9: cairo_close_path(cr); break;
            case 10: source(c, false); cairo_fill_preserve(cr); break;
            case 11: source(c, true); cairo_stroke_preserve(cr); break;
            case 12: {
                Style *saved = malloc(sizeof(*saved));
                if (!saved) { fail(env, "Cannot save drawing state"); return; }
                *saved = c->style;
                c->style.font = pango_font_description_copy(saved->font);
                if (c->style.fill_pattern) cairo_pattern_reference(c->style.fill_pattern);
                if (c->style.stroke_pattern) cairo_pattern_reference(c->style.stroke_pattern);
                c->style.previous = saved;
                cairo_save(cr);
                break;
            }
            case 13: {
                Style *saved = c->style.previous;
                if (saved) {
                    release_style(&c->style);
                    c->style = *saved;
                    free(saved);
                    cairo_restore(cr);
                }
                break;
            }
            case 14: cairo_translate(cr, v[0], v[1]); break;
            case 15: cairo_scale(cr, v[0], v[1]); break;
            case 16: cairo_rotate(cr, v[0]); break;
            case 17: cairo_set_line_width(cr, v[0]); break;
            case 19: cairo_clip_preserve(cr); break;
            case 20: cairo_curve_to(cr, v[0], v[1], v[2], v[3], v[4], v[5]); break;
            case 21: {
                char *description = g_strndup(value, size);
                pango_font_description_free(c->style.font);
                c->style.font = pango_font_description_from_string(description);
                g_free(description);
                break;
            }
            case 22: {
                source(c, false);
                layout_text(c, value, (int)size);
                cairo_path_t *path = cairo_copy_path(cr);
                cairo_new_path(cr);
                cairo_move_to(cr, v[0], v[1] - (double)pango_layout_get_baseline(c->layout) / PANGO_SCALE);
                pango_cairo_show_layout(cr, c->layout);
                cairo_new_path(cr);
                cairo_append_path(cr, path);
                cairo_path_destroy(path);
                break;
            }
            case 29:
                cairo_set_antialias(cr, v[0] == 1 ? CAIRO_ANTIALIAS_FAST : CAIRO_ANTIALIAS_DEFAULT);
                break;
            case 25: case 26: case 27: case 28: {
                if (end - p < 4) goto malformed;
                uint32_t count = read_uint(&p);
                if (count > (size_t)(end - p) / 12) goto malformed;
                cairo_pattern_t *pattern;
                if (count == 0) pattern = cairo_pattern_create_rgba(0, 0, 0, 0);
                else if (op < 27) pattern = cairo_pattern_create_linear(v[0], v[1], v[2], v[3]);
                else pattern = cairo_pattern_create_radial(v[0], v[1], v[2], v[3], v[4], v[5]);
                cairo_pattern_set_extend(pattern, CAIRO_EXTEND_PAD);
                for (uint32_t i = 0; i < count; i++) {
                    double offset = read_double(&p);
                    uint32_t stop = read_uint(&p);
                    cairo_pattern_add_color_stop_rgba(pattern, offset, (stop >> 24) / 255.0,
                        ((stop >> 16) & 255) / 255.0, ((stop >> 8) & 255) / 255.0, (stop & 255) / 255.0);
                }
                cairo_status_t status = cairo_pattern_status(pattern);
                if (status != CAIRO_STATUS_SUCCESS) {
                    cairo_pattern_destroy(pattern);
                    fail(env, cairo_status_to_string(status));
                    return;
                }
                replace_pattern(&c->style, op == 26 || op == 28, pattern);
                break;
            }
            case 23: {
                if (v[2] == 0 || v[3] == 0) break;
                if (v[2] < 0) { v[0] += v[2]; v[2] = -v[2]; }
                if (v[3] < 0) { v[1] += v[3]; v[3] = -v[3]; }
                cairo_path_t *path = cairo_copy_path(cr);
                cairo_new_path(cr);
                cairo_save(cr);
                cairo_translate(cr, v[0], v[1]);
                int width = cairo_image_surface_get_width(image);
                int height = cairo_image_surface_get_height(image);
                cairo_scale(cr, v[2] / width, v[3] / height);
                cairo_set_source_surface(cr, image, 0, 0);
                cairo_pattern_t *pattern = cairo_get_source(cr);
                cairo_pattern_set_extend(pattern, CAIRO_EXTEND_PAD);
                cairo_pattern_set_filter(pattern, CAIRO_FILTER_BILINEAR);
                cairo_rectangle(cr, 0, 0, width, height);
                cairo_fill(cr);
                cairo_restore(cr);
                cairo_append_path(cr, path);
                cairo_path_destroy(path);
                break;
            }
        }
    }
    cairo_status_t status = cairo_status(cr);
    if (status != CAIRO_STATUS_SUCCESS) fail(env, cairo_status_to_string(status));
    return;
malformed:
    fail(env, "Malformed drawing command");
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_show(JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    int width, height;
    if (!SDL_GetCurrentRenderOutputSize(c->renderer, &width, &height)) {
        fail(env, SDL_GetError());
        return;
    }
    SDL_FRect destination = {0, 0, (float)c->width, (float)c->height};
    bool size_mismatch = width != c->width || height != c->height;
    cairo_surface_flush(c->surface);
    if ((size_mismatch && !SDL_RenderClear(c->renderer)) || !SDL_UpdateTexture(c->texture, NULL, cairo_image_surface_get_data(c->surface),
                           cairo_image_surface_get_stride(c->surface)) ||
        !SDL_RenderTexture(c->renderer, c->texture, NULL, &destination) ||
        !SDL_RenderPresent(c->renderer)) fail(env, SDL_GetError());
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
    Canvas next = {.window = c->window, .renderer = c->renderer};
    if (!create_backing(env, &next, width, height)) {
        release_backing(&next);
        return 0;
    }
    release_backing(c);
    c->logical_width = next.logical_width;
    c->logical_height = next.logical_height;
    c->width = next.width;
    c->height = next.height;
    c->texture = next.texture;
    c->surface = next.surface;
    c->cr = next.cr;
    c->layout = next.layout;
    c->style = next.style;
    return logical_size(c);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_requestWindowSize
  (JNIEnv *env, jclass type, jlong handle, jint width, jint height) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return; }
    if (!SDL_SetWindowSize(c->window, width, height)) fail(env, SDL_GetError());
}

static int next_event(Canvas *c, void *output) {
    g_clear_pointer(&c->input_text, g_free);
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
                c->input_text = g_strdup(event.text.text);
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
    cairo_status_t status = cairo_surface_write_to_png(c->surface, path);
    free(path);
    if (status != CAIRO_STATUS_SUCCESS) fail(env, cairo_status_to_string(status));
}


JNIEXPORT void JNICALL Java_nativecanvas_Canvas_extents
  (JNIEnv *env, jclass type, jlong handle, jint kind, jdoubleArray output) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    double x1, y1, x2, y2;
    switch (kind) {
        case 0: cairo_path_extents(c->cr, &x1, &y1, &x2, &y2); break;
        case 1: cairo_fill_extents(c->cr, &x1, &y1, &x2, &y2); break;
        case 2: cairo_stroke_extents(c->cr, &x1, &y1, &x2, &y2); break;
        case 3: cairo_clip_extents(c->cr, &x1, &y1, &x2, &y2); break;
        default: fail(env, "Invalid bounds kind"); return;
    }
    cairo_status_t status = cairo_status(c->cr);
    if (status != CAIRO_STATUS_SUCCESS) {
        fail(env, cairo_status_to_string(status));
        return;
    }
    jdouble bounds[] = {x1, y1, x2, y2};
    (*env)->SetDoubleArrayRegion(env, output, 0, 4, bounds);
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_measure
  (JNIEnv *env, jclass type, jlong handle, jbyteArray bytes, jdoubleArray output) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    char *text = string(env, bytes);
    if (!text) return;
    layout_text(c, text, (*env)->GetArrayLength(env, bytes));
    free(text);
    PangoRectangle ink, logical;
    pango_layout_get_extents(c->layout, &ink, &logical);
    int baseline = pango_layout_get_baseline(c->layout);
    double unit = 1.0 / PANGO_SCALE;
    jdouble metrics[] = {
        logical.width * unit, logical.height * unit, (baseline - logical.y) * unit,
        ink.x * unit, (ink.y - baseline) * unit, ink.width * unit, ink.height * unit
    };
    (*env)->SetDoubleArrayRegion(env, output, 0, 7, metrics);
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_loadPng
  (JNIEnv *env, jclass type, jbyteArray filename, jintArray output) {
    char *path = string(env, filename);
    if (!path) return 0;
    cairo_surface_t *image = cairo_image_surface_create_from_png(path);
    free(path);
    cairo_status_t status = cairo_surface_status(image);
    if (status != CAIRO_STATUS_SUCCESS) {
        cairo_surface_destroy(image);
        fail(env, cairo_status_to_string(status));
        return 0;
    }
    jint size[] = {cairo_image_surface_get_width(image), cairo_image_surface_get_height(image)};
    (*env)->SetIntArrayRegion(env, output, 0, 2, size);
    return (jlong)(intptr_t)image;
}

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_destroyImage
  (JNIEnv *env, jclass type, jlong image) {
    cairo_surface_destroy((cairo_surface_t *)(intptr_t)image);
}
