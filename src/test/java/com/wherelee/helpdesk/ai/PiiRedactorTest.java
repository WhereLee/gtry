package com.wherelee.helpdesk.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    void redactsPhoneEmailIdCardAndOrderNumber() {
        String text = "联系我 13800138000，邮箱 user@example.com，身份证 110101199001011234，订单号 AB123456";

        String redacted = PiiRedactor.redact(text);

        assertThat(redacted).doesNotContain("13800138000");
        assertThat(redacted).doesNotContain("user@example.com");
        assertThat(redacted).doesNotContain("110101199001011234");
        assertThat(redacted).doesNotContain("AB123456");
        assertThat(redacted).contains("[PHONE]", "[EMAIL]", "[ID_CARD]", "[ORDER]");
    }

    @Test
    void keepsNormalBusinessText() {
        String text = "订单金额超过 160 元，想退货，运费谁承担？";
        assertThat(PiiRedactor.redact(text)).isEqualTo(text);
    }
}
