package com.automation.util;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A request parameter that names an enum constant, such as the status filter of a list endpoint.
 * Trimmed and in any case; an unknown value fails with the allowed values (TODO 4e7a7401, b22c4db0).
 * A bare {@code Enum.valueOf} answered "No enum constant com.automation.entity.RunStatus.FOO" — a
 * class name, and nothing about what would have been right.
 */
public final class EnumParameter {

    private EnumParameter() {
    }

    /**
     * @return the constant, or {@code null} for a missing or blank value (no filter)
     * @throws IllegalArgumentException for an unknown value, naming the allowed ones (the API answers 400)
     */
    public static <E extends Enum<E>> E parseOptional(Class<E> type, String parameter, String value) {
        return value == null || value.isBlank() ? null : parse(type, parameter, value);
    }

    /** @throws IllegalArgumentException for an unknown value, naming the allowed ones (the API answers 400) */
    public static <E extends Enum<E>> E parse(Class<E> type, String parameter, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(parameter + " muss " + allowed(type) + " sein, nicht '" + value + "'");
        }
    }

    private static String allowed(Class<? extends Enum<?>> type) {
        List<String> names = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        return names.size() == 1 ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " oder " + names.getLast();
    }
}
