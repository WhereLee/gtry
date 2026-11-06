package com.wherelee.helpdesk.wiki;

import com.wherelee.helpdesk.tenant.Tenant;
import com.wherelee.helpdesk.tenant.TenantContext;
import com.wherelee.helpdesk.tenant.TenantRepository;
import com.wherelee.helpdesk.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates a deterministic, sizeable wiki corpus so retrieval quality can be
 * measured without shipping a private knowledge base. Two tenants, ten spaces each,
 * 125 pages per space and four sections per page => 10,000 indexed sections.
 */
@Service
public class WikiSeeder {

    private static final Logger log = LoggerFactory.getLogger(WikiSeeder.class);

    private static final int PAGES_PER_SPACE = 125;
    private static final int SECTIONS_PER_PAGE = 4;
    private static final int TOTAL_SECTIONS = 2 * 10 * PAGES_PER_SPACE * SECTIONS_PER_PAGE;

    private static final String[] HEADINGS = {"适用条件", "处理流程", "时效说明", "例外情况"};
    private static final String[] CONDITIONS = {
            "订单状态为已签收", "商品未拆封", "商品已拆封但存在质量问题", "会员等级为金卡及以上",
            "使用指定支付方式", "在活动期内下单", "未使用优惠券", "已完成实名认证"
    };
    private static final String[] STEPS = {
            "核验订单号", "确认商品状态", "提交售后单", "仓库验收", "原路退款",
            "短信通知客户", "更新工单状态", "回访确认"
    };
    private static final String[] INFOS = {
            "订单号", "支付凭证", "商品照片", "收货地址", "发票抬头", "企业税号"
    };
    private static final String[] SLAS = {"1 个工作日", "24 小时", "3 个工作日", "5 个工作日", "2 小时"};
    private static final String[] EXCEPTIONS = {
            "超过 30 天的订单", "定制类商品", "已开具专票的订单", "跨境订单",
            "企业批量采购", "活动赠品", "已经拆封的个护商品"
    };
    private static final String[] FALLBACKS = {"人工复核", "升级主管", "补发优惠券", "转交供应商"};

    private static final List<String[]> ACME_SPACES = List.of(
            new String[]{"returns", "退货售后", "退货、换货、维修与退款"},
            new String[]{"shipping", "配送物流", "发货、时效、丢件与地址修改"},
            new String[]{"invoice", "发票财务", "发票、抬头、冲红与报销"},
            new String[]{"account", "账户安全", "登录、改绑、风控与找回"},
            new String[]{"member", "会员权益", "等级、积分、优惠券与专享"},
            new String[]{"product", "商品知识", "规格、兼容性、保修与使用"},
            new String[]{"payment", "支付问题", "支付失败、重复扣款与分期"},
            new String[]{"promotion", "优惠活动", "满减、拼团、秒杀与价保"},
            new String[]{"complaint", "投诉处理", "服务投诉、赔付与升级"},
            new String[]{"privacy", "隐私合规", "数据导出、删除与授权"}
    );

    private static final List<String[]> CLOUDWISE_SPACES = List.of(
            new String[]{"billing", "计费套餐", "Free/Team/Business、续费与发票"},
            new String[]{"quota", "配额限制", "成员数、存储、API 调用与超限"},
            new String[]{"permissions", "权限管理", "Owner/Admin/Member/Guest 与审计"},
            new String[]{"export", "数据导出", "全量导出、格式、链接有效期"},
            new String[]{"deletion", "数据删除", "软删除、恢复与物理清理"},
            new String[]{"integration", "集成对接", "SSO、Webhook、API Key 与限流"},
            new String[]{"security", "安全合规", "2FA、登录审计、IP 白名单"},
            new String[]{"backup", "备份恢复", "自动备份、恢复点与演练"},
            new String[]{"upgrade", "升级降级", "套餐变更、按天折算与生效时间"},
            new String[]{"support", "服务支持", "SLA、工单响应与紧急联系人"}
    );

    private static final String[] ACME_PRODUCTS = {
            "耳机", "手机", "笔记本", "显示器", "键盘", "手表", "音箱", "相机", "平板", "路由器"
    };
    private static final String[] CLOUDWISE_PRODUCTS = {
            "基础版", "团队版", "企业版", "API", "审计日志", "数据导出", "SSO", "Webhook", "移动端", "桌面端"
    };

    private final WikiRepository repository;
    private final WikiIndexService indexService;
    private final TenantRepository tenants;
    private final JobStatus status = new JobStatus();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "helpdesk-seed");
        thread.setDaemon(true);
        return thread;
    });

    public WikiSeeder(WikiRepository repository, WikiIndexService indexService, TenantRepository tenants) {
        this.repository = repository;
        this.indexService = indexService;
        this.tenants = tenants;
    }

    public JobStatus.Snapshot start(boolean force) {
        if (status.snapshot().state() == JobStatus.State.RUNNING) {
            return status.snapshot();
        }
        status.start(TOTAL_SECTIONS);
        executor.submit(() -> run(force));
        return status.snapshot();
    }

    public JobStatus.Snapshot status() {
        return status.snapshot();
    }

    private void run(boolean force) {
        try {
            long pages = 0;
            long sections = 0;
            long questions = 0;
            int completed = 0;
            for (Tenant tenant : tenants.findAll()) {
                if ("platform".equals(tenant.slug())) {
                    continue;
                }
                final int offset = completed;
                long[] counts = TenantContext.callWith(
                        TenantScope.tenant(tenant.id()),
                        () -> seedTenant(tenant, force, offset)
                );
                pages += counts[0];
                sections += counts[1];
                questions += counts[2];
                completed += (int) counts[1];
                status.progress(completed);
            }
            status.done(Map.of(
                    "pages", pages,
                    "sections", sections,
                    "questions", questions
            ));
            log.info("helpdesk_seed_done pages={} sections={} questions={}", pages, sections, questions);
        } catch (Exception ex) {
            status.fail(ex.getMessage());
            log.error("helpdesk_seed_failed", ex);
        }
    }

    private long[] seedTenant(Tenant tenant, boolean force, int offset) {
        if (!force && repository.countPages() > 0) {
            log.info("helpdesk_seed_skip tenant={} reason=existing", tenant.slug());
            return new long[]{0, 0, 0};
        }
        if (force) {
            repository.deleteAll();
        }

        boolean acme = "acme".equals(tenant.slug());
        List<String[]> spaces = acme ? ACME_SPACES : CLOUDWISE_SPACES;
        String[] products = acme ? ACME_PRODUCTS : CLOUDWISE_PRODUCTS;
        UUID ownerId = UUID.fromString(acme
                ? "00000000-0000-0000-0000-000000000021"
                : "00000000-0000-0000-0000-000000000031");
        Random random = new Random(tenant.slug().hashCode());
        Instant now = Instant.now();

        long pageCount = 0;
        long sectionCount = 0;
        long questionCount = 0;
        List<UUID> sectionIds = new ArrayList<>(spaces.size() * PAGES_PER_SPACE * SECTIONS_PER_PAGE);

        for (int spaceIndex = 0; spaceIndex < spaces.size(); spaceIndex++) {
            String[] spaceDef = spaces.get(spaceIndex);
            WikiSpace space = repository.createSpace(tenant.id(), spaceDef[0], spaceDef[1], spaceDef[2]);
            List<WikiRepository.PageRow> pages = new ArrayList<>(PAGES_PER_SPACE);
            List<WikiRepository.SectionRow> sections = new ArrayList<>(PAGES_PER_SPACE * SECTIONS_PER_PAGE);
            List<WikiRepository.QuestionRow> questions = new ArrayList<>(PAGES_PER_SPACE);

            for (int i = 0; i < PAGES_PER_SPACE; i++) {
                String product = products[i % products.length];
                int variant = i / products.length + 1;
                String metric = acme
                        ? "订单金额超过 " + (100 + variant * 10) + " 元"
                        : "席位数量超过 " + (3 + variant * 2) + " 人";
                String title = product + " · " + space.name() + " · " + metric;
                UUID pageId = UUID.randomUUID();
                pages.add(new WikiRepository.PageRow(
                        pageId, tenant.id(), space.id(),
                        slug(spaceDef[0] + "-" + variant + "-" + (i % products.length)),
                        title,
                        i % 17 == 0 ? "INTERNAL" : "PUBLIC",
                        ownerId,
                        now.minus(random.nextInt(400), ChronoUnit.DAYS),
                        now.minus(random.nextInt(30), ChronoUnit.DAYS)
                ));

                UUID firstSectionId = null;
                for (int s = 0; s < SECTIONS_PER_PAGE; s++) {
                    UUID sectionId = UUID.randomUUID();
                    if (s == 0) {
                        firstSectionId = sectionId;
                    }
                    String headingPath = title + " > " + HEADINGS[s];
                    String content = content(acme, product, space.name(), metric, s, random);
                    sections.add(new WikiRepository.SectionRow(
                            sectionId, tenant.id(), pageId, s, headingPath, content));
                    sectionIds.add(sectionId);
                }
                questions.add(new WikiRepository.QuestionRow(
                        UUID.randomUUID(), tenant.id(), firstSectionId,
                        product + metric + "时，" + space.name() + "怎么处理？",
                        "FACT"
                ));
            }

            repository.insertPages(pages);
            repository.insertSections(sections);
            repository.insertQuestions(questions);
            pageCount += pages.size();
            sectionCount += sections.size();
            questionCount += questions.size();
            log.info("helpdesk_seed_space tenant={} space={} pages={} sections={}",
                    tenant.slug(), space.slug(), pages.size(), sections.size());
        }

        for (int i = 0; i < sectionIds.size(); i += 250) {
            List<UUID> chunk = sectionIds.subList(i, Math.min(i + 250, sectionIds.size()));
            indexService.indexSections(chunk);
            status.progress(offset + i + chunk.size());
            log.info("helpdesk_seed_index_progress tenant={} indexed={}/{}",
                    tenant.slug(), i + chunk.size(), sectionIds.size());
        }
        return new long[]{pageCount, sectionCount, questionCount};
    }

    private String content(boolean acme, String product, String space, String metric, int section, Random random) {
        String prefix = product + " " + space + "：";
        return switch (section) {
            case 0 -> prefix + metric + "；" + pick(CONDITIONS, random) + "；" + pick(CONDITIONS, random)
                    + "。不适用于：" + pick(EXCEPTIONS, random) + "。";
            case 1 -> prefix + metric + "的处理流程：1）" + pick(STEPS, random) + "；2）" + pick(STEPS, random)
                    + "；3）" + pick(STEPS, random) + "。需要客户提供：" + pick(INFOS, random) + "。";
            case 2 -> prefix + metric + "的时效：" + pick(SLAS, random) + "内完成初审，"
                    + pick(SLAS, random) + "内给出结果。超时可以在工单中升级。";
            default -> prefix + metric + "的例外：" + pick(EXCEPTIONS, random)
                    + "。需要主管审批，审批通过后按" + pick(FALLBACKS, random) + "处理。";
        };
    }

    private String pick(String[] values, Random random) {
        return values[random.nextInt(values.length)];
    }

    private String slug(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }
}
