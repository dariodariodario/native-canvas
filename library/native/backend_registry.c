#include "backend.h"
#include <string.h>

#ifdef NATIVECANVAS_HAS_CAIRO
extern const CanvasBackend cairo_backend;
#endif
#ifdef NATIVECANVAS_HAS_SKIA
extern const CanvasBackend skia_backend;
#endif

const CanvasBackend *canvas_backend(const char *name) {
    static const CanvasBackend *const backends[] = {
#ifdef NATIVECANVAS_HAS_CAIRO
        &cairo_backend,
#endif
#ifdef NATIVECANVAS_HAS_SKIA
        &skia_backend,
#endif
    };
    for (size_t i = 0; i < sizeof(backends) / sizeof(backends[0]); ++i) {
        if (strcmp(name, backends[i]->name) == 0) return backends[i];
    }
    return NULL;
}
