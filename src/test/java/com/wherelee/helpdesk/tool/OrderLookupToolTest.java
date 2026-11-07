package com.wherelee.helpdesk.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLookupToolTest {

    private final OrderLookupTool tool = new OrderLookupTool();

    @Test
    void extractsOrderNumberAndReturnsDemoOrder() {
        assertThat(tool.lookup("订单号 ORD123456，想退货"))
                .hasValueSatisfying(order -> {
                    assertThat(order.orderNo()).isEqualTo("ORD123456");
                    assertThat(order.status()).isEqualTo("已签收");
                    assertThat(order.returnable()).isTrue();
                });
    }

    @Test
    void ignoresQuestionsWithoutOrderNumber() {
        assertThat(tool.lookup("耳机怎么退货？")).isEmpty();
    }
}
