package com.studysphere.document;

import com.studysphere.document.engine.DocumentEngine;
import com.studysphere.document.engine.DocumentEngine.IndexedChunk;
import com.studysphere.document.engine.DocumentEngine.Source;
import com.studysphere.resource.storage.ResourceStorage;
import java.io.IOException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentService {
    private static final String VISIBLE = """
            FROM documents d JOIN resources r ON r.id=d.resource_id
            WHERE d.status<>'DELETING' AND (r.user_id=? OR EXISTS (
                SELECT 1 FROM resource_permissions p WHERE p.resource_id=r.id AND p.user_id=?))
            """;
    private static final String FIELDS = "SELECT d.id, d.resource_id, d.owner_id, d.status, d.error, r.title ";
    private static final RowMapper<Item> ITEMS = (row, index) -> new Item(row.getString("id"),
            row.getString("resource_id"), row.getString("title"), row.getString("status"),
            row.getString("error"), row.getLong("owner_id"));
    private final JdbcTemplate jdbc;
    private final ResourceStorage storage;
    private final DocumentEngine engine;
    private final TransactionTemplate tx;

    DocumentService(JdbcTemplate jdbc, ResourceStorage storage, DocumentEngine engine,
                    PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.engine = engine;
        this.tx = new TransactionTemplate(manager);
    }

    Page list(long user, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Invalid page.");
        var results = jdbc.query(FIELDS + VISIBLE + " ORDER BY d.id DESC LIMIT ? OFFSET ?",
                ITEMS, user, user, size + 1, (long) page * size);
        return new Page(results.stream().limit(size).map(item -> item.view(user)).toList(), page, size,
                results.size() > size);
    }

    View create(long user, long resource) {
        return tx.execute(status -> {
            var types = jdbc.queryForList(
                    "SELECT media_type FROM resources WHERE id=? AND user_id=? FOR UPDATE", String.class, resource, user);
            if (types.isEmpty()) throw missing();
            if (!types.getFirst().equals("application/pdf") && !types.getFirst().equals("text/plain"))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Choose a PDF or UTF-8 TXT resource.");
            var existing = jdbc.queryForList("SELECT id FROM documents WHERE resource_id=?", Long.class, resource);
            if (!existing.isEmpty()) return visible(user, existing.getFirst()).view(user);
            long id = insert("""
                    INSERT INTO documents(resource_id,owner_id) VALUES (?,?)
                    """, resource, user);
            jdbc.update("INSERT INTO ingestion_jobs(document_id,available_at) VALUES (?,UTC_TIMESTAMP(6))", id);
            return visible(user, id).view(user);
        });
    }

    View status(long user, long id) {
        return visible(user, id).view(user);
    }

    public List<String> practiceExcerpts(long user, long id) {
        if (!visible(user, id).status().equals("READY")) throw conflict("Wait until the document is ready.");
        return jdbc.queryForList("""
                SELECT content FROM document_chunks WHERE document_id=?
                ORDER BY position_index LIMIT 10
                """, String.class, id);
    }

    View retry(long user, long id) {
        return tx.execute(status -> {
            Item item = visible(user, id);
            if (item.owner() != user) throw missing();
            if (jdbc.update("""
                    UPDATE documents SET status='PENDING', error=NULL WHERE id=? AND status='FAILED'
                    """, id) != 1) throw conflict("Only failed documents can be retried.");
            jdbc.update("""
                    INSERT INTO ingestion_jobs(document_id,available_at) VALUES (?,UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE available_at=UTC_TIMESTAMP(6)
                    """, id);
            return visible(user, id).view(user);
        });
    }

    void delete(long user, long id) {
        tx.executeWithoutResult(status -> {
            if (visible(user, id).owner() != user) throw missing();
            jdbc.update("UPDATE documents SET status='DELETING' WHERE id=?", id);
            jdbc.update("""
                    INSERT INTO ingestion_jobs(document_id,available_at) VALUES (?,UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE available_at=UTC_TIMESTAMP(6)
                    """, id);
        });
    }

    Answer ask(long user, long id, String question) {
        Item document = visible(user, id);
        if (!document.status().equals("READY")) throw conflict("Wait until the document is ready.");
        if (question == null || question.isBlank() || question.length() > 2000)
            throw invalid("Enter a question of up to 2,000 characters.");
        try {
            var sources = new ArrayList<Source>();
            for (long chunk : engine.search(document.owner(), id, question.strip(), 5).stream().distinct().limit(5).toList()) {
                jdbc.query("""
                        SELECT id,page_number,content FROM document_chunks WHERE document_id=? AND id=?
                        """, (row, index) -> new Source(row.getLong("id"), row.getObject("page_number", Integer.class),
                        row.getString("content")), id, chunk).stream().findFirst().ifPresent(sources::add);
            }
            if (!visible(user, id).status().equals("READY")) throw conflict("Document is no longer ready.");
            if (sources.isEmpty()) return new Answer("The document does not contain enough relevant information.", List.of());
            String answer = engine.answer(question.strip(), List.copyOf(sources));
            if (!visible(user, id).status().equals("READY")) throw conflict("Document is no longer ready.");
            return new Answer(answer, sources.stream().map(source -> new Citation(Long.toString(source.id()),
                    source.page(), source.text())).toList());
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (DocumentEngine.IndexUnavailable failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The document search index is unavailable. Try again when it is online.");
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document answers are temporarily unavailable.");
        }
    }

    @Scheduled(fixedDelayString = "${studysphere.documents.poll-ms:2000}")
    void work() {
        // A resource cascade leaves its document tombstone so vector cleanup survives account deletion.
        jdbc.update("""
                INSERT IGNORE INTO ingestion_jobs(document_id,available_at)
                SELECT id,UTC_TIMESTAMP(6) FROM documents WHERE resource_id IS NULL
                """);
        var ready = jdbc.queryForList("""
                SELECT j.document_id FROM ingestion_jobs j JOIN documents d ON d.id=j.document_id
                WHERE j.available_at<=UTC_TIMESTAMP(6)
                  AND (d.status IN ('PENDING','PROCESSING','DELETING') OR d.resource_id IS NULL)
                ORDER BY j.available_at LIMIT 5
                """, Long.class);
        ready.forEach(this::run);
    }

    private void run(long id) {
        Job job = tx.execute(status -> {
            var jobs = jdbc.query("""
                    SELECT d.resource_id,d.owner_id,d.status FROM documents d JOIN ingestion_jobs j ON d.id=j.document_id
                    WHERE d.id=? AND j.available_at<=UTC_TIMESTAMP(6) FOR UPDATE SKIP LOCKED
                    """, (row, index) -> new Job(row.getObject("resource_id", Long.class),
                    row.getLong("owner_id"), row.getString("status")), id);
            if (jobs.isEmpty()) return null;
            var next = jobs.getFirst();
            if (next.resource() != null && !next.status().equals("DELETING"))
                jdbc.update("UPDATE documents SET status='PROCESSING' WHERE id=?", id);
            jdbc.update("""
                    UPDATE ingestion_jobs SET attempts=attempts+1,available_at=UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE
                    WHERE document_id=?
                    """, id);
            return next;
        });
        if (job == null) return;
        if (job.resource() == null || job.status().equals("DELETING")) {
            try {
                engine.delete(job.owner(), id);
                jdbc.update("DELETE FROM documents WHERE id=? AND (resource_id IS NULL OR status='DELETING')", id);
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE ingestion_jobs SET available_at=UTC_TIMESTAMP(6)+INTERVAL 1 MINUTE WHERE document_id=?", id);
            }
            return;
        }
        try {
            var file = jdbc.queryForObject("SELECT object_key,media_type FROM resources WHERE id=?",
                    (row, index) -> new File(row.getString("object_key"), row.getString("media_type")), job.resource());
            if (file == null) throw missing();
            List<DocumentEngine.Chunk> extracted;
            try (var input = storage.open(file.key())) {
                extracted = engine.extract(input, file.type());
            }
            if (extracted.isEmpty()) throw invalid("No readable text was found.");
            tx.executeWithoutResult(status -> {
                var editable = jdbc.queryForList("""
                        SELECT id FROM documents WHERE id=? AND resource_id IS NOT NULL AND status='PROCESSING' FOR UPDATE
                        """, Long.class, id);
                if (editable.isEmpty()) return;
                jdbc.update("DELETE FROM document_chunks WHERE document_id=?", id);
                var chunks = new ArrayList<IndexedChunk>();
                for (int index = 0; index < extracted.size(); index++) {
                    var chunk = extracted.get(index);
                    long chunkId = insert("""
                            INSERT INTO document_chunks(document_id,position_index,page_number,content) VALUES (?,?,?,?)
                            """, id, index, chunk.page(), chunk.text());
                    chunks.add(new IndexedChunk(chunkId, chunk.text()));
                }
                engine.delete(job.owner(), id);
                engine.index(job.owner(), id, chunks);
                jdbc.update("UPDATE documents SET status='READY',error=NULL WHERE id=?", id);
                jdbc.update("DELETE FROM ingestion_jobs WHERE document_id=?", id);
            });
        } catch (IOException | RuntimeException failure) {
            String message = switch (failure) {
                case DocumentEngine.IndexUnavailable ignored -> "The document search index is unavailable. Retry when it is online.";
                case ResponseStatusException rejected when rejected.getStatusCode() == HttpStatus.BAD_REQUEST ->
                    "No readable text was found in this document.";
                default -> "Could not index this document. Check the file and provider, then retry.";
            };
            jdbc.update("""
                    UPDATE documents SET status='FAILED',error=?
                    WHERE id=? AND resource_id IS NOT NULL AND status='PROCESSING'
                    """, message, id);
            jdbc.update("UPDATE ingestion_jobs SET available_at=UTC_TIMESTAMP(6) WHERE document_id=?", id);
        }
    }

    private Item visible(long user, long id) {
        return jdbc.query(FIELDS + VISIBLE + " AND d.id=?", ITEMS, user, user, id)
                .stream().findFirst().orElseThrow(DocumentService::missing);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Database did not generate an identifier.");
        return key.getKey().longValue();
    }

    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found."); }

    private record Item(String id, String resourceId, String title, String status, String error, long owner) {
        View view(long caller) { return new View(id, resourceId, title, status, error, caller == owner); }
    }
    private record Job(Long resource, long owner, String status) {}
    private record File(String key, String type) {}
    record View(String id, String resourceId, String title, String status, String error, boolean owned) {}
    record Page(List<View> items, int page, int size, boolean hasNext) {}
    record Citation(String chunkId, Integer page, String text) {}
    record Answer(String answer, List<Citation> citations) {}
}
