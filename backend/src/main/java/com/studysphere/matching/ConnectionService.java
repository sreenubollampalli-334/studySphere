package com.studysphere.matching;

import java.util.List;

import com.studysphere.profile.ProfileService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectionService {
    private final JdbcTemplate jdbc;
    private final ProfileService profiles;

    public ConnectionService(JdbcTemplate jdbc, ProfileService profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    public boolean canMessage(long owner, long peer) {
        if (owner == peer) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM connection_requests
                    WHERE low_user_id=? AND high_user_id=? AND status='ACCEPTED')
                AND NOT EXISTS(SELECT 1 FROM user_blocks
                    WHERE (blocker_id=? AND blocked_id=?) OR (blocker_id=? AND blocked_id=?))
                """, Boolean.class, low(owner, peer), high(owner, peer), owner, peer, peer, owner));
    }

    public void lockPair(long owner, long peer) {
        if (owner == peer) throw invalid("Choose another member.");
        for (long id : new long[]{low(owner, peer), high(owner, peer)}) {
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, id).isEmpty()) {
                throw missing();
            }
        }
    }

    public List<Request> requests(long owner) {
        return jdbc.query("""
                SELECT id, requester_id, IF(low_user_id=?,high_user_id,low_user_id) AS peer_id, status
                FROM connection_requests WHERE (low_user_id=? OR high_user_id=?) AND status='PENDING' ORDER BY id DESC
                """, (row, i) -> requestView(owner, row.getLong("id"), row.getLong("requester_id"),
                row.getLong("peer_id"), row.getString("status")), owner, owner, owner);
    }

    @Transactional
    public Request send(long owner, long peer) {
        lockPair(owner, peer);
        if (!eligible(owner, peer)) throw missing();
        var rows = jdbc.query("""
                SELECT id, status FROM connection_requests WHERE low_user_id=? AND high_user_id=? FOR UPDATE
                """, (row, i) -> new Pair(row.getLong("id"), row.getString("status")), low(owner, peer), high(owner, peer));
        if (!rows.isEmpty()) {
            Pair pair = rows.getFirst();
            if (List.of("PENDING", "ACCEPTED").contains(pair.status())) throw conflict("A connection or request already exists.");
            jdbc.update("UPDATE connection_requests SET requester_id=?,status='PENDING' WHERE id=?", owner, pair.id());
            return requestView(owner, pair.id(), owner, peer, "PENDING");
        }

        jdbc.update("""
                INSERT INTO connection_requests(low_user_id,high_user_id,requester_id,status) VALUES (?,?,?,'PENDING')
                """, low(owner, peer), high(owner, peer), owner);
        long id = jdbc.queryForObject("SELECT id FROM connection_requests WHERE low_user_id=? AND high_user_id=?",
                Long.class, low(owner, peer), high(owner, peer));
        return requestView(owner, id, owner, peer, "PENDING");
    }

    @Transactional
    public Request respond(long owner, long id, String status) {
        if (status == null || !List.of("ACCEPTED", "DECLINED", "CANCELED").contains(status)) {
            throw invalid("Choose ACCEPTED, DECLINED or CANCELED.");
        }
        var peers = jdbc.queryForList("""
                SELECT IF(low_user_id=?,high_user_id,low_user_id) FROM connection_requests
                WHERE id=? AND (low_user_id=? OR high_user_id=?)
                """, Long.class, owner, id, owner, owner);
        if (peers.isEmpty()) throw missing();
        long peer = peers.getFirst();
        lockPair(owner, peer);
        var request = jdbc.query("""
                SELECT requester_id,status FROM connection_requests WHERE id=? FOR UPDATE
                """, (row, i) -> new Pair(row.getLong("requester_id"), row.getString("status")), id);
        if (request.isEmpty() || blocked(owner, peer)) throw missing();
        Pair current = request.getFirst();
        if ((current.id() == owner) != "CANCELED".equals(status)) throw missing();
        if (!current.status().equals("PENDING")) throw conflict("This request is no longer pending.");
        jdbc.update("UPDATE connection_requests SET status=? WHERE id=?", status, id);
        return requestView(owner, id, current.id(), peer, status);
    }

    public List<Peer> connections(long owner) {
        return jdbc.query("""
                SELECT IF(low_user_id=?,high_user_id,low_user_id) AS peer_id FROM connection_requests
                WHERE (low_user_id=? OR high_user_id=?) AND status='ACCEPTED' ORDER BY id DESC
                """, (row, i) -> view(row.getLong("peer_id")), owner, owner, owner);
    }

    @Transactional
    public void disconnect(long owner, long peer) {
        lockPair(owner, peer);
        jdbc.update("DELETE FROM connection_requests WHERE low_user_id=? AND high_user_id=? AND status='ACCEPTED'",
                low(owner, peer), high(owner, peer));
    }

    public List<Peer> blocks(long owner) {
        return jdbc.query("SELECT blocked_id FROM user_blocks WHERE blocker_id=? ORDER BY blocked_id",
                (row, i) -> view(row.getLong(1)), owner);
    }

    @Transactional
    public void block(long owner, long peer) {
        lockPair(owner, peer);
        if (!blockedBy(owner, peer)) {
            jdbc.update("INSERT INTO user_blocks(blocker_id,blocked_id) VALUES (?,?)", owner, peer);
        }
        jdbc.update("DELETE FROM connection_requests WHERE low_user_id=? AND high_user_id=?",
                low(owner, peer), high(owner, peer));
    }

    @Transactional
    public void unblock(long owner, long peer) {
        lockPair(owner, peer);
        jdbc.update("DELETE FROM user_blocks WHERE blocker_id=? AND blocked_id=?", owner, peer);
    }

    public List<Long> candidates(long owner) {
        return jdbc.queryForList("""
                SELECT p.user_id FROM student_profiles p JOIN users u ON u.id=p.user_id
                WHERE p.discoverable=TRUE AND p.user_id<>? AND u.status='ACTIVE'
                AND NOT EXISTS(SELECT 1 FROM user_blocks
                    WHERE (blocker_id=? AND blocked_id=p.user_id) OR (blocker_id=p.user_id AND blocked_id=?))
                ORDER BY p.user_id
                """, Long.class, owner, owner, owner);
    }

    private boolean eligible(long owner, long peer) {
        return !blocked(owner, peer) && profiles.publicProfile(peer).isPresent();
    }

    private boolean blocked(long a, long b) {
        return blockedBy(a, b) || blockedBy(b, a);
    }

    private boolean blockedBy(long a, long b) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_blocks WHERE blocker_id=? AND blocked_id=?)
                """, Boolean.class, a, b));
    }

    private Request requestView(long owner, long id, long sender, long peer, String status) {
        return new Request(Long.toString(id), Long.toString(peer), view(peer).displayName(),
                sender == owner ? "OUTGOING" : "INCOMING", status);
    }

    private Peer view(long id) {
        String name = profiles.publicProfile(id).map(ProfileService.PublicProfile::displayName).orElse("StudySphere member");
        return new Peer(Long.toString(id), name);
    }

    static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) return id;
        } catch (NumberFormatException ignored) {
            // Invalid identifier syntax.
        }
        throw invalid("Send a valid member or request ID.");
    }

    static MatchingFailure invalid(String message) { return new MatchingFailure(400, message); }
    private static MatchingFailure missing() { return new MatchingFailure(404, "Member or request unavailable."); }
    private static MatchingFailure conflict(String message) { return new MatchingFailure(409, message); }
    private static long low(long a, long b) { return Math.min(a,b); }
    private static long high(long a, long b) { return Math.max(a,b); }

    public record Peer(String userId, String displayName) {}
    public record Request(String id, String userId, String displayName, String direction, String status) {}
    private record Pair(long id, String status) {}
}
