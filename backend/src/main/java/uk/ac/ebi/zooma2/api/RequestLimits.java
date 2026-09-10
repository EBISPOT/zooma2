package uk.ac.ebi.zooma2.api;

import java.util.List;

import io.javalin.http.BadRequestResponse;

/**
 * Input caps shared by every API version, each overridable by environment
 * variable. They bound the work one request can demand of the mapping
 * pipeline (and of OLS); the legacy V2 endpoints were bounded only by the
 * request body size until they adopted these.
 */
public final class RequestLimits {

    public static final int MAX_PROPERTIES = envInt("ZOOMA2_MAX_PROPERTIES", 1000, 1, 100_000);
    public static final int MAX_DEEP_PROPERTIES = envInt("ZOOMA2_MAX_DEEP_PROPERTIES", 200, 1, 100_000);
    public static final int MAX_PROPERTY_TEXT_LENGTH = envInt("ZOOMA2_MAX_PROPERTY_TEXT_LENGTH", 1000, 1, 1_000_000);
    public static final int MAX_PROPERTY_TYPE_LENGTH = envInt("ZOOMA2_MAX_PROPERTY_TYPE_LENGTH", 200, 1, 10_000);
    public static final int MAX_ANNOTATE_TEXT_LENGTH = envInt("ZOOMA2_MAX_ANNOTATE_TEXT_LENGTH", 50_000, 1, 5_000_000);
    public static final int MAX_LIST_ITEMS = envInt("ZOOMA2_MAX_FILTER_ITEMS", 200, 1, 100_000);
    public static final int MAX_LIST_ITEM_LENGTH = envInt("ZOOMA2_MAX_FILTER_ITEM_LENGTH", 200, 1, 10_000);
    public static final int MAX_EXCLUDED_TERMS = envInt("ZOOMA2_MAX_EXCLUDED_TERMS", 1000, 1, 100_000);
    public static final int MAX_MODEL_LENGTH = envInt("ZOOMA2_MAX_MODEL_LENGTH", 200, 1, 10_000);

    private RequestLimits() {
    }

    public static void validateStringList(String name, List<String> values, int maxItems, int maxLength) {
        if (values == null) {
            return;
        }
        if (values.size() > maxItems) {
            throw new BadRequestResponse("'" + name + "' cannot contain more than " + maxItems + " items");
        }
        for (int i = 0; i < values.size(); i++) {
            validateString(name + "[" + i + "]", values.get(i), true, maxLength);
        }
    }

    public static void validateString(String name, String value, boolean required, int maxLength) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new BadRequestResponse("'" + name + "' is required and cannot be empty");
            }
            return;
        }
        if (value.length() > maxLength) {
            throw new BadRequestResponse("'" + name + "' cannot be longer than " + maxLength + " characters");
        }
    }

    static int envInt(String name, int defaultValue, int min, int max) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < min || parsed > max) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid " + name + "='" + raw + "'; using " + defaultValue);
            return defaultValue;
        }
    }
}
