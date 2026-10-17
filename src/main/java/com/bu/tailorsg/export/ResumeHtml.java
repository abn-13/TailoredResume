package com.bu.tailorsg.export;

import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Section;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The spec 4 template as XHTML for the PDF renderer: A4, one column, Liberation Sans 11pt, headings 12.5pt
 * bold, name 17pt. Bullets are the "•" character as text with a hanging indent, so a parser reads a real
 * bullet glyph.
 *
 * <p>Dates are right-aligned with two inline-blocks (headline 76%, dates 24%), never a table. Not
 * {@code float: right}: openhtmltopdf paints floats in a separate layer, so PDFBox extracted every date at
 * the top of the page, far from its job (found by the ATS-04 round trip).
 *
 * <p>Built with Jsoup elements rather than string concatenation, so every value is escaped.
 */
public final class ResumeHtml {

    private ResumeHtml() {}

    static final String CSS = """
            @page { size: A4; margin: 2cm; }
            body { font-family: 'Liberation Sans'; font-size: 11pt; line-height: 1.25; color: #000; margin: 0; }
            .name { font-size: 17pt; font-weight: bold; text-align: center; margin: 0 0 2pt 0; }
            .contact, .auth { font-size: 10.5pt; text-align: center; margin: 0; }
            h2 { font-size: 12.5pt; font-weight: bold; margin: 9pt 0 3pt 0; padding-bottom: 1pt; border-bottom: 0.75pt solid #000; }
            .entry { margin: 0 0 4pt 0; }
            .head { margin: 0; }
            .head .what { display: inline-block; width: 76%; vertical-align: top; font-weight: bold; }
            .head .dates { display: inline-block; width: 24%; vertical-align: top; text-align: right; }
            .detail, .line { margin: 0; }
            .bullet { margin: 1pt 0 0 0; padding-left: 10pt; text-indent: -7pt; }
            """;

    public static Document of(ResumeDocument doc, String title) {
        Document html = Document.createShell("");
        html.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        html.head().appendElement("meta").attr("charset", "UTF-8");
        html.title(title);
        html.head().appendElement("meta").attr("name", "author").attr("content", doc.header().name());
        html.head().appendElement("style").appendText(CSS);
        Element body = html.body();

        body.appendElement("div").addClass("name").text(doc.header().name());
        body.appendElement("div").addClass("contact").text(doc.contactLine());
        if (doc.header().workAuthLine() != null) {
            body.appendElement("div").addClass("auth").text(doc.header().workAuthLine());
        }
        for (Section s : doc.sections()) {
            body.appendElement("h2").text(s.heading());
            s.lines().forEach(l -> body.appendElement("div").addClass("line").text(l));
            for (Entry e : s.entries()) {
                Element entry = body.appendElement("div").addClass("entry");
                Element head = entry.appendElement("div").addClass("head");
                head.appendElement("span").addClass("what").text(e.headline());
                head.appendElement("span").addClass("dates").text(e.dates());
                e.details().forEach(d -> entry.appendElement("div").addClass("detail").text(d));
                // A plain space after the bullet: non-breaking spaces extract as U+00A0, which many parsers do not treat as a space.
                e.bullets().forEach(b -> entry.appendElement("div").addClass("bullet").text("\u2022 " + b.text()));
            }
            s.skillLines().forEach(sl -> {
                Element line = body.appendElement("div").addClass("line");
                line.appendElement("b").text(sl.category() + ":");
                line.appendText(" " + String.join(", ", sl.items()));
            });
        }
        return html;
    }
}
