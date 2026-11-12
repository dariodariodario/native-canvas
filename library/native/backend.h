#pragma once
#include <SDL3/SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Values match the Canvas Java command protocol. */
typedef enum {
    DRAW_SET_FILL_COLOR = 1,
    DRAW_SET_STROKE_COLOR = 2,
    DRAW_FILL_RECT = 3,
    DRAW_STROKE_RECT = 4,
    DRAW_CLEAR_RECT = 5,
    DRAW_BEGIN_PATH = 6,
    DRAW_MOVE_TO = 7,
    DRAW_LINE_TO = 8,
    DRAW_CLOSE_PATH = 9,
    DRAW_FILL_PATH = 10,
    DRAW_STROKE_PATH = 11,
    DRAW_SAVE = 12,
    DRAW_RESTORE = 13,
    DRAW_TRANSLATE = 14,
    DRAW_SCALE = 15,
    DRAW_ROTATE = 16,
    DRAW_SET_LINE_WIDTH = 17,
    DRAW_ADD_RECT = 18,
    DRAW_CLIP_PATH = 19,
    DRAW_CUBIC_TO = 20,
    DRAW_SET_FONT = 21,
    DRAW_FILL_TEXT = 22,
    DRAW_IMAGE = 23,
    DRAW_CLIP_RECT = 24,
    DRAW_SET_FILL_LINEAR = 25,
    DRAW_SET_STROKE_LINEAR = 26,
    DRAW_SET_FILL_RADIAL = 27,
    DRAW_SET_STROKE_RADIAL = 28,
    DRAW_SET_ANTIALIAS = 29,
} DrawOperation;

typedef struct {
    double offset;
    uint32_t rgba;
} GradientStop;

typedef struct {
    DrawOperation op;
    double args[6];
    uint32_t rgba;
    const char *text;
    uint32_t text_size;
    void *image;
    const GradientStop *stops;
    uint32_t stop_count;
} DrawCommand;

typedef struct {
    int width, height;
    double scale_x, scale_y;
} RenderSize;

/* Error-returning operations return NULL on success. A failed resize retains the old target. */
typedef struct {
    const char *name;
    SDL_WindowFlags window_flags;
    const char *(*create)(SDL_Window *, RenderSize, void **);
    const char *(*resize)(SDL_Window *, RenderSize, void **);
    void (*destroy)(void *);
    const char *(*renderer)(void *);
    const char *(*draw)(void *, const DrawCommand *);
    const char *(*finish_batch)(void *);
    const char *(*present)(void *);
    const char *(*write_png)(void *, const char *);
    const char *(*extents)(void *, int, double *);
    void (*measure)(void *, const char *, size_t, double *);
    double (*caret_x)(void *, const char *, size_t, size_t byte_offset, size_t utf16_offset);
    const char *(*load_image)(const char *, int *, void **);
    void (*destroy_image)(void *);
} CanvasBackend;

const CanvasBackend *canvas_backend(const char *name);

#ifdef __cplusplus
}
#endif
