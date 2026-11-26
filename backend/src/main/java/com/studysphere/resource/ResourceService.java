package com.studysphere.resource;

import com.studysphere.auth.AccountDeletionRequested;
import com.studysphere.resource.storage.ResourceStorage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ResourceService {
    static final int LIMIT = 20 * 1024 * 1024;
    private static final String ACCESS = """
            (r.user_id = ? OR EXISTS (SELECT 1 FROM resource_permissions p WHERE p.resource_id=r.id AND p.user_id=?))
            """;
    private final JdbcTemplate jdbc;
    private final ResourceStorage storage;
    private final TransactionTemplate transactions;

    ResourceService(JdbcTemplate jdbc, ResourceStorage storage, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.transactions = transactions;
    }

    public Page<Resource> list(long owner, int page, int size, String q, String tag, String folder,
            String collection, String view) {
        requirePage(page, size);
        var conditions = new StringBuilder(" FROM resources r WHERE ").append(ACCESS);
        var args = new ArrayList<Object>(List.of(owner, owner));
        if (q != null && !q.isBlank()) {
            conditions.append(" AND (LOCATE(?,r.title)>0 OR LOCATE(?,r.file_name)>0)");
            String query = clean(q, 160, "Search");
            args.add(query);
            args.add(query);
        }
        if (tag != null && !tag.isBlank()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM resource_tags t WHERE t.resource_id=r.id AND t.name=?)");
            args.add(clean(tag, 50, "Tag"));
        }
        if (folder != null && !folder.isBlank()) {
            long id = id(folder);
            requireNamed(owner, id, "resource_folders");
            conditions.append(" AND r.user_id=? AND r.folder_id=?");
            args.add(owner);
            args.add(id);
        }
        if (collection != null && !collection.isBlank()) {
            long id = id(collection);
            requireNamed(owner, id, "collections");
            conditions.append(" AND EXISTS (SELECT 1 FROM collection_resources c WHERE c.collection_id=? AND c.resource_id=r.id)");
            args.add(id);
        }
        if ("bookmarks".equals(view)) {
            conditions.append(" AND EXISTS (SELECT 1 FROM bookmarks b WHERE b.user_id=? AND b.resource_id=r.id)");
            args.add(owner);
        }
        if ("recent".equals(view)) {
            conditions.append(" AND EXISTS (SELECT 1 FROM resource_access_history h WHERE h.user_id=? AND h.resource_id=r.id)");
            args.add(owner);
            conditions.append(" ORDER BY (SELECT h.accessed_at FROM resource_access_history h WHERE h.user_id=? AND h.resource_id=r.id) DESC, r.id DESC");
            args.add(owner);
        } else {
            conditions.append(" ORDER BY r.created_at DESC, r.id DESC");
        }
        args.add(size + 1);
        args.add((long) page * size);
        return page(jdbc.query("SELECT r.*" + conditions + " LIMIT ? OFFSET ?", row(owner), args.toArray()), page, size);
    }

    public List<Resource> recent(long owner) {
        return list(owner, 0, 10, null, null, null, null, "recent").items();
    }

    public Resource get(long owner, long id) {
        return first("SELECT r.* FROM resources r WHERE r.id=? AND " + ACCESS, row(owner), id, owner, owner);
    }

    public Resource upload(long owner, MultipartFile file, String title, String tagString, String folderId) {
        if (file == null || file.isEmpty()) throw invalid("Choose a file.");
        if (file.getSize() > LIMIT) throw new ResourceFailure(413, "File exceeds 20 MiB.");
        String name = cleanFileName(file.getOriginalFilename());
        String label = title == null || title.isBlank() ? name : clean(title, 160, "Title");
        if (label.length() > 160) label = label.substring(0, 160);
        List<String> tags = tags(tagString == null || tagString.isBlank() ? List.of() : Arrays.asList(tagString.split(",")));
        Long folder = folderId == null || folderId.isBlank() ? null : id(folderId);
        byte[] fileBytes;
        try (InputStream content = file.getInputStream()) {
            fileBytes = content.readNBytes(LIMIT + 1);
        } catch (IOException failure) {
            throw invalid("Unable to read the file.");
        }
        if (fileBytes.length == 0) throw invalid("Choose a file.");
        if (fileBytes.length > LIMIT) throw new ResourceFailure(413, "File exceeds 20 MiB.");
        String media = determineType(name, fileBytes);
        String key = UUID.randomUUID().toString();
        // The durable claim covers a crash between storage and metadata commit.
        jdbc.update("INSERT INTO resource_object_cleanup VALUES (?, UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)", key);
        String finalLabel = label;
        try {
            return transactions.execute(status -> {
                lockOwner(owner);
                if (folder != null) requireNamed(owner, folder, "resource_folders");
                jdbc.queryForObject("SELECT object_key FROM resource_object_cleanup WHERE object_key=? FOR UPDATE",
                        String.class, key);
                storage.put(key, new ByteArrayInputStream(fileBytes), fileBytes.length, media);
                long id = insert("""
                        INSERT INTO resources (user_id,folder_id,object_key,title,file_name,media_type,size_bytes,created_at)
                        VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                        """, owner, folder, key, finalLabel, name, media, fileBytes.length);
                replaceTags(id, tags);
                jdbc.update("DELETE FROM resource_object_cleanup WHERE object_key=?", key);
                return get(owner, id);
            });
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE resource_object_cleanup SET available_at=UTC_TIMESTAMP(6) WHERE object_key=?", key);
            throw failure;
        }
    }

    @Transactional
    public Resource update(long owner, long id, UpdateResource changes) {
        own(owner, id, true);
        if (changes == null) throw invalid("Resource updates are required.");
        if (changes.title() != null) jdbc.update("UPDATE resources SET title=? WHERE id=?", clean(changes.title(), 160, "Title"), id);
        if (changes.folderId() != null) {
            Long folder = changes.folderId().isBlank() ? null : id(changes.folderId());
            if (folder != null) requireNamed(owner, folder, "resource_folders");
            jdbc.update("UPDATE resources SET folder_id=? WHERE id=?", folder, id);
        }
        if (changes.tags() != null) replaceTags(id, tags(changes.tags()));
        return get(owner, id);
    }

    @Transactional
    public void delete(long owner, long id) {
        String key = own(owner, id, true);
        enqueue(key);
        jdbc.update("DELETE FROM resources WHERE id=?", id);
    }

    @EventListener
    public void enqueueOwnedObjectsForDeletion(AccountDeletionRequested event) {
        jdbc.update("""
                INSERT INTO resource_object_cleanup (object_key,available_at)
                SELECT object_key,UTC_TIMESTAMP(6) FROM resources WHERE user_id=?
                ON DUPLICATE KEY UPDATE available_at=UTC_TIMESTAMP(6)
                """, event.userId());
    }

    private void enqueue(String key) {
        jdbc.update("""
                INSERT INTO resource_object_cleanup VALUES (?,UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE available_at=UTC_TIMESTAMP(6)
                """, key);
    }

    @Scheduled(fixedDelayString = "${studysphere.resources.cleanup-delay-ms:5000}")
    public void cleanObjects() {
        var keys = jdbc.queryForList("""
                SELECT object_key FROM resource_object_cleanup WHERE available_at<=UTC_TIMESTAMP(6)
                ORDER BY available_at LIMIT 20
                """, String.class);
        for (String key : keys) {
            try {
                transactions.executeWithoutResult(status -> {
                    var claim = jdbc.queryForList("""
                            SELECT object_key FROM resource_object_cleanup
                            WHERE object_key=? AND available_at<=UTC_TIMESTAMP(6) FOR UPDATE
                            """, String.class, key);
                    if (claim.isEmpty()) return;
                    storage.delete(key);
                    jdbc.update("DELETE FROM resource_object_cleanup WHERE object_key=?", key);
                });
            } catch (RuntimeException failure) {
                jdbc.update("""
                        UPDATE resource_object_cleanup SET available_at=UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE
                        WHERE object_key=?
                        """, key);
                LoggerFactory.getLogger(ResourceService.class).warn("Resource object cleanup failed; scheduled for retry.");
            }
        }
    }

    @Transactional
    public Download download(long owner, long id) {
        Resource resource = get(owner, id);
        String key = first("SELECT r.object_key FROM resources r WHERE r.id=? AND " + ACCESS + " FOR SHARE",
                (rs, index) -> rs.getString(1), id, owner, owner);
        var content = storage.open(key);
        try {
            jdbc.update("""
                    INSERT INTO resource_access_history VALUES (?,?,UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE accessed_at=UTC_TIMESTAMP(6)
                    """, owner, id);
        } catch (RuntimeException failure) {
            try { content.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        return new Download(resource, content);
    }

    public List<Permission> permissions(long owner, long id) {
        own(owner, id, false);
        return jdbc.query("""
                SELECT u.id,u.canonical_email FROM resource_permissions p JOIN users u ON u.id=p.user_id
                WHERE p.resource_id=? ORDER BY u.canonical_email
                """, (rs, i) -> new Permission(rs.getString(1), rs.getString(2)), id);
    }

    @Transactional
    public Permission share(long owner, long resourceId, String email) {
        own(owner, resourceId, true);
        String canonical = clean(email, 254, "Email").toLowerCase(Locale.ROOT);
        var recipient = first("SELECT id,canonical_email FROM users WHERE canonical_email=? AND status='ACTIVE'",
                (rs, i) -> new Permission(rs.getString(1), rs.getString(2)), canonical);
        if (id(recipient.userId()) == owner) throw invalid("You already own this resource.");
        jdbc.update("""
                INSERT INTO resource_permissions VALUES (?,?)
                ON DUPLICATE KEY UPDATE user_id=resource_permissions.user_id
                """, resourceId, id(recipient.userId()));
        return recipient;
    }

    @Transactional
    public void revoke(long owner, long resource, long recipient) {
        own(owner, resource, true);
        jdbc.update("DELETE FROM resource_permissions WHERE resource_id=? AND user_id=?", resource, recipient);
        jdbc.update("DELETE FROM bookmarks WHERE resource_id=? AND user_id=?", resource, recipient);
        jdbc.update("DELETE FROM resource_access_history WHERE resource_id=? AND user_id=?", resource, recipient);
        jdbc.update("""
                DELETE c FROM collection_resources c JOIN collections a ON a.id=c.collection_id
                WHERE c.resource_id=? AND a.user_id=?
                """, resource, recipient);
    }

    Page<Named> names(long owner, String table, int page, int size) {
        requirePage(page, size);
        return page(jdbc.query("SELECT id,name FROM " + table + " WHERE user_id=? ORDER BY name,id LIMIT ? OFFSET ?",
                (r,i) -> new Named(r.getString(1),r.getString(2)), owner, size + 1, (long) page * size), page, size);
    }

    Named createName(long owner, String table, String raw) {
        String name = clean(raw, 120, "Name");
        try {
            return new Named(Long.toString(insert("INSERT INTO " + table + " (user_id,name) VALUES (?,?)", owner, name)), name);
        } catch (DuplicateKeyException duplicate) {
            throw new ResourceFailure(409, "A name like this already exists.");
        }
    }

    void deleteName(long owner, long id, String table) {
        if (jdbc.update("DELETE FROM " + table + " WHERE id=? AND user_id=?", id, owner) == 0) throw missing();
    }

    @Transactional
    public void bookmark(long owner, long resource, boolean add) {
        if (add) {
            requireReadable(owner, resource);
            jdbc.update("INSERT INTO bookmarks VALUES (?,?) ON DUPLICATE KEY UPDATE user_id=bookmarks.user_id", owner, resource);
        } else {
            jdbc.update("DELETE FROM bookmarks WHERE user_id=? AND resource_id=?", owner, resource);
        }
    }

    @Transactional
    public void member(long owner, long collection, long resource, boolean add) {
        requireNamed(owner, collection, "collections");
        if (add) {
            requireReadable(owner, resource);
            jdbc.update("""
                    INSERT INTO collection_resources VALUES (?,?)
                    ON DUPLICATE KEY UPDATE resource_id=collection_resources.resource_id
                    """, collection, resource);
        } else {
            jdbc.update("DELETE FROM collection_resources WHERE collection_id=? AND resource_id=?", collection, resource);
        }
    }

    private void requireReadable(long owner, long id) {
        first("SELECT r.id FROM resources r WHERE r.id=? AND " + ACCESS + " FOR SHARE",
                (rs,i) -> rs.getLong(1), id, owner, owner);
    }

    private void lockOwner(long owner) {
        first("SELECT id FROM users WHERE id=? FOR UPDATE", (rs,i) -> rs.getLong(1), owner);
    }

    private void requireNamed(long owner, long id, String table) {
        first("SELECT id FROM " + table + " WHERE id=? AND user_id=?", (rs,i) -> rs.getLong(1), id, owner);
    }

    private String own(long owner, long id, boolean lock) {
        return first("SELECT object_key FROM resources WHERE user_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs,i) -> rs.getString(1), owner, id);
    }

    private RowMapper<Resource> row(long owner) {
        return (rs, i) -> new Resource(rs.getString("id"), rs.getString("title"), rs.getString("file_name"),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("folder_id"),
                jdbc.queryForList("SELECT name FROM resource_tags WHERE resource_id=? ORDER BY name", String.class, rs.getLong("id")),
                owner == rs.getLong("user_id"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private <T> T first(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst().orElseThrow(ResourceService::missing);
    }

    private void replaceTags(long id, List<String> tags) {
        jdbc.update("DELETE FROM resource_tags WHERE resource_id=?", id);
        for (String tag : tags) jdbc.update("INSERT INTO resource_tags VALUES (?,?)", id, tag);
    }

    private static List<String> tags(List<String> raw) {
        if (raw.size() > 12) throw invalid("Add up to 12 tags.");
        return raw.stream().map(tag -> clean(tag, 50, "Tag").toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static String cleanFileName(String input) {
        if (input == null) throw invalid("File name is required.");
        String name = input.replace('\\', '/');
        return clean(name.substring(name.lastIndexOf('/') + 1), 180, "File name");
    }

    private static String clean(String input, int length, String label) {
        if (input == null || input.isBlank() || input.strip().length() > length
                || input.chars().anyMatch(Character::isISOControl)) throw invalid(label + " is invalid.");
        return input.strip();
    }

    private static String determineType(String name, byte[] bytes) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && starts(bytes, '%', 'P', 'D', 'F', '-')) return "application/pdf";
        if (lower.endsWith(".png") && starts(bytes, 137, 'P', 'N', 'G', 13, 10, 26, 10)) return "image/png";
        if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && starts(bytes, 255, 216, 255)) return "image/jpeg";
        if (lower.endsWith(".txt")) {
            try {
                var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
                if (text.chars().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                    return "text/plain";
                }
            } catch (java.nio.charset.CharacterCodingException ignored) {
                // Only well-formed UTF-8 is accepted.
            }
        }
        throw new ResourceFailure(415, "Choose a PDF, PNG, JPEG, or UTF-8 text file.");
    }

    private static boolean starts(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((bytes[i] & 255) != magic[i]) return false;
        return true;
    }

    static long id(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw invalid("Invalid identifier.");
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) { throw invalid("Invalid identifier."); }
    }

    private static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Page or size is invalid.");
    }

    private static <T> Page<T> page(List<T> items, int page, int size) {
        return new Page<>(items.subList(0, Math.min(size, items.size())), page, size, items.size() > size);
    }

    static ResourceFailure invalid(String detail) { return new ResourceFailure(400, detail); }
    private static ResourceFailure missing() { return new ResourceFailure(404, "Resource not found."); }

    public record Resource(String id, String title, String fileName, String mediaType, long sizeBytes,
            String folderId, List<String> tags, boolean owned, Instant createdAt) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    record Named(String id, String name) {}
    record Permission(String userId, String email) {}
    record UpdateResource(String title, String folderId, List<String> tags) {}
    record Download(Resource resource, InputStream content) {}
}
