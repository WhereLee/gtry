package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a held-out question set by paraphrasing existing page titles. The wording
 * does not reuse the section text ("订单满 110 元" vs "订单金额超过 110 元"), so it
 * measures generalization instead of exact-term matching.
 */
@Service
public class BlindQuestionService {

    private static final Logger log = LoggerFactory.getLogger(BlindQuestionService.class);
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");

    private final WikiRepository repository;
    private final TenantRepository tenants;

    public BlindQuestionService(WikiRepository repository, TenantRepository tenants) {
        this.repository = repository;
        this.tenants = tenants;
    }

    public Map<String, Object> generate() {
        int total = 0;
        for (Tenant tenant : tenants.findAll()) {
            if ("platform".equals(tenant.slug())) {
                continue;
            }
            total += TenantContext.callWith(TenantScope.tenant(tenant.id()), () -> generateTenant(tenant));
        }
        log.info("helpdesk_blind_questions_generated count={}", total);
        return Map.of("questions", total, "kind", "BLIND");
    }

    private int generateTenant(Tenant tenant) {
        repository.deleteQuestionsByKind("BLIND");
        boolean acme = "acme".equals(tenant.slug());
        List<WikiRepository.PageForQuestion> pages = repository.listPagesForQuestions();
        List<WikiRepository.QuestionRow> rows = new ArrayList<>(pages.size() * 2);
        for (WikiRepository.PageForQuestion page : pages) {
            String[] parts = page.title().split(" · ");
            if (parts.length < 3) {
                continue;
            }
            String product = parts[0];
            String space = parts[1];
            String metric = parts[2];
            Matcher matcher = NUMBER.matcher(metric);
            if (!matcher.find()) {
                continue;
            }
            String number = matcher.group(1);
            String q1 = acme
                    ? "我买的" + product + "，订单满 " + number + " 元，" + space + "需要注意什么？"
                    : "我们团队用" + product + "，成员有 " + number + " 个人，" + space + "怎么处理？";
            String q2 = acme
                    ? product + "订单达到 " + number + " 元时，" + space + "怎么处理？"
                    : product + "的成员数达到 " + number + " 人时，" + space + "要注意什么？";
            rows.add(new WikiRepository.QuestionRow(
                    UUID.randomUUID(), tenant.id(), page.sectionId(), q1, "BLIND"));
            rows.add(new WikiRepository.QuestionRow(
                    UUID.randomUUID(), tenant.id(), page.sectionId(), q2, "BLIND"));
        }
        repository.insertQuestions(rows);
        return rows.size();
    }
}
