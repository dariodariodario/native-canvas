#include "skia_text.h"
#include "include/core/SkFont.h"
#include "include/ports/SkFontMgr_mac_ct.h"
#include "modules/skparagraph/include/ParagraphBuilder.h"
#include "modules/skunicode/include/SkUnicode_icu.h"
#include <cmath>
#include <cstdlib>
#include <sstream>
#include <string>

using namespace skia::textlayout;

SkiaText::SkiaText()
    : fonts(sk_make_sp<FontCollection>()), unicode(SkUnicodes::ICU::Make()) {
    fonts->setDefaultFontManager(SkFontMgr_New_CoreText(nullptr));
}

SkiaText::Font SkiaText::defaultFont() {
    Font font;
    font.setFontFamilies({SkString("Helvetica")});
    font.setFontSize(10);
    return font;
}

void SkiaText::setFont(Font& style, const char *text, size_t size) {
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

std::unique_ptr<Paragraph> SkiaText::layout(Font font, const char *text, size_t size) {
    ParagraphStyle style;
    style.setTextStyle(font);
    auto builder = ParagraphBuilder::make(style, fonts, unicode);
    builder->addText(text, size);
    auto result = builder->Build();
    result->layout(SK_ScalarInfinity);
    return result;
}

void SkiaText::draw(SkCanvas& canvas, const Font& font, const SkPaint& paint,
                    const char *text, size_t size, float x, float baseline) {
    if (!size) return;
    Font styled = font;
    styled.setForegroundPaint(paint);
    auto paragraph = layout(styled, text, size);
    paragraph->paint(&canvas, x, baseline - paragraph->getAlphabeticBaseline());
}

void SkiaText::measure(const Font& font, const char *text, size_t size, double *out) {
    auto paragraph = layout(font, text, size);
    float baseline = paragraph->getAlphabeticBaseline();
    SkRect ink = SkRect::MakeEmpty();
    paragraph->visit([&](int, const Paragraph::VisitorInfo *run) {
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
    out[0] = paragraph->getMaxIntrinsicWidth();
    out[1] = paragraph->getHeight();
    out[2] = baseline;
    out[3] = ink.left(); out[4] = ink.isEmpty() ? 0 : ink.top() - baseline;
    out[5] = ink.width(); out[6] = ink.height();
}

double SkiaText::caretX(const Font& font, const char *text, size_t size, bool atEnd, size_t utf16Offset) {
    if (size == 0) return 0;
    auto paragraph = layout(font, text, size);
    Paragraph::GlyphInfo glyph;
    if (!paragraph->getGlyphInfoAtUTF16Offset(atEnd ? utf16Offset - 1 : utf16Offset, &glyph))
        return paragraph->getMaxIntrinsicWidth();
    const SkRect& box = glyph.fGraphemeLayoutBounds;
    return (glyph.fDirection == TextDirection::kRtl) != atEnd ? box.right() : box.left();
}
