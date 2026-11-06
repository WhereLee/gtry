package com.wherelee.helpdesk.wiki;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chinese text has no spaces, so PostgreSQL's default parser would treat a whole
 * sentence as one token. We index ASCII words plus CJK unigrams and bigrams, which
 * gives usable lexical recall without a Jieba/Zhparser extension.
 */
public final class ChineseText {

    private static final Pattern ASCII_WORD = Pattern.compile("[a-zA-Z0-9]+");

    private ChineseText() {
    }

    public static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = ASCII_WORD.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase());
        }
        String cjk = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cjk.length(); i++) {
            tokens.add(cjk.substring(i, i + 1));
            if (i + 1 < cjk.length()) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        return tokens;
    }

    public static String searchText(String text) {
        Set<String> unique = new LinkedHashSet<>(tokens(text));
        return String.join(" ", unique);
    }

    public static String tsQuery(String text) {
        // Bigrams only: unigrams add a lot of OR terms (and matches) without much
        // precision. Capping the term count keeps ts_rank over a bounded set.
        Set<String> unique = new LinkedHashSet<>(bigramTokens(text));
        return unique.stream().limit(16).collect(java.util.stream.Collectors.joining(" | "));
    }

    public static List<String> bigramTokens(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = ASCII_WORD.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase());
        }
        String cjk = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 1 < cjk.length(); i++) {
            tokens.add(cjk.substring(i, i + 2));
        }
        if (cjk.length() == 1) {
            tokens.add(cjk);
        }
        return tokens;
    }
}
