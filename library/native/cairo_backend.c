#include "backend.h"
#include <cairo.h>
#include <pango/pangocairo.h>
#include <stdlib.h>
#include <string.h>

typedef struct Style {
    uint32_t fill, stroke;
    cairo_pattern_t *fill_pattern, *stroke_pattern;
    PangoFontDescription *font;
    struct Style *previous;
} Style;

typedef struct {
    SDL_Renderer *renderer;
    SDL_Texture *texture;
    cairo_surface_t *surface;
    cairo_t *cr;
    PangoLayout *layout;
    Style style;
    int width, height;
} CairoTarget;

static void release_style(Style *style) {
    if (style->font) pango_font_description_free(style->font);
    if (style->fill_pattern) cairo_pattern_destroy(style->fill_pattern);
    if (style->stroke_pattern) cairo_pattern_destroy(style->stroke_pattern);
}

static void release_backing(CairoTarget *c) {
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

static const char *initialize(CairoTarget *c, RenderSize size) {
    c->width = size.width;
    c->height = size.height;
    if (c->renderer) {
        c->texture = SDL_CreateTexture(c->renderer, SDL_PIXELFORMAT_ARGB8888,
                                      SDL_TEXTUREACCESS_STREAMING, size.width, size.height);
        if (!c->texture || !SDL_SetTextureBlendMode(c->texture, SDL_BLENDMODE_NONE))
            return SDL_GetError();
    }
    c->surface = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, size.width, size.height);
    cairo_surface_set_device_scale(c->surface, size.scale_x, size.scale_y);
    c->cr = cairo_create(c->surface);
    cairo_status_t status = cairo_status(c->cr);
    if (status != CAIRO_STATUS_SUCCESS) return cairo_status_to_string(status);
    c->style.fill = c->style.stroke = 0x000000ff;
    c->style.font = pango_font_description_from_string("Sans 10px");
    c->layout = pango_cairo_create_layout(c->cr);
    cairo_set_line_width(c->cr, 1);
    return NULL;
}


static const char *create(SDL_Window *window, RenderSize size, void **out) {
    CairoTarget *c = calloc(1, sizeof(*c));
    if (!c) return "Cannot allocate Cairo target";
    if (window && !(c->renderer = SDL_CreateRenderer(window, NULL))) {
        free(c);
        return SDL_GetError();
    }
    const char *error = initialize(c, size);
    if (error) {
        release_backing(c);
        if (c->renderer) SDL_DestroyRenderer(c->renderer);
        free(c);
        return error;
    }
    *out = c;
    return NULL;
}

static void destroy(void *target) {
    CairoTarget *c = target;
    release_backing(c);
    if (c->renderer) SDL_DestroyRenderer(c->renderer);
    free(c);
}

static const char *resize(SDL_Window *window, RenderSize size, void **target) {
    CairoTarget *c = *target;
    CairoTarget next = {.renderer = c->renderer};
    const char *error = initialize(&next, size);
    if (error) {
        release_backing(&next);
        return error;
    }
    release_backing(c);
    *c = next;
    return NULL;
}

static const char *renderer(void *target) {
    CairoTarget *c = target;
    return c->renderer ? SDL_GetRendererName(c->renderer) : "offscreen";
}

static void color(cairo_t *cr, uint32_t rgba) {
    cairo_set_source_rgba(cr, (rgba >> 24) / 255.0, ((rgba >> 16) & 255) / 255.0,
                         ((rgba >> 8) & 255) / 255.0, (rgba & 255) / 255.0);
}

static void source(CairoTarget *c, bool stroke) {
    cairo_pattern_t *pattern = stroke ? c->style.stroke_pattern : c->style.fill_pattern;
    if (pattern) cairo_set_source(c->cr, pattern);
    else color(c->cr, stroke ? c->style.stroke : c->style.fill);
}

static void replace_pattern(Style *style, bool stroke, cairo_pattern_t *pattern) {
    cairo_pattern_t **slot = stroke ? &style->stroke_pattern : &style->fill_pattern;
    if (*slot) cairo_pattern_destroy(*slot);
    *slot = pattern;
}

static void rect(CairoTarget *c, int op, const double *v) {
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

static void layout_text(CairoTarget *c, const char *text, int size) {
    pango_layout_set_font_description(c->layout, c->style.font);
    pango_layout_set_text(c->layout, text, size);
    pango_cairo_update_layout(c->cr, c->layout);
}

static const char *draw(void *target, const DrawCommand *command) {
    CairoTarget *c = target;
    cairo_t *cr = c->cr;
    unsigned op = command->op;
    uint32_t rgba = command->rgba, size = command->text_size;
    const char *value = command->text;
    void *image = command->image;
    double v[6];
    memcpy(v, command->args, sizeof(v));
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
            if (!saved) return "Cannot save drawing state";
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
            uint32_t count = command->stop_count;
            const unsigned char *stop_data = command->stops;
            cairo_pattern_t *pattern;
            if (count == 0) pattern = cairo_pattern_create_rgba(0, 0, 0, 0);
            else if (op < 27) pattern = cairo_pattern_create_linear(v[0], v[1], v[2], v[3]);
            else pattern = cairo_pattern_create_radial(v[0], v[1], v[2], v[3], v[4], v[5]);
            cairo_pattern_set_extend(pattern, CAIRO_EXTEND_PAD);
            for (uint32_t i = 0; i < count; i++) {
                double offset;
                uint32_t stop;
                memcpy(&offset, stop_data, sizeof(offset));
                memcpy(&stop, stop_data + sizeof(offset), sizeof(stop));
                stop_data += sizeof(offset) + sizeof(stop);
                cairo_pattern_add_color_stop_rgba(pattern, offset, (stop >> 24) / 255.0,
                    ((stop >> 16) & 255) / 255.0, ((stop >> 8) & 255) / 255.0, (stop & 255) / 255.0);
            }
            cairo_status_t status = cairo_pattern_status(pattern);
            if (status != CAIRO_STATUS_SUCCESS) {
                cairo_pattern_destroy(pattern);
                return cairo_status_to_string(status);
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
    return NULL;
}

static const char *finish_batch(void *target) {
    cairo_status_t status = cairo_status(((CairoTarget *)target)->cr);
    return status == CAIRO_STATUS_SUCCESS ? NULL : cairo_status_to_string(status);
}

static const char *present(void *target) {
    CairoTarget *c = target;
    int width, height;
    if (!SDL_GetCurrentRenderOutputSize(c->renderer, &width, &height)) {
        return SDL_GetError();
    }
    SDL_FRect destination = {0, 0, (float)c->width, (float)c->height};
    bool size_mismatch = width != c->width || height != c->height;
    cairo_surface_flush(c->surface);
    if ((size_mismatch && !SDL_RenderClear(c->renderer)) || !SDL_UpdateTexture(c->texture, NULL, cairo_image_surface_get_data(c->surface),
                           cairo_image_surface_get_stride(c->surface)) ||
        !SDL_RenderTexture(c->renderer, c->texture, NULL, &destination) ||
        !SDL_RenderPresent(c->renderer)) return SDL_GetError();
    return NULL;
}


static const char *write_png(void *target, const char *path) {
    cairo_status_t status = cairo_surface_write_to_png(((CairoTarget *)target)->surface, path);
    return status == CAIRO_STATUS_SUCCESS ? NULL : cairo_status_to_string(status);
}

static const char *extents(void *target, int kind, double *bounds) {
    CairoTarget *c = target;
    double x1, y1, x2, y2;
    switch (kind) {
        case 0: cairo_path_extents(c->cr, &x1, &y1, &x2, &y2); break;
        case 1: cairo_fill_extents(c->cr, &x1, &y1, &x2, &y2); break;
        case 2: cairo_stroke_extents(c->cr, &x1, &y1, &x2, &y2); break;
        case 3: cairo_clip_extents(c->cr, &x1, &y1, &x2, &y2); break;
        default: return "Invalid bounds kind";
    }
    cairo_status_t status = cairo_status(c->cr);
    bounds[0] = x1; bounds[1] = y1; bounds[2] = x2; bounds[3] = y2;
    return status == CAIRO_STATUS_SUCCESS ? NULL : cairo_status_to_string(status);
}

static void measure(void *target, const char *text, size_t size, double *output) {
    CairoTarget *c = target;
    layout_text(c, text, (int)size);
    PangoRectangle ink, logical;
    pango_layout_get_extents(c->layout, &ink, &logical);
    int baseline = pango_layout_get_baseline(c->layout);
    double unit = 1.0 / PANGO_SCALE;
    double metrics[] = {
        logical.width * unit, logical.height * unit, (baseline - logical.y) * unit,
        ink.x * unit, (ink.y - baseline) * unit, ink.width * unit, ink.height * unit
    };
    memcpy(output, metrics, sizeof(metrics));
}


static const char *load_image(const char *path, int *size, void **out) {
    cairo_surface_t *image = cairo_image_surface_create_from_png(path);
    cairo_status_t status = cairo_surface_status(image);
    if (status != CAIRO_STATUS_SUCCESS) {
        cairo_surface_destroy(image);
        return cairo_status_to_string(status);
    }
    size[0] = cairo_image_surface_get_width(image);
    size[1] = cairo_image_surface_get_height(image);
    *out = image;
    return NULL;
}

static void destroy_image(void *image) {
    cairo_surface_destroy(image);
}

const CanvasBackend cairo_backend = {
    .name = "cairo", .create = create, .resize = resize, .destroy = destroy,
    .renderer = renderer, .draw = draw, .finish_batch = finish_batch,
    .present = present, .write_png = write_png, .extents = extents, .measure = measure,
    .load_image = load_image, .destroy_image = destroy_image
};
