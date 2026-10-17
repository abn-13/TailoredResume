package com.bu.tailorsg.master;

import com.bu.tailorsg.json.JsonText;
import com.bu.tailorsg.json.JsonText.JsonSyntaxException;
import com.bu.tailorsg.llm.JsonSchemaService;
import com.bu.tailorsg.llm.JsonSchemaService.SchemaViolation;
import com.bu.tailorsg.master.MasterDiffer.Change;
import com.bu.tailorsg.master.MasterResumeVersionRepository.VersionSummary;
import com.bu.tailorsg.master.ValidationError.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads, validates, versions and diffs the master resume.
 *
 * <p>Every write goes through one pipeline, and nothing is saved unless every stage passes:
 * <ol>
 *   <li>parse (strict: duplicate keys rejected, line/column reported)</li>
 *   <li>privacy, P6 ({@link ForbiddenContentValidator})</li>
 *   <li>server-owned fields: {@code meta.version}, {@code meta.updatedAt}, default locale/spelling</li>
 *   <li>IDs assigned and checked ({@link IdAssigner})</li>
 *   <li>JSON Schema</li>
 *   <li>cross-field integrity ({@link MasterIntegrityValidator})</li>
 * </ol>
 * All errors from all stages are reported together, so one round of fixes is usually enough.
 */
@Service
public class MasterResumeService {

    private static final Logger log = LoggerFactory.getLogger(MasterResumeService.class);

    public static final String SCHEMA = "schema/master-resume.schema.json";
    public static final String SAMPLE = "classpath:samples/master-sample.json";
    static final int MAX_NOTE_LENGTH = 200;

    private final MasterResumeVersionRepository versions;
    private final IdCounterRepository counters;
    private final JsonSchemaService schemas;
    private final ForbiddenContentValidator privacy;
    private final IdAssigner idAssigner;
    private final MasterIntegrityValidator integrity;
    private final MasterDiffer differ;
    private final JsonText jsonText;
    private final JsonMapper jsonMapper;
    private final ResourceLoader resourceLoader;
    private final Clock clock;

    public MasterResumeService(MasterResumeVersionRepository versions, IdCounterRepository counters,
                               JsonSchemaService schemas, ForbiddenContentValidator privacy, IdAssigner idAssigner,
                               MasterIntegrityValidator integrity, MasterDiffer differ, JsonText jsonText,
                               JsonMapper jsonMapper, ResourceLoader resourceLoader, Clock clock) {
        this.versions = versions;
        this.counters = counters;
        this.schemas = schemas;
        this.privacy = privacy;
        this.idAssigner = idAssigner;
        this.integrity = integrity;
        this.differ = differ;
        this.jsonText = jsonText;
        this.jsonMapper = jsonMapper;
        this.resourceLoader = resourceLoader;
        this.clock = clock;
    }

    /** A stored version with its document parsed. */
    public record StoredVersion(int versionNo, Instant createdAt, String changeNote, JsonNode json) {}

    /**
     * Outcome of a successful validate or save.
     *
     * @param versionNo   the version created (or that would be created, for validate); for an
     *                    unchanged document, the current version
     * @param unchanged   true when the content equals the current version, so no new version was made
     * @param document    the document exactly as stored, including server-assigned IDs
     * @param assignedIds IDs the server issued in this operation
     * @param notes       informational findings, e.g. unevidenced skills
     */
    public record SaveResult(int versionNo, boolean unchanged, JsonNode document,
                             List<String> assignedIds, List<String> notes) {}

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Optional<StoredVersion> current() {
        return versions.findTopByOrderByVersionNoDesc().map(this::toStored);
    }

    @Transactional(readOnly = true)
    public Optional<StoredVersion> version(int versionNo) {
        return versions.findByVersionNo(versionNo).map(this::toStored);
    }

    /** Typed view of the current version, for code that reads the resume (checks, renderer). */
    @Transactional(readOnly = true)
    public Optional<MasterResume> currentResume() {
        return current().map(v -> toRecord(v.json()));
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> history() {
        return versions.findAllByOrderByVersionNoDesc();
    }

    @Transactional(readOnly = true)
    public List<Change> diff(int fromVersion, int toVersion) {
        JsonNode from = version(fromVersion).orElseThrow(() -> VersionNotFoundException.version(fromVersion)).json();
        JsonNode to = version(toVersion).orElseThrow(() -> VersionNotFoundException.version(toVersion)).json();
        return differ.diff(from, to);
    }

    public String pretty(JsonNode node) {
        return jsonText.pretty(node);
    }

    public String sampleJson() {
        Resource resource = resourceLoader.getResource(SAMPLE);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + SAMPLE, e);
        }
    }

    // ---------------------------------------------------------------- writes

    /**
     * Runs the whole pipeline without saving: what would happen if this were saved now.
     *
     * <p>Why {@code noRollbackFor}: callers ask this for a verdict, and "invalid" is a normal answer. Without
     * it, a {@link MasterValidationException} leaving this method marks the caller's transaction (which this
     * method joins) rollback-only, even when the caller catches it, and the caller's commit then fails with
     * {@code UnexpectedRollbackException}. Safe because {@code prepare} writes nothing before it throws.
     */
    @Transactional(readOnly = true, noRollbackFor = MasterValidationException.class)
    public SaveResult validate(String rawJson) {
        return prepare(parse(rawJson), Set.of()).toResult();
    }

    /** Validates and stores a new version, unless the content is unchanged. */
    @Transactional
    public SaveResult save(String rawJson, String changeNote) {
        return persist(prepare(parse(rawJson), Set.of()), changeNote);
    }

    /**
     * {@link #validate(String)} for a document that is already parsed (e.g. the result of a JSON Patch).
     * Update previews call this inside their own transaction and catch the exception (see {@link #validate}).
     */
    @Transactional(readOnly = true, noRollbackFor = MasterValidationException.class)
    public SaveResult validateDocument(JsonNode document) {
        return prepare(document, Set.of()).toResult();
    }

    /** {@link #save(String, String)} for a document that is already parsed. */
    @Transactional
    public SaveResult saveDocument(JsonNode document, String changeNote) {
        return persist(prepare(document, Set.of()), changeNote);
    }

    /** The current document, or an empty object when there is no master resume yet (the base for patches). */
    @Transactional(readOnly = true)
    public JsonNode currentDocumentOrEmpty() {
        return current().map(StoredVersion::json).orElseGet(JsonNodeFactory.instance::objectNode);
    }

    /**
     * Re-saves an old version as the newest one. Its IDs are allowed even if retired since:
     * they are the same items they always identified.
     */
    @Transactional
    public SaveResult restore(int versionNo) {
        JsonNode old = version(versionNo).orElseThrow(() -> VersionNotFoundException.version(versionNo)).json();
        return persist(prepare(old, IdAssigner.collectIds(old)), "Restored from version " + versionNo);
    }

    /** Loads the bundled fake sample. Only allowed on an empty database, so it can never overwrite real data. */
    @Transactional
    public SaveResult loadSample() {
        if (versions.count() > 0) {
            throw new IllegalStateException("A master resume already exists; the sample can only be loaded into an empty database.");
        }
        return persist(prepare(parse(sampleJson()), Set.of()), "Loaded sample data (fake person)");
    }

    // ---------------------------------------------------------------- pipeline

    /** Everything a save needs, computed before anything is written. */
    private record Prepared(JsonNode document, int versionNo, boolean unchanged, List<String> assignedIds,
                            Map<String, Long> counterUpdates, List<String> notes) {
        SaveResult toResult() {
            return new SaveResult(versionNo, unchanged, document, assignedIds, notes);
        }
    }

    private JsonNode parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new MasterValidationException(List.of(
                    new ValidationError("", "Nothing to save: the master resume JSON is empty.", Kind.SYNTAX)));
        }
        try {
            return jsonText.parseStrict(rawJson);
        } catch (JsonSyntaxException e) {
            throw new MasterValidationException(List.of(new ValidationError("", e.describe(), Kind.SYNTAX)));
        }
    }

    private Prepared prepare(JsonNode input, Set<String> allowedRetiredIds) {
        if (!(input instanceof ObjectNode inputObject)) {
            throw new MasterValidationException(List.of(
                    new ValidationError("", "The master resume must be a JSON object: { \"meta\": ..., \"basics\": ... }.", Kind.SYNTAX)));
        }
        List<ValidationError> errors = new ArrayList<>(privacy.check(inputObject));

        Optional<StoredVersion> current = current();
        int nextVersionNo = current.map(v -> v.versionNo() + 1).orElse(1);
        ObjectNode candidate = stampMeta(inputObject, nextVersionNo);

        Map<String, Long> lastIssued = counters.findAll().stream()
                .collect(Collectors.toMap(IdCounter::getPrefix, IdCounter::getLastValue));
        Set<String> currentIds = current.map(v -> IdAssigner.collectIds(v.json())).orElse(Set.of());
        IdAssigner.Result ids = idAssigner.assign(candidate, currentIds, lastIssued, allowedRetiredIds);
        errors.addAll(ids.errors());
        JsonNode document = ids.document();

        addSchemaErrors(document, errors);

        List<String> notes = List.of();
        if (errors.isEmpty()) { // the typed view needs a schema-valid document
            MasterIntegrityValidator.Outcome outcome = integrity.check(toRecord(document));
            errors.addAll(outcome.errors());
            notes = outcome.notes();
        }
        if (!errors.isEmpty()) {
            throw new MasterValidationException(errors);
        }

        boolean unchanged = current.map(v -> sameContent(v.json(), document)).orElse(false);
        int versionNo = unchanged ? current.get().versionNo() : nextVersionNo;
        return new Prepared(document, versionNo, unchanged, ids.assignedIds(), ids.counterUpdates(), notes);
    }

    /**
     * Adds schema errors, skipping ones already explained by an earlier stage at the same place
     * (a forbidden key also fails "additionalProperties"; a malformed id also fails its "pattern").
     */
    private void addSchemaErrors(JsonNode document, List<ValidationError> errors) {
        Set<String> explained = new HashSet<>();
        errors.forEach(e -> explained.add(e.path()));
        for (SchemaViolation v : schemas.violations(SCHEMA, document)) {
            String propertyPath = v.property() == null ? null : v.path() + "/" + ForbiddenContentValidator.escape(v.property());
            if (explained.contains(v.path()) || (propertyPath != null && explained.contains(propertyPath))) {
                continue;
            }
            errors.add(new ValidationError(v.path(), v.message(), Kind.SCHEMA));
        }
    }

    /**
     * Returns a copy with the server-owned meta fields set. meta goes first in a new document,
     * and the user's locale/spelling are kept if present.
     */
    private ObjectNode stampMeta(ObjectNode input, int versionNo) {
        JsonNode existing = input.get("meta");
        if (existing != null && !existing.isObject()) {
            return input.deepCopy(); // wrong type: the schema reports it
        }
        ObjectNode meta = existing == null ? JsonNodeFactory.instance.objectNode() : ((ObjectNode) existing).deepCopy();
        meta.put("version", versionNo);
        meta.put("updatedAt", clock.instant().truncatedTo(ChronoUnit.SECONDS).toString());
        if (!meta.has("locale")) {
            meta.put("locale", "en-SG");
        }
        if (!meta.has("spelling")) {
            meta.put("spelling", "british");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("meta", meta);
        input.properties().forEach(e -> {
            if (!e.getKey().equals("meta")) {
                result.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        return result;
    }

    /** Equal apart from the fields the server rewrites on every save. */
    private static boolean sameContent(JsonNode a, JsonNode b) {
        return withoutVolatileMeta(a).equals(withoutVolatileMeta(b));
    }

    private static JsonNode withoutVolatileMeta(JsonNode doc) {
        JsonNode copy = doc.deepCopy();
        if (copy.get("meta") instanceof ObjectNode meta) {
            meta.remove("version");
            meta.remove("updatedAt");
        }
        return copy;
    }

    private SaveResult persist(Prepared prepared, String changeNote) {
        if (prepared.unchanged()) {
            log.info("Master resume unchanged; still version {}", prepared.versionNo());
            return prepared.toResult();
        }
        versions.save(new MasterResumeVersion(prepared.versionNo(), jsonMapper.writeValueAsString(prepared.document()),
                trimNote(changeNote), clock.instant()));
        prepared.counterUpdates().forEach((prefix, value) -> counters.findById(prefix).ifPresentOrElse(
                counter -> counter.raiseTo(value),
                () -> counters.save(new IdCounter(prefix, value))));
        log.info("Saved master resume version {} ({} new ids)", prepared.versionNo(), prepared.assignedIds().size());
        return prepared.toResult();
    }

    private static String trimNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.strip();
        return trimmed.length() <= MAX_NOTE_LENGTH ? trimmed : trimmed.substring(0, MAX_NOTE_LENGTH);
    }

    private StoredVersion toStored(MasterResumeVersion entity) {
        return new StoredVersion(entity.getVersionNo(), entity.getCreatedAt(), entity.getChangeNote(),
                jsonMapper.readTree(entity.getJson()));
    }

    MasterResume toRecord(JsonNode document) {
        return jsonMapper.treeToValue(document, MasterResume.class);
    }

    /**
     * Typed view of any document for display (e.g. an update's preview). Empty when the document does not
     * fit the records, which can happen for an invalid preview; callers then show raw JSON instead.
     */
    public Optional<MasterResume> asResume(JsonNode document) {
        if (document == null || !document.isObject()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toRecord(document));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
