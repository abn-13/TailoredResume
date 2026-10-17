# TailorSG

A local, single-user web app that tailors your master resume and cover letters to Singapore job
descriptions, then checks the output with deterministic, rule-based checks, not the LLM's own opinion.

- Spec: [docs/SPEC.md](docs/SPEC.md)
- Design decisions and conventions: [CLAUDE.md](CLAUDE.md)
- Licence: MIT ([LICENSE](LICENSE))

**Status:** all phases (0-9, plus 5b) are built and tested: 438 automated tests pass, plus 2 live API tests
that run only when a key is set. Screenshots of every page are in [docs/screenshots/](docs/screenshots/), and a
sample exported resume and cover letter (fake person) are in [docs/samples/](docs/samples/).

## Getting started

Everything runs on your own computer. Each person uses their own Anthropic API key and pays for their own
usage; nothing is shared between users.

### 1. Install Java 21

- **macOS:** `brew install --cask temurin@21`, or download Temurin 21 from <https://adoptium.net>.
- **Windows:** install Temurin 21 from <https://adoptium.net>. Below, use `mvnw.cmd` wherever it says `./mvnw`. (Built and tested on macOS; Windows should work but has not been tried yet.)
- **Linux:** your package manager's OpenJDK 21, or Temurin 21.

Check with `java -version` (it should say 21). You do not need to install Maven: the project brings its own
(`./mvnw`).

### 2. Get the code

```bash
git clone https://github.com/<owner>/tailorsg.git
cd tailorsg
```

### 3. Try it first in demo mode (no key, no cost)

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--tailorsg.llm.demo-mode=true --spring.datasource.url=jdbc:h2:mem:demo"
```

Open <http://localhost:8080>, go to *Master Resume* and click **Load sample**, then follow
[How to use it](#how-to-use-it). Every page shows a yellow **DEMO MODE** banner: model answers come from
`llm/demo/DemoLlmTransport`, which builds them from your own data with simple rules (it copies master
highlights verbatim, finds known skills in the posting, and so on). Nothing is sent to Anthropic and nothing
costs money; the checks, rendering and export are the real ones. Resume import is not available in demo mode.
The in-memory database disappears when you stop the app (`Ctrl+C`).

### 4. Get your own Anthropic API key

1. Sign up at <https://platform.claude.com> (the Claude Console). This is separate from a claude.ai chat
   subscription: a Pro or Max plan does not pay for API use.
2. Add prepaid credit under Settings, Billing. A new key may be refused until billing is set up.
3. Settings, API Keys, **Create Key**. Copy it straight away: it is shown only once.
4. Set a monthly spend limit in the console.

Rough cost: about US$0.10-0.30 per job application (job extraction, tailoring, fit advice, cover letter), an
estimate. The app's footer and Settings page show the estimated cost of every call you make.

### 5. Run it for real

macOS / Linux:

```bash
cp .env.example .env            # .env is git-ignored; put your key in it: ANTHROPIC_API_KEY=sk-ant-...
set -a; source .env; set +a     # load it into this terminal (repeat in every new terminal)
./mvnw spring-boot:run
```

Windows (PowerShell):

```powershell
$env:ANTHROPIC_API_KEY = "sk-ant-..."
.\mvnw.cmd spring-boot:run
```

Open <http://localhost:8080>, then Settings, **Test Claude connection** (costs well under a cent). Then
*Master Resume*, **Import** your current resume. The app reads the key from the `ANTHROPIC_API_KEY` environment
variable only; it never reads `.env` itself.

Stop with `Ctrl+C`. Your data is in `./data/` (the database `./data/tailorsg.mv.db` and exported files in
`./data/exports/`). Back it up if it matters to you; delete the folder to start over.

## Your data and privacy

- **It stays on your computer.** The server only listens on 127.0.0.1, so nobody else on your network can open
  it. `./data/` and `.env` are git-ignored: never commit them, and never share your key.
- **What goes to Anthropic.** When you use an AI feature (resume import, job extraction, tailoring, fit advice,
  cover letter, free-text updates), the text it needs, including your resume, is sent to Anthropic's API under
  your own key. Before anything is sent or stored, lines that look like an NRIC/FIN, a date of birth and
  similar personal data are removed. Demo mode sends nothing.
- **Advice, not guarantees.** The scores are heuristics from published guidance, not a real employer's ATS.
  The Employment Pass salary figures in `application.yml` (`tailorsg.fit`) change over time: check
  <https://www.mom.gov.sg> before relying on them. Not affiliated with Anthropic, MOM, TAFEP or any university.

## How to use it

1. **Master resume** (once, then keep it growing). *Master Resume, Import* your current PDF/DOCX, review every
   "check this" note, apply. Then *Master Resume, Health*: fix lint findings, and add metrics where it says
   "add metric".
2. **New Application.** Paste a job link (MyCareersFuture, Greenhouse and Lever work best; LinkedIn is blocked
   by design), upload screenshots, or paste the text. Check the extracted job: move skills between Required and
   Preferred, fix the title and company.
3. **Set up the application** (bottom of the job page): page target, summary, work-authorisation line, cover
   letter notes. *Create application and tailor.*
4. **Resume tab.** Read the preview beside the scorecard. Hover a bullet to see the master text it cites.
   Fix every ERROR (TRUTH errors mean something is not backed by your master). Use the keyword panel's
   "Add this bullet" only for terms your master already shows.
5. **Fit tab** (advisory). The facts come from code; the "Should you apply?" advice comes from the model and is
   checked against those facts. It never changes a check result.
6. **Cover letter tab.** Write in your own words why you want this company, then generate. Fix dashes with one
   click (you see the before and after). Re-check after edits.
7. **Export tab.** Export PDF and/or DOCX. Errors block export unless you type a reason (it is stored with the
   file). Files land in `./data/exports/Company_Title_date/`. Set the status (APPLIED, INTERVIEW...) on the
   dashboard tracker.
8. **Keep the master current.** *Add Info, Free text*: describe new work in your own words; answer the
   model's questions; review the diff; apply.

## Test

```bash
./mvnw test                          # all tests; the live API test runs only if ANTHROPIC_API_KEY is set
./mvnw test -DexcludedGroups=live    # never call the real API
./mvnw test -Dtest=ClaudeClientTest  # one class
```

Tests use an in-memory database (the `test` profile is set in `pom.xml`) and never touch `./data`.

## Manual test script

### Phase 0: scaffolding

Run each step in order. Expected results are in *italics*.

1. **Tests pass without a key.** In a shell without `ANTHROPIC_API_KEY`: `./mvnw test`.
   *`Failures: 0, Errors: 0, Skipped: 2` (the two live API tests: ping and schema compile).*
2. **App starts without a key.** `./mvnw spring-boot:run`.
   *Log ends with `Started TailorSgApplication`. `./data/tailorsg.mv.db` now exists.*
3. **Health endpoint.** `curl -s localhost:8080/api/health`
   *`"status":"UP"`, `"database":"UP"`, `"apiKeyConfigured":false`, both model IDs, `"sessionCostUsd":"$0.0000"`.*
4. **Pages.** Open <http://localhost:8080> and click every nav link.
   *Dashboard shows Database "Connected" and Claude API key "Not set". The footer reads "LLM spend this session:
   $0.0000 across 0 calls · all time $0.0000 (0)".*
5. **Settings without a key.** Open Settings.
   *The models table shows writer `claude-sonnet-5-5` (high, 16000, 2.00 / 10.00) and extractor
   `claude-haiku-5-5` (medium, 8000, 0.10 / 0.50). "Test Claude connection" is greyed out.*
6. **Ping without a key.** `curl -s -i -X POST localhost:8080/api/llm/ping`
   *HTTP 503, `"status":"NOT_CONFIGURED"`, error says ANTHROPIC_API_KEY is not set.*
7. **Local-only guard.**
   `curl -s -i -X POST -H 'Origin: https://evil.example' localhost:8080/api/llm/ping` gives *403 Forbidden: Cross-origin POST blocked*;
   `curl -s -i -H 'Host: evil.example' localhost:8080/api/health` gives *403 Forbidden: Host 'evil.example' is not allowed*.
8. **With a key.** Stop the app, run `set -a; source .env; set +a`, start it again.
   *Dashboard shows Claude API key "Configured".*
9. **Ping from the UI.** Settings, then **Test Claude connection**.
   *Within a few seconds a green box: "Connected. claude-haiku-5-5 replied ..." with latency, tokens, cost and
   "attempt 1". The footer total updates without a page reload (htmx out-of-band swap). The app log has a
   line `LLM call prompt=ping v1 model=claude-haiku-5-5 tokens[in=.. out=..] cost=$0.0000.. sessionTotal=..`.*
10. **Ping from curl.** `curl -s -X POST localhost:8080/api/llm/ping`
    *HTTP 200, `"status":"OK"`, your model, a short greeting, `"attempts":1`.*
11. **Live test.** With the key exported: `./mvnw test -Dtest=ClaudePingLiveTest`
    *1 test run, 0 skipped, passes.*
12. **No secrets in the repo.** `git status` does not list `.env` or `data/`, and
    `grep -rnE "sk-ant-[A-Za-z0-9_-]{20,}" --exclude-dir=target --exclude-dir=data --exclude-dir=.git --exclude=.env .` prints nothing.

### Phase 1: master resume

Try this phase on a **throwaway in-memory database** first, so the fake sample never lands in your real
`./data`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:mem:try"
```

1. **Tests.** `./mvnw test` *All pass, 2 skipped (live API tests without a key).*
2. **Empty state.** Open <http://localhost:8080/master>.
   *"No master resume yet" and a **Load sample (fake data)** button. Dashboard shows Master resume "Not created".*
3. **Load the sample.** Click **Load sample**.
   *Green message "Loaded the sample master resume (fake data) as version 1". The editor shows Jordan Tan's JSON and
   "Version 1 · saved ...". The address bar is plain `/master` (no `;jsessionid`).*
4. **Errors are collected and explained.** In the editor, change `"phone"` to `"9123 4567"`, add
   `"dateOfBirth": "1995-01-01"` inside `basics`, and change `"startDate": "2026-05"` of `work-1` to `"May 2026"`.
   Click **Validate only**.
   *Red box "3 problems. Nothing was saved." with a PRIVACY error at `/basics/dateOfBirth` and SCHEMA errors
   "use E.164 ..." and "use YYYY-MM, e.g. 2024-05". No raw regexes. Undo the three edits.*
5. **IDs are assigned for you.** In `work-1`'s `highlights`, add
   `{"id": "NEW-1", "text": "Wrote Helm charts for the refund service"}`, and give the Kubernetes skill
   (`skill-11`) `"evidenceIds": ["NEW-1"]`. Type a change note and click **Save new version**.
   *"Saved as version 2. New IDs assigned: work-1.h4". The textarea now shows `"id": "work-1.h4"` and the
   skill's evidence says `"work-1.h4"` (no page reload).*
6. **Cited highlights can't silently disappear.** Delete the whole `work-2` object and click **Validate only**.
   *INTEGRITY errors: skills cite `work-2.h1` / `work-2.h2`, which no longer exist. Undo.*
7. **IDs are never reused.** Delete the `certificates` entry (`cert-1`) and save (version 3). Then add
   `{"id": "cert-1", "name": "Another cert", "issuer": "X"}` to `certificates` and validate.
   *IDS error "'cert-1' was used before and has been retired". Remove the `"id"` line and validate again:
   "IDs that would be assigned: cert-2".*
8. **Unchanged saves are no-ops.** Reload `/master` and click **Save new version** without editing.
   *"No changes, so still version 3."*
9. **History and diff.** Open the **History** tab.
   *v3 (current), v2, v1 with notes. "Changes" on v2 shows two ADDED rows:
   `work › work-1 › highlights › work-1.h4` and `skills › skill-11 › evidenceIds`. Compare v1 to v3 with the selects.*
10. **Restore.** Open v1 and click **Restore as a new version**.
    *"Restored version 1 as new version 4." `cert-1` is back (restoring may reuse that version's IDs).*
11. **API.**
    `curl -s localhost:8080/api/master | head -c 300` gives *the current JSON*.
    `curl -s localhost:8080/api/master/versions` gives *4 entries, newest first*.
    `curl -s "localhost:8080/api/master/diff?from=3&to=4"` gives *the changes as JSON*.
    `curl -s -X PUT -H 'Content-Type: application/json' -d '{"basics":{"name":"A","email":"x"}}' localhost:8080/api/master`
    gives *HTTP 422, `application/problem+json`, with an `errors` array (email message)*.
12. **Your real resume.** Stop the throwaway app and start normally (`./mvnw spring-boot:run`). Paste your own
    master JSON into the editor (omit all `id`s and let the server assign them), or wait for Phase 2's
    PDF/DOCX import and form.

### Phase 2: updates, forms and import

Again on a throwaway database, with the sample loaded:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:mem:try"
# then open http://localhost:8080/master and click Load sample
```

1. **Tests.** `./mvnw test` *All pass, 2 skipped (live API tests).*
2. **Readable view.** Master Resume, then **View**.
   *Jordan Tan's resume in reading order: dates like "May 2026 - Present", IDs such as `work-1.h1` next to each
   highlight, green metric chips, skills grouped by category, Kubernetes with a dashed "no evidence" chip.*
3. **Form validation.** Add Info, then **Work**. Leave the job title empty, set Start to a date, and submit.
   *"Job title is required" under the field; nothing is proposed.* On the **Skill** tab enter `java`,
   category Languages: *"You already have the skill Java (skill-1)."*
4. **Propose a highlight.** **Highlight** tab: belongs to `work-1`, text
   `Added Grafana alerts on Redis memory pressure, cutting cache-related incidents by 50%`, metric `50` / `%` /
   `incident reduction`, select **Redis** under Skills it shows, and add new skill `Grafana` (Tools). Submit.
   *You land on "Update #1 proposed" with three ADDED rows: the highlight `work-1.h4`, skill `skill-14` Grafana,
   and `work-1.h4` added to Redis's evidence. "2 new IDs on apply: work-1.h4, skill-14".*
5. **Privacy still applies.** Back on the Highlight tab, enter text containing `NRIC S1234567D` and submit.
   *Red box "nothing was proposed" with a PRIVACY error.*
6. **Edit the patch.** On update #1, open **Edit the patch**, change the highlight's `"priority": 2` to `3`, and
   click **Preview changes**. *The changes table updates with a "not saved" note.* Click **Save patch**.
   *"Patch saved."* Type invalid JSON and Preview: *"does not apply ... Line 1, column ..."*.
7. **Apply.** Click **Apply to master resume**.
   *"Applied. Your master resume is now version 2; new IDs: work-1.h4, skill-14." The badge says applied, with a link
   to the v1 to v2 diff. View now shows the new highlight and Grafana with 1 evidence.*
8. **Stale updates are caught.** Propose another highlight for `work-1` (don't apply). In the editor, move the
   `work-2` object above `work-1` and save. Open the proposed update.
   *"This patch does not apply to the current master resume. Operation 1 (test /work/0/id): expected "work-1" but
   found "work-2"." Apply is disabled.* Reject it.
9. **Updates list.** **Updates** tab. *Both updates with status badges; filter chips work.* The dashboard's master
   card shows "N updates to review" while any are proposed.
10. **API.**
    `curl -s -X POST localhost:8080/api/updates/form -H 'Content-Type: application/json' -d '{"type":"award","title":"Dean'"'"'s List"}'`
    gives *201 with `preview.canApply: true`*. Use its `id`:
    `curl -s -X POST localhost:8080/api/updates/<id>/apply` gives *`masterVersionNo`*, and repeating it gives *409*.
11. **Import (needs `ANTHROPIC_API_KEY`).** Without a key, the Import tab explains why the button is disabled.
    With a key, on a fresh throwaway DB, upload your resume PDF or DOCX.
    *You land on a review page with a "Check these" list: lines removed for privacy, model warnings, assumed
    +65, year-only dates set to January, and any name, title, bullet or number not found in your file. Open
    "Resulting master resume" and compare it against your resume line by line before applying.* Settings,
    "Recent model calls" shows *two calls, `master-import-profile` and `master-import-entries` (together well
    under a cent)*: the import is split in two because the API rejects the whole schema as too large.
    A scanned or image-only PDF gives *"No readable text found..."*.
12. **Your real data.** When happy, run normally (`./mvnw spring-boot:run`), import your real resume, review every
    note, and apply.

### Phases 3-9: checks, jobs, tailoring, fit, export, cover letter, free text, polish

The quickest way to walk through all of it is demo mode on a throwaway database (no key, no cost):

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--tailorsg.llm.demo-mode=true --spring.datasource.url=jdbc:h2:mem:try"
# then open http://localhost:8080/master and click Load sample
```

Repeat the LLM steps later with a real key (`set -a; source .env; set +a`, start without demo mode) to see
Claude's own output go through the same checks.

**Phase 3: CheckEngine and master health**

1. **Tests.** `./mvnw test` *All pass, 2 skipped (live API tests).* `CheckRuleFixturesTest` runs a passing and a failing JSON
   fixture for every rule (`src/test/resources/fixtures/checks/`).
2. **Health.** Master Resume, **Health**. *Scorecard with "Singapore norms" and "Bullet quality" panels,
   PASS/WARN/FAIL badges and 0-100 sub-scores, the banner "Scores are heuristics from published guidance, not a
   real employer's ATS", and "Kubernetes (skill-11)" under skills without evidence.*
3. **Add metric loop.** Click **add metric** next to a LINT-02 finding. *The Add Info metric form opens with that
   highlight's text.*
4. **Live lint.** Add Info, **Highlight**, type `Responsible for helping with the API`. *Within a second the
   preview shows LINT-01 (weak opener) and LINT-03 (too short).*

**Phase 4: job descriptions**

5. **Paste.** New Application, **Paste**: paste a real posting (100+ characters). *You land on "Step 2 of 3"
   with Required and Preferred skill chips, details and company facts.* Move a skill with "to preferred →".
6. **Blocked site.** **Link** tab, `https://www.linkedin.com/jobs/view/1`. *"This site blocks automated
   fetching. Paste the JD text or upload screenshots. (Reason: LinkedIn requires a login.)"*
7. **Real link** (needs a key, not demo). A MyCareersFuture, Greenhouse or Lever posting URL. *The fetch note
   names the adapter used.*

**Phase 5: tailoring and the TRUTH checks**

8. **Tailor.** On the job page, keep the options and click **Create application and tailor**. *The Resume tab:
   preview (employer, title and dates exactly as in your master) beside the scorecard with "No fabrication"
   PASS. Hover a bullet: its source IDs and master text appear.*
9. **Catch a fabrication.** Click **Edit** on the first experience bullet, write
   `Built Kafka pipelines for Grab handling 2,000 refunds a day with Spring Boot`, **Save and re-check**.
   *Without a page reload: TRUTH-02 (number 2000), TRUTH-03 (Apache Kafka) and TRUTH-04 (Grab) as ERRORs; "jump
   to" highlights the bullet.* Edit it back.
10. **Keyword panel.** *Matched (green), in your master but not used (amber, with an editable "Add this bullet"
    box citing its source), gaps (grey, never to be added).*

**Phase 5b: fit (advisory)**

11. **Fit tab.** *"Facts, from code": required/preferred coverage, years of software experience, earliest
    start, an Employment Pass salary note, and every job term marked EVIDENCED / LISTED / UNEVIDENCED /
    MENTIONED / GAP.* Click **Get advice**. *"Should you apply? ADVISORY" with a recommendation, reasons,
    strengths citing highlight IDs, honest ways to address gaps, and a 1-2 week project idea that targets a
    real gap. Anything the model could not back up is listed under "What code removed or flagged".*

**Phase 6: export**

12. **Export.** Export tab, **Check only**. *"Last export check" with an "ATS parseability" panel and the "What an
    ATS would likely extract" table (all ok).* Click **Export** for PDF, then DOCX. *Both in "Exported files"
    with page count and SHA-256; download them; the PDF is one page, Liberation Sans 11pt, dates right-aligned,
    real bullets.*
13. **Blocker.** Re-create the fabricated bullet from step 9 and Export. *"Export blocked: ... ERROR finding(s)".*
    Type a reason of 10+ characters and Export. *Saved; the reason shows in the files table.*

**Phase 7: cover letter**

14. **Write.** Cover letter tab: fill "Why this company" and availability, **Write cover letter**. *Letter with
    your contact line, today's date, "Hiring Manager" and the company, "Application for <job title>", paragraphs
    (hover for sources), "Yours sincerely," and your name. Checks: No fabrication + Cover letter.*
15. **Dashes.** Edit a paragraph to include `small — but useful`. *CL-01 ERROR and a "Fix dashes" box showing
    before and after.* **Apply the fix**. *The dash becomes a comma; CL-01 is gone.*
16. **Export** the letter as PDF. *It appears on the Export tab as `..._Cover_Letter_....pdf`.*

**Phase 8: free-text updates**

17. **Describe it.** Add Info, **Free text**: `In my internship I moved the nightly batch job from cron to Spring
    Batch, and failures dropped from 4 a month to none.` **Propose a change**. *Review page with "What you wrote",
    a diff, and "Check these" (questions, and any number or skill not in your text).*
18. **Answer.** Type an answer and **Update the proposal**. *A new update replaces the old one (now rejected).*
    Apply it. *Your master gets a new version.*

**Phase 9: polish**

19. **Tracker.** Dashboard. *Applications table with checks summary; change a status in the dropdown.*
20. **Prompt versions.** *The application header shows "tailor prompt v1", "fit-advice prompt v1",
    "cover-letter prompt v1".*
21. **Cost.** *The footer shows this session's and all-time spend; Settings, "Recent model calls" lists each call
    with tokens and cost (never the text).*
22. **Thresholds.** Settings, **Check thresholds**: change `"maxWords": 28` to `30`, **Save thresholds**.
    *"Thresholds saved."* Set `minWords` above `maxWords`: *"Not saved: lint.maxWords must be greater than
    lint.minWords."* **Reset to defaults**.
23. **No secrets.** `grep -rnE "sk-ant-[A-Za-z0-9_-]{20,}" --exclude-dir=target --exclude-dir=data --exclude-dir=.git --exclude=.env .`
    *prints nothing.*

H2 console (optional): <http://localhost:8080/h2-console>, JDBC URL `jdbc:h2:file:./data/tailorsg`,
user `sa`, blank password. Stop the app first if another tool needs the DB file.

## Configuration

All app settings live under `tailorsg.*` in [src/main/resources/application.yml](src/main/resources/application.yml):

| Key | Meaning |
|---|---|
| `tailorsg.models.writer` / `.extractor` | Model IDs (tailoring and cover letters / extraction and parsing) |
| `tailorsg.llm.{writer,extractor}.effort` | `low`, `medium`, `high`, `xhigh` or `max` |
| `tailorsg.llm.{writer,extractor}.max-tokens` | Output cap per response (includes thinking) |
| `tailorsg.llm.timeout`, `.max-retries` | HTTP timeout; SDK retries on 429/5xx/network errors |
| `tailorsg.pricing.<model-id>.*` | USD per million tokens, for the cost estimate only |
| `tailorsg.llm.demo-mode` | `true`: answer every prompt locally (no key, no cost, banner on every page) |
| `tailorsg.llm.writer.refusal-fallback` | Opt the writer model into the server-side refusal fallback (beta) |
| `tailorsg.storage.exports-dir` | Where exported files go (`./data/exports`) |
| `tailorsg.jobs.*` | Job fetching: timeout, user agent, minimum text, adapter API bases |
| `tailorsg.fit.ep-thresholds` | Employment Pass minimum salaries by date (check MOM each year) |
| `tailorsg.checks.*` | Every check threshold and word list (can also be edited on the Settings page) |

## API

| Method and path | Purpose |
|---|---|
| `GET /api/health` | App, database, master version, API key present, session cost |
| `POST /api/llm/ping` | One tiny real call to the extractor model |
| `GET /api/master` | Current master resume JSON (404 if none) |
| `PUT /api/master?note=...` | Replace it, creating a new version (422 with every error if invalid) |
| `POST /api/master/validate` | Run every check, save nothing |
| `POST /api/master/sample` | Load the fake sample (empty database only, else 409) |
| `GET /api/master/versions` | Version list, newest first |
| `GET /api/master/versions/{n}` | One version's JSON |
| `POST /api/master/versions/{n}/restore` | Save version n again as the newest version |
| `GET /api/master/diff?from=&to=` | ID-aware changes between two versions |
| `POST /api/master/import` (multipart `file`) | PDF/DOCX resume becomes a proposed update (needs API key) |
| `POST /api/updates/form` | `{"type": "work"\|"project"\|"education"\|"skill"\|"certificate"\|"award"\|"highlight", ...}` becomes a proposed update |
| `GET /api/updates?status=` | Updates, newest first (PROPOSED, APPLIED, REJECTED) |
| `GET /api/updates/{id}` | One update with its patch, notes and live preview |
| `PATCH /api/updates/{id}` | Replace its proposed patch (body: an RFC 6902 array) |
| `POST /api/updates/{id}/apply` | Apply to the master (409 if stale or already resolved, 422 if invalid) |
| `POST /api/updates/{id}/reject` | Reject it |
| `POST /api/updates/free-text` | `{"text": "..."}` becomes a proposed update with questions (needs API key) |
| `GET /api/master/health` | Lint + Singapore checks on the whole master |
| `POST /api/jobs/from-url`, `/from-text`, `/from-image` (multipart `files`) | Job posting into a JobSpec |
| `GET /api/jobs`, `GET /api/jobs/{id}`, `PUT /api/jobs/{id}` | List, read, correct a JobSpec |
| `POST /api/applications` | `{"jobId": 1, "options": {"pageTarget": 1, "includeSummary": true, ...}}` |
| `GET /api/applications`, `GET /api/applications/{id}` | Tracker list; one application |
| `POST /api/applications/{id}/tailor` | Tailoring prompt, then the checks |
| `POST /api/applications/{id}/check` | Re-run the checks |
| `PUT /api/applications/{id}/resume` | Replace the tailored resume (schema-validated, 422 if not) |
| `GET`/`POST /api/applications/{id}/fit` | Fit facts and stored advice / ask for new advice |
| `GET`/`POST /api/applications/{id}/cover-letter` | Read / write the cover letter |
| `POST /api/applications/{id}/export?type=pdf\|docx` | Render, round-trip check, save (409 if blocked; body `{"overrideReason": "..."}`) |
| `GET /api/applications/{id}/exports` | Exported files with SHA-256 and download links |

Errors use RFC 9457 problem JSON (`application/problem+json`).

## Project layout

```
src/main/java/com/bu/tailorsg/
  TailorSgApplication.java
  config/   TailorSgProperties (typed config), LocalOnlyRequestFilter, TimeConfig
  llm/      ClaudeClient (the only LLM entry point), AnthropicTransport (SDK), PromptLibrary,
            JsonSchemaService, ApiSchemaSanitizer, LlmUsageMeter, LlmPing
  master/   MasterResume (records), MasterResumeService (save pipeline, versions), IdAssigner,
            ForbiddenContentValidator (P6), MasterIntegrityValidator, MasterDiffer,
            MasterResumeVersion + IdCounter (entities), MasterApiController, MasterPageController
  updates/  PendingUpdate (entity), PendingUpdateService (preview / apply), API and page controllers;
            form/: EntryForm (7 types), FormPatchBuilder, AddInfoPageController
  resumeimport/  ResumeImportService, PersonalDataRedactor, ImportMapper, EvidenceLinker, ImportVerifier
  documents/     TextExtractor (PDFBox / POI)
  checks/   CheckEngine, CheckContext, CheckReport, ThresholdService; one package per category:
            sg/ (SG-01..11), lint/ (LINT-01..09), kw/ (KW-01..03, dictionary, matcher), truth/ (TRUTH-01..06),
            ats/ (ATS-01..10, OpenResume-style parser), cl/ (CL-01..06, dash fixer)
  health/   MasterHealthService and the Health page
  jobs/     JobService, JobSpec, JobPosting; source/: MyCareersFuture, Greenhouse, Lever, generic adapters
  tailoring/  TailoredResume, TailoringService (prompt 5.3)
  render/   ResumeDocument (one layout model for preview, PDF, DOCX and checks), ResumeRenderer, TailoredRenderer
  applications/  Application entity, ApplicationService, API and page controllers
  fit/      FitService (facts + verified advisory narrative), FitController
  export/   PdfRenderer (openhtmltopdf + Liberation Sans), DocxRenderer (POI), inspectors, ExportService
  coverletter/  CoverLetterService (prompt 5.4), LetterDocument, CoverLetterController
  updates/freetext/  FreeTextUpdateService (prompt 5.2) and the answer-and-regenerate loop
  llm/demo/ DemoLlmTransport (demo mode)
  json/     JsonText (strict parse, pretty-print), JsonPatch + JsonPointer (RFC 6902 / 6901)
  web/      HealthController, LlmController, PageController (dashboard, settings), LayoutAdvice,
            SystemStatusService, ResumeView, UploadLimitAdvice
src/main/resources/
  application.yml
  prompts/        versioned system prompts (*.md with front matter)
  schema/         master-resume.schema.json; llm/ holds JSON Schemas for LLM responses
  samples/        master-sample.json (fake person, used by tests and "Load sample")
  fonts/          Liberation Sans (SIL Open Font License, see LICENSE-LiberationSans.txt), embedded in PDFs
  checks/, skills/  action verbs; keyword dictionary (tech_skills.csv, extend freely)
  templates/      Thymeleaf pages, layout.html fragments, fragments/ for htmx
  static/css/     app.css
src/test/java/...  unit tests, web-layer tests (MockMvc), live API test (tag "live")
src/test/resources/fixtures/   checks/ (one JSON per rule), jobs/ (real saved API responses), canned model outputs
docs/SPEC.md       the full specification
docs/screenshots/  every page, taken in demo mode with the fake sample
```

## Troubleshooting

- **Port 8080 in use:** `lsof -nP -iTCP:8080 -sTCP:LISTEN` to find it, or run with `--server.port=8081`
  (`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`).
- **"Database may be already in use":** another TailorSG instance (or the H2 console in another tool) has
  the file open. Stop it.
- **Key exported but the dashboard says "Not set":** the app was started from a different shell. Export the
  key in the same terminal, then restart.
- **A PDF looks different from the preview:** the PDF uses the embedded Liberation Sans (metric-compatible
  with Arial); the browser preview uses your system's Arial. Line breaks can differ slightly.
- **TRUTH-04 flags a real name:** the name is not anywhere in your master. Add it to the master (it is a fact
  about you), then re-check; or export with a typed reason.
- **Ping says model not found (404):** a model ID in `tailorsg.models.*` is wrong or retired. Check
  <https://platform.claude.com/docs/en/about-claude/models/overview>.

## Licence

MIT, see [LICENSE](LICENSE). The bundled Liberation Sans fonts (`src/main/resources/fonts/`, embedded in
exported PDFs) are under the SIL Open Font License 1.1, see
[LICENSE-LiberationSans.txt](src/main/resources/fonts/LICENSE-LiberationSans.txt).
