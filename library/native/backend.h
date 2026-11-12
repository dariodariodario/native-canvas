#pragma once
#include <SDL3/SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    unsigned op;
    double args[6];
    uint32_t rgba;
    const char *text;
    uint32_t text_size;
    void *image;
    const unsigned char *stops; /* Packed native-endian double offset and uint32_t color. */
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
    const char *(*load_image)(const char *, int *, void **);
    void (*destroy_image)(void *);
} CanvasBackend;

const CanvasBackend *canvas_backend(const char *name);

#ifdef __cplusplus
}
#endif
