package com.bu.tailorsg.resumeimport;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Deterministic clean-up of the extractor model's output into the master resume shape.
 *
 * <p>The import schema makes every field required ("" or [] when absent) to fit the API's limits, so
 * this removes the empty values, converts dates and phone numbers, and adds a warning for every
 * assumption it makes, so the user can check each one on the review page. It never invents content:
 * it only reformats or drops what the model returned.
 */
@Component
public class ImportMapper {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern YEAR_MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern YEAR = Pattern.compile("^\\d{4}$");
    private static final Pattern BARE_DOMAIN = Pattern.compile("^(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> WORK_AUTH_TEXT = Map.of(
            "EP_REQUIRED", "Work authorisation: Employment Pass required",
            "EP_HOLDER", "Work authorisation: Employment Pass holder",
            "PR", "Work authorisation: Singapore Permanent Resident",
            "CITIZEN", "Work authorisation: Singapore Citizen");

    /** @param warnings things the user should check, in plain English */
    public record Mapped(ObjectNode document, List<String> warnings) {}

    public Mapped map(JsonNode llm) {
        List<String> warnings = new ArrayList<>();
        ObjectNode doc = NODES.objectNode();
        doc.set("basics", basics(llm.path("basics"), warnings));
        list(doc, "work", experiences(llm.path("work"), "Experience", warnings));
        list(doc, "projects", mapEach(llm.path("projects"), (p, out) -> project(p, out, warnings)));
        list(doc, "education", mapEach(llm.path("education"), (e, out) -> education(e, out, warnings)));
        list(doc, "skills", skills(llm.path("skills")));
        list(doc, "certificates", mapEach(llm.path("certificates"), (c, out) -> {
            copyText(c, out, "name", "issuer");
            date(c, out, "date", "Certificate " + c.path("name").stringValue(""), warnings);
            url(c, out, "url", warnings);
        }));
        list(doc, "awards", mapEach(llm.path("awards"), (a, out) -> {
            copyText(a, out, "title", "awarder");
            date(a, out, "date", "Award " + a.path("title").stringValue(""), warnings);
            copyText(a, out, "summary");
        }));
        list(doc, "languages", mapEach(llm.path("languages"), (l, out) -> copyText(l, out, "language", "fluency")));
        list(doc, "volunteer", experiences(llm.path("volunteer"), "Volunteering", warnings));
        list(doc, "extras", mapEach(llm.path("extras"), (x, out) -> {
            copyText(x, out, "type", "text");
            date(x, out, "date", "Extra \"" + x.path("text").stringValue("") + "\"", warnings);
        }));
        return new Mapped(doc, warnings);
    }

    // ---------------------------------------------------------------- sections

    private ObjectNode basics(JsonNode in, List<String> warnings) {
        ObjectNode out = NODES.objectNode();
        copyText(in, out, "name", "label", "email");
        phone(in.path("phone").stringValue("").strip(), out, warnings);
        url(in, out, "url", warnings);
        String city = in.path("city").stringValue("").strip();
        if (!city.isEmpty()) {
            String country = in.path("countryCode").stringValue("").strip().toUpperCase(Locale.ROOT);
            if (country.isEmpty() && city.equalsIgnoreCase("Singapore")) {
                country = "SG";
            }
            out.putObject("location").put("city", city).put("countryCode", country);
        }
        list(out, "profiles", mapEach(in.path("profiles"), (p, o) -> {
            copyText(p, o, "network", "username");
            url(p, o, "url", warnings);
        }));
        String summary = in.path("summary").stringValue("").strip();
        if (!summary.isEmpty()) {
            out.putArray("summaryVariants").addObject().put("text", summary);
        }
        String auth = in.path("workAuthorization").stringValue("");
        if (WORK_AUTH_TEXT.containsKey(auth)) {
            out.putObject("workAuthorization")
                    .put("status", auth).put("displayText", WORK_AUTH_TEXT.get(auth)).put("showOnResumeDefault", true);
        } else if (auth.equals("OTHER")) {
            warnings.add("The resume mentions a work authorisation that TailorSG could not classify. Add it in the editor if you want it shown.");
        }
        return out;
    }

    private ArrayNode experiences(JsonNode items, String section, List<String> warnings) {
        ArrayNode out = NODES.arrayNode();
        int index = 0;
        for (JsonNode in : items.values()) {
            ObjectNode item = NODES.objectNode();
            String label = section + " \"" + in.path("position").stringValue("") + ", " + in.path("name").stringValue("") + "\"";
            copyText(in, item, "name", "position", "location", "employmentType");
            date(in, item, "startDate", label, warnings);
            date(in, item, "endDate", label, warnings);
            if (!item.has("endDate") && index > 0) {
                warnings.add(label + " has no end date, so it will be treated as a current role. Add the end date if it has ended.");
            }
            copyText(in, item, "summary");
            highlights(in, item);
            if (!item.isEmpty()) {
                out.add(item);
                index++;
            }
        }
        return out;
    }

    private void project(JsonNode in, ObjectNode out, List<String> warnings) {
        String label = "Project \"" + in.path("name").stringValue("") + "\"";
        copyText(in, out, "name", "role");
        url(in, out, "url", warnings);
        date(in, out, "startDate", label, warnings);
        date(in, out, "endDate", label, warnings);
        copyText(in, out, "description");
        highlights(in, out);
        list(out, "keywords", strings(in.path("keywords")));
    }

    private void education(JsonNode in, ObjectNode out, List<String> warnings) {
        String label = "Education \"" + in.path("studyType").stringValue("") + ", " + in.path("institution").stringValue("") + "\"";
        copyText(in, out, "institution", "area", "studyType");
        date(in, out, "startDate", label, warnings);
        date(in, out, "endDate", label, warnings);
        copyText(in, out, "score", "scoreScale");
        list(out, "courses", strings(in.path("courses")));
        highlights(in, out);
    }

    private ArrayNode skills(JsonNode items) {
        ArrayNode out = NODES.arrayNode();
        Set<String> seen = new HashSet<>();
        for (JsonNode in : items.values()) {
            String name = in.path("name").stringValue("").strip();
            if (!name.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT))) {
                out.addObject().put("name", name).put("category", in.path("category").stringValue("Tools"));
            }
        }
        return out;
    }

    private void highlights(JsonNode in, ObjectNode out) {
        ArrayNode highlights = NODES.arrayNode();
        for (JsonNode h : in.path("highlights").values()) {
            String text = h.path("text").stringValue("").strip();
            if (text.isEmpty()) {
                continue;
            }
            ObjectNode highlight = highlights.addObject().put("text", text);
            list(highlight, "metrics", mapEach(h.path("metrics"), (m, o) -> {
                if (!m.path("value").stringValue("").isBlank() && !m.path("label").stringValue("").isBlank()) {
                    copyText(m, o, "value", "unit", "label");
                }
            }));
            list(highlight, "skills", strings(h.path("skills")));
        }
        list(out, "highlights", highlights);
    }

    // ---------------------------------------------------------------- value conversions

    /** "YYYY-MM" kept; "YYYY" becomes "YYYY-01" with a warning; anything else is dropped with a warning. */
    private static void date(JsonNode in, ObjectNode out, String key, String label, List<String> warnings) {
        String value = in.path(key).stringValue("").strip();
        if (value.isEmpty()) {
            return;
        }
        if (YEAR_MONTH.matcher(value).matches()) {
            out.put(key, value);
        } else if (YEAR.matcher(value).matches()) {
            out.put(key, value + "-01");
            warnings.add(label + ": only the year was given for " + key + " (" + value + "), so it was set to January. Correct the month.");
        } else {
            warnings.add(label + ": could not read " + key + " \"" + value + "\". Add it as YYYY-MM.");
        }
    }

    /**
     * Spaces and dashes are removed. An 8-digit number starting 6, 8 or 9 is a Singapore number, so it gets
     * +65 (with a warning). Anything else is kept as written for the user to fix in review.
     */
    private static void phone(String raw, ObjectNode out, List<String> warnings) {
        if (raw.isEmpty()) {
            return;
        }
        String compact = raw.replaceAll("[\\s().-]", "");
        if (compact.matches("^\\+[1-9]\\d{7,14}$")) {
            out.put("phone", compact);
        } else if (compact.matches("^[689]\\d{7}$")) {
            out.put("phone", "+65" + compact);
            warnings.add("Phone " + raw + " had no country code; assumed Singapore (+65).");
        } else if (compact.matches("^0065\\d{8}$")) {
            out.put("phone", "+" + compact.substring(2));
        } else {
            out.put("phone", raw);
            warnings.add("Phone \"" + raw + "\" is not in +country-code format (e.g. +6591234567). Fix it before applying.");
        }
    }

    /** Adds https:// to bare domains such as github.com/jordan (with a warning); drops values that aren't links. */
    private static void url(JsonNode in, ObjectNode out, String key, List<String> warnings) {
        String value = in.path(key).stringValue("").strip();
        if (value.isEmpty()) {
            return;
        }
        if (value.matches("^https?://\\S+$")) {
            out.put(key, value);
        } else if (BARE_DOMAIN.matcher(value).matches()) {
            out.put(key, "https://" + value);
            warnings.add("Added https:// to " + value + ".");
        } else {
            warnings.add("\"" + value + "\" does not look like a link, so it was left out.");
        }
    }

    private static void copyText(JsonNode in, ObjectNode out, String... keys) {
        for (String key : keys) {
            String value = in.path(key).stringValue("").strip();
            if (!value.isEmpty()) {
                out.put(key, value);
            }
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.values().forEach(v -> {
            String s = v.stringValue("").strip();
            if (!s.isEmpty() && !values.contains(s)) {
                values.add(s);
            }
        });
        return values;
    }

    private static ArrayNode mapEach(JsonNode items, BiConsumer<JsonNode, ObjectNode> mapper) {
        ArrayNode out = NODES.arrayNode();
        for (JsonNode in : items.values()) {
            ObjectNode item = NODES.objectNode();
            mapper.accept(in, item);
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private static void list(ObjectNode node, String key, ArrayNode values) {
        if (!values.isEmpty()) {
            node.set(key, values);
        }
    }

    private static void list(ObjectNode node, String key, List<String> values) {
        if (!values.isEmpty()) {
            ArrayNode array = node.putArray(key);
            values.forEach(array::add);
        }
    }
}
