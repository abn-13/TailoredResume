package com.bu.tailorsg.export;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * XHTML to PDF with openhtmltopdf. Liberation Sans (metric-compatible with Arial, SIL OFL) is embedded from
 * {@code resources/fonts}, so the file looks the same everywhere and every glyph can be extracted (ATS-01,
 * ATS-07).
 */
@Component
public class PdfRenderer {

    static final String FAMILY = "Liberation Sans";

    public byte[] render(Document html) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withProducer("TailorSG");
            font(builder, "LiberationSans-Regular.ttf", 400, FontStyle.NORMAL);
            font(builder, "LiberationSans-Bold.ttf", 700, FontStyle.NORMAL);
            font(builder, "LiberationSans-Italic.ttf", 400, FontStyle.ITALIC);
            builder.withW3cDocument(new W3CDom().fromJsoup(html), "/");
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed", e);
        }
        return out.toByteArray();
    }

    private static void font(PdfRendererBuilder builder, String file, int weight, FontStyle style) {
        // The cast picks the InputStream overload; the other overload takes a PDFBox font supplier.
        FSSupplier<InputStream> supplier = () -> PdfRenderer.class.getResourceAsStream("/fonts/" + file);
        builder.useFont(supplier, FAMILY, weight, style, true);
    }
}
