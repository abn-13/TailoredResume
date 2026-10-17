package com.bu.tailorsg.jobs.source;

import com.bu.tailorsg.jobs.JobsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyCareersFuture (spec 3, adapter 1): the job UUID is the 32-hex suffix of the page path, and
 * GET {api}/v2/jobs/{uuid} returns the posting as JSON. The API is public but undocumented; the mapping
 * here follows a real response saved as fixtures/jobs/mcf-job.json. Any failure falls through to the next
 * adapter.
 */
@Component
@Order(1)
public class MyCareersFutureSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(MyCareersFutureSource.class);
    static final Pattern UUID = Pattern.compile("([0-9a-f]{32})/?$");

    private final HttpFetcher fetcher;
    private final JobsProperties props;
    private final JsonMapper json;

    public MyCareersFutureSource(HttpFetcher fetcher, JobsProperties props, JsonMapper json) {
        this.fetcher = fetcher;
        this.props = props;
        this.json = json;
    }

    public String name() {
        return "MyCareersFuture";
    }

    public boolean supports(URI uri) {
        return uri.getHost() != null && uri.getHost().endsWith("mycareersfuture.gov.sg")
                && uri.getPath() != null && UUID.matcher(uri.getPath()).find();
    }

    public Optional<FetchedJob> fetch(URI uri) {
        Matcher m = UUID.matcher(uri.getPath());
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            HttpFetcher.Response response = fetcher.get(URI.create(props.mcfApiBase() + "/v2/jobs/" + m.group(1)), "application/json");
            if (!response.ok()) {
                log.info("MyCareersFuture API answered {} for {}; falling through", response.status(), m.group(1));
                return Optional.empty();
            }
            return Optional.of(map(json.readTree(response.body())));
        } catch (IOException | JacksonException e) {
            log.info("MyCareersFuture API failed for {}: {}; falling through", m.group(1), e.getMessage());
            return Optional.empty();
        }
    }

    FetchedJob map(JsonNode job) {
        String company = job.path("hiringCompany").path("name").stringValue("");
        if (company.isBlank()) {
            company = job.path("postedCompany").path("name").stringValue("");
        }
        List<String> skills = new ArrayList<>();
        job.path("skills").values().forEach(s -> skills.add(s.path("skill").stringValue("")));
        skills.removeIf(String::isBlank);

        StringBuilder text = new StringBuilder(HtmlText.fromHtml(job.path("description").stringValue("")));
        List<String> types = new ArrayList<>();
        job.path("employmentTypes").values().forEach(t -> types.add(t.path("employmentType").stringValue("")));
        List<String> levels = new ArrayList<>();
        job.path("positionLevels").values().forEach(t -> levels.add(t.path("position").stringValue("")));
        text.append("\n\n");
        if (!types.isEmpty()) {
            text.append("Employment type: ").append(String.join(", ", types)).append('\n');
        }
        if (!levels.isEmpty()) {
            text.append("Position level: ").append(String.join(", ", levels)).append('\n');
        }
        if (job.path("minimumYearsExperience").isNumber()) {
            text.append("Minimum years of experience: ").append(job.get("minimumYearsExperience").asInt()).append('\n');
        }
        if (!skills.isEmpty()) {
            text.append("Skills listed on MyCareersFuture: ").append(String.join(", ", skills)).append('\n');
        }
        JsonNode salary = job.path("salary");
        Double min = salary.path("minimum").isNumber() ? salary.get("minimum").asDouble() : null;
        Double max = salary.path("maximum").isNumber() ? salary.get("maximum").asDouble() : null;
        boolean hidden = job.path("metadata").path("isHideSalary").asBoolean(false);
        return new FetchedJob(name(), job.path("title").stringValue(""), company, "Singapore", text.toString().strip(),
                skills, hidden ? null : min, hidden ? null : max, hidden || min == null ? null : "SGD (monthly)");
    }
}
