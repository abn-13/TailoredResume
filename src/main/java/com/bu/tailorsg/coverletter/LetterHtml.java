package com.bu.tailorsg.coverletter;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** A cover letter as XHTML for the PDF renderer: the resume's font (spec, Phase 7), 11pt, A4, 2.2cm margins. */
public final class LetterHtml {

    private LetterHtml() {}

    static final String CSS = """
            @page { size: A4; margin: 2.2cm; }
            body { font-family: 'Liberation Sans'; font-size: 11pt; line-height: 1.35; color: #000; margin: 0; }
            .name { font-size: 14pt; font-weight: bold; margin: 0; }
            .block { margin: 0 0 10pt 0; }
            .subject { font-weight: bold; margin: 0 0 10pt 0; }
            p { margin: 0 0 9pt 0; }
            """;

    public static Document of(LetterDocument letter, String title) {
        Document html = Document.createShell("");
        html.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        html.head().appendElement("meta").attr("charset", "UTF-8");
        html.title(title);
        html.head().appendElement("meta").attr("name", "author").attr("content", letter.name());
        html.head().appendElement("style").appendText(CSS);
        Element body = html.body();
        Element head = body.appendElement("div").addClass("block");
        head.appendElement("div").addClass("name").text(letter.name());
        head.appendElement("div").text(letter.contactLine());
        body.appendElement("div").addClass("block").text(letter.date());
        Element to = body.appendElement("div").addClass("block");
        letter.recipientBlock().forEach(l -> to.appendElement("div").text(l));
        body.appendElement("div").addClass("subject").text(letter.subject());
        body.appendElement("p").text(letter.salutation());
        letter.paragraphs().forEach(p -> body.appendElement("p").text(p));
        Element close = body.appendElement("div").addClass("block");
        close.appendElement("div").text(letter.signOff());
        close.appendElement("div").text(letter.name());
        return html;
    }
}
