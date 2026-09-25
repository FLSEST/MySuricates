package com.automation.service.integration.providers;

import java.util.Map;

/**
 * Whole-number parameters shared by the integration actions — pure functions, no I/O (TODO ac639b39).
 *
 * <p>A number is read like its text, the way {@code TelegramParams} started it: {@code 5}, {@code "5"}
 * and {@code " 5 "} are the same value, while {@code 1.5}, {@code 5.0} and values beyond the target
 * type fail with the parameter's name — as the strings {@code "1.5"} and {@code "5.0"} always did.
 * Before, a JSON number went through {@code intValue()}: {@code limit: 1.5} silently became 1, and a
 * number beyond {@code int} wrapped around to another, possibly negative value.
 *
 * <p>Missing and blank are the same: no value, so the caller's default applies.
 */
public final class IntegrationParams {

    private IntegrationParams() {}

    /** {@code params.get(key)} as int; {@code defaultValue} when missing or blank. */
    public static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Integer value = optionalInt(params.get(key), key);
        return value != null ? value : defaultValue;
    }

    /**
     * {@code value} as int; {@code null} when missing or blank.
     *
     * @throws IllegalArgumentException {@code Parameter '<key>' must be a number} for anything else
     */
    public static Integer optionalInt(Object value, String key) {
        try {
            return parseInt(value);
        } catch (NumberFormatException e) {
            throw notANumber(key);
        }
    }

    /** Like {@link #optionalInt}, for a {@code long}. */
    public static Long optionalLong(Object value, String key) {
        if (isBlank(value)) return null;
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            throw notANumber(key);
        }
    }

    /**
     * {@code value} as int, read like its text; {@code null} when missing or blank. For callers that
     * word the error themselves.
     *
     * @throws NumberFormatException for a decimal, a value beyond {@code int} or anything but digits
     */
    public static Integer parseInt(Object value) {
        return isBlank(value) ? null : Integer.parseInt(value.toString().trim());
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static IllegalArgumentException notANumber(String key) {
        return new IllegalArgumentException("Parameter '" + key + "' must be a number");
    }
}
