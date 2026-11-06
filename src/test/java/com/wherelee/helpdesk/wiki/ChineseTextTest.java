package com.wherelee.helpdesk.wiki;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChineseTextTest {

    @Test
    void producesUnigramsAndBigramsForChinese() {
        assertThat(ChineseText.tokens("退货退款")).contains("退", "退货", "货退", "退款");
    }

    @Test
    void keepsAsciiWordsLowercase() {
        assertThat(ChineseText.tokens("API Key")).contains("api", "key");
    }

    @Test
    void buildsOrQueryForPostgres() {
        String query = ChineseText.tsQuery("退货退款");
        assertThat(query).contains(" | ");
        assertThat(query).contains("退货");
    }

    @Test
    void deduplicatesSearchText() {
        String searchText = ChineseText.searchText("退货 退货");
        assertThat(searchText.split(" ")).doesNotHaveDuplicates();
        assertThat(searchText).contains("退货");
    }
}
