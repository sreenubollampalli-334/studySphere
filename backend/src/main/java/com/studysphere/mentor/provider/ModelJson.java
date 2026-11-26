package com.studysphere.mentor.provider;

import java.util.regex.Pattern;

/** Removes an optional Markdown wrapper before the caller validates the JSON schema. */
public final class ModelJson {
    private static final Pattern FENCE = Pattern.compile("(?is)\\A\\x60{3}(?:json)?\\h*\\R(.*?)\\R\\h*\\x60{3}\\z");

    private ModelJson() {}

    public static String unwrap(String output) {
        if (output == null) return "";
        String stripped = output.strip();
        var wrapped = FENCE.matcher(stripped);
        return wrapped.matches() ? wrapped.group(1) : stripped;
    }
}
