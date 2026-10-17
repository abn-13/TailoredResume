# TailorSG specification (original, as given on 2026-10-15)

> This is the original spec, kept word for word except for the author's personal details (removed before
> publishing). Decisions that amend it (e.g. Spring Boot 4.1 instead of 3.x, the maintained openhtmltopdf fork,
> prompts 5.3 and 5.4 generalised so anyone can use the app) are recorded in `CLAUDE.md`. Where the two
> disagree, CLAUDE.md wins.

You are helping me build "TailorSG": a local, single-user web app that tailors my resume and cover letter to specific job descriptions for the Singapore job market, with OBJECTIVE, rule-based quality checks that do not rely on the LLM's own judgment. [Personal details about the author removed before publishing.] I am learning Spring Boot, so write idiomatic, well-commented Java and explain non-obvious decisions briefly in code comments.

Work incrementally. Do NOT build everything at once. Follow the phases at the end. At the start, create CLAUDE.md summarising this spec, the conventions, and the current phase. Update it at the end of each phase. After each phase, stop, show me how to run and test it, and wait for my go-ahead.

## 0. TECH STACK AND CONSTRAINTS

- Java 21, Spring Boot 3.x, Maven. Packages under com.bu.tailorsg.
- Web: Spring MVC + Thymeleaf + htmx (small JS only where needed). Plain CSS, no heavy frontend framework.
- Storage: H2 in file mode (./data/tailorsg) via Spring Data JPA. Store JSON documents in CLOB/JSON columns, serialised with Jackson.
- LLM: Anthropic Claude API. Use the official Anthropic Java SDK if available (check Maven Central for com.anthropic:anthropic-java and use the latest version). Otherwise use a thin java.net.http client against POST https://api.anthropic.com/v1/messages with headers x-api-key, anthropic-version, content-type.
  - API key from env var ANTHROPIC_API_KEY only. Never log it or commit it.
  - Model IDs live in application.yml so I can change them:
      tailorsg.models.writer = claude-sonnet-5-5   (tailoring, cover letters)
      tailorsg.models.extractor = claude-haiku-5-5 (JD extraction, free-text parsing)
  - Before coding, verify these IDs against https://platform.claude.com/docs/en/about-claude/models/overview and tell me if they have changed.
  - Every LLM call must request JSON output that matches a schema (use structured outputs / tool-use JSON schema if the SDK supports it). Validate the response with a JSON Schema validator (networknt json-schema-validator). Retry once with the validation errors appended, then fail visibly.
- Documents:
  - PDF: render a Thymeleaf HTML template to PDF with openhtmltopdf (com.openhtmltopdf). Embed a standard font (e.g., Arial-compatible Liberation Sans or Carlito) so text extracts cleanly.
  - DOCX: generate with Apache POI XWPF using plain paragraphs and real bullet numbering. NO tables, NO text boxes, NO header/footer content.
  - Text extraction for round-trip checks: Apache PDFBox (PDFTextStripper with sortByPosition=true) and POI XWPFWordExtractor.
  - Fetching: Jsoup for HTML.
- No Python in v1. Design the checker behind a Java interface (ResumeParser) so I could later plug in a Python or Node sidecar (e.g., OpenResume's parser) over HTTP. If you think a sidecar is needed earlier, explain why before adding one.
- Testing: JUnit 5 + AssertJ. Every checker rule gets unit tests with a passing and a failing fixture. Put fixtures in src/test/resources/fixtures.
- Logging: SLF4J. Log LLM token usage per call, and keep a running cost estimate in the UI footer using per-MTok prices from application.yml.

## 1. CORE PRINCIPLES (put these in CLAUDE.md)

P1. The master resume is the single source of truth. The LLM may SELECT, ORDER and REWORD content from it. It may NEVER introduce new facts, numbers, employers, titles, dates, tools, certifications or outcomes.
P2. Structural facts are rendered straight from master data, never from LLM text: name, contact, employer names, job titles, locations, dates, degrees, institutions, grades.
P3. Every generated bullet carries sourceIds pointing to master-resume item IDs. Code verifies provenance deterministically (section 6E).
P4. Quality is judged by the deterministic CheckEngine, not by the LLM. Any LLM "judge" is labelled ADVISORY in the UI and never changes the pass/fail status.
P5. Export is blocked while any ERROR-level check fails, unless I explicitly override with a typed reason (stored with the application).
P6. Singapore fair-employment hygiene: never output a photo, NRIC/FIN, date of birth, age, gender, race, religion, marital status or family details, full residential address, referee contact details, or expected salary.

## 2. DATA MODEL

### 2.1 MasterResume JSON (a superset of the JSON Resume schema; store versioned)

```
{
  "meta": { "version": int, "updatedAt": ISO8601, "locale": "en-SG", "spelling": "british" },
  "basics": {
    "name", "label", "email", "phone" (E.164, e.g. +65...), "url",
    "location": { "city": "Singapore", "countryCode": "SG" },
    "profiles": [ { "network": "LinkedIn|GitHub|Portfolio", "username", "url" } ],
    "summaryVariants": [ { "id": "sum-1", "text", "targetRoles": [..] } ],
    "workAuthorization": { "status": "EP_REQUIRED|EP_HOLDER|PR|CITIZEN|OTHER", "displayText": "Work authorisation: Employment Pass required", "showOnResumeDefault": true }
  },
  "work": [ { "id": "work-1", "name" (employer), "position", "location", "employmentType": "Internship|Full-time|Contract|Part-time",
              "startDate": "YYYY-MM", "endDate": "YYYY-MM|null(current)", "summary",
              "highlights": [ { "id": "work-1.h1", "text", "metrics": [ { "value": "35", "unit": "%", "label": "latency reduction" } ],
                                "skills": ["Java","Spring Boot"], "tags": ["backend","performance"],
                                "evidence": "optional private note, never rendered", "priority": 1-5 } ] } ],
  "projects": [ { "id": "proj-1", "name", "role", "url", "repo", "startDate", "endDate", "description",
                  "highlights": [same shape as above], "keywords": [..] } ],
  "education": [ { "id": "edu-1", "institution", "area", "studyType", "startDate", "endDate", "score", "scoreScale",
                   "courses": [..], "highlights": [same shape] } ],
  "skills": [ { "id": "skill-java", "name": "Java", "category": "Languages|Frameworks|Cloud/DevOps|Databases|Tools|Domain|Soft",
                "level": "optional", "evidenceIds": ["work-1.h1","proj-2.h3"] } ],
  "certificates": [ { "id", "name", "issuer", "date", "url" } ],
  "awards": [ { "id", "title", "awarder", "date", "summary" } ],
  "languages": [ { "language", "fluency" } ],
  "volunteer": [ same shape as work ],
  "extras": [ { "id", "type": "publication|leadership|cca|other", "text", "date" } ]
}
```

Rules:
- IDs are stable and never reused. Generate them server-side (prefix + incrementing counter).
- A skill with no evidenceIds is allowed but gets flagged "unevidenced" in the master-resume health view.
- Never store NRIC, DOB, photo, religion, marital status, address. Validation rejects these keys and the regex patterns in 6A.

### 2.2 JPA entities

- MasterResumeVersion(id, versionNo, json CLOB, changeNote, createdAt). The latest row is "current". Keep all history with a diff view.
- PendingUpdate(id, sourceType FREE_TEXT|FORM|IMPORT, rawInput CLOB, proposedPatch CLOB (RFC 6902 JSON Patch), status PROPOSED|APPLIED|REJECTED, createdAt).
- JobPosting(id, sourceType URL|SCREENSHOT|PASTE, sourceUrl, sourceHost, rawText CLOB, extracted CLOB (JobSpec JSON), title, company, createdAt).
- Application(id, jobPostingId, masterVersionNo, tailoredResume CLOB (TailoredResume JSON), coverLetter CLOB, checkReport CLOB, overrideReason, status DRAFT|EXPORTED|APPLIED|INTERVIEW|REJECTED|OFFER, createdAt, updatedAt).
- ExportFile(id, applicationId, type PDF|DOCX, path, sha256, pageCount, createdAt). Files go in ./data/exports/{company}_{title}_{date}/.

### 2.3 JobSpec JSON (extracted from the JD)

```
{ "title", "company", "location", "seniority", "employmentType", "salaryRange": {min,max,currency} | null,
  "requiredSkills": [ {"term","aliases":[..]} ], "preferredSkills": [..], "responsibilities": [..],
  "qualifications": [..], "keywordsRaw": [..], "companyFacts": [..] (only facts stated in the JD), "applyInstructions": "...", "coverLetterRequested": bool }
```

### 2.4 TailoredResume JSON (LLM output, validated)

```
{ "targetTitleLine": "string (must reuse a master label/summary variant or JD title only if truthful)",
  "summary": { "text", "sourceIds": [..] } | null,
  "sectionOrder": ["education","experience","projects","skills", ...],
  "experience": [ { "workId": "work-1", "bullets": [ { "text", "sourceIds": ["work-1.h1"] } ] } ],
  "projects": [ { "projectId", "bullets": [ ... ] } ],
  "skillsLines": [ { "category", "items": [ { "name", "skillId" } ] } ],
  "omitted": [ { "id", "reason" } ],
  "gapsNotAddressed": [ "JD terms with no evidence in master" ] }
```

The renderer pulls employer/title/dates for workId from master data (P2).

## 3. FEATURES AND ENDPOINTS

UI pages (Thymeleaf): Dashboard, Master Resume (view/edit/history/health), Add Info (free text + form), New Application (JD input), Application detail (tailored preview, scorecard, cover letter, exports), Settings.

REST/MVC endpoints (return JSON for htmx fragments where helpful):

Master resume
- GET  /api/master                         -> current MasterResume
- PUT  /api/master                         -> replace (schema-validated, creates a new version)
- GET  /api/master/versions                -> list; GET /api/master/versions/{n}; GET /api/master/diff?from=&to=
- POST /api/master/import                  -> upload my existing resume (PDF/DOCX). Extract text (PDFBox/POI) and ask the extractor model to map it into MasterResume JSON. Show it as a PendingUpdate for review.
- GET  /api/master/health                  -> run bullet lint + SG checks on the whole master (no JD)

Updates (multiple avenues)
- POST /api/updates/free-text  {text}      -> extractor model returns a JSON Patch proposal + human-readable summary + clarifying questions (e.g., "What was the measurable outcome?"). Never auto-apply.
- POST /api/updates/form       {typed entry: work|project|education|skill|certificate|award|highlight} -> server builds the patch directly (no LLM)
- POST /api/updates/{id}/apply  | /reject ; PATCH /api/updates/{id} (I can edit the proposed patch before applying)
- Form UX: a dynamic form per entry type with fields matching the schema. For highlights, include optional "metric value / unit / label" inputs and a skills multi-select (with "add new skill"). Show a live bullet-lint preview as I type.

Job descriptions
- POST /api/jobs/from-url    {url}
- POST /api/jobs/from-image  (multipart, 1..5 images: PNG/JPEG/WebP; downscale so the long edge is <= 1568 px before sending)
- POST /api/jobs/from-text   {text}
- GET  /api/jobs/{id}; PUT /api/jobs/{id} (I can correct the extracted JobSpec)

Applications
- POST /api/applications {jobId, options:{pageTarget:1|2, includeSummary, showWorkAuth, coverLetter:bool, tone}}
- POST /api/applications/{id}/tailor        -> runs the tailoring prompt, then the CheckEngine
- POST /api/applications/{id}/cover-letter  -> runs the cover-letter prompt, then the cover-letter checks
- POST /api/applications/{id}/check         -> re-run all checks (after my manual edits)
- PUT  /api/applications/{id}/resume        -> my manual edits to TailoredResume (re-validated)
- POST /api/applications/{id}/export?type=pdf|docx -> render, then run the parse round-trip check, then save
- GET  /api/applications (tracker list with status)

URL ingestion strategy (implement as a JobSourceAdapter chain, first match wins):
1) mycareersfuture.gov.sg: extract the job UUID (32-hex id at the end of the path) and try GET https://api.mycareersfuture.gov.sg/v2/jobs/{uuid}. It is an undocumented public API, so inspect the real response first and write a test with a saved fixture. Map title, postedCompany.name, description (HTML to text), skills, salary. On any failure fall through.
2) boards.greenhouse.io / job-boards.greenhouse.io: use the public Job Board API (boards-api.greenhouse.io/v1/boards/{board}/jobs/{id}). jobs.lever.co: use api.lever.co/v0/postings/{company}/{id}. Verify the URL formats against their docs before coding.
3) Generic: Jsoup GET with a normal browser User-Agent and a 10s timeout. First look for `<script type="application/ld+json">` with @type JobPosting and use its description. Otherwise use readability-style main-text extraction.
4) linkedin.com, and any site returning 401/403/429, a login wall, or under 500 chars of text: do NOT try to bypass it. Return a friendly message: "This site blocks automated fetching. Paste the JD text or upload screenshots." Respect robots.txt. No headless browsers in v1.

After raw text is obtained by any route, run the JD extraction prompt (section 5.1) to produce JobSpec.

## 4. RESUME TEMPLATE (ATS-safe, single column)

- A4 page, margins 1.8-2.2 cm. One column. Body font 10.5-11 pt (default 11), headings 12-13 pt bold, name 16-18 pt. Fonts: Arial/Liberation Sans or Calibri/Carlito, embedded.
- Order (default; tailoring may reorder Experience/Projects/Skills by relevance):
  Name / contact line (phone | email | LinkedIn URL | GitHub URL | portfolio) / optional work-authorisation line
  Summary (optional, 2-3 lines)
  Education
  Experience (or "Work Experience")
  Projects
  Skills (grouped lines, e.g., "Languages: Java, Python, TypeScript")
  Certifications / Awards / Leadership (only if relevant)
- Allowed section headings ONLY (case-insensitive): Summary, Profile, Education, Experience, Work Experience, Professional Experience, Internship Experience, Projects, Technical Projects, Skills, Technical Skills, Certifications, Awards, Leadership, Co-curricular Activities, Publications, Volunteering, Languages.
- Job header line: Position, Employer, City, then dates right-aligned via a tab stop or flex, NOT a table. Date format "MMM YYYY - MMM YYYY" or "MMM YYYY - Present", using an ASCII hyphen with spaces.
- Bullets: standard round bullet character. No icons, no images, no colour-dependent meaning, no columns, no tables, no text boxes, nothing in the header/footer, no skill bars.
- Links: show the visible URL text (e.g., github.com/username), not "click here".
- File naming: Firstname_Lastname_Resume_Company.pdf / .docx.

## 5. LLM PROMPTS (store as versioned files in src/main/resources/prompts/*.md and load at runtime)

### 5.1 JD extraction (extractor model; input = raw text or images)

System: "You extract job postings into JSON. Copy terms exactly as written in the posting. Do not infer skills that are not stated. Split requirements into requiredSkills (must/required/minimum/you have) vs preferredSkills (nice to have/preferred/bonus/plus). For each skill, add common aliases (e.g., 'JS' for 'JavaScript', 'Postgres' for 'PostgreSQL', 'k8s' for 'Kubernetes'). companyFacts must only contain facts stated in the posting. If text is cut off or unreadable in the screenshot, set a field 'warnings'. Output only JSON matching the schema."

For screenshots: send all images in one message in order, with the instruction "These images are consecutive parts of one job posting."

### 5.2 Free-text to master update (extractor model)

System: "You convert a user's free-text description of new experience into an RFC 6902 JSON Patch against the provided MasterResume JSON. Rules: (1) Only use facts the user wrote. Never invent numbers, tools, outcomes, dates or titles. (2) If a field is unknown, leave it out and add a clarifying question. (3) Reuse existing IDs when updating. Use placeholder IDs 'NEW-1', 'NEW-2' for new items (the server assigns real IDs). (4) Put any metrics the user stated into highlights[].metrics. (5) Map tools to existing skills by name. Propose new skills only if the user mentioned them, and attach evidenceIds. (6) Draft highlight text in action-verb, past-tense resume style, but keep the user's facts verbatim. Output JSON: { patch: [...], summary: 'plain English list of changes', questions: [...] }."

Server: validate the patch applies cleanly and the result passes the schema. Show a side-by-side diff with Apply / Edit / Reject.

### 5.3 Tailoring (writer model)

System prompt (include verbatim, then the master JSON, JobSpec JSON and options as separate user-content blocks):

"You tailor a resume for a specific job in Singapore. You are given MASTER (the only source of truth) and JOB (the job spec).
HARD RULES:
1. Use only facts in MASTER. Do not add or alter numbers, tools, technologies, employers, titles, dates, team sizes, outcomes or certifications. If a JD requirement has no supporting evidence in MASTER, list it in gapsNotAddressed and do not mention it.
2. Every bullet must list sourceIds of the MASTER highlight(s) it is derived from. Merging two highlights is allowed (cite both). Never cite an ID you did not use.
3. Do not output employer names, job titles, dates, education details or contact info. The renderer adds them from MASTER using workId/projectId.
4. Never include photo, NRIC/FIN, age, date of birth, gender, race, religion, marital status, family details, residential address, referee details or expected salary.
STYLE RULES (these are also checked by code):
5. Each bullet starts with a strong past-tense action verb (present tense only for a current role's ongoing work). Never start with 'Responsible for', 'Helped', 'Assisted', 'Worked on', 'Participated in', 'Involved in', 'Tasked with'.
6. Prefer the Competency-Action-Result shape: what you did, with what tools/context, and the measurable or qualitative result ('Accomplished X as measured by Y by doing Z'). Keep any metric from MASTER. Never invent one.
7. 12-28 words per bullet, one sentence, no first-person pronouns, no full stop at the end, British/Singapore spelling consistent with MASTER.meta.spelling.
8. Mirror the JD's exact terminology when MASTER evidence supports it (e.g., if JD says 'RESTful APIs' and MASTER says 'REST endpoints', write 'RESTful APIs'). Do not repeat any single keyword more than 3 times in the whole resume. No keyword lists inside bullets.
9. Most relevant role: 3-6 bullets. Older or less relevant roles: 1-3. Projects: 2-3 bullets each, max 3 projects unless pageTarget=2. Fit pageTarget (1 page is about 450-550 words of body text for this template).
10. Order sections and items by relevance to JOB while keeping reverse-chronological order within each section.
11. For a career switcher (mechanical engineering to software), surface transferable evidence that MASTER supports (e.g., simulation, data analysis, automation scripts, systems thinking) using software-relevant language, without overstating.
12. Summary (if requested): 2-3 lines, no clichés ('passionate', 'hard-working', 'team player', 'results-driven', 'go-getter', 'detail-oriented', 'dynamic'), and cite sourceIds.
Output only JSON matching the TailoredResume schema."

### 5.4 Cover letter (writer model)

System prompt:

"You write a cover letter that Bu will send as himself for a job in Singapore. Use only facts from MASTER and JOB, plus USER_NOTES (his own reasons for wanting this company, if provided).
Structure: his contact line (email, mobile), date (D Month YYYY), recipient (named person if known, otherwise 'Hiring Manager' and the company), a subject line 'Application for [exact JD title]', then 3-4 paragraphs:
 (1) Opening: why this company and role specifically. Reference at least one concrete fact from JOB.companyFacts or USER_NOTES. Never open with 'I am writing to apply' or 'I am excited to apply'.
 (2-3) Body: the 2-3 most relevant achievements from MASTER, told with a bit more context than the resume bullets (problem, action, result). Explain the mechanical-engineering-to-software transition as an asset when relevant. Address at most one gap honestly if helpful (e.g., 'I have not used Kafka in production, but I ...') only when MASTER supports the 'but'.
 (4) Closing: restate fit in one sentence, availability (from USER_NOTES, e.g. 'available from January 2027') and a clear call to action.
Sign off with 'Yours sincerely,' and his name.
Length: 250-380 words (half to one A4 page). Plain, direct, warm, professional British/Singapore English.
WRITING STYLE (strict): Do NOT use em dashes (—) or en dashes (–) anywhere. Use commas, full stops, colons or parentheses instead. Avoid these words/phrases: 'delve', 'testament', 'tapestry', 'thrilled', 'leverage' (as a verb), 'synergy', 'passionate about', 'in today's fast-paced world', 'I believe I would be a great fit', 'hit the ground running'. Vary sentence openings: no more than 3 sentences starting with 'I' in a row. No exclamation marks.
Do not mention nationality, age, marital status or salary. Mention work authorisation only if USER_NOTES.mentionWorkPass is true, as one neutral sentence.
Output JSON: { recipientLine, subject, paragraphs: [ {text, sourceIds:[..]} ], signOff, wordCount }."

## 6. CHECK ENGINE (deterministic; the heart of the app)

Design:
- interface Check { String id(); Category category(); List<Finding> run(CheckContext ctx); }
- Finding { checkId, severity ERROR|WARN|INFO, location (section/itemId/bulletIndex or page), message, suggestedFix, evidence (the offending text) }
- CheckContext holds the master, JobSpec, TailoredResume, rendered plain text, exported file bytes (when available) and options.
- Scorecard UI: one panel per category with a PASS/WARN/FAIL badge (FAIL if any ERROR), counts, and a list of findings with "jump to" and suggested fix. Also show a per-category 0-100 sub-score (formula below) but NO single overall "ATS score". Show a banner: "Scores are heuristics from published guidance, not a real employer's ATS."
- All thresholds live in application.yml under tailorsg.checks.* so I can tune them. Each rule's Javadoc cites its basis (e.g., "NUS CFG@FASS guide: min font 11", "TAFEP: remove photo fields").

### A) SINGAPORE NORMS (category SG)

- SG-01 ERROR: NRIC/FIN pattern  `\b[STFGM]\d{7}[A-Z]\b`  anywhere.
- SG-02 ERROR: date of birth / age: regex for `(date of birth|DOB|born on|age:?\s*\d{2}|\b\d{2}\s*(years old|y/o))`, case-insensitive.
- SG-03 ERROR: marital/family/religion/race/gender fields: lines matching `^(marital status|religion|race|gender|sex|no\. of children|children)\s*[:\-]` and words like "married", "single" in the header block.
- SG-04 ERROR: an image or photo present in the PDF (PDFBox: any image XObject) or DOCX (any XWPFPicture).
- SG-05 WARN: a full residential address (Singapore postal code `\bSingapore\s*\d{6}\b` or `\b\d{6}\b` next to "Blk|Block|Street|Avenue|Road|#\d{2}-\d{2,4}"). The fix: keep "Singapore" only.
- SG-06 WARN: expected/current salary mentioned ("expected salary", "salary", "SGD|S\$\s?\d" in the header/personal area).
- SG-07 WARN: "References available upon request" or referee contact details.
- SG-08 WARN: a standalone "Nationality:" field. INFO: work-authorisation line present/absent vs the user setting.
- SG-09 WARN: page count > pageTarget. ERROR: page count > 2.
- SG-10 WARN: inconsistent date formats (detect all date tokens and require a single pattern, MMM YYYY). ERROR: non-reverse-chronological order within Experience/Education.
- SG-11 WARN: spelling-variant mixing (both -ise and -ize forms, e.g. organise/organize, optimise/optimize, analyse/analyze, colour/color, centre/center). Default preference: British.

### B) ATS PARSEABILITY (category ATS); runs on the exported file

- ATS-01 ERROR: text extractable. PDFBox text length > 300 chars and >= 90% of the rendered source characters (catches image-only output).
- ATS-02 ERROR: name appears within the first 3 non-empty extracted lines. Email (RFC-lite regex) and phone extracted.
- ATS-03 ERROR: every rendered section heading found in the extracted text, in order, and each is in the allowed-headings list.
- ATS-04 ERROR: for every experience item, the extracted text contains the position, employer and both date strings within a 400-character window, in source order.
- ATS-05 WARN: reading order. The sequence similarity (normalised Levenshtein or LCS ratio on tokens) between the source plain text and the extracted text is >= 0.90 (WARN below 0.90, ERROR below 0.75).
- ATS-06 ERROR (DOCX): no tables (XWPFTable count = 0), no text boxes/shapes, header and footer contain no text.
- ATS-07 WARN: fonts. All fonts embedded and in the allow-list. Body size >= 10.5pt (ERROR < 10pt). NUS recommends 11.
- ATS-08 WARN: non-standard bullet glyphs (anything other than • ▪ - ‣ ◦), or ligature/odd Unicode that extracts badly (e.g., private-use code points).
- ATS-10 WARN: a heuristic field parser (implement an OpenResume-style 4-step parser in Java: lines -> sections by heading match -> subsections by date lines -> features) recovers at least 90% of master-sourced fields (name, email, phone, each job's title/company/dates, each degree/institution). Show a table of "what an ATS would likely extract" next to the expected values.

### C) KEYWORD MATCH (category KW)

- Build the dictionary at startup from:
  (1) src/main/resources/skills/tech_skills.csv: a curated list (I will extend it) with canonical name, aliases and category. Seed with ~400 common software terms: languages, frameworks, cloud, DevOps, databases, testing, practices like Agile/Scrum/CI/CD/microservices/REST/TDD.
  (2) optional import of Singapore Skills Framework for ICT TSC titles (CSV from SkillsFuture/SSG Jobs-Skills portal) into skills/sfw_ict.csv.
- Matching: normalise case, punctuation and aliases (JS=JavaScript, Postgres=PostgreSQL, Springboot=Spring Boot, k8s=Kubernetes, CI/CD variants). Match whole words or phrases. Simple lemmatisation for plurals.
- Score:
    requiredCoverage = matched required terms / total required terms
    preferredCoverage = matched preferred / total preferred
    KW sub-score = round(100 * (0.75*requiredCoverage + 0.25*preferredCoverage))
    Thresholds (heuristic, configurable): required coverage >= 0.80 PASS; 0.60-0.79 WARN; < 0.60 FAIL (WARN-level finding, not an export blocker).
- For each missing term, classify:
    EVIDENCED_NOT_USED: the term (or alias) exists in master (skills or any highlight) but not in the tailored output. Suggested fix: "Add from work-2.h3: ...".
    GAP: not in master at all. Suggested fix: "Do not add. Consider addressing in the cover letter, or add to master only if true."
- KW-02 WARN: any keyword appears more than 3 times, or a bullet contains more than 4 dictionary skills (stuffing).
- KW-03 INFO: whether the JD's exact job title (or a truthful close variant) appears in the summary/title line.

### D) BULLET LINT (category LINT); runs on the tailored resume AND on the master (health view)

- LINT-01 WARN: the first word is not in action_verbs.txt (seed 200+ verbs: Architected, Automated, Built, Cut, Debugged, Delivered, Deployed, Designed, Developed, Implemented, Improved, Integrated, Led, Migrated, Optimised, Reduced, Refactored, Shipped, Streamlined, Tested, ...). ERROR if it starts with a banned opener: Responsible for, Helped, Assisted, Worked on, Participated in, Involved in, Tasked with, Duties included.
- LINT-02 INFO per bullet, WARN at resume level: metric presence. A bullet "has a metric" if it matches `\d|%|\$|S\$|x\b|\bk\b|million|hours|ms|users|requests`. Target: >= 50% of Experience bullets and >= 30% of Project bullets have a metric (heuristic). Never suggest inventing numbers. Suggest asking me for one and adding it to the master.
- LINT-03 WARN: length outside 12-28 words, or longer than 2 rendered lines.
- LINT-04 WARN: first-person pronouns (I, me, my, we, our).
- LINT-05 WARN: tense. A past role's bullets must be past tense. A current role may use present tense for ongoing work. Use a simple heuristic: the first verb ends in -ed or is in the irregular-past list for past roles.
- LINT-06 WARN: cliché/filler list (configurable): team player, hard-working, detail-oriented, results-driven, go-getter, self-starter, synergy, passionate, dynamic, think outside the box, various, etc., successfully.
- LINT-07 WARN: redundant phrasing per NUS guidance: "Conducted research" -> "Researched", "Performed an analysis" -> "Analysed", "Was responsible for".
- LINT-08 WARN: the same starting verb used more than 2 times in the resume, or twice consecutively.
- LINT-09 WARN: bullet counts per role outside the 5.3 rule 9 ranges. Trailing full stops used inconsistently.

### E) NO-FABRICATION / PROVENANCE (category TRUTH); export blocker

- TRUTH-01 ERROR: every bullet has >= 1 sourceId and every sourceId exists in master.
- TRUTH-02 ERROR: number check. Extract all numeric tokens from the bullet (normalise "35 %"->"35%", "1,200"->"1200", "S$5k"->"5000", "2x"->"2"). Each must appear in the cited sources' text or metrics. Exception: a small allow-list of non-claim numbers (e.g., version numbers like "Java 21", "Python 3" if present in master skills).
- TRUTH-03 ERROR: tool/skill check. Every dictionary skill mentioned in the bullet must appear in the cited sources OR in master.skills with evidenceIds that overlap the cited sources. Otherwise it is "unsupported skill".
- TRUTH-04 ERROR: proper-noun check. Capitalised multi-word names or product names (regex for Title Case sequences not at the sentence start, minus the dictionary and verbs) must appear somewhere in master.
- TRUTH-05 ERROR: structural fields in the rendered output (employer, title, dates, institution, degree) equal master values exactly (guaranteed by the renderer, but assert it).
- TRUTH-06 WARN: scope inflation words not present in the source ("led", "managed", "owned", "architected", "spearheaded", team-size phrases) when the source used weaker verbs ("contributed", "supported", "assisted"). Show a side-by-side.

The cover letter runs TRUTH-02/03/04 per paragraph against its cited sourceIds plus JOB.companyFacts and USER_NOTES.

### F) COVER LETTER (category CL)

- CL-01 ERROR: em dash (U+2014) or en dash (U+2013) present. Auto-fix offer: replace with a comma or full stop and show the diff.
- CL-02 WARN: word count outside 250-380. ERROR above 450.
- CL-03 WARN: banned openers/phrases list (5.4). More than 3 consecutive sentences starting with "I".
- CL-04 WARN: fewer than 1 company-specific fact (a sentence containing the company name plus a JD companyFacts term, or USER_NOTES content).
- CL-05 WARN: generic salutation "To Whom It May Concern" / "Dear Sir or Madam". Prefer a named person or "Dear Hiring Manager".
- CL-06 ERROR: mentions salary, age, nationality, marital status (unless mentionWorkPass for the single permitted sentence).

Sub-score formula per category: start at 100, subtract 25 per ERROR, 8 per WARN, 0 per INFO, floor at 0. KW uses its coverage formula instead.

## 7. UI DETAILS

- New Application: tabs Link / Screenshot(s) / Paste. Then an editable JobSpec (required/preferred skill chips I can move between lists) and options (page target, summary, work-auth line, cover letter, USER_NOTES: why this company, availability, mentionWorkPass).
- Application detail: a live HTML preview (same template as the PDF) beside the scorecard. Clicking a finding highlights the bullet. Hovering a bullet shows its source IDs and the original master text. Inline edit, then re-check.
- Keyword panel: matched (green), EVIDENCED_NOT_USED (amber, one-click edit box prefilled from the source that I confirm), GAP (grey).
- Master health page (lint findings, unevidenced skills, "add metric" quick form) and Add Info page (free-text box with an example placeholder plus a tabbed form; both produce a PendingUpdate diff).
- Export is disabled while ERRORs exist. The override requires a typed reason.

## 8. PHASED BUILD ORDER (stop after each phase)

- Phase 0 - Scaffolding: Spring Boot project, H2 file DB, Thymeleaf layout, application.yml with config keys, CLAUDE.md, README with run steps, a health endpoint, a Claude client wrapper with a "ping" test calling the extractor model (skipped if no API key), and a .gitignore that excludes ./data and .env.
- Phase 1 - Data model: MasterResume JSON Schema file (src/main/resources/schema/master-resume.schema.json), Jackson records, versioning entity, ID generator, CRUD endpoints, a raw JSON editor page with validation errors, and a seed sample master (fake data) for tests.
- Phase 2 - Structured form + resume import: the form-based PendingUpdate flow (no LLM), JSON Patch apply with diff view, and PDF/DOCX import via text extraction + extractor model (review before apply).
- Phase 3 - CheckEngine v1 on plain text: categories SG, LINT, KW (with a hand-entered JobSpec), the Master health page, and unit tests for every rule. (Build the checks BEFORE any generation so I can test them on my current resume.)
- Phase 4 - JD ingestion: paste, URL adapters (MCF, Greenhouse, Lever, JSON-LD, generic, blocked-site message), screenshot via vision, JobSpec editor. Save real fixtures for tests.
- Phase 5 - Tailoring: prompt 5.3, TailoredResume validation, the renderer that merges master structural data, the TRUTH checks, the preview + scorecard UI, and manual bullet editing.
- Phase 6 - Export: openhtmltopdf PDF + POI DOCX from the same TailoredResume, ATS round-trip checks (ATS-01..10), the OpenResume-style heuristic parser, the export blocker + override, and file storage.
- Phase 7 - Cover letter: prompt 5.4, CL checks, the em-dash auto-fix, and PDF/DOCX export with the same font family.
- Phase 8 - Free-text master updates: prompt 5.2 with clarifying questions, the diff/apply flow, and an "add metric" quick-fix loop from LINT-02.
- Phase 9 - Polish: application tracker statuses, cost footer, prompt versioning shown on each application, and settings for thresholds.

Definition of done for each phase: it compiles, tests pass (mvn test), there is a manual test script in README, and no secrets are in code.

## ADD-ON (from the user)

"Perhaps an AI recommendation of how well this job description suits my profile and whether it's worth applying or should I do some small project to supplement my application."
