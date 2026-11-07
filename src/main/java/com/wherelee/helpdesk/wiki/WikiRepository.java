package com.wherelee.helpdesk.wiki;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WikiRepository {

    private static final RowMapper<WikiSpace> SPACE_MAPPER = (rs, rowNum) -> new WikiSpace(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("description")
    );

    private static final RowMapper<WikiPage> PAGE_MAPPER = (rs, rowNum) -> new WikiPage(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("space_id", UUID.class),
            rs.getString("space_name"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("visibility"),
            rs.getString("status"),
            rs.getObject("owner_id", UUID.class),
            toInstant(rs.getTimestamp("last_reviewed_at")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private static final RowMapper<WikiCandidate> CANDIDATE_MAPPER = (rs, rowNum) -> new WikiCandidate(
            rs.getObject("chunk_id", UUID.class),
            rs.getObject("section_id", UUID.class),
            rs.getObject("page_id", UUID.class),
            rs.getString("page_title"),
            rs.getString("heading_path"),
            rs.getString("content"),
            rs.getString("visibility"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getDouble("score"),
            0
    );

    private static final RowMapper<WikiPageVersion> VERSION_MAPPER = (rs, rowNum) -> new WikiPageVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("page_id", UUID.class),
            rs.getInt("version"),
            rs.getString("title"),
            rs.getString("visibility"),
            rs.getString("status"),
            rs.getString("sections"),
            rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public WikiRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WikiSpace createSpace(UUID tenantId, String slug, String name, String description) {
        return jdbc.queryForObject("""
                INSERT INTO wiki_space (tenant_id, slug, name, description)
                VALUES (?, ?, ?, ?)
                RETURNING id, tenant_id, slug, name, description
                """, SPACE_MAPPER, tenantId, slug, name, description);
    }

    public List<WikiSpace> listSpaces() {
        return jdbc.query("""
                SELECT id, tenant_id, slug, name, description
                FROM wiki_space ORDER BY name
                """, SPACE_MAPPER);
    }

    public Optional<WikiSpace> findSpaceBySlug(String slug) {
        return jdbc.query("""
                SELECT id, tenant_id, slug, name, description
                FROM wiki_space WHERE slug = ?
                """, SPACE_MAPPER, slug).stream().findFirst();
    }

    public Optional<WikiSpace> findSpaceById(UUID id) {
        return jdbc.query("""
                SELECT id, tenant_id, slug, name, description
                FROM wiki_space WHERE id = ?
                """, SPACE_MAPPER, id).stream().findFirst();
    }

    public WikiPage createPage(
            UUID tenantId, UUID spaceId, String slug, String title,
            String visibility, UUID ownerId, Instant lastReviewedAt
    ) {
        return jdbc.queryForObject("""
                INSERT INTO wiki_page
                    (tenant_id, space_id, slug, title, visibility, owner_id, last_reviewed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, tenant_id, space_id,
                          (SELECT name FROM wiki_space s WHERE s.id = space_id) AS space_name,
                          slug, title, visibility, status, owner_id, last_reviewed_at,
                          created_at, updated_at
                """, PAGE_MAPPER, tenantId, spaceId, slug, title, visibility, ownerId,
                lastReviewedAt == null ? null : Timestamp.from(lastReviewedAt));
    }

    public Optional<WikiPage> findPage(UUID id) {
        return jdbc.query("""
                SELECT p.id, p.tenant_id, p.space_id, s.name AS space_name, p.slug, p.title,
                       p.visibility, p.status, p.owner_id, p.last_reviewed_at,
                       p.created_at, p.updated_at
                FROM wiki_page p JOIN wiki_space s ON s.id = p.space_id
                WHERE p.id = ?
                """, PAGE_MAPPER, id).stream().findFirst();
    }

    public List<WikiPage> listPages(UUID spaceId, int limit) {
        if (spaceId == null) {
            return jdbc.query("""
                    SELECT p.id, p.tenant_id, p.space_id, s.name AS space_name, p.slug, p.title,
                           p.visibility, p.status, p.owner_id, p.last_reviewed_at,
                           p.created_at, p.updated_at
                    FROM wiki_page p JOIN wiki_space s ON s.id = p.space_id
                    ORDER BY p.updated_at DESC LIMIT ?
                    """, PAGE_MAPPER, limit);
        }
        return jdbc.query("""
                SELECT p.id, p.tenant_id, p.space_id, s.name AS space_name, p.slug, p.title,
                       p.visibility, p.status, p.owner_id, p.last_reviewed_at,
                       p.created_at, p.updated_at
                FROM wiki_page p JOIN wiki_space s ON s.id = p.space_id
                WHERE p.space_id = ? ORDER BY p.updated_at DESC LIMIT ?
                """, PAGE_MAPPER, spaceId, limit);
    }

    public WikiSection createSection(UUID tenantId, UUID pageId, int seq, String headingPath, String content) {
        return jdbc.queryForObject("""
                INSERT INTO wiki_section (tenant_id, page_id, seq, heading_path, content)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, page_id, seq, heading_path, content
                """, (rs, rowNum) -> new WikiSection(
                rs.getObject("id", UUID.class),
                rs.getObject("page_id", UUID.class),
                rs.getInt("seq"),
                rs.getString("heading_path"),
                rs.getString("content")
        ), tenantId, pageId, seq, headingPath, content);
    }

    public List<WikiSection> listSections(UUID pageId) {
        return jdbc.query("""
                SELECT id, page_id, seq, heading_path, content
                FROM wiki_section WHERE page_id = ? ORDER BY seq
                """, (rs, rowNum) -> new WikiSection(
                rs.getObject("id", UUID.class),
                rs.getObject("page_id", UUID.class),
                rs.getInt("seq"),
                rs.getString("heading_path"),
                rs.getString("content")
        ), pageId);
    }

    public Optional<SectionWithPage> findSection(UUID sectionId) {
        return jdbc.query("""
                SELECT s.id, s.tenant_id, s.page_id, s.heading_path, s.content,
                       p.title, p.visibility, p.updated_at
                FROM wiki_section s JOIN wiki_page p ON p.id = s.page_id
                WHERE s.id = ?
                """, (rs, rowNum) -> new SectionWithPage(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("page_id", UUID.class),
                rs.getString("heading_path"),
                rs.getString("content"),
                rs.getString("title"),
                rs.getString("visibility"),
                rs.getTimestamp("updated_at").toInstant()
        ), sectionId).stream().findFirst();
    }

    public void upsertChunk(
            UUID tenantId, UUID sectionId, UUID pageId,
            String content, String searchText, String embedding
    ) {
        jdbc.update("""
                INSERT INTO wiki_chunk
                    (tenant_id, section_id, page_id, content, search_text, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::vector, now())
                ON CONFLICT (section_id) DO UPDATE
                SET content = EXCLUDED.content,
                    search_text = EXCLUDED.search_text,
                    embedding = EXCLUDED.embedding,
                    updated_at = now()
                """, tenantId, sectionId, pageId, content, searchText, embedding);
    }

    public List<WikiCandidate> searchVector(String embedding, int limit, boolean includeInternal) {
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.section_id, c.page_id, p.title AS page_title,
                       s.heading_path, c.content, p.visibility, p.updated_at,
                       1 - (c.embedding <=> ?::vector) AS score
                FROM wiki_chunk c
                JOIN wiki_section s ON s.id = c.section_id
                JOIN wiki_page p ON p.id = c.page_id
                WHERE p.status = 'PUBLISHED'
                  AND (? OR p.visibility = 'PUBLIC')
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """, CANDIDATE_MAPPER, embedding, includeInternal, embedding, limit);
    }

    public List<WikiCandidate> searchFullText(String tsQuery, int limit, boolean includeInternal) {
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.section_id, c.page_id, p.title AS page_title,
                       s.heading_path, c.content, p.visibility, p.updated_at,
                       ts_rank(c.tsv, to_tsquery('simple', ?)) AS score
                FROM wiki_chunk c
                JOIN wiki_section s ON s.id = c.section_id
                JOIN wiki_page p ON p.id = c.page_id
                WHERE c.tsv @@ to_tsquery('simple', ?)
                  AND p.status = 'PUBLISHED'
                  AND (? OR p.visibility = 'PUBLIC')
                ORDER BY score DESC
                LIMIT ?
                """, CANDIDATE_MAPPER, tsQuery, tsQuery, includeInternal, limit);
    }

    public long countPages() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM wiki_page", Long.class);
        return count == null ? 0 : count;
    }

    public long countSections() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM wiki_section", Long.class);
        return count == null ? 0 : count;
    }

    public long countChunks() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM wiki_chunk", Long.class);
        return count == null ? 0 : count;
    }

    public long countEvalQuestions() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM eval_question", Long.class);
        return count == null ? 0 : count;
    }

    public List<WikiPage> stalePages(int days, int limit) {
        return jdbc.query("""
                SELECT p.id, p.tenant_id, p.space_id, s.name AS space_name, p.slug, p.title,
                       p.visibility, p.status, p.owner_id, p.last_reviewed_at,
                       p.created_at, p.updated_at
                FROM wiki_page p JOIN wiki_space s ON s.id = p.space_id
                WHERE p.last_reviewed_at IS NULL OR p.last_reviewed_at < now() - (? || ' days')::interval
                ORDER BY p.last_reviewed_at NULLS FIRST
                LIMIT ?
                """, PAGE_MAPPER, days, limit);
    }

    public List<UUID> listSectionIds(UUID pageId) {
        return jdbc.queryForList("SELECT id FROM wiki_section WHERE page_id = ? ORDER BY seq",
                UUID.class, pageId);
    }

    public Optional<UUID> findFirstSectionId(UUID pageId) {
        return jdbc.queryForList(
                "SELECT id FROM wiki_section WHERE page_id = ? ORDER BY seq LIMIT 1",
                UUID.class, pageId).stream().findFirst();
    }

    public int deletePage(UUID pageId) {
        return jdbc.update("DELETE FROM wiki_page WHERE id = ?", pageId);
    }

    public int updatePage(UUID pageId, String title, String visibility, Instant reviewedAt) {
        return jdbc.update("""
                UPDATE wiki_page
                SET title = ?, visibility = ?, last_reviewed_at = ?, updated_at = now()
                WHERE id = ?
                """, title, visibility, reviewedAt == null ? null : Timestamp.from(reviewedAt), pageId);
    }

    public int updatePageStatus(UUID pageId, String status, Instant reviewedAt) {
        return jdbc.update("""
                UPDATE wiki_page
                SET status = ?, last_reviewed_at = COALESCE(?, last_reviewed_at), updated_at = now()
                WHERE id = ?
                """, status, reviewedAt == null ? null : Timestamp.from(reviewedAt), pageId);
    }

    public int nextPageVersion(UUID pageId) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1 FROM wiki_page_version WHERE page_id = ?
                """, Integer.class, pageId);
        return next == null ? 1 : next;
    }

    public void insertPageVersion(
            UUID tenantId, UUID pageId, int version, String title, String visibility,
            String status, String sectionsJson, UUID createdBy
    ) {
        jdbc.update("""
                INSERT INTO wiki_page_version
                    (tenant_id, page_id, version, title, visibility, status, sections, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, tenantId, pageId, version, title, visibility, status, sectionsJson, createdBy);
    }

    public List<WikiPageVersion> listPageVersions(UUID pageId) {
        return jdbc.query("""
                SELECT id, page_id, version, title, visibility, status, sections, created_by, created_at
                FROM wiki_page_version
                WHERE page_id = ?
                ORDER BY version DESC
                """, VERSION_MAPPER, pageId);
    }

    public Optional<WikiPageVersion> findPageVersion(UUID pageId, int version) {
        return jdbc.query("""
                SELECT id, page_id, version, title, visibility, status, sections, created_by, created_at
                FROM wiki_page_version
                WHERE page_id = ? AND version = ?
                """, VERSION_MAPPER, pageId, version).stream().findFirst();
    }

    public void deleteSections(UUID pageId) {
        jdbc.update("DELETE FROM wiki_section WHERE page_id = ?", pageId);
    }

    public void audit(
            UUID tenantId, UUID actorId, String action,
            String targetType, String targetId, String detailJson
    ) {
        jdbc.update("""
                INSERT INTO audit_log (tenant_id, actor_id, action, target_type, target_id, detail)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, tenantId, actorId, action, targetType, targetId, detailJson);
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM eval_question");
        jdbc.update("DELETE FROM wiki_space");
    }

    public void insertPages(List<PageRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO wiki_page
                    (id, tenant_id, space_id, slug, title, visibility, owner_id, last_reviewed_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, 500, (ps, row) -> {
            ps.setObject(1, row.id());
            ps.setObject(2, row.tenantId());
            ps.setObject(3, row.spaceId());
            ps.setString(4, row.slug());
            ps.setString(5, row.title());
            ps.setString(6, row.visibility());
            ps.setObject(7, row.ownerId());
            ps.setTimestamp(8, row.lastReviewedAt() == null ? null : Timestamp.from(row.lastReviewedAt()));
            ps.setTimestamp(9, Timestamp.from(row.updatedAt()));
        });
    }

    public void insertSections(List<SectionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO wiki_section (id, tenant_id, page_id, seq, heading_path, content)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows, 500, (ps, row) -> {
            ps.setObject(1, row.id());
            ps.setObject(2, row.tenantId());
            ps.setObject(3, row.pageId());
            ps.setInt(4, row.seq());
            ps.setString(5, row.headingPath());
            ps.setString(6, row.content());
        });
    }

    public void insertQuestions(List<QuestionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO eval_question (id, tenant_id, section_id, question, kind)
                VALUES (?, ?, ?, ?, ?)
                """, rows, 500, (ps, row) -> {
            ps.setObject(1, row.id());
            ps.setObject(2, row.tenantId());
            ps.setObject(3, row.sectionId());
            ps.setString(4, row.question());
            ps.setString(5, row.kind());
        });
    }

    public List<EvalQuestionRow> sampleQuestions(int limit, String kind) {
        return jdbc.query("""
                SELECT q.id, q.section_id, s.page_id, q.question
                FROM eval_question q
                JOIN wiki_section s ON s.id = q.section_id
                WHERE q.kind = ?
                ORDER BY random() LIMIT ?
                """, (rs, rowNum) -> new EvalQuestionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getObject("page_id", UUID.class),
                rs.getString("question")
        ), kind, limit);
    }

    public List<PageForQuestion> listPagesForQuestions() {
        return jdbc.query("""
                SELECT p.id, p.title, s.id AS section_id
                FROM wiki_page p
                JOIN wiki_section s ON s.page_id = p.id AND s.seq = 0
                """, (rs, rowNum) -> new PageForQuestion(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getObject("section_id", UUID.class)
        ));
    }

    public void deleteQuestionsByKind(String kind) {
        jdbc.update("DELETE FROM eval_question WHERE kind = ?", kind);
    }

    public long countQuestionsByKind(String kind) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM eval_question WHERE kind = ?", Long.class, kind);
        return count == null ? 0 : count;
    }

    public record PageRow(
            UUID id, UUID tenantId, UUID spaceId, String slug, String title,
            String visibility, UUID ownerId, Instant lastReviewedAt, Instant updatedAt
    ) {
    }

    public record SectionRow(
            UUID id, UUID tenantId, UUID pageId, int seq, String headingPath, String content
    ) {
    }

    public record QuestionRow(
            UUID id, UUID tenantId, UUID sectionId, String question, String kind
    ) {
    }

    public record EvalQuestionRow(UUID id, UUID sectionId, UUID pageId, String question) {
    }

    public record PageForQuestion(UUID pageId, String title, UUID sectionId) {
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record SectionWithPage(
            UUID id, UUID tenantId, UUID pageId, String headingPath, String content,
            String pageTitle, String visibility, Instant updatedAt
    ) {
    }
}
