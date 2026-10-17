package com.bu.tailorsg.updates;

import com.bu.tailorsg.json.JsonPatch;
import com.bu.tailorsg.json.JsonPatchException;
import com.bu.tailorsg.json.JsonText;
import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import com.bu.tailorsg.master.MasterDiffer;
import com.bu.tailorsg.master.MasterDiffer.Change;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.SaveResult;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.master.MasterValidationException;
import com.bu.tailorsg.master.ValidationError;
import com.bu.tailorsg.updates.PendingUpdate.Source;
import com.bu.tailorsg.updates.PendingUpdate.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Proposes, previews, applies and rejects changes to the master resume.
 *
 * <p>A preview is always computed against the <em>current</em> master, so the review page shows what
 * applying would really do even if the master changed after the update was proposed. Applying runs the
 * full master save pipeline, so an update can never bypass validation.
 */
@Service
public class PendingUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PendingUpdateService.class);
    private static final int MAX_CHANGE_NOTE = 200;

    private final PendingUpdateRepository repository;
    private final MasterResumeService master;
    private final MasterDiffer differ;
    private final JsonText jsonText;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public PendingUpdateService(PendingUpdateRepository repository, MasterResumeService master, MasterDiffer differ,
                                JsonText jsonText, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.master = master;
        this.differ = differ;
        this.jsonText = jsonText;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** Everything needed to create an update. */
    public record Proposal(Source source, String rawInput, JsonNode patch, String summary, List<String> notes) {}

    /**
     * What applying a patch to the current master would do.
     *
     * @param baseVersionNo current master version the preview was computed against (null if none)
     * @param patchError    why the patch cannot be applied at all (e.g. a {@code test} guard failed), or null
     * @param errors        why the patched master would be rejected (empty if it would be accepted)
     * @param changes       ID-aware differences between the current master and the result
     * @param assignedIds   IDs the server would issue
     * @param newNotes      validation notes the change would introduce (e.g. a new unevidenced skill)
     * @param result        the resulting document with would-be IDs, or the raw patched document if it is
     *                      invalid; null when the patch could not be applied
     */
    public record Preview(Integer baseVersionNo, String patchError, List<ValidationError> errors, List<Change> changes,
                          List<String> assignedIds, List<String> newNotes, JsonNode result) {

        public boolean canApply() {
            return patchError == null && errors.isEmpty() && !changes.isEmpty();
        }

        /** Why Apply is disabled, for the UI. Null when it can be applied. */
        public String blockedReason() {
            if (patchError != null) {
                return "This update no longer applies to the current master resume.";
            }
            if (!errors.isEmpty()) {
                return "The result would not pass validation. Edit the patch to fix the problems below.";
            }
            if (changes.isEmpty()) {
                return "This update changes nothing.";
            }
            return null;
        }
    }

    /** Thrown by {@link #create} when a proposal must be valid (forms) but is not. */
    public static class InvalidProposalException extends RuntimeException {
        private final transient Preview preview;

        public InvalidProposalException(Preview preview) {
            super(preview.patchError() != null ? preview.patchError()
                    : preview.errors().size() + " validation problem(s) in the resulting master resume");
            this.preview = preview;
        }

        public Preview preview() {
            return preview;
        }
    }

    // ---------------------------------------------------------------- create / read

    /**
     * Stores a proposal for review.
     *
     * @param mustBeValid true for forms: the user can fix the form, so reject invalid proposals up front.
     *                    False for imports: keep the update so the user can repair it in the patch editor.
     */
    @Transactional
    public PendingUpdate create(Proposal proposal, boolean mustBeValid) {
        Preview preview = preview(proposal.patch());
        if (mustBeValid && !preview.canApply()) {
            throw new InvalidProposalException(preview);
        }
        PendingUpdate update = repository.save(new PendingUpdate(proposal.source(), proposal.rawInput(),
                jsonMapper.writeValueAsString(proposal.patch()), truncate(proposal.summary(), 300),
                proposal.notes().isEmpty() ? null : jsonMapper.writeValueAsString(proposal.notes()),
                preview.baseVersionNo(), clock.instant()));
        log.info("Proposed update #{} ({}): {}", update.getId(), proposal.source(), proposal.summary());
        return update;
    }

    @Transactional(readOnly = true)
    public PendingUpdate get(long id) {
        return repository.findById(id).orElseThrow(() -> new UpdateNotFoundException(id));
    }

    /** All updates newest first, or only those with the given status. */
    @Transactional(readOnly = true)
    public List<PendingUpdate> list(Status status) {
        return status == null ? repository.findAllByOrderByCreatedAtDescIdDesc()
                : repository.findByStatusOrderByCreatedAtDescIdDesc(status);
    }

    @Transactional(readOnly = true)
    public long countProposed() {
        return repository.countByStatus(Status.PROPOSED);
    }

    public JsonNode patchOf(PendingUpdate update) {
        return jsonMapper.readTree(update.getProposedPatch());
    }

    public List<String> notesOf(PendingUpdate update) {
        return update.getNotes() == null ? List.of()
                : jsonMapper.readValue(update.getNotes(), new TypeReference<List<String>>() {});
    }

    // ---------------------------------------------------------------- preview

    @Transactional(readOnly = true)
    public Preview preview(PendingUpdate update) {
        return preview(patchOf(update));
    }

    @Transactional(readOnly = true)
    public Preview preview(JsonNode patch) {
        Integer baseVersion = master.current().map(StoredVersion::versionNo).orElse(null);
        JsonNode base = master.currentDocumentOrEmpty();
        JsonNode patched;
        try {
            patched = JsonPatch.apply(patch, base);
        } catch (JsonPatchException e) {
            return new Preview(baseVersion, e.getMessage(), List.of(), List.of(), List.of(), List.of(), null);
        }
        try {
            SaveResult result = master.validateDocument(patched);
            List<String> newNotes = new ArrayList<>(result.notes());
            newNotes.removeAll(notesOf(base));
            return new Preview(baseVersion, null, List.of(), differ.diff(base, result.document()),
                    result.assignedIds(), newNotes, result.document());
        } catch (MasterValidationException e) {
            return new Preview(baseVersion, null, e.errors(), differ.diff(base, patched), List.of(), List.of(), patched);
        }
    }

    /** Validation notes of the current master, so a preview only shows notes the change introduces. */
    private List<String> notesOf(JsonNode base) {
        if (base.isEmpty()) {
            return List.of();
        }
        try {
            return master.validateDocument(base).notes();
        } catch (MasterValidationException e) {
            return List.of(); // a stored master is always valid; this is just defensive
        }
    }

    // ---------------------------------------------------------------- edit / apply / reject

    /**
     * Replaces the proposed patch with an edited one (spec: PATCH /api/updates/{id}).
     *
     * @throws JsonSyntaxException if the text is not valid JSON
     * @throws JsonPatchException  if it is JSON but not a patch (not an array)
     */
    @Transactional
    public Preview replacePatch(long id, String patchText) {
        JsonNode patch = parsePatch(patchText);
        PendingUpdate update = get(id);
        update.replacePatch(jsonMapper.writeValueAsString(patch));
        return preview(patch);
    }

    /** Parses edited patch text, checking only that it is an array of objects. */
    public JsonNode parsePatch(String patchText) {
        JsonNode patch = jsonText.parseStrict(patchText == null ? "" : patchText);
        if (!patch.isArray() || patch.values().stream().anyMatch(op -> !op.isObject())) {
            throw new JsonPatchException("A JSON Patch must be an array of operation objects, e.g. [{\"op\": \"add\", ...}].");
        }
        return patch;
    }

    /**
     * Applies the update to the current master, creating a new version.
     *
     * @throws IllegalStateException      if it is not PROPOSED, or would change nothing
     * @throws JsonPatchException         if the patch no longer applies (e.g. a guard failed)
     * @throws MasterValidationException  if the result is not a valid master resume
     */
    @Transactional
    public SaveResult apply(long id) {
        PendingUpdate update = get(id);
        if (update.getStatus() != Status.PROPOSED) {
            throw new IllegalStateException("Update #" + id + " is already " + update.getStatus().name().toLowerCase() + ".");
        }
        JsonNode patched = JsonPatch.apply(patchOf(update), master.currentDocumentOrEmpty());
        SaveResult saved = master.saveDocument(patched, truncate("Update #" + id + ": " + update.getSummary(), MAX_CHANGE_NOTE));
        if (saved.unchanged()) {
            throw new IllegalStateException("Update #" + id + " changes nothing in the current master resume.");
        }
        update.markApplied(saved.versionNo(), clock.instant());
        log.info("Applied update #{} as master version {}", id, saved.versionNo());
        return saved;
    }

    @Transactional
    public void reject(long id) {
        get(id).markRejected(clock.instant());
        log.info("Rejected update #{}", id);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
