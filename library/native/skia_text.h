#pragma once
#include "modules/skparagraph/include/FontCollection.h"
#include "modules/skparagraph/include/Paragraph.h"
#include "modules/skunicode/include/SkUnicode.h"

class SkiaText {
public:
    using Font = skia::textlayout::TextStyle;

    SkiaText();
    static Font defaultFont();
    static void setFont(Font&, const char *, size_t);
    void draw(SkCanvas&, const Font&, const SkPaint&, const char *, size_t, float x, float baseline);
    void measure(const Font&, const char *, size_t, double *);
    double caretX(const Font&, const char *, size_t, bool atEnd, size_t utf16Offset);

private:
    std::unique_ptr<skia::textlayout::Paragraph> layout(Font, const char *, size_t);
    sk_sp<skia::textlayout::FontCollection> fonts;
    sk_sp<SkUnicode> unicode;
};
