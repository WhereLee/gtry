package com.wherelee.helpdesk.ai;

import java.util.regex.Pattern;

/**
 * Retrieved wiki text is untrusted input. We never execute it, but a page could try
 * to talk the model out of its instructions. Snippets are delimited, delimiter
 * sequences are escaped, and suspicious snippets are marked as untrusted.
 */
public final class PromptGuard {

    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?(previous|above)\\s+instructions"
                    + "|system\\s*prompt|you\\s+are\\s+now|developer\\s+mode|jailbreak"
                    + "|忽略(以上|之前|前面|上述).{0,8}(指令|规则|提示)"
                    + "|系统提示词|开发者模式|越狱|你现在是|从现在开始你)"
    );

    private PromptGuard() {
    }

    public static boolean suspicious(String text) {
        return text != null && INJECTION.matcher(text).find();
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("<<<DOC", "<<< DOC").replace("<<<END", "<<< END");
    }

    public static String wrap(int index, String title, String headingPath, String content) {
        boolean suspicious = suspicious(content);
        String marker = suspicious ? "[UNTRUSTED CONTENT] " : "";
        return "<<<DOC id=" + index + ">>>\n"
                + marker + "《" + escape(title) + "》 " + escape(headingPath) + "\n"
                + escape(content) + "\n"
                + "<<<END DOC>>>";
    }
}
