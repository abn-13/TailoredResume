package com.bu.tailorsg.jobs.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.Set;

/** HTML to readable plain text that keeps the posting's structure: headings, paragraphs and bullets on their own lines. */
public final class HtmlText {

    private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "ul", "ol", "table", "tr",
            "h1", "h2", "h3", "h4", "h5", "h6", "header", "footer", "blockquote", "pre", "dl", "dt", "dd");

    private HtmlText() {}

    public static String fromHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return fromElement(Jsoup.parseBodyFragment(html).body());
    }

    /** For HTML that arrives entity-encoded inside JSON (Greenhouse "content"). */
    public static String fromEncodedHtml(String encoded) {
        return fromHtml(Parser.unescapeEntities(encoded == null ? "" : encoded, false));
    }

    public static String fromElement(Element root) {
        StringBuilder sb = new StringBuilder();
        append(root, sb);
        return sb.toString().replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static void append(Node node, StringBuilder sb) {
        if (node instanceof TextNode text) {
            sb.append(text.text());
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.normalName();
        if (tag.equals("script") || tag.equals("style") || tag.equals("noscript")) {
            return;
        }
        if (tag.equals("br")) {
            sb.append('\n');
            return;
        }
        boolean block = BLOCKS.contains(tag);
        if (tag.equals("li")) {
            sb.append("\n• ");
        } else if (block) {
            sb.append('\n');
        }
        for (Node child : el.childNodes()) {
            append(child, sb);
        }
        if (block) {
            sb.append('\n'); // list items need no closing newline: the next "\n• " or the list's own newline ends them
        }
    }
}
