#include "command_buffer.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

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

const char *draw_batch(const CanvasBackend *backend, void *target,
                       const unsigned char *p, size_t length) {
    const unsigned char *end = p + length;
    static const unsigned char arity[] = {
        [DRAW_FILL_RECT] = 4,
        [DRAW_STROKE_RECT] = 4,
        [DRAW_CLEAR_RECT] = 4,
        [DRAW_MOVE_TO] = 2,
        [DRAW_LINE_TO] = 2,
        [DRAW_TRANSLATE] = 2,
        [DRAW_SCALE] = 2,
        [DRAW_ROTATE] = 1,
        [DRAW_SET_LINE_WIDTH] = 1,
        [DRAW_ADD_RECT] = 4,
        [DRAW_CUBIC_TO] = 6,
        [DRAW_FILL_TEXT] = 2,
        [DRAW_IMAGE] = 4,
        [DRAW_CLIP_RECT] = 4,
        [DRAW_SET_FILL_LINEAR] = 4,
        [DRAW_SET_STROKE_LINEAR] = 4,
        [DRAW_SET_FILL_RADIAL] = 6,
        [DRAW_SET_STROKE_RADIAL] = 6,
        [DRAW_SET_ANTIALIAS] = 1,
    };
    while (p < end) {
        unsigned op = *p++;
        if (op == 0 || op >= sizeof(arity)) goto malformed;
        DrawCommand command = {.op = (DrawOperation)op};
        switch (command.op) {
            case DRAW_SET_FILL_COLOR: case DRAW_SET_STROKE_COLOR:
                if ((size_t)(end - p) < sizeof(uint32_t)) goto malformed;
                command.rgba = read_uint(&p);
                break;
            case DRAW_SET_FONT: case DRAW_FILL_TEXT:
                if ((size_t)(end - p) < sizeof(uint32_t)) goto malformed;
                command.text_size = read_uint(&p);
                if ((size_t)(end - p) < command.text_size) goto malformed;
                command.text = (const char *)p;
                p += command.text_size;
                break;
            case DRAW_IMAGE: {
                uint64_t address;
                if ((size_t)(end - p) < sizeof(address)) goto malformed;
                memcpy(&address, p, sizeof(address));
                p += sizeof(address);
                command.image = (void *)(uintptr_t)address;
                break;
            }
            default:
                break;
        }
        if ((size_t)(end - p) < arity[op] * sizeof(double)) goto malformed;
        bool finite = true;
        for (unsigned i = 0; i < arity[op]; ++i) {
            command.args[i] = read_double(&p);
            finite = finite && isfinite(command.args[i]);
        }

        GradientStop local_stops[8];
        GradientStop *allocated_stops = NULL;
        if (op >= DRAW_SET_FILL_LINEAR && op <= DRAW_SET_STROKE_RADIAL) {
            const size_t stop_bytes = sizeof(double) + sizeof(uint32_t);
            if ((size_t)(end - p) < sizeof(uint32_t)) goto malformed;
            command.stop_count = read_uint(&p);
            if (command.stop_count > (size_t)(end - p) / stop_bytes) goto malformed;
            if (!finite) {
                p += command.stop_count * stop_bytes;
                continue;
            }
            GradientStop *stops = local_stops;
            if (command.stop_count > sizeof(local_stops) / sizeof(local_stops[0])) {
                allocated_stops = calloc(command.stop_count, sizeof(*stops));
                if (!allocated_stops) return "Cannot allocate gradient stops";
                stops = allocated_stops;
            }
            for (uint32_t i = 0; i < command.stop_count; ++i) {
                stops[i].offset = read_double(&p);
                stops[i].rgba = read_uint(&p);
            }
            command.stops = stops;
        }
        if (!finite) continue;
        const char *error = backend->draw(target, &command);
        free(allocated_stops);
        if (error) return error;
    }
    return backend->finish_batch ? backend->finish_batch(target) : NULL;

malformed:
    return "Malformed drawing command";
}
