package com.bu.tailorsg.export;

import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Section;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.List;

/**
 * The same template as {@link ResumeHtml}, as a Word document (spec 4, ATS-06): real Word bullet numbering
 * (not typed "•" characters), dates on a right-aligned tab stop, and no tables, text boxes, headers or
 * footers. Arial is named as the font (Word has it; LibreOffice substitutes metric-compatible Liberation Sans).
 *
 * <p>Sizes in Word XML: half-points for fonts, twentieths of a point ("twips") for lengths. A4 is
 * 11906 x 16838 twips; 2 cm is 1134 twips.
 */
@Component
public class DocxRenderer {

    static final String FONT = "Arial";
    static final int PAGE_W = 11906;
    static final int PAGE_H = 16838;
    static final int MARGIN = 1134;
    static final int TEXT_WIDTH = PAGE_W - 2 * MARGIN;

    public byte[] render(ResumeDocument doc, String title) {
        try (XWPFDocument docx = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            page(docx);
            docx.getProperties().getCoreProperties().setTitle(title);
            docx.getProperties().getCoreProperties().setCreator(doc.header().name());
            BigInteger bullets = bulletNumbering(docx);

            XWPFParagraph name = paragraph(docx, ParagraphAlignment.CENTER, 0, 40);
            run(name, doc.header().name(), 17, true);
            run(paragraph(docx, ParagraphAlignment.CENTER, 0, 0), doc.contactLine(), 10.5, false);
            if (doc.header().workAuthLine() != null) {
                run(paragraph(docx, ParagraphAlignment.CENTER, 0, 0), doc.header().workAuthLine(), 10.5, false);
            }
            for (Section s : doc.sections()) {
                XWPFParagraph h = paragraph(docx, ParagraphAlignment.LEFT, 180, 60);
                h.setBorderBottom(Borders.SINGLE);
                run(h, s.heading(), 12.5, true);
                s.lines().forEach(l -> run(paragraph(docx, ParagraphAlignment.LEFT, 0, 0), l, 11, false));
                for (Entry e : s.entries()) {
                    XWPFParagraph head = paragraph(docx, ParagraphAlignment.LEFT, 60, 0);
                    rightTab(head);
                    run(head, e.headline(), 11, true);
                    if (!e.dates().isEmpty()) {
                        XWPFRun dates = run(head, "", 11, false);
                        dates.addTab();
                        dates.setText(e.dates());
                    }
                    e.details().forEach(d -> run(paragraph(docx, ParagraphAlignment.LEFT, 0, 0), d, 11, false));
                    for (var b : e.bullets()) {
                        XWPFParagraph p = paragraph(docx, ParagraphAlignment.LEFT, 20, 0);
                        p.setNumID(bullets);
                        p.setNumILvl(BigInteger.ZERO);
                        run(p, b.text(), 11, false);
                    }
                }
                for (var sl : s.skillLines()) {
                    XWPFParagraph p = paragraph(docx, ParagraphAlignment.LEFT, 0, 0);
                    run(p, sl.category() + ":", 11, true);
                    run(p, " " + String.join(", ", sl.items()), 11, false);
                }
            }
            docx.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("DOCX rendering failed", e);
        }
    }

    /** Plain paragraphs (cover letters): one Word paragraph per string, 11pt, spacing after each. */
    public byte[] renderParagraphs(List<String> paragraphs, String title, String author) {
        try (XWPFDocument docx = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            page(docx);
            docx.getProperties().getCoreProperties().setTitle(title);
            docx.getProperties().getCoreProperties().setCreator(author);
            for (String text : paragraphs) {
                XWPFParagraph p = paragraph(docx, ParagraphAlignment.LEFT, 0, 200);
                String[] lines = text.split("\n", -1);
                XWPFRun r = run(p, lines[0], 11, false);
                for (int i = 1; i < lines.length; i++) {
                    r.addBreak();
                    r.setText(lines[i]);
                }
            }
            docx.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("DOCX rendering failed", e);
        }
    }

    private static void page(XWPFDocument docx) {
        CTSectPr sect = docx.getDocument().getBody().addNewSectPr();
        CTPageSz size = sect.addNewPgSz();
        size.setW(BigInteger.valueOf(PAGE_W));
        size.setH(BigInteger.valueOf(PAGE_H));
        CTPageMar mar = sect.addNewPgMar();
        BigInteger m = BigInteger.valueOf(MARGIN);
        mar.setTop(m);
        mar.setBottom(m);
        mar.setLeft(m);
        mar.setRight(m);
    }

    /** One abstract list: level 0 is a "•" bullet with a hanging indent. */
    private static BigInteger bulletNumbering(XWPFDocument docx) {
        CTAbstractNum abs = CTAbstractNum.Factory.newInstance();
        abs.setAbstractNumId(BigInteger.ZERO);
        CTLvl lvl = abs.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);
        lvl.addNewStart().setVal(BigInteger.ONE);
        lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
        lvl.addNewLvlText().setVal("•");
        CTInd ind = lvl.addNewPPr().addNewInd();
        ind.setLeft(BigInteger.valueOf(360));
        ind.setHanging(BigInteger.valueOf(220));
        CTFonts fonts = lvl.addNewRPr().addNewRFonts();
        fonts.setAscii(FONT);
        fonts.setHAnsi(FONT);
        XWPFNumbering numbering = docx.createNumbering();
        BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(abs));
        return numbering.addNum(abstractId);
    }

    private static void rightTab(XWPFParagraph p) {
        CTTabStop tab = p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetTabs()
                ? p.getCTP().getPPr().getTabs().addNewTab()
                : (p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr()).addNewTabs().addNewTab();
        tab.setVal(STTabJc.RIGHT);
        tab.setPos(BigInteger.valueOf(TEXT_WIDTH));
    }

    private static XWPFParagraph paragraph(XWPFDocument docx, ParagraphAlignment align, int before, int after) {
        XWPFParagraph p = docx.createParagraph();
        p.setAlignment(align);
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static XWPFRun run(XWPFParagraph p, String text, double pt, boolean bold) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(pt);
        r.setBold(bold);
        r.setText(text);
        return r;
    }
}
