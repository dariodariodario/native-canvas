#include "backend.h"
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <SDL3/SDL_metal.h>
#include "include/core/SkCanvas.h"
#include "include/core/SkSurface.h"
#include "include/core/SkImage.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkPaint.h"
#include "include/core/SkData.h"
#include "include/core/SkFont.h"
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
#include "include/ports/SkFontMgr_mac_ct.h"
#include "modules/skparagraph/include/FontCollection.h"
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skunicode/include/SkUnicode_icu.h"
#include <algorithm>
#include <cmath>
#include <sstream>
#include <string>

using namespace skia::textlayout;

struct DrawingStyle {
    SkPaint fill, stroke;
    TextStyle text;

    DrawingStyle() {
        fill.setAntiAlias(true);
        stroke.setAntiAlias(true);
        stroke.setStyle(SkPaint::kStroke_Style);
        stroke.setStrokeWidth(1);
        stroke.setStrokeMiter(10);
        text.setFontFamilies({SkString("Helvetica")});
        text.setFontSize(10);
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
    sk_sp<FontCollection> fonts = sk_make_sp<FontCollection>();
    sk_sp<SkUnicode> unicode = SkUnicodes::ICU::Make();
};

static void skia_destroy(void *backend);

static void *skia_create(SDL_Window *window, int width, int height, double sx, double sy) {
    @autoreleasepool {
        auto *b = new Backend();
        b->fonts->setDefaultFontManager(SkFontMgr_New_CoreText(nullptr));
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
        if (!drawable) return false;
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

static void setFont(TextStyle& style, const char *text, size_t size) {
    std::istringstream words(std::string(text, size));
    std::vector<std::string> tokens;
    for (std::string word; words >> word;) tokens.push_back(word);
    float points = 10;
    if (!tokens.empty()) {
        char *end;
        const char *number = tokens.back().c_str();
        float parsed = std::strtof(number, &end);
        if (end != number && (!*end || std::string(end) == "px") && std::isfinite(parsed) && parsed > 0) {
            points = *end ? parsed : parsed * 96 / 72;
            tokens.pop_back();
        }
    }
    int weight = SkFontStyle::kNormal_Weight;
    auto slant = SkFontStyle::kUpright_Slant;
    while (!tokens.empty()) {
        const auto& suffix = tokens.back();
        if (suffix == "Bold") weight = SkFontStyle::kBold_Weight;
        else if (suffix == "Italic") slant = SkFontStyle::kItalic_Slant;
        else if (suffix == "Oblique") slant = SkFontStyle::kOblique_Slant;
        else if (suffix != "Regular" && suffix != "Normal") break;
        tokens.pop_back();
    }
    std::string family;
    for (const auto& token : tokens) {
        if (!family.empty()) family += ' ';
        family += token;
    }
    if (family.empty() || family == "Sans") family = "Helvetica";
    else if (family == "Serif") family = "Times";
    else if (family == "Monospace") family = "Menlo";
    style.setFontFamilies({SkString(family.c_str())});
    style.setFontStyle(SkFontStyle(weight, SkFontStyle::kNormal_Width, slant));
    style.setFontSize(points);
}

static std::unique_ptr<Paragraph> paragraph(Backend& b, const char *text, size_t size) {
    ParagraphStyle style;
    TextStyle font = b.style.text;
    font.setForegroundPaint(b.style.fill);
    style.setTextStyle(font);
    auto builder = ParagraphBuilder::make(style, b.fonts, b.unicode);
    builder->addText(text, size);
    auto result = builder->Build();
    result->layout(SK_ScalarInfinity);
    return result;
}

static SkPath localPath(Backend& b, SkCanvas& canvas) {
    SkMatrix inverse;
    if (!canvas.getTotalMatrix().invert(&inverse)) return SkPath();
    return b.path.snapshot().makeTransform(inverse);
}

static void skia_draw(void *backend, unsigned op, const double *v, uint32_t rgba,
                           const char *text, uint32_t size, void *image) {
    auto& b = *static_cast<Backend *>(backend);
    auto& canvas = *b.surface->getCanvas();
    auto& style = b.style;
    auto point = [&](int i) {
        return canvas.getTotalMatrix().mapPoint({(float)v[i], (float)v[i + 1]});
    };
    switch (op) {
        case 1: case 2: {
            auto& paint = op == 1 ? style.fill : style.stroke;
            paint.setShader(nullptr);
            paint.setColor(color(rgba));
            break;
        }
        case 3: case 4: case 5: case 18: case 23: case 24: {
            auto rect = SkRect::MakeXYWH(v[0], v[1], v[2], v[3]);
            rect.sort();
            if (op == 3 || op == 4) canvas.drawRect(rect, op == 3 ? style.fill : style.stroke);
            else if (op == 5) {
                SkPaint clear;
                clear.setAntiAlias(style.fill.isAntiAlias());
                clear.setBlendMode(SkBlendMode::kClear);
                canvas.drawRect(rect, clear);
            } else if (op == 18) {
                b.path.addPath(SkPath::Rect(rect), canvas.getTotalMatrix());
            } else if (op == 23) {
                canvas.drawImageRect(*static_cast<sk_sp<SkImage> *>(image), rect,
                                     SkSamplingOptions(SkFilterMode::kLinear));
            } else canvas.clipRect(rect, SkClipOp::kIntersect, true);
            break;
        }
        case 6: b.path.reset(); break;
        case 7: b.path.moveTo(point(0)); break;
        case 8:
            if (b.path.isEmpty()) b.path.moveTo(point(0));
            else b.path.lineTo(point(0));
            break;
        case 9: b.path.close(); break;
        case 10: case 11: canvas.drawPath(localPath(b, canvas), op == 10 ? style.fill : style.stroke); break;
        case 12: b.stack.push_back(style); canvas.save(); break;
        case 13:
            if (!b.stack.empty()) {
                style = std::move(b.stack.back());
                b.stack.pop_back();
                canvas.restore();
            }
            break;
        case 14: canvas.translate(v[0], v[1]); break;
        case 15: if (v[0] && v[1]) canvas.scale(v[0], v[1]); break;
        case 16: canvas.rotate(v[0] * 180 / 3.141592653589793); break;
        case 17: if (v[0] > 0) style.stroke.setStrokeWidth(v[0]); break;
        case 19: canvas.clipPath(localPath(b, canvas), SkClipOp::kIntersect, true); break;
        case 20:
            if (b.path.isEmpty()) b.path.moveTo(point(0));
            b.path.cubicTo(point(0), point(2), point(4));
            break;
        case 21: setFont(style.text, text, size); break;
        case 22:
            if (size) {
                auto layout = paragraph(b, text, size);
                layout->paint(&canvas, v[0], v[1] - layout->getAlphabeticBaseline());
            }
            break;
    }
}

static void skia_gradient(void *backend, unsigned op, const double *v,
                               uint32_t count, const unsigned char *data) {
    auto& style = static_cast<Backend *>(backend)->style;
    auto& paint = op == 26 || op == 28 ? style.stroke : style.fill;
    std::vector<std::pair<float, SkColor>> stops;
    for (uint32_t i = 0; i < count; i++, data += 12) {
        double offset;
        uint32_t rgba;
        memcpy(&offset, data, 8);
        memcpy(&rgba, data + 8, 4);
        stops.emplace_back(std::clamp(offset, 0., 1.), color(rgba));
    }
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
    SkPoint end{(float)v[op < 27 ? 2 : 3], (float)v[op < 27 ? 3 : 4]};
    SkPoint points[] = {start, end};
    auto shader = op < 27
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
            bounds = inverse.mapRect(SkRect::Make(canvas.getDeviceClipBounds()));
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

static void skia_measure(void *backend, const char *text, size_t size, double *out) {
    auto layout = paragraph(*static_cast<Backend *>(backend), text, size);
    float baseline = layout->getAlphabeticBaseline();
    SkRect ink = SkRect::MakeEmpty();
    layout->visit([&](int, const Paragraph::VisitorInfo *run) {
        if (!run || !run->count) return;
        std::vector<SkRect> glyphBounds(run->count);
        run->font.getBounds({run->glyphs, static_cast<size_t>(run->count)}, glyphBounds, nullptr);
        for (int i = 0; i < run->count; ++i) {
            if (glyphBounds[i].isEmpty()) continue;
            const auto point = run->origin + run->positions[i];
            glyphBounds[i].offset(point.x(), point.y());
            ink.join(glyphBounds[i]);
        }
    });
    out[0] = layout->getMaxIntrinsicWidth();
    out[1] = layout->getHeight();
    out[2] = baseline;
    out[3] = ink.left(); out[4] = ink.isEmpty() ? 0 : ink.top() - baseline;
    out[5] = ink.width(); out[6] = ink.height();
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
    void *next = nullptr;
    if (const char *error = create(window, size, &next)) return error;
    skia_destroy(*target);
    *target = next;
    return nullptr;
}

static const char *renderer(void *target) {
    return static_cast<Backend *>(target)->view ? "skia-metal" : "skia-raster";
}

static const char *draw(void *target, const DrawCommand *command) {
    if (command->op >= 25 && command->op <= 28)
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
    present, write_png, extents, skia_measure, load_image, skia_destroy_image
};
