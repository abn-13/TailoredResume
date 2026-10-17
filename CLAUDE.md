# TailorSG: working notes for Claude

Local, single-user Spring Boot web app that tailors one **master resume** (and cover letters) to
Singapore job descriptions, then judges the output with **deterministic, rule-based checks**,
not the LLM's own opinion.

- **Full spec, word for word:** `docs/SPEC.md`. Read the relevant section before starting a phase.
  This file records decisions that amend the spec; where they conflict, this file wins.
- **Code style:** the author is learning Spring Boot, so write idiomatic, well-commented Java and explain
  non-obvious decisions briefly in comments.
- **Users:** anyone job hunting in Singapore, each running their own copy on localhost with their own API key.
  Nothing may assume a particular person (name, pronouns, background, nationality).

## How we work

1. Build one phase at a time (see "Phases"). Never start the next phase without the user's go-ahead.
   (On 2026-10-17 the user asked for Phases 3-9 in one go; that was a one-off. For new work, go back to asking.)
2. Before coding a phase: read its section of `docs/SPEC.md`, verify any new library versions on
   Maven Central, and ask only genuinely blocking questions.
3. End of each phase, in this order:
   - `./mvnw test` is green (paste the summary line to the user)
   - README "Manual test script" has a section for the phase
   - this file: update "Current phase", tick the phase, add decisions and gotchas
   - dashboard "Build progress" (`templates/dashboard.html`): classes `done` / `current` / `next`
   - no secrets: `grep -rnE "sk-ant-[A-Za-z0-9_-]{20,}" --exclude-dir=target --exclude-dir=data --exclude-dir=.git --exclude=.env .` returns nothing
   - stop, show how to run and test, and wait

## Core principles (from the spec, non-negotiable)

- **P1.** The master resume is the single source of truth. The LLM may SELECT, ORDER and REWORD content
  from it. It may NEVER introduce new facts, numbers, employers, titles, dates, tools, certifications or outcomes.
- **P2.** Structural facts are rendered straight from master data, never from LLM text: name, contact,
  employer names, job titles, locations, dates, degrees, institutions, grades.
- **P3.** Every generated bullet carries `sourceIds` pointing to master-resume item IDs. Code verifies
  provenance deterministically (spec 6E).
- **P4.** Quality is judged by the deterministic CheckEngine, not by the LLM. Any LLM "judge" is labelled
  ADVISORY in the UI and never changes the pass/fail status.
- **P5.** Export is blocked while any ERROR-level check fails, unless the user overrides with a typed
  reason (stored with the application).
- **P6.** Singapore fair-employment hygiene: never output a photo, NRIC/FIN, date of birth, age, gender,
  race, religion, marital status or family details, full residential address, referee contact details,
  or expected salary.

## Stack (verified 2026-10-15)

| Piece | Choice | Notes |
|---|---|---|
| Language / build | Java 21 (Temurin), Maven via `./mvnw` (3.9.14) | |
| Framework | **Spring Boot 4.1.1** | User chose 4.1.1 over the spec's 3.x (3.5 OSS support ended 2026-06-30) |
| Web | Spring MVC + Thymeleaf + htmx 2.0.11 (WebJar) | No Layout Dialect: plain `th:fragment`s in `templates/layout.html` |
| JSON | **Jackson 3** (`tools.jackson.*`) in app code | The Anthropic SDK uses Jackson 2 (`com.fasterxml.*`) internally; never mix them in our code |
| DB | H2 file `./data/tailorsg`, Spring Data JPA, `ddl-auto: update` | Tests use in-memory H2 (`test` profile, set by surefire) |
| LLM | `com.anthropic:anthropic-java` 2.72.0 | Official SDK, non-streaming Messages API |
| Schema validation | `com.networknt:json-schema-validator` 3.0.8 | Draft 2020-12; 3.x is Jackson 3 based |
| PDF (Phase 6) | `io.github.openhtmltopdf:openhtmltopdf-pdfbox` 1.1.97 | **Amends spec:** `com.openhtmltopdf` was abandoned in 2021 (PDFBox 2); this is the maintained fork on PDFBox 3. Font: Liberation Sans 2.1.5 (SIL OFL) in `resources/fonts` |
| HTML | Jsoup 1.23.2 | Job pages (Phase 4); building XHTML for the PDF renderer (`W3CDom`) |
| JSON Patch | own `json/JsonPatch` + `JsonPointer` (RFC 6902 / 6901) | zjsonpatch, java-json-tools and fabric8's fork are all Jackson 2. Tested against RFC 6902 Appendix A |
| PDF/DOCX text | PDFBox 3.0.8, POI 5.5.1 (`poi-ooxml`) | Logging bridges cleanly: PDFBox's commons-logging 1.3 finds SLF4J; POI's log4j-api goes through log4j-to-slf4j |

## Models and pricing (verified 2026-10-15 on platform.claude.com)

- `tailorsg.models.writer = claude-sonnet-5-5` ($2 in / $10 out per MTok, cache read $0.10, 5m cache write $2.50)
- `tailorsg.models.extractor = claude-haiku-5-5` ($0.10 / $0.50; prompts > 100k tokens: $0.50 / $2.50)
- Both IDs are current, unchanged from the spec. Retirement not before Sep/Oct 2027.
- Both models use adaptive thinking by default. Haiku 5.5 default effort is `medium`, Sonnet 5.5 is `high`;
  we set effort explicitly per role in `tailorsg.llm.*`.

## LLM conventions

- **One entry point:** `ClaudeClient.callJson(promptId, userBlocks)`. Nothing else talks to the SDK
  except `AnthropicTransport` (behind the `LlmTransport` seam, so tests use a scripted fake).
- **Prompts:** `src/main/resources/prompts/{id}.md` with front matter (`version`, `role`, `schema`).
  Bump `version` on every text change. Spec prompt texts (5.1-5.4) go in verbatim.
- **Schemas:** LLM output schemas in `src/main/resources/schema/llm/`; domain schemas (e.g.
  `master-resume.schema.json`) in `src/main/resources/schema/`.
- **Structured output** via `output_config.format` (JSON Schema). The API answers **400** to unsupported
  keywords, so `ApiSchemaSanitizer` is an **allowlist** (type, properties, required, additionalProperties,
  items, enum, const, anyOf, allOf, $ref, $defs, definitions, description, format, default, minItems 0/1);
  everything else (pattern, minLength, title, errorMessage...) is stripped, `oneOf` becomes `anyOf`, and
  `additionalProperties: false` is forced. The **full** schema is enforced locally by `JsonSchemaService`.
- **Per-request limits (400 if exceeded):** at most **24 optional properties** and **16 union-typed
  properties** (`anyOf` or `"type": [..]`) across the schema; no recursion, no external `$ref`.
  `LlmSchemaLimitsTest` checks every prompt's schema; add new prompts to it. Pattern for big outputs:
  make every property required and use `""` / `[]` for "absent" (see `master-import.schema.json`), then
  clean up deterministically in code (see `ImportMapper`).
- **Retry policy:** invalid JSON or schema errors lead to exactly one retry with the errors appended as an
  extra user block, then `LlmValidationException`. `refusal` / `max_tokens` /
  `model_context_window_exceeded` fail immediately (no retry).
- **Do not use:** forced `tool_choice` (`any`/`tool` returns 400 on Sonnet 5.5), assistant prefill (400),
  `thinking.budget_tokens` (400), `thinking: disabled` (400 on Sonnet 5.5).
- **Refusal fallback:** writer calls opt in (`tailorsg.llm.writer.refusal-fallback: true`: body
  `fallbacks: "default"` + beta header `server-side-fallback-2026-07-01`). Haiku 5.5 has none.
- **Prompt caching:** MASTER is always the first user block, marked cacheable (`TextBlock(text, true)`), in
  tailor, fit-advice, cover-letter and master-update. Minimum cacheable prefix is 286 tokens.
- **`LlmRequest` carries `promptId`** (logging, and `DemoLlmTransport`'s dispatch).
- **Demo mode** (`tailorsg.llm.demo-mode=true`): `llm/demo/DemoLlmTransport` is `@Primary` and answers every
  prompt locally from the request (verbatim master highlights, dictionary skills); model name `demo-local`,
  priced at 0. Banner on every page (`LayoutAdvice.demoMode`). Import is not supported in demo mode.
  `SystemStatus.apiKeyConfigured` reports the real key (from `AnthropicTransport`), not the demo.
- **Every call is persisted** (`LlmCall`: tokens and cost only, never text) for the footer's all-time total
  and Settings' "Recent model calls".
- **Never send or store P6 data from uploads:** `PersonalDataRedactor` removes matching lines before text
  reaches the LLM or the DB. Reuse it for anything user-uploaded (JD screenshots excepted: those are
  about the employer, not the user).
- **Cost:** `LlmUsageMeter` logs tokens and cost for every call, keeps a session total and stores each call (all-time total) shown
  in the footer. Never log prompt/response bodies (they contain the resume).

## Code conventions

- Packages under `com.bu.tailorsg`, by feature: `config`, `llm`, `web` (shared pages, health), `json`
  (hand-edited JSON parse/print), `master` (model, validation, versions, controllers), `checks` (SG patterns
  now, CheckEngine in Phase 3). Add `updates`, `jobs`, `applications`, `render`, `export` as phases arrive.
  Each feature keeps its own API and page controllers.
- Records for DTOs and config; constructor injection only; no field `@Autowired`.
- Typed config in `TailorSgProperties` (`@ConfigurationProperties("tailorsg")`, validated at startup).
  All check thresholds go under `tailorsg.checks.*` (stubbed, commented out, in application.yml).
- Each check rule: Javadoc cites its basis (e.g. "NUS CFG@FASS guide: min font 11", "TAFEP: remove photo
  fields"), plus a unit test with a passing and a failing fixture in `src/test/resources/fixtures/`.
- Tests: JUnit 5 + AssertJ. Web tests use `@SpringBootTest` + `@AutoConfigureMockMvc` + `MockMvcTester`.
  Tests that hit the real API are `@Tag("live")` + `@EnabledIfEnvironmentVariable(ANTHROPIC_API_KEY)`.
  Mock `LlmPing`/`ClaudeClient` with `@MockitoBean` in web tests so they never spend money.
- htmx endpoints return Thymeleaf fragments (`templates/fragments/`) with HTTP 200 even for validation
  errors (htmx does not swap 4xx bodies by default); use `hx-swap-oob` for secondary updates (footer cost,
  editor textarea, version line). Non-htmx writes (load sample, restore) use Post/Redirect/Get with flash
  messages (`layout :: flash`).
- JSON APIs report errors as RFC 9457 problem details (`ProblemDetail`); validation failures are 422 with an
  `errors` array of `{path (JSON Pointer), message, kind}`.
- Smoke-testing the running app: always use a scratch DB, never `./data` (it holds the user's real resume):
  `./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:mem:try --server.port=8090"`.

## Master resume decisions (Phase 1)

- **Storage:** each version is the validated JSON document in a CLOB (`master_resume_version`), never
  updated or deleted; highest `version_no` is current. `MasterResume` records are a typed *view* for code
  (round-trip tested so no field is lost). `meta.version` / `meta.updatedAt` are server-owned.
- **Save pipeline** (`MasterResumeService`): strict parse (duplicate keys rejected, line/column) → P6
  privacy (`ForbiddenContentValidator`: forbidden keys anywhere + SG-01/02/03/05 patterns on every string)
  → stamp meta → IDs (`IdAssigner`) → JSON Schema → integrity (`MasterIntegrityValidator`). All errors are
  collected and reported together; schema errors already explained by an earlier stage are de-duplicated.
  Saving identical content does not create a version.
- **IDs (amends spec example):** prefix + counter for every section, including skills (`skill-1`, not the
  spec's `skill-java`), because the spec's rule is "prefix + incrementing counter, never reused".
  Prefixes: `sum`, `work`, `proj`, `edu`, `vol`, `skill`, `cert`, `award`, `extra`; highlights are
  `<parentId>.h<n>`. Missing id or `NEW-n` placeholder → server assigns; `NEW-n` in `skills[].evidenceIds`
  is rewritten. Counters (`id_counter` table) only go up. An explicit ID not in the current version whose
  number was already issued is **retired** and rejected, except when restoring the version it came from.
  Highlight IDs cannot move to another parent.
- **Integrity:** every `evidenceIds` entry must resolve to an existing highlight (so deleting a cited
  highlight requires removing the citation in the same save); end date ≥ start date. Unevidenced skills
  are allowed and returned as notes.
- **Schema style:** `additionalProperties: false` everywhere (typos fail loudly); `pattern` instead of
  `format` (networknt treats `format` as annotation only); readable messages via the networknt
  `errorMessage` keyword, which `ApiSchemaSanitizer` strips before anything is sent to Claude.
- **SG patterns** live in `checks/SgPatterns` and are shared with Phase 3. Word boundaries were added to
  the spec's SG-02 regex (`\bDOB\b`, `\bage`) to avoid false positives on "Adobe", "storage 20 GB".
- **Sample:** `src/main/resources/samples/master-sample.json` (fake person "Jordan Tan"), used by tests and
  by "Load sample", which only works on an empty database.
- **Diff:** `MasterDiffer` matches arrays by `id` (paths like `/work/work-1/highlights/work-1.h2/text`),
  reports scalar lists as added/removed values, ignores server-stamped meta. Phase 2 will reuse it for the
  PendingUpdate diff view.

## Updates and import decisions (Phase 2)

- **Every change to the master goes through a `PendingUpdate`** (except the raw JSON editor and restore):
  proposal (RFC 6902 patch with `NEW-n` placeholders) → review page → apply. Applying runs the normal
  master save pipeline, so updates cannot bypass validation.
- **Previews are always computed against the current master**, never a stored snapshot. Server-built
  patches start with `test` guards on the IDs they touch, so a stale update fails with a clear message
  instead of editing the wrong entry. `Preview.newNotes` shows only notes the change introduces.
- **Forms** (`EntryForm` sealed hierarchy, JavaBeans for Spring binding + Jackson `"type"` polymorphism):
  field rules via Bean Validation; master-dependent rules (duplicate skill, unknown parent/evidence) in
  `FormPatchBuilder` as `FieldProblem(field, message)`, shown next to the input. Form proposals must be
  valid (`create(..., mustBeValid=true)`); imports may be invalid and are repaired in the patch editor.
  Dated entries are inserted newest-first. Highlight form: selected skills get the new highlight as
  evidence; "new skills" are created with it as evidence.
- **Import** (`ResumeImportService`): extract text (`documents/TextExtractor`, type from magic bytes) →
  redact P6 lines → extractor model (`prompts/master-import.md` v1) → `ImportMapper` (empties dropped,
  YYYY→YYYY-01, SG phone → +65, bare domains → https://, each with a warning) → `EvidenceLinker` (skill
  evidence by highlight skills list or whole-word mention; names under 3 chars only via the list) →
  `ImportVerifier` (every name, title, bullet and number must appear in the uploaded text, else a note).
  One `add ""` (empty master) or `replace ""` (existing master, with a warning) patch.
- **Readable view** (`fragments/resume-view.html` + `ResumeView` bean): used by `/master/view` and the
  update review page.
- **Deferred:** the "live bullet-lint preview" on the highlight form (spec 3) needs the LINT rules, so it
  arrives in Phase 3. Free-text updates are Phase 8 (`Source.FREE_TEXT` already exists; store clarifying
  questions in `notes` or add a column then).

## Tailoring, fit, export, cover letter, free text (Phases 5-9)

- **Prompts** (spec text + "Field notes"): `tailor.md` (5.3), `cover-letter.md` (5.4), `master-update.md`
  (5.2), plus our own `fit-advice.md` (5b). **Amends spec:** v2 of `tailor.md` (rule 11) and `cover-letter.md`
  no longer assume one person: the letter is written for "the person in MASTER" with neutral pronouns, and the
  career-change guidance applies only when MASTER shows one. The original wording stays in docs/SPEC.md. All schemas are
  all-required; `master-update` sends patch values as `valueJson` strings (typed schema), parsed in code.
- **What the model never writes (P2):** employer, title, dates, education, contact line, letter date,
  recipient block, sign-off name, skill spellings (rendered from master by ID). `targetTitleLine` is stored
  but not rendered (the spec 4 template has no title line, and free text there could not be verified).
- **Rendering:** one `ResumeDocument` model feeds the HTML preview, PDF, DOCX and the check text. Tailored
  entries are reverse-chronological whatever order the model returns. Bullet anchors `b-<itemId>-<index>`.
  Checks always use the *current* master; the page warns when it changed since tailoring.
- **TRUTH (6E):** `TruthSources` gives each item's allowed text (cited highlights incl. skills and metric
  values; for letters also job companyFacts/title/company and USER_NOTES). TRUTH-01 also requires citations to
  belong to the entry the bullet sits under, and checks workId/projectId/skillId. TRUTH-02 normalises numbers
  and skips versioned skill names. TRUTH-03 uses longest-match terms ("RESTful APIs" is REST, not "API
  design"), ignores organisation names ("Example Fintech" is not the skill "Fintech") and, in letters,
  negated sentences ("I have not used Kafka"). TRUTH-04 needs names somewhere in the master (incl. the
  work-authorisation text).
- **Fit (5b):** `FitFacts` is deterministic (evidence class per term, years with overlaps merged, seniority,
  Employment Pass salary floor by start date from `tailorsg.fit.ep-thresholds`: S$5,600 from 2025, S$6,000
  from 1 Jan 2027, checked 2026-10-17). `FitService.verify` removes strengths without valid citations or that
  name a gap, non-gap "gaps", and projects targeting no gap; notes are shown. ADVISORY everywhere.
- **Export (6):** PDF = Jsoup XHTML + openhtmltopdf, A4, 2 cm, Liberation Sans 11pt, dates via two
  inline-blocks (76% / 24%). DOCX = POI, Arial, real numbering, right tab stop at the text width, no tables.
  Both are read back (PDFBox / Tika-style DOCX text) for ATS-01..08 and ATS-10 (`HeuristicResumeParser`,
  behind `ResumeParser`). DOCX page count comes from the PDF. P5: ERRORs block saving unless a reason of 10+
  characters is typed (stored on `ExportFile` and `Application`). Files are never overwritten (`_2` suffix).
  `Application.lastExportCheck` holds the last resume check for the Export tab.
- **Cover letter (7):** CL-01..06 in `checks/cl`; `DashFixer` is the auto-fix (numbers range to "to",
  otherwise commas), shown before/after. Letter checks = CL + TRUTH.
- **Free text (8):** proposals are ordinary `PendingUpdate`s (`FREE_TEXT`, may be invalid so they can be
  repaired). Notes: model questions ("Question: ..."), numbers not in the user's text (a `replace` may keep
  numbers of the value it replaces), new skills not mentioned, `/meta` ops dropped. P6 lines are redacted
  before the model sees the text. "Answer" makes a new proposal from text + answers and rejects the old one.
- **Polish (9):** tracker statuses on dashboard/list/detail; prompt versions per application
  (`Application.promptVersions`); thresholds editable on Settings as JSON, validated, stored in `app_setting`
  and read per check run (no cache, so rolled-back tests cannot leave stale values).

## Security

- Server binds to `127.0.0.1` only (`server.address`).
- `LocalOnlyRequestFilter`: rejects non-local Host headers (DNS rebinding) and cross-site writes
  (`Origin` / `Sec-Fetch-Site`). curl and tests (no such headers) pass. Keep it when adding endpoints.
- API key: env var `ANTHROPIC_API_KEY` only (read once in `AnthropicTransport`, never via `fromEnv()`,
  never logged). `.env` and `./data/` are git-ignored; `.env.example` is the template.

## Gotchas learned

- Spring Boot 4 renamed/moved things: `spring-boot-starter-webmvc` (not `-web`), H2 console is its own
  module `spring-boot-h2console`, MockMvc test support is `spring-boot-starter-webmvc-test`, and
  `@AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure`.
- Jackson 3: `asString()` can throw on non-string nodes. Use `isString()` + `stringValue()` or
  `stringValue(default)`. `JsonNode.properties()` / `values()` replace `fields()` / `elements()`.
- networknt 3.x API: `SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)`,
  `schema.validate(node)` returns `List<com.networknt.schema.Error>`.
- Anthropic Java SDK: use `.model(String)` (typed constants lag releases); structured output is
  `OutputConfig.builder().format(JsonOutputFormat.builder().schema(...))`; effort is
  `OutputConfig.Effort.of("medium")`.
- `webjars-locator-lite` is on the classpath, so templates use `/webjars/htmx.org/dist/htmx.min.js` (no version).
- **Thymeleaf fragment selectors also match element names.** `~{layout :: nav}` matched the fragment
  *and* every `<nav>` in layout.html, leaking the Master tabs into every header. Never name a fragment
  after an HTML element: layout fragments are `htmlHead`, `topbar`, `siteFooter` (regression-tested).
- Jackson serialises a record's `isX()` helper as property `x`. Mark such helpers `@JsonIgnore`
  (see `MasterResume.Experience.isCurrent`).
- networknt: `format` (email, uri, date) is NOT asserted by default; use `pattern`. Error paths are JSON
  Pointers (`/work/0/id`, root `""`). Custom messages: `errorMessage` keyword (enabled in `JsonSchemaService`).
- Jackson 3: `jsonMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)` for hand-edited JSON;
  `JacksonException.getLocation()` gives line/column.
- Tomcat adds `;jsessionid=` to the first redirect unless `server.servlet.session.tracking-modes: cookie`.
- `@Transactional` on a `@SpringBootTest` class rolls back each test, including MockMvc requests
  (same thread). DB-touching test classes must use it so tests don't leak data into each other.
- **Thymeleaf 3.1 restricted mode:** expressions in `th:href`/`th:src` (and `th:on*`) may not call beans
  (`@bean.method()`) or static classes. Compute the value in `th:with` first, then use the variable.
- `th:replace` / `th:insert` run before `th:if` on the same element, so a condition there is ignored:
  wrap the fragment call in `<th:block th:if="...">`.
- Thymeleaf calls into records/classes by reflection: anything a template touches must be `public`
  (a package-private record's accessors fail at render time).
- Java 21: no unnamed variables (`catch (Exception _)` is Java 22+), even though Sonar suggests them.
- htmx does not swap 4xx/5xx responses by default; page fragments for validation errors return 200.
- **openhtmltopdf paints `float: right` in a separate layer**, so PDFBox extracted every floated date at the
  top of the page (ATS-04 caught it). Use inline-blocks for right-aligned dates.
- **PDFBox `TextPosition.getFontSizeInPt()` truncates** (11pt reads as 10). Use `getYScale()`.
- **Non-breaking spaces extract as U+00A0**, which Java's `\s` does not match: use normal spaces in PDFs.
- **POI's `XWPFWordExtractor` drops list bullets**; `DocxInspector.text` adds "• " for numbered paragraphs.
- **Thymeleaf 3.1 forbids `T(...)` static access in expressions**: add a helper method to the record instead.
- Exported files survive `@Transactional` rollback: tests use a random exports folder per context
  (`${random.uuid}` in application-test.yml) and clear it in `@BeforeEach`.
- A generic `.field input { width: 100% }` rule also stretches radio buttons: scope it.

## Phases

- [x] **Phase 0** Scaffolding: Boot app, H2 file DB, Thymeleaf layout + nav + footer, application.yml,
  `/api/health`, `ClaudeClient` (structured output + validation + one retry + cost meter), ping
  (`POST /api/llm/ping`, Settings button), local-only filter, tests, README, .gitignore.
- [x] **Phase 1** Data model: `schema/master-resume.schema.json`, `MasterResume` records,
  `MasterResumeVersion` + `IdCounter` entities, `IdAssigner`, privacy + integrity validators, `MasterDiffer`,
  `/api/master` (GET, PUT, validate, sample, versions, versions/{n}, restore, diff), pages `/master`
  (JSON editor with htmx validate/save), `/master/history`, `/master/versions/{n}`, `/master/diff`,
  dashboard master card, fake sample.
- [x] **Phase 2** `PendingUpdate` entity + service (propose / preview / edit patch / apply / reject),
  `JsonPatch`, Add Info forms for 7 entry types → `FormPatchBuilder`, `/api/updates*`, review page with
  ID-aware diff, readable result and htmx patch editor, `/master/view`, PDF/DOCX import (`/master/import`,
  `POST /api/master/import`) with redaction, mapping, evidence linking and provenance notes.
- [x] **Phase 3** CheckEngine (`checks/`): `Check` beans (SG-01..11 in `sg/SgChecks`, LINT-01..09 in
  `lint/LintChecks`, KW-01..03 in `kw/KwChecks`), `CheckContext` (builder), `CheckEngine` → `CheckReport`
  (per-category status + sub-score; KW scored by coverage and never ERROR). Data: `checks/action_verbs.txt`,
  `skills/tech_skills.csv` (438 terms; `cs` flag = case-sensitive). Render model `render/ResumeDocument` +
  `ResumeRenderer.renderMaster` (also the source of check text). Master health page `/master/health`
  (+ `GET /api/master/health`), keyword check vs hand-entered terms, Add metric quick form (`type=metric`),
  live lint preview on the highlight form. Rule tests are data-driven: one JSON per rule in
  `src/test/resources/fixtures/checks/` run by `CheckRuleFixturesTest` (also asserts every rule has one).
- [x] **Phase 4** `jobs/`: `JobPosting` entity, `JobService` (from text / URL / screenshots, all through
  `prompts/jd-extract.md` = spec 5.1 verbatim + field notes; schema all-required), `JobSpecMapper` (""/0 →
  absent, dedupe, site's structured title/company/salary win). `jobs/source/`: `JobSourceChain` of
  `@Order`ed adapters: MyCareersFuture (v2 API by 32-hex UUID), Greenhouse (boards-api, entity-encoded
  HTML), Lever (postings API), generic (JSON-LD JobPosting, else readability main text); `BlockedSiteException`
  for LinkedIn / 401/403/429 / login walls / <500 chars / robots.txt (`RobotsPolicy`); `UrlGuard` blocks
  private addresses (tests set `tailorsg.jobs.allow-private-hosts`). Screenshots: `ScreenshotInput` (PNG/JPEG/
  WebP via TwelveMonkeys, long edge ≤1568, re-encoded PNG) sent as image blocks. LLM requests now take
  `UserBlock`s (text, cacheable text, image). Pages: `/applications/new` (Link/Screenshot(s)/Paste),
  `/jobs`, `/jobs/{id}` editor (htmx skill chips, details form, JSON, master coverage, application options).
  Real API fixtures: `src/test/resources/fixtures/jobs/{mcf,gh,lever}-job.json` (saved 2026-10-17).
- [x] **Phase 5** Tailoring: prompt 5.3, TailoredResume validation, renderer merging master structural
  data, TRUTH checks, preview + scorecard UI, manual bullet editing.
- [x] **Phase 5b** (user add-on) **Job-fit recommendation, ADVISORY.** Deterministic part first: required/
  preferred coverage, GAP vs EVIDENCED_NOT_USED terms, seniority/years mismatch flags. Then an LLM narrative
  (writer model, schema-validated) recommending one of: apply now / apply and address a gap in the cover
  letter / build a small project first. A project suggestion must target specific GAP terms, fit in about
  1-2 weeks, and never imply the user already has the skill. Labelled ADVISORY; never affects pass/fail (P4).
- [x] **Phase 6** Export: openhtmltopdf PDF + POI DOCX, ATS-01..10 round-trip checks, OpenResume-style
  parser, export blocker + override, file storage.
- [x] **Phase 7** Cover letter: prompt 5.4, CL checks, em-dash auto-fix, PDF/DOCX export.
- [x] **Phase 8** Free-text master updates: prompt 5.2, clarifying questions, diff/apply, "add metric" loop.
- [x] **Phase 9** Polish: tracker statuses, persisted cost footer, prompt versions per application,
  editable threshold settings.

## Current phase

**All phases complete (2026-10-17).** 425 tests pass, 1 skipped (live ping without a key). Smoke-tested end to
end in demo mode on a scratch database; screenshots in `docs/screenshots/`, sample exports in `docs/samples/`.
The real LLM prompts (tailor, fit-advice, cover-letter, master-update, jd-extract, master-import) have not
run against the live API yet (no key in the dev shell): their schemas pass `LlmSchemaLimitsTest`, and every
pipeline is tested with canned outputs. First thing to do with a key: run one real application through and
compare its scorecard with the demo's.

Ideas not built (ask before starting): Skills Framework for ICT import (`skills/sfw_ict.csv`), a form-based
threshold editor, per-application option editing after creation (page target, summary).
