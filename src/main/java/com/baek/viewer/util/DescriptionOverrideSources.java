package com.baek.viewer.util;

/**
 * {@link com.baek.viewer.model.ApiRecord#getDescriptionOverrideSource()} 값.
 */
public final class DescriptionOverrideSources {

    public static final String MANUAL = "MANUAL";
    public static final String AI_AUTO = "AI_AUTO";
    public static final String AI_MANUAL = "AI_MANUAL";

    private DescriptionOverrideSources() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case AI_AUTO, "AI_AUTO_FILL", "AUTO" -> AI_AUTO;
            case AI_MANUAL, "AI_MANUAL_EDIT" -> AI_MANUAL;
            case MANUAL, "USER" -> MANUAL;
            default -> s.length() <= 30 ? s : s.substring(0, 30);
        };
    }
}
