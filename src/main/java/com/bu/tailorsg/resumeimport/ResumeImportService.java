package com.bu.tailorsg.resumeimport;

import com.bu.tailorsg.documents.TextExtractor;
import com.bu.tailorsg.documents.TextExtractor.Extracted;
import com.bu.tailorsg.documents.UnreadableDocumentException;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.updates.PendingUpdate;
import com.bu.tailorsg.updates.PendingUpdateService;
import com.bu.tailorsg.updates.PendingUpdateService.Proposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an uploaded PDF/DOCX resume into a PendingUpdate for review (spec 3: POST /api/master/import).
 *
 * <ol>
 *   <li>Extract text (PDFBox / POI).</li>
 *   <li>Redact lines that look like P6 personal data, before storing or sending anything.</li>
 *   <li>The extractor model maps the text to JSON (schema-validated, one retry).</li>
 *   <li>Deterministic code cleans it up ({@link ImportMapper}), links skill evidence
 *       ({@link EvidenceLinker}), and lists every value not found in the text ({@link ImportVerifier}).</li>
 *   <li>The result becomes one patch replacing the whole document. Nothing is applied until the user
 *       reviews it.</li>
 * </ol>
 */
@Service
public class ResumeImportService {

    private static final Logger log = LoggerFactory.getLogger(ResumeImportService.class);
    static final String PROMPT = "master-import";
    static final int MAX_TEXT_CHARS = 40_000;

    private final TextExtractor extractor;
    private final PersonalDataRedactor redactor;
    private final ClaudeClient claude;
    private final ImportMapper mapper;
    private final EvidenceLinker linker;
    private final ImportVerifier verifier;
    private final PendingUpdateService updates;
    private final MasterResumeService master;

    public ResumeImportService(TextExtractor extractor, PersonalDataRedactor redactor, ClaudeClient claude,
                               ImportMapper mapper, EvidenceLinker linker, ImportVerifier verifier,
                               PendingUpdateService updates, MasterResumeService master) {
        this.extractor = extractor;
        this.redactor = redactor;
        this.claude = claude;
        this.mapper = mapper;
        this.linker = linker;
        this.verifier = verifier;
        this.updates = updates;
        this.master = master;
    }

    /**
     * @throws UnreadableDocumentException if the file is not a readable PDF/DOCX
     * @throws com.bu.tailorsg.llm.LlmException if the model call fails (or no API key is set)
     */
    public PendingUpdate importResume(String filename, byte[] bytes) {
        Extracted extracted = extractor.extract(bytes);
        if (extracted.text().length() > MAX_TEXT_CHARS) {
            throw new UnreadableDocumentException("This file has " + extracted.text().length()
                    + " characters of text; a resume should be well under " + MAX_TEXT_CHARS + ". Is it the right file?");
        }
        PersonalDataRedactor.Redacted redacted = redactor.redact(extracted.text());

        LlmResult result = claude.callJson(PROMPT, List.of("<resume_text>\n" + redacted.text() + "\n</resume_text>"));
        ImportMapper.Mapped mapped = mapper.map(result.json());
        ObjectNode document = mapped.document();
        int evidenced = linker.link(document);

        List<String> notes = new ArrayList<>();
        boolean replacing = master.current().isPresent();
        if (replacing) {
            notes.add("This replaces your whole master resume. Existing IDs are retired, and new entries get new IDs.");
        }
        notes.addAll(redacted.notes());
        result.json().path("warnings").values().forEach(w -> notes.add("From the model: " + w.stringValue("")));
        notes.addAll(mapped.warnings());
        notes.addAll(verifier.verify(document, redacted.text()));

        ArrayNode patch = JsonNodeFactory.instance.arrayNode();
        patch.addObject().put("op", replacing ? "replace" : "add").put("path", "").set("value", document);

        String summary = "Import from " + displayName(filename) + ": " + counts(document, evidenced);
        log.info("Imported {} ({} chars, {} pages): {}", displayName(filename), extracted.text().length(),
                extracted.pages(), summary);
        // Not "must be valid": an import with a fixable problem should still reach the review page.
        return updates.create(new Proposal(PendingUpdate.Source.IMPORT, redacted.text(), patch, summary, notes), false);
    }

    private static String counts(JsonNode doc, int evidenced) {
        int highlights = 0;
        for (String section : List.of("work", "projects", "education", "volunteer")) {
            for (JsonNode item : doc.path(section).values()) {
                highlights += item.path("highlights").size();
            }
        }
        return doc.path("work").size() + " jobs, " + doc.path("projects").size() + " projects, "
                + doc.path("education").size() + " education, " + highlights + " highlights, "
                + doc.path("skills").size() + " skills (" + evidenced + " with evidence)";
    }

    private static String displayName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "uploaded file";
        }
        String name = filename.replaceAll("[\\\\/]", "_").strip();
        return name.length() <= 60 ? name : name.substring(0, 59) + "…";
    }
}
