package com.wherelee.helpdesk.ai;

import java.util.regex.Pattern;

/**
 * Redacts common customer identifiers before the question is sent to an external
 * LLM. The original ticket keeps the real values for the agent.
 */
public final class PiiRedactor {

    private static final Pattern EMAIL =
            Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern ID_CARD =
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD =
            Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ORDER =
            Pattern.compile("(?i)(订单号|订单编号|order\\s*(?:id|no)?)[:：\\s]*([A-Za-z0-9-]{6,})");

    private PiiRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = EMAIL.matcher(text).replaceAll("[EMAIL]");
        result = ID_CARD.matcher(result).replaceAll("[ID_CARD]");
        result = BANK_CARD.matcher(result).replaceAll("[BANK_CARD]");
        result = PHONE.matcher(result).replaceAll("[PHONE]");
        result = ORDER.matcher(result).replaceAll("$1[ORDER]");
        return result;
    }
}
