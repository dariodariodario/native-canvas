#include "backend.h"
#include "skia_text.h"
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <SDL3/SDL_metal.h>
#include "include/core/SkCanvas.h"
#include "include/core/SkSurface.h"
#include "include/core/SkImage.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPaint.h"
#include "include/core/SkData.h"
#include "include/pathops/SkPathOps.h"
#include "include/effects/SkGradientShader.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendContext.h"
#include "include/gpu/ganesh/mtl/GrMtlDirectContext.h"
#include "include/gpu/ganesh/mtl/GrMtlBackendSurface.h"
#include <vector>

#include "include/core/SkPathUtils.h"
#include "include/core/SkStream.h"
#include "include/codec/SkPngDecoder.h"
#include "include/encode/SkPngEncoder.h"
#include <algorithm>

struct DrawingStyle {
    SkPaint fill, stroke;
    SkiaText::Font font = SkiaText::defaultFont();
    SkPath clip;

    DrawingStyle() {
        fill.setAntiAlias(true);
        stroke.setAntiAlias(true);
        stroke.setStyle(SkPaint::kStroke_Style);
        stroke.setStrokeWidth(1);
        stroke.setStrokeMiter(10);
    }
};

struct Backend {
    SDL_MetalView view = nullptr;
    id<MTLDevice> device = nil;
    id<MTLCommandQueue> queue = nil;
    CAMetalLayer *layer = nil;
    sk_sp<GrDirectContext> context;
    sk_sp<SkSurface> surface;
    DrawingStyle style;
    std::vector<DrawingStyle> stack;
    SkPathBuilder path;
    SkiaText text;
};

static void skia_destroy(void *backend);

static void *skia_create(SDL_Window *window, int width, int height, double sx, double sy) {
    @autoreleasepool {
        auto *b = new Backend();
        SkImageInfo info = SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
        if (window) {
            b->view = SDL_Metal_CreateView(window);
            b->device = MTLCreateSystemDefaultDevice();
            b->queue = [b->device newCommandQueue];
            if (!b->view || !b->device || !b->queue) { skia_destroy(b); return nullptr; }
            b->layer = (__bridge CAMetalLayer *)SDL_Metal_GetLayer(b->view);
            b->layer.device = b->device;
            b->layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
            b->layer.framebufferOnly = NO;
            b->layer.drawableSize = CGSizeMake(width, height);
            GrMtlBackendContext context;
            context.fDevice.retain((__bridge const void *)b->device);
            context.fQueue.retain((__bridge const void *)b->queue);
            b->context = GrDirectContexts::MakeMetal(context);
            if (b->context) b->surface = SkSurfaces::RenderTarget(b->context.get(), skgpu::Budgeted::kYes, info);
        } else {
            b->surface = SkSurfaces::Raster(info);
        }
        if (!b->surface) { skia_destroy(b); return nullptr; }
        b->style.clip = SkPath::Rect(SkRect::MakeWH(width, height));
        b->surface->getCanvas()->clear(SK_ColorTRANSPARENT);
        b->surface->getCanvas()->scale(sx, sy);
        return b;
    }
}

static void skia_destroy(void *backend) {
    auto *b = static_cast<Backend *>(backend);
    if (!b) return;
    b->surface.reset();
    b->context.reset();
    if (b->view) SDL_Metal_DestroyView(b->view);
    delete b;
}

static bool skia_present(void *backend) {
    @autoreleasepool {
        auto *b = static_cast<Backend *>(backend);
        id<CAMetalDrawable> drawable = [b->layer nextDrawable];
        if (!drawable) return true;
        GrMtlTextureInfo texture;
        texture.fTexture.retain((__bridge const void *)drawable.texture);
        auto target = GrBackendRenderTargets::MakeMtl((int)drawable.texture.width,
                                                      (int)drawable.texture.height, texture);
        auto output = SkSurfaces::WrapBackendRenderTarget(b->context.get(), target,
                kTopLeft_GrSurfaceOrigin, kBGRA_8888_SkColorType, nullptr, nullptr);
        if (!output) return false;
        output->getCanvas()->clear(SK_ColorTRANSPARENT);
        output->getCanvas()->drawImage(b->surface->makeImageSnapshot(), 0, 0);
        b->context->flushAndSubmit();
        id<MTLCommandBuffer> commands = [b->queue commandBuffer];
        [commands presentDrawable:drawable];
        [commands commit];
        return true;
    }
}

static SkColor color(uint32_t rgba) {
    return (rgba >> 8) | (rgba << 24);
}

static SkPath localPath(Backend& b, SkCanvas& canvas) {
    SkMatrix inverse;
    if (!canvas.getTotalMatrix().invert(&inverse)) return SkPath();
    return b.path.snapshot().makeTransform(inverse);
}

static void clip(Backend& backend, SkCanvas& canvas, const SkPath& path) {
    canvas.clipPath(path, SkClipOp::kIntersect, true);
    SkPath devicePath = path.makeTransform(canvas.getTotalMatrix());
    SkRect previousRect, nextRect;
    if (backend.style.clip.isRect(&previousRect) && devicePath.isRect(&nextRect)) {
        if (!previousRect.intersect(nextRect)) previousRect.setEmpty();
        backend.style.clip = SkPath::Rect(previousRect);
        return;
    }
    auto intersection = Op(backend.style.clip, devicePath, kIntersect_SkPathOp);
    if (intersection) backend.style.clip = std::move(*intersection);
    else backend.style.clip = SkPath::Rect(SkRect::Make(canvas.getDeviceClipBounds()));
}

static void skia_draw(void *backend, DrawOperation op, const double *v, uint32_t rgba,
                           const char *text, uint32_t size, void *image) {
    auto& b = *static_cast<Backend *>(backend);
    auto& canvas = *b.surface->getCanvas();
    auto& style = b.style;
    auto point = [&](int i) {
        return canvas.getTotalMatrix().mapPoint({(float)v[i], (float)v[i + 1]});
    };
    switch (op) {
        case DRAW_SET_FILL_COLOR: case DRAW_SET_STROKE_COLOR: {
            auto& paint = op == DRAW_SET_FILL_COLOR ? style.fill : style.stroke;
            paint.setShader(nullptr);
            paint.setColor(color(rgba));
            break;
        }
        case DRAW_FILL_RECT: case DRAW_STROKE_RECT: case DRAW_CLEAR_RECT: case DRAW_ADD_RECT: case DRAW_IMAGE: case DRAW_CLIP_RECT: {
            if (op == DRAW_ADD_RECT && (!v[2] || !v[3])) break;
            auto rect = SkRect::MakeXYWH(v[0], v[1], v[2], v[3]);
            rect.sort();
            if (op == DRAW_FILL_RECT || op == DRAW_STROKE_RECT) canvas.drawRect(rect, op == DRAW_FILL_RECT ? style.fill : style.stroke);
            else if (op == DRAW_CLEAR_RECT) {
                SkPaint clear;
                clear.setAntiAlias(style.fill.isAntiAlias());
                clear.setBlendMode(SkBlendMode::kClear);
                canvas.drawRect(rect, clear);
            } else if (op == DRAW_ADD_RECT) {
                auto direction = (v[2] < 0) != (v[3] < 0)
                    ? SkPathDirection::kCCW : SkPathDirection::kCW;
                b.path.addPath(SkPath::Rect(rect, direction), canvas.getTotalMatrix());
            } else if (op == DRAW_IMAGE) {
                canvas.drawImageRect(*static_cast<sk_sp<SkImage> *>(image), rect,
                                     SkSamplingOptions(SkFilterMode::kLinear));
            } else clip(b, canvas, SkPath::Rect(rect));
            break;
        }
        case DRAW_BEGIN_PATH:
            b.path.reset();
            break;
        case DRAW_MOVE_TO: b.path.moveTo(point(0)); break;
        case DRAW_LINE_TO:
            if (b.path.isEmpty()) b.path.moveTo(point(0));
            else b.path.lineTo(point(0));
            break;
        case DRAW_CLOSE_PATH: b.path.close(); break;
        case DRAW_FILL_PATH: case DRAW_STROKE_PATH: canvas.drawPath(localPath(b, canvas), op == DRAW_FILL_PATH ? style.fill : style.stroke); break;
        case DRAW_SAVE: b.stack.push_back(style); canvas.save(); break;
        case DRAW_RESTORE:
            if (!b.stack.empty()) {
                style = std::move(b.stack.back());
                b.stack.pop_back();
                canvas.restore();
            }
            break;
        case DRAW_TRANSLATE: canvas.translate(v[0], v[1]); break;
        case DRAW_SCALE: if (v[0] && v[1]) canvas.scale(v[0], v[1]); break;
        case DRAW_ROTATE: canvas.rotate(v[0] * 180 / 3.141592653589793); break;
        case DRAW_SET_LINE_WIDTH: if (v[0] > 0) style.stroke.setStrokeWidth(v[0]); break;
        case DRAW_CLIP_PATH: clip(b, canvas, localPath(b, canvas)); break;
        case DRAW_CUBIC_TO:
            if (b.path.isEmpty()) b.path.moveTo(point(0));
            b.path.cubicTo(point(0), point(2), point(4));
            break;
        case DRAW_SET_FONT: SkiaText::setFont(style.font, text, size); break;
        case DRAW_FILL_TEXT:
            b.text.draw(canvas, style.font, style.fill, text, size, v[0], v[1]);
            break;
        default: break;
    }
}

static void skia_gradient(void *backend, DrawOperation op, const double *v,
                               uint32_t count, const GradientStop *data) {
    auto& style = static_cast<Backend *>(backend)->style;
    auto& paint = op == DRAW_SET_STROKE_LINEAR || op == DRAW_SET_STROKE_RADIAL ? style.stroke : style.fill;
    std::vector<std::pair<float, SkColor>> stops;
    for (uint32_t i = 0; i < count; ++i)
        stops.emplace_back(data[i].offset, color(data[i].rgba));
    std::stable_sort(stops.begin(), stops.end(), [](auto a, auto b) { return a.first < b.first; });
    std::vector<SkColor> colors;
    std::vector<SkScalar> positions;
    for (auto stop : stops) {
        positions.push_back(stop.first);
        colors.push_back(stop.second);
    }
    paint.setShader(nullptr);
    paint.setColor(count ? colors.back() : SK_ColorTRANSPARENT);
    if (count < 2) return;
    SkPoint start{(float)v[0], (float)v[1]};
    SkPoint end{(float)v[op < DRAW_SET_FILL_RADIAL ? 2 : 3], (float)v[op < DRAW_SET_FILL_RADIAL ? 3 : 4]};
    SkPoint points[] = {start, end};
    auto shader = op < DRAW_SET_FILL_RADIAL
        ? SkGradientShader::MakeLinear(points, colors.data(), positions.data(), count, SkTileMode::kClamp)
        : SkGradientShader::MakeTwoPointConical(start, v[2], end, v[5], colors.data(),
                                               positions.data(), count, SkTileMode::kClamp);
    if (shader) {
        paint.setColor(SK_ColorWHITE);
        paint.setShader(std::move(shader));
    }
}

static void skia_extents(void *backend, int kind, double *out) {
    auto& b = *static_cast<Backend *>(backend);
    auto& canvas = *b.surface->getCanvas();
    SkRect bounds = SkRect::MakeEmpty();
    if (kind == 3) {
        SkMatrix inverse;
        if (canvas.getTotalMatrix().invert(&inverse))
            bounds = b.style.clip.makeTransform(inverse).computeTightBounds();
    } else {
        auto path = localPath(b, canvas);
        if (kind == 2) path = skpathutils::FillPathWithPaint(path, b.style.stroke);
        if (kind == 1) {
            SkPath filled;
            if (Simplify(path, &filled)) path = std::move(filled);
        }
        bounds = path.computeTightBounds();
    }
    out[0] = bounds.left(); out[1] = bounds.top();
    out[2] = bounds.right(); out[3] = bounds.bottom();
}

static void skia_measure(void *target, const char *text, size_t size, double *out) {
    auto& backend = *static_cast<Backend *>(target);
    backend.text.measure(backend.style.font, text, size, out);
}

static double caret_x(void *target, const char *text, size_t size, size_t byte_offset, size_t utf16_offset) {
    auto& backend = *static_cast<Backend *>(target);
    return backend.text.caretX(backend.style.font, text, size, byte_offset == size, utf16_offset);
}

static bool skia_png(void *backend, const char *path) {
    auto& b = *static_cast<Backend *>(backend);
    auto data = SkPngEncoder::Encode(b.context.get(), b.surface->makeImageSnapshot().get(), {});
    if (!data) return false;
    SkFILEWStream file(path);
    return file.isValid() && file.write(data->data(), data->size());
}

static void *skia_load_image(const char *path, int *size) {
    auto data = SkData::MakeFromFileName(path);
    if (!data) return nullptr;
    auto codec = SkPngDecoder::Decode(data, nullptr);
    if (!codec) return nullptr;
    auto [image, result] = codec->getImage();
    if (!image || result != SkCodec::kSuccess) return nullptr;
    size[0] = image->width(); size[1] = image->height();
    return new sk_sp<SkImage>(std::move(image));
}

static void skia_destroy_image(void *image) {
    delete static_cast<sk_sp<SkImage> *>(image);
}

static const char *create(SDL_Window *window, RenderSize size, void **out) {
    *out = skia_create(window, size.width, size.height, size.scale_x, size.scale_y);
    return *out ? nullptr : "Cannot initialize Skia target";
}

static const char *resize(SDL_Window *window, RenderSize size, void **target) {
    auto& backend = *static_cast<Backend *>(*target);
    auto info = SkImageInfo::Make(size.width, size.height, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    auto surface = backend.context
        ? SkSurfaces::RenderTarget(backend.context.get(), skgpu::Budgeted::kYes, info)
        : SkSurfaces::Raster(info);
    if (!surface) return "Cannot resize Skia target";
    surface->getCanvas()->clear(SK_ColorTRANSPARENT);
    surface->getCanvas()->scale(size.scale_x, size.scale_y);
    backend.surface = std::move(surface);
    backend.style = DrawingStyle();
    backend.style.clip = SkPath::Rect(SkRect::MakeWH(size.width, size.height));
    backend.stack.clear();
    backend.path.reset();
    if (backend.layer) backend.layer.drawableSize = CGSizeMake(size.width, size.height);
    return nullptr;
}

static const char *renderer(void *target) {
    return static_cast<Backend *>(target)->view ? "skia-metal" : "skia-raster";
}

static const char *draw(void *target, const DrawCommand *command) {
    if (command->op >= DRAW_SET_FILL_LINEAR && command->op <= DRAW_SET_STROKE_RADIAL)
        skia_gradient(target, command->op, command->args, command->stop_count, command->stops);
    else
        skia_draw(target, command->op, command->args, command->rgba,
                  command->text, command->text_size, command->image);
    return nullptr;
}

static const char *present(void *target) {
    return skia_present(target) ? nullptr : "Skia Metal presentation failed";
}

static const char *write_png(void *target, const char *path) {
    return skia_png(target, path) ? nullptr : "Cannot write Skia PNG";
}

static const char *extents(void *target, int kind, double *bounds) {
    skia_extents(target, kind, bounds);
    return nullptr;
}

static const char *load_image(const char *path, int *size, void **out) {
    *out = skia_load_image(path, size);
    return *out ? nullptr : "Cannot decode Skia PNG";
}

extern "C" const CanvasBackend skia_backend = {
    "skia", SDL_WINDOW_METAL, create, resize, skia_destroy, renderer, draw, nullptr,
    present, write_png, extents, skia_measure, caret_x, load_image, skia_destroy_image
};
