package com.studysphere.community;

import com.studysphere.admin.ReportIntake;
import com.studysphere.mentor.provider.MentorProvider;
import com.studysphere.resource.storage.ResourceStorage;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommunityService {
    private static final String COMMUNITIES = """
            SELECT c.*,m.role FROM communities c
            LEFT JOIN community_members m ON m.community_id=c.id AND m.user_id=?
            """;
    private static final String POSTS = """
            SELECT p.*,COALESCE(s.display_name,'StudySphere member') AS author_name,
              COALESCE((SELECT SUM(value) FROM community_votes WHERE post_id=p.id),0) AS score,
              COALESCE(v.value,0) AS my_vote
            FROM community_posts p LEFT JOIN student_profiles s ON s.user_id=p.author_id
            LEFT JOIN community_votes v ON v.post_id=p.id AND v.user_id=?
            """;
    private static final String RESOURCES = """
            SELECT r.id,r.title,r.file_name,r.media_type,r.size_bytes,r.object_key
            FROM community_resources cr JOIN resources r ON r.id=cr.resource_id
            JOIN community_members m ON m.community_id=cr.community_id AND m.user_id=?
            WHERE cr.community_id=?
            """;
    private static final RowMapper<Community> COMMUNITY = (r, i) -> new Community(
            r.getString("id"), r.getString("subject_id"), r.getString("name"), r.getString("description"), r.getString("role"));
    private static final RowMapper<Post> POST = (r, i) -> new Post(
            r.getString("id"), r.getString("community_id"), r.getString("author_id"), r.getString("author_name"),
            r.getString("title"), r.getString("body"), r.getLong("score"), r.getInt("my_vote"),
            r.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    private static final RowMapper<Resource> RESOURCE = (r, i) -> new Resource(r.getString("id"), r.getString("title"),
            r.getString("file_name"), r.getString("media_type"), r.getLong("size_bytes"));

    private final JdbcTemplate jdbc;
    private final ResourceStorage storage;
    private final MentorProvider mentor;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ReportIntake reports;

    CommunityService(JdbcTemplate jdbc, ResourceStorage storage, MentorProvider mentor, ObjectMapper json,
                     org.springframework.transaction.PlatformTransactionManager manager, ReportIntake reports) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.mentor = mentor;
        this.json = json;
        this.reports = reports;
        this.transactions = new TransactionTemplate(manager);
    }

    Page<Community> communities(long user, int page, int size) {
        return page(jdbc.query(COMMUNITIES + " ORDER BY c.name,c.id LIMIT ? OFFSET ?", COMMUNITY,
                user, size + 1, offset(page,size)), page, size);
    }

    Community community(long user, long id) {
        return first(COMMUNITIES + " WHERE c.id=?", COMMUNITY, user, id);
    }

    @Transactional
    Community create(long user, long subject, String name, String description) {
        if (jdbc.queryForList("SELECT id FROM subjects WHERE id=?", Long.class, subject).isEmpty()) {
            throw bad("Unknown subject.");
        }
        long id = insert("INSERT INTO communities(subject_id,owner_id,name,description) VALUES (?,?,?,?)",
                subject, user, required(name,120,"Name"), optional(description,600,"Description"));
        jdbc.update("INSERT INTO community_members VALUES (?,?,'OWNER')", id, user);
        return community(user,id);
    }

    @Transactional
    Community join(long user, long id) {
        lock(id);
        if (role(user,id) == null) jdbc.update("INSERT INTO community_members VALUES (?,?,'MEMBER')", id,user);
        return community(user,id);
    }

    @Transactional
    void leave(long user, long id) {
        lock(id);
        if ("OWNER".equals(member(user,id))) throw conflict("The community owner cannot leave.");
        jdbc.update("DELETE FROM community_members WHERE community_id=? AND user_id=?", id,user);
    }

    @Transactional(readOnly = true)
    Page<Member> members(long user, long id, int page, int size) {
        member(user,id);
        return page(jdbc.query("""
                SELECT m.user_id,m.role,COALESCE(p.display_name,'StudySphere member') AS display_name
                FROM community_members m LEFT JOIN student_profiles p ON p.user_id=m.user_id
                WHERE m.community_id=? ORDER BY m.role,m.user_id LIMIT ? OFFSET ?
                """, (r,i) -> new Member(r.getString("user_id"),r.getString("display_name"),r.getString("role")),
                id,size+1,offset(page,size)), page,size);
    }

    @Transactional
    void changeRole(long user, long community, long target, String requested) {
        lock(community);
        if (!"OWNER".equals(member(user,community))) throw forbidden();
        if (target == user || !("MEMBER".equals(requested) || "MODERATOR".equals(requested))) {
            throw bad("Choose a member or moderator role for another member.");
        }
        member(target,community);
        jdbc.update("UPDATE community_members SET role=? WHERE community_id=? AND user_id=?", requested,community,target);
    }

    @Transactional(readOnly = true)
    Page<Post> posts(long user, long community,int page,int size) {
        member(user,community);
        return page(jdbc.query(POSTS+" WHERE p.community_id=? ORDER BY p.id DESC LIMIT ? OFFSET ?", POST,
                user,community,size+1,offset(page,size)),page,size);
    }

    @Transactional
    Post post(long user, long community,String title,String body,boolean allowAiSummary) {
        lock(community);
        member(user,community);
        long id=insert("""
                INSERT INTO community_posts(community_id,author_id,title,body,allow_ai_summary,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))
                """,community,user,required(title,160,"Title"),required(body,8000,"Post"),allowAiSummary);
        return findPost(user,id);
    }

    @Transactional
    void report(long user, long post, Long comment, String reason) {
        var entry = lockedPost(user, post);
        var kind = ReportIntake.Kind.COMMUNITY_POST;
        long target = post;
        long author = Long.parseLong(entry.authorId());
        if (comment != null) {
            kind = ReportIntake.Kind.COMMUNITY_COMMENT;
            target = comment;
            author = first("SELECT author_id FROM community_comments WHERE id=? AND post_id=?",
                    (r,i) -> r.getLong(1), comment, post);
        }
        if (user == author) throw bad("Choose content from another member.");
        reports.record(kind, target, user, author, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void hideReportedContent(ReportIntake.Kind kind, long target) {
        String sql = switch (kind) {
            case COMMUNITY_POST -> "DELETE FROM community_posts WHERE id=?";
            case COMMUNITY_COMMENT -> "DELETE FROM community_comments WHERE id=?";
            case PRIVATE_MESSAGE -> throw new IllegalArgumentException("Expected community content");
        };
        jdbc.update(sql, target);
    }

    @Transactional
    void deletePost(long user,long id) {
        Post post=lockedPost(user,id);
        mayModerate(user,Long.parseLong(post.communityId()),Long.parseLong(post.authorId()));
        jdbc.update("DELETE FROM community_posts WHERE id=?",id);
    }

    @Transactional(readOnly = true)
    Page<Comment> comments(long user,long id,int page,int size) {
        visiblePost(user,id);
        return page(jdbc.query("""
                SELECT c.*,COALESCE(s.display_name,'StudySphere member') AS author_name FROM community_comments c
                LEFT JOIN student_profiles s ON s.user_id=c.author_id
                WHERE c.post_id=? ORDER BY c.id LIMIT ? OFFSET ?
                """, (r,i) -> new Comment(r.getString("id"),r.getString("author_id"),r.getString("author_name"),
                r.getString("body"),r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                id,size+1,offset(page,size)),page,size);
    }

    @Transactional
    Comment comment(long user,long id,String body) {
        lockedPost(user,id);
        long comment=insert("INSERT INTO community_comments(post_id,author_id,body,created_at) VALUES (?,?,?,UTC_TIMESTAMP(6))",
                id,user,required(body,4000,"Comment"));
        return first("""
                SELECT c.*,COALESCE(s.display_name,'StudySphere member') AS author_name FROM community_comments c
                LEFT JOIN student_profiles s ON s.user_id=c.author_id WHERE c.id=?
                """,(r,i) -> new Comment(r.getString("id"),r.getString("author_id"),r.getString("author_name"),
                r.getString("body"),r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),comment);
    }

    @Transactional
    void deleteComment(long user,long postId,long commentId) {
        Post post=lockedPost(user,postId);
        long author=first("SELECT author_id FROM community_comments WHERE id=? AND post_id=?",
                (r,i)->r.getLong(1),commentId,postId);
        mayModerate(user,Long.parseLong(post.communityId()),author);
        jdbc.update("DELETE FROM community_comments WHERE id=? AND post_id=?",commentId,postId);
    }

    @Transactional
    Post vote(long user,long id,Integer value) {
        lockedPost(user,id);
        if (value == null) {
            jdbc.update("DELETE FROM community_votes WHERE post_id=? AND user_id=?",id,user);
        } else {
            if (value != 1 && value != -1) throw bad("Vote must be 1 or -1.");
            jdbc.update("INSERT INTO community_votes VALUES (?,?,?) ON DUPLICATE KEY UPDATE value=?",id,user,value,value);
        }
        return findPost(user,id);
    }

    @Transactional(readOnly = true)
    Page<Post> related(long user,long id) {
        Post chosen=visiblePost(user,id);
        Set<String> words=words(chosen.title());
        List<Post> matches=jdbc.query(POSTS+" WHERE p.community_id=? AND p.id<>? ORDER BY p.id DESC LIMIT 100",
                POST,user,Long.parseLong(chosen.communityId()),id).stream()
                .filter(post -> overlap(words,post)>0)
                .sorted(Comparator.comparingLong((Post post)->overlap(words,post)).reversed())
                .limit(5).toList();
        return new Page<>(matches,0,5,false);
    }

    Page<Resource> resources(long user,long community,int page,int size) {
        member(user,community);
        return page(jdbc.query(RESOURCES+" ORDER BY r.id DESC LIMIT ? OFFSET ?",RESOURCE,
                user,community,size+1,offset(page,size)),page,size);
    }

    @Transactional
    void share(long user,long community,long resource) {
        lock(community);
        member(user,community);
        first("SELECT id FROM resources WHERE id=? AND user_id=? FOR SHARE",(r,i)->r.getLong(1),resource,user);
        jdbc.update("""
                INSERT INTO community_resources(community_id,resource_id,shared_by) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE shared_by=community_resources.shared_by
                """,community,resource,user);
    }

    @Transactional
    void unshare(long user,long community,long resource) {
        lock(community);
        member(user,community);
        long author=first("SELECT shared_by FROM community_resources WHERE community_id=? AND resource_id=?",
                (r,i)->r.getLong(1),community,resource);
        mayModerate(user,community,author);
        jdbc.update("DELETE FROM community_resources WHERE community_id=? AND resource_id=?",community,resource);
    }

    Download download(long user,long community,long resource) {
        return first(RESOURCES+" AND r.id=?", (r,i)->new Download(RESOURCE.mapRow(r,i),
                storage.open(r.getString("object_key"))),user,community,resource);
    }

    Summary summarize(long user,long community,boolean consent) {
        if (!consent) throw bad("Confirm the AI disclosure before summarizing.");
        SummaryRequest request=transactions.execute(status -> reserveSummary(user,community));
        if (request == null) throw new IllegalStateException("Missing summary reservation.");
        String result=null;
        try {
            result=mentor.stream("SUMMARIZE",List.of(new MentorProvider.Turn("user",request.input())))
                    .reduceWith(StringBuilder::new,(summary,delta)->{
                        if (summary.length()+delta.length()>4000) throw new IllegalStateException("Summary too large.");
                        return summary.append(delta);
                    }).map(StringBuilder::toString).block(Duration.ofSeconds(30));
            if (result==null || result.isBlank()) throw new IllegalStateException("Summary was empty.");
        } catch (RuntimeException failure) {
            finishUsage(request.id(),"FAILED",0);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The summary provider did not finish.");
        }
        finishUsage(request.id(),"COMPLETE",result.length());
        member(user,community);
        return new Summary(result);
    }

    private SummaryRequest reserveSummary(long user,long community) {
        lock(community);
        member(user,community);
        if (!mentor.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The summary provider is unavailable.");
        var posts=jdbc.query("""
                SELECT title,LEFT(body,600) AS excerpt FROM community_posts WHERE community_id=? AND allow_ai_summary=TRUE
                ORDER BY id DESC LIMIT 12
                """,(r,i)->new Excerpt(r.getString(1),r.getString(2)),community);
        if (posts.isEmpty()) throw conflict("No community posts are opted in to AI summaries.");
        first("SELECT id FROM users WHERE id=? FOR UPDATE",(r,i)->r.getLong(1),user);
        Integer recent=jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_usage_logs WHERE user_id=? AND mode='COMM_SUMMARY'
                AND started_at >= UTC_TIMESTAMP(6)-INTERVAL 1 HOUR
                """,Integer.class,user);
        if (recent!=null && recent>=6) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Try again later.");
        String input="Summarize these consenting community posts. " + json.writeValueAsString(posts);
        long id=insert("""
                INSERT INTO ai_usage_logs(user_id,provider,mode,status,input_characters,started_at)
                VALUES (?,?,'COMM_SUMMARY','STARTED',?,UTC_TIMESTAMP(6))
                """,user,mentor.id(),input.length());
        return new SummaryRequest(id,input);
    }

    private void finishUsage(long id,String status,int characters) {
        jdbc.update("UPDATE ai_usage_logs SET status=?,output_characters=?,finished_at=UTC_TIMESTAMP(6) WHERE id=?",
                status,characters,id);
    }

    private Post findPost(long user,long id) { return first(POSTS+" WHERE p.id=?",POST,user,id); }

    private Post visiblePost(long user,long id) {
        Post result=findPost(user,id);
        member(user,Long.parseLong(result.communityId()));
        return result;
    }

    private Post lockedPost(long user,long id) {
        long community=first("SELECT community_id FROM community_posts WHERE id=?",(r,i)->r.getLong(1),id);
        lock(community);
        member(user,community);
        first("SELECT id FROM community_posts WHERE id=? AND community_id=? FOR UPDATE",
                (r,i)->r.getLong(1),id,community);
        return findPost(user,id);
    }

    private void lock(long community) {
        first("SELECT id FROM communities WHERE id=? FOR UPDATE",(r,i)->r.getLong(1),community);
    }

    private String role(long user,long community) {
        return jdbc.queryForList("SELECT role FROM community_members WHERE community_id=? AND user_id=?",
                String.class,community,user).stream().findFirst().orElse(null);
    }

    private String member(long user,long community) {
        String role=role(user,community);
        if (role==null) throw missing();
        return role;
    }

    private void mayModerate(long user,long community,long author) {
        String role=member(user,community);
        if (user!=author && !"OWNER".equals(role) && !"MODERATOR".equals(role)) throw forbidden();
    }

    private long insert(String sql,Object... args) {
        var key=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var stmt=connection.prepareStatement(sql,java.sql.Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++) stmt.setObject(i+1,args[i]);
            return stmt;
        },key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    private <T> T first(String sql,RowMapper<T> mapper,Object... args) {
        List<T> rows=jdbc.query(sql,mapper,args);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private static long offset(int page,int size) {
        if(page<0 || size<1 || size>100) throw bad("Choose a valid page.");
        return (long)page*size;
    }

    private static <T> Page<T> page(List<T> rows,int page,int size) {
        return new Page<>(rows.subList(0,Math.min(rows.size(),size)),page,size,rows.size()>size);
    }

    private static Set<String> words(String title) {
        return Arrays.stream(title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word->word.length()>=3).collect(Collectors.toSet());
    }

    private static long overlap(Set<String> terms,Post post) {
        return words(post.title()).stream().filter(terms::contains).count();
    }

    private static String required(String value,int max,String label) {
        if(value==null || value.isBlank()) throw bad(label+" is required.");
        return optional(value,max,label);
    }

    private static String optional(String value,int max,String label) {
        String result=value==null?"":value.strip();
        if(result.length()>max) throw bad(label+" exceeds "+max+" characters.");
        return result;
    }

    private static ResponseStatusException bad(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,detail); }
    private static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT,detail); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Community item not found."); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Community moderation permission required."); }

    record Community(String id,String subjectId,String name,String description,String role) {}
    record Member(String userId,String displayName,String role) {}
    record Post(String id,String communityId,String authorId,String authorName,String title,String body,long score,int myVote,Instant createdAt) {}
    record Comment(String id,String authorId,String authorName,String body,Instant createdAt) {}
    record Resource(String id,String title,String fileName,String mediaType,long sizeBytes) {}
    record Download(Resource resource,InputStream content) {}
    record Page<T>(List<T> items,int page,int size,boolean hasNext) {}
    record Summary(String summary) {}
    private record SummaryRequest(long id,String input) {}
    private record Excerpt(String title,String excerpt) {}
}
