package com.wherelee.helpdesk.ticket;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic first-pass triage. Rules are intentionally cheap and explainable:
 * an LLM can be added later as a second opinion, but ticket routing must not depend
 * on an external model being available.
 */
@Service
public class TicketTriageService {

    public record TriageResult(
            String category,
            String priority,
            UUID assigneeId,
            String source,
            String reason
    ) {
    }

    private record CategoryRule(String category, List<String> keywords) {
    }

    private static final List<CategoryRule> RULES = List.of(
            new CategoryRule("RETURNS", List.of("退货", "退款", "换货", "售后", "维修", "退回", "退钱", "无理由")),
            new CategoryRule("SHIPPING", List.of("物流", "快递", "发货", "配送", "地址", "签收", "运费", "到货", "丢件", "少件")),
            new CategoryRule("INVOICE", List.of("发票", "抬头", "税号", "开票", "冲红", "报销")),
            new CategoryRule("PAYMENT", List.of("支付", "扣款", "付款", "分期", "银行卡", "支付宝", "微信", "重复扣款")),
            new CategoryRule("ACCOUNT", List.of("登录", "密码", "账号", "账户", "验证码", "改绑", "找回", "冻结", "实名")),
            new CategoryRule("MEMBER", List.of("会员", "积分", "等级", "优惠券", "权益")),
            new CategoryRule("PRODUCT", List.of("商品", "规格", "兼容", "保修", "说明书", "参数", "使用")),
            new CategoryRule("PROMOTION", List.of("活动", "满减", "拼团", "秒杀", "价保", "优惠")),
            new CategoryRule("COMPLAINT", List.of("投诉", "赔偿", "态度", "服务", "升级", "主管")),
            new CategoryRule("QUOTA", List.of("配额", "限制", "存储", "成员数", "调用", "超限")),
            new CategoryRule("BILLING", List.of("套餐", "计费", "续费", "账单", "订阅", "价格")),
            new CategoryRule("PERMISSIONS", List.of("权限", "角色", "owner", "admin", "guest", "sso")),
            new CategoryRule("INTEGRATION", List.of("集成", "webhook", "api key", "对接", "回调")),
            new CategoryRule("SECURITY", List.of("2fa", "审计", "白名单", "安全", "风控", "泄露")),
            new CategoryRule("BACKUP", List.of("备份", "恢复", "容灾")),
            new CategoryRule("UPGRADE", List.of("升级", "降级", "套餐变更", "折算")),
            new CategoryRule("EXPORT", List.of("导出", "下载", "备份文件")),
            new CategoryRule("DELETION", List.of("删除", "清理", "注销")),
            new CategoryRule("PRIVACY", List.of("隐私", "个人信息", "授权", "数据删除")),
            new CategoryRule("SUPPORT", List.of("工单", "客服", "sla", "响应", "支持"))
    );

    private static final List<String> URGENT_KEYWORDS = List.of(
            "投诉", "赔偿", "欺诈", "盗刷", "泄露", "无法登录", "数据丢失",
            "紧急", "立刻", "马上", "重复扣款", "退款失败");
    private static final List<String> HIGH_KEYWORDS = List.of(
            "退款", "退货", "发票", "超时", "异常", "失败", "错误", "损坏", "少件", "丢件", "取消");
    private static final List<String> LOW_KEYWORDS = List.of(
            "咨询", "建议", "如何使用", "怎么用", "介绍", "了解", "价格");
    private static final Set<String> HIGH_RISK_CATEGORIES =
            Set.of("COMPLAINT", "PRIVACY", "SECURITY", "PAYMENT");
    private static final Set<String> CATEGORIES = Set.of(
            "RETURNS", "SHIPPING", "INVOICE", "PAYMENT", "ACCOUNT", "MEMBER", "PRODUCT",
            "PROMOTION", "COMPLAINT", "QUOTA", "BILLING", "PERMISSIONS", "INTEGRATION",
            "SECURITY", "BACKUP", "UPGRADE", "EXPORT", "DELETION", "PRIVACY", "SUPPORT", "OTHER");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");

    private final TicketRepository tickets;

    public TicketTriageService(TicketRepository tickets) {
        this.tickets = tickets;
    }

    public TriageResult triage(String subject, String description, UUID tenantId) {
        String subjectText = normalize(subject);
        String descriptionText = normalize(description);
        CategoryRule best = null;
        int bestScore = 0;
        String bestKeyword = null;
        for (CategoryRule rule : RULES) {
            int score = 0;
            String matched = null;
            for (String keyword : rule.keywords()) {
                int subjectHits = count(subjectText, keyword);
                int descriptionHits = count(descriptionText, keyword);
                if (subjectHits + descriptionHits > 0) {
                    score += subjectHits * 2 + descriptionHits;
                    if (matched == null) {
                        matched = keyword;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule;
                bestKeyword = matched;
            }
        }

        String category = best == null ? "OTHER" : best.category();
        String allText = subjectText + " " + descriptionText;
        String priority = priority(allText, category);
        UUID assigneeId = tickets.findLeastLoadedAgent(tenantId).orElse(null);
        String reason = best == null
                ? "未命中分类关键词，进入默认队列"
                : "规则命中 " + category + "，关键词：" + bestKeyword;
        return new TriageResult(category, priority, assigneeId, "RULE", reason);
    }

    public boolean isValidCategory(String category) {
        return category != null && CATEGORIES.contains(category.toUpperCase(Locale.ROOT));
    }

    public boolean isValidPriority(String priority) {
        return priority != null && PRIORITIES.contains(priority.toUpperCase(Locale.ROOT));
    }

    private String priority(String text, String category) {
        if (containsAny(text, URGENT_KEYWORDS)) {
            return "URGENT";
        }
        if (containsAny(text, HIGH_KEYWORDS)) {
            return "HIGH";
        }
        if (containsAny(text, LOW_KEYWORDS) && !HIGH_RISK_CATEGORIES.contains(category)) {
            return "LOW";
        }
        if (HIGH_RISK_CATEGORIES.contains(category)) {
            return "HIGH";
        }
        return "NORMAL";
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private int count(String text, String keyword) {
        int hits = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            hits++;
            index += keyword.length();
        }
        return hits;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
