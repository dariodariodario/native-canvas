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
    bool quit;
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

static void release(Canvas *c) {
    if (!c) return;
    if (c->layout) g_object_unref(c->layout);
    if (c->style.font) pango_font_description_free(c->style.font);
    while (c->style.previous) {
        Style *saved = c->style.previous;
        c->style.previous = saved->previous;
        pango_font_description_free(saved->font);
        free(saved);
    }
    if (c->cr) cairo_destroy(c->cr);
    if (c->surface) cairo_surface_destroy(c->surface);
    if (c->texture) SDL_DestroyTexture(c->texture);
    if (c->renderer) SDL_DestroyRenderer(c->renderer);
    if (c->window) SDL_DestroyWindow(c->window);
    if (c == window_canvas) {
        window_canvas = NULL;
        SDL_QuitSubSystem(SDL_INIT_VIDEO);
    }
    free(c);
}

JNIEXPORT jlong JNICALL Java_nativecanvas_Canvas_open
  (JNIEnv *env, jclass type, jbyteArray title, jint width, jint height) {
    Canvas *c = calloc(1, sizeof(*c));
    if (!c) { fail(env, "Cannot allocate canvas"); return 0; }
    c->width = width;
    c->height = height;
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
        c->window = SDL_CreateWindow(name, width, height, SDL_WINDOW_HIGH_PIXEL_DENSITY);
        free(name);
        if (!c->window) goto sdl_error;
        c->renderer = SDL_CreateRenderer(c->window, NULL);
        if (!c->renderer || !SDL_GetWindowSizeInPixels(c->window, &c->width, &c->height))
            goto sdl_error;
        c->texture = SDL_CreateTexture(c->renderer, SDL_PIXELFORMAT_ARGB8888,
                                      SDL_TEXTUREACCESS_STREAMING, c->width, c->height);
        if (!c->texture || !SDL_SetTextureBlendMode(c->texture, SDL_BLENDMODE_NONE))
            goto sdl_error;
    }
    c->surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, c->width, c->height);
    cairo_surface_set_device_scale(c->surface, (double)c->width / width, (double)c->height / height);
    c->cr = cairo_create(c->surface);
    cairo_status_t status = cairo_status(c->cr);
    if (status != CAIRO_STATUS_SUCCESS) {
        fail(env, cairo_status_to_string(status));
        goto error;
    }
    c->style.fill = c->style.stroke = 0x000000ff;
    c->style.font = pango_font_description_from_string("Sans 10px");
    c->layout = pango_cairo_create_layout(c->cr);
    cairo_set_line_width(c->cr, 1);
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
    } else if (op == 4) {
        color(cr, c->style.stroke);
        cairo_stroke(cr);
    } else {
        color(cr, c->style.fill);
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
        0, 0, 0, 4, 4, 4, 0, 2, 2, 0, 0, 0, 0, 0, 2, 2, 1, 1, 4, 0, 6, 0, 2, 4
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
            case 1: c->style.fill = rgba; break;
            case 2: c->style.stroke = rgba; break;
            case 3: case 4: case 5: case 18: rect(c, op, v); break;
            case 6: cairo_new_path(cr); break;
            case 7: cairo_move_to(cr, v[0], v[1]); break;
            case 8: cairo_line_to(cr, v[0], v[1]); break;
            case 9: cairo_close_path(cr); break;
            case 10: color(cr, c->style.fill); cairo_fill_preserve(cr); break;
            case 11: color(cr, c->style.stroke); cairo_stroke_preserve(cr); break;
            case 12: {
                Style *saved = malloc(sizeof(*saved));
                if (!saved) { fail(env, "Cannot save drawing state"); return; }
                *saved = c->style;
                c->style.font = pango_font_description_copy(saved->font);
                c->style.previous = saved;
                cairo_save(cr);
                break;
            }
            case 13: {
                Style *saved = c->style.previous;
                if (saved) {
                    pango_font_description_free(c->style.font);
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
                color(cr, c->style.fill);
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
    cairo_surface_flush(c->surface);
    if (!SDL_UpdateTexture(c->texture, NULL, cairo_image_surface_get_data(c->surface),
                           cairo_image_surface_get_stride(c->surface)) ||
        !SDL_RenderTexture(c->renderer, c->texture, NULL, NULL) ||
        !SDL_RenderPresent(c->renderer)) fail(env, SDL_GetError());
}

static int next_event(Canvas *c, void *output) {
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

JNIEXPORT jboolean JNICALL Java_nativecanvas_Canvas_poll(JNIEnv *env, jclass type, jlong handle) {
    Canvas *c = (Canvas *)(intptr_t)handle;
    if (!c->window) { fail(env, "Canvas has no window"); return JNI_FALSE; }
    next_event(c, NULL);
    return !c->quit;
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

JNIEXPORT void JNICALL Java_nativecanvas_Canvas_destroy(JNIEnv *env, jclass type, jlong handle) {
    release((Canvas *)(intptr_t)handle);
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
