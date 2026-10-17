package com.bu.tailorsg.master;

import com.bu.tailorsg.checks.SgPatterns;
import com.bu.tailorsg.master.ValidationError.Kind;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces P6 on the master resume: personal data that Singapore fair-employment practice says not to
 * ask for or show must never even be stored. Spec 2.1: "Validation rejects these keys and the regex
 * patterns in 6A."
 *
 * <p>Runs before schema validation so the user gets a specific "P6" message rather than a generic
 * "unknown property". It scans every key and every string value, wherever it is in the document.
 */
@Component
public class ForbiddenContentValidator {

    /**
     * Keys are compared after lower-casing and removing everything but letters and digits,
     * so "dateOfBirth", "date_of_birth" and "Date-Of-Birth" all match "dateofbirth".
     */
    static final Set<String> FORBIDDEN_KEYS = Set.of(
            "nric", "fin", "nricfin", "nricno", "nricnumber", "ic", "icnumber", "passportnumber",
            "dob", "dateofbirth", "birthdate", "birthday", "age",
            "photo", "photograph", "picture", "image", "avatar", "headshot",
            "religion", "race", "ethnicity", "gender", "sex",
            "maritalstatus", "marital", "children", "numberofchildren", "dependants", "dependents",
            "address", "homeaddress", "residentialaddress", "streetaddress", "postalcode", "postcode", "zip",
            "salary", "expectedsalary", "currentsalary", "lastdrawnsalary",
            "referees", "referee", "references");

    /** Patterns checked against every string value: (label, pattern). A list keeps error order stable. */
    private static final List<Map.Entry<String, Pattern>> VALUE_PATTERNS = List.of(
            Map.entry("an NRIC/FIN number", SgPatterns.NRIC_FIN),
            Map.entry("a date of birth or age", SgPatterns.DOB_OR_AGE),
            Map.entry("a personal-particulars field (marital status, religion, race, gender, children)",
                    SgPatterns.PERSONAL_FIELD_LINE),
            Map.entry("a residential address (keep the city only)", SgPatterns.RESIDENTIAL_ADDRESS));

    /** Header fields where words like "married" or "single" can only mean marital status. */
    private static final Set<String> HEADER_POINTERS = Set.of("/basics/name", "/basics/label");

    public List<ValidationError> check(JsonNode document) {
        List<ValidationError> errors = new ArrayList<>();
        walk(document, "", errors);
        return errors;
    }

    private void walk(JsonNode node, String pointer, List<ValidationError> errors) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String childPointer = pointer + "/" + escape(field.getKey());
                if (FORBIDDEN_KEYS.contains(normaliseKey(field.getKey()))) {
                    errors.add(new ValidationError(childPointer,
                            "'" + field.getKey() + "' must never be stored (P6, Singapore fair-employment hygiene). Remove it.",
                            Kind.PRIVACY));
                    continue; // don't also report the value inside a forbidden key
                }
                walk(field.getValue(), childPointer, errors);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode item : node.values()) {
                walk(item, pointer + "/" + i++, errors);
            }
        } else if (node.isString()) {
            checkText(node.stringValue(), pointer, errors);
        }
    }

    private void checkText(String text, String pointer, List<ValidationError> errors) {
        for (Map.Entry<String, Pattern> check : VALUE_PATTERNS) {
            SgPatterns.firstMatch(check.getValue(), text).ifPresent(match ->
                    errors.add(new ValidationError(pointer,
                            "Looks like " + check.getKey() + ": \"" + match.strip() + "\". This must never be stored (P6).",
                            Kind.PRIVACY)));
        }
        if (HEADER_POINTERS.contains(pointer)) {
            SgPatterns.firstMatch(SgPatterns.MARITAL_WORDS, text).ifPresent(match ->
                    errors.add(new ValidationError(pointer,
                            "\"" + match + "\" in the header reads as marital status (P6). Remove it.", Kind.PRIVACY)));
        }
    }

    static String normaliseKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** JSON Pointer escaping (RFC 6901): "~" becomes "~0" and "/" becomes "~1". */
    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }
}
