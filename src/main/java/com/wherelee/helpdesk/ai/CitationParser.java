package com.wherelee.helpdesk.ai;

import com.wherelee.helpdesk.wiki.WikiHit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the [1] / [2] markers in the model output back to the snippets that were
 * actually sent. Only cited snippets are returned, so the UI does not claim sources
 * the model never used.
 */
public final class CitationParser {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,2})]");

    private CitationParser() {
    }

    public static List<WikiHit> parse(String answer, List<WikiHit> snippets) {
        if (answer == null || answer.isBlank() || snippets == null || snippets.isEmpty()) {
            return List.of();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(answer);
        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }
        List<WikiHit> cited = new ArrayList<>();
        for (Integer id : ids) {
            if (id >= 1 && id <= snippets.size()) {
                cited.add(snippets.get(id - 1));
            }
        }
        return cited;
    }
}
