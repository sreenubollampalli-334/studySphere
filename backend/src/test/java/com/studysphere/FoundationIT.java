package com.studysphere;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.studysphere.auth.AuthMailer;
import com.studysphere.notification.NotificationService;
import com.studysphere.document.engine.DocumentEngine;
import com.studysphere.mentor.provider.MentorProvider;
import reactor.core.publisher.Flux;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"studysphere.auth.secure-cookies=false",
                "studysphere.mentor.requests-per-minute=2", "studysphere.mentor.timeout-seconds=2"})
@Import(FoundationIT.MailConfiguration.class)
class FoundationIT {

    private static final String PASSWORD = "StudySphere test password";
    private static final String NEW_PASSWORD = "Updated StudySphere test password";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        String url = requiredEnvironment("STUDYSPHERE_TEST_DB_URL");
        String user = requiredEnvironment("STUDYSPHERE_TEST_DB_USER");
        String password = requiredEnvironment("STUDYSPHERE_TEST_DB_PASSWORD");
        requireFreshSchema(url, user, password);

        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }

    @Autowired
    Flyway flyway;

    @Autowired
    NotificationService notifications;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    RecordingMailer mailer;

    @LocalServerPort
    int port;

    @Test
    void appliesBaselineToFreshMysql() {
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .contains("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
                        "16", "17", "18", "19", "20", "21", "22");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void exposesOnlyPublicHealthStatus() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var health = get(client, "/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");

            var environment = get(client, "/actuator/env");
            assertThat(environment.statusCode()).isEqualTo(403);
        }
    }

    @Test
    void enforcesAdminRoleAndInvalidatesAuthenticationWhileRestricted() throws Exception {
        String email = newEmail();
        String adminEmail = newEmail();
        String restrictions = "/api/v1/admin/restrictions";
        try (var student = new Browser(); var admin = new Browser()) {
            registerAndVerify(student, email);
            registerAndVerify(admin, adminEmail);
            String studentToken = accessToken(student.post("/login", credentials(email, PASSWORD)));
            String adminToken = accessToken(admin.post("/login", credentials(adminEmail, PASSWORD)));
            long userId = jdbc.queryForObject("SELECT id FROM users WHERE canonical_email = ?", Long.class, email);
            String restriction = json.createObjectNode().put("reason", "Repeated policy violations")
                    .put("expiresAt", java.time.Instant.now().plus(Duration.ofHours(1)).toString()).toString();
            assertThat(student.send("GET", restrictions, null, null).statusCode()).isEqualTo(401);
            assertThat(student.send("GET", restrictions, null, studentToken).statusCode()).isEqualTo(403);
            assertThat(student.send("PUT", restrictions + "/" + userId, restriction, studentToken).statusCode())
                    .isEqualTo(403);

            jdbc.update("""
                    INSERT INTO user_roles (user_id, role_id)
                    SELECT users.id, roles.id FROM users CROSS JOIN roles
                    WHERE users.canonical_email = ? AND roles.code = 'ADMIN'
                    """, adminEmail);
            assertThat(admin.send("GET", restrictions, null, adminToken).statusCode()).isEqualTo(200);
            assertThat(admin.send("PUT", restrictions + "/" + userId, restriction, adminToken).statusCode())
                    .isBetween(200, 299);
            assertThat(student.me(studentToken).statusCode()).isEqualTo(401);
            assertThat(student.post("/refresh", null).statusCode()).isEqualTo(401);
            assertThat(student.post("/login", credentials(email, PASSWORD)).body()).contains("ACCOUNT_RESTRICTED");
            assertThat(admin.send("DELETE", restrictions + "/" + userId,
                    "{\"reason\":\"Review completed\"}", adminToken).statusCode()).isEqualTo(204);
            assertThat(student.me(studentToken).statusCode()).isEqualTo(401);
            assertThat(student.post("/login", credentials(email, PASSWORD)).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void verifiesAccountsAndRevokesARefreshFamilyOnReplay() throws Exception {
        String email = newEmail();
        try (var browser = new Browser(); var replay = new Browser()) {
            assertThat(browser.post("/register", credentials(email, PASSWORD)).statusCode()).isEqualTo(403);
            browser.bootstrapCsrf();
            assertThat(browser.post("/register", credentials(email, PASSWORD)).statusCode()).isEqualTo(202);
            assertThat(browser.post("/register", credentials(email.toUpperCase(java.util.Locale.ROOT), PASSWORD))
                    .statusCode()).isEqualTo(202);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE canonical_email = ?", Integer.class, email))
                    .isOne();
            assertThat(browser.me(null).statusCode()).isEqualTo(401);
            assertThat(browser.post("/login", credentials(email, PASSWORD)).statusCode()).isEqualTo(403);

            String verification = mailer.verificationToken(email);
            assertThat(browser.post("/verify", tokenBody(verification)).statusCode()).isEqualTo(204);
            assertThat(browser.post("/verify", tokenBody(verification)).statusCode()).isEqualTo(400);
            assertThat(browser.post("/login", credentials(email, "incorrect test password")).statusCode()).isEqualTo(401);

            var login = browser.post("/login", credentials(email, PASSWORD));
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.headers().allValues("set-cookie").stream()
                    .anyMatch(header -> header.startsWith("studysphere_refresh=") && header.contains("HttpOnly"))).isTrue();
            String firstAccess = accessToken(login);
            String firstRefresh = browser.cookie("studysphere_refresh");
            var me = browser.me(firstAccess);
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(json.readTree(me.body()).get("email").asText()).isEqualTo(email);
            assertThat(json.readTree(me.body()).get("roles").get(0).asText()).isEqualTo("STUDENT");
            assertThat(me.body()).doesNotContain("password", "hash");

            var rotation = browser.post("/refresh", null);
            assertThat(rotation.statusCode()).isEqualTo(200);
            String successorAccess = accessToken(rotation);
            assertThat(browser.cookie("studysphere_refresh").equals(firstRefresh)).isFalse();
            assertThat(browser.me(firstAccess).statusCode()).isEqualTo(401);
            assertThat(browser.me(successorAccess).statusCode()).isEqualTo(200);

            replay.bootstrapCsrf();
            replay.installRefreshCookie(firstRefresh);
            assertThat(replay.post("/refresh", null).statusCode()).isEqualTo(401);
            assertThat(browser.me(successorAccess).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void resetRejectsExpiryAndOneUseAndLogoutRevokesAccess() throws Exception {
        String email = newEmail();
        try (var browser = new Browser()) {
            registerAndVerify(browser, email);
            String initialAccess = accessToken(browser.post("/login", credentials(email, PASSWORD)));
            assertThat(browser.post("/forgot-password", "{\"email\":\"%s\"}".formatted(email)).statusCode())
                    .isEqualTo(202);
            String expired = mailer.resetToken(email);
            jdbc.update("""
                    UPDATE password_reset_tokens SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 HOUR
                    WHERE user_id = (SELECT id FROM users WHERE canonical_email = ?)
                    """, email);
            assertThat(browser.post("/reset", resetBody(expired)).statusCode()).isEqualTo(400);

            assertThat(browser.post("/forgot-password", "{\"email\":\"%s\"}".formatted(email)).statusCode())
                    .isEqualTo(202);
            String valid = mailer.resetToken(email);
            assertThat(browser.post("/reset", resetBody(valid)).statusCode()).isEqualTo(204);
            assertThat(browser.post("/reset", resetBody(valid)).statusCode()).isEqualTo(400);
            assertThat(browser.me(initialAccess).statusCode()).isEqualTo(401);
            assertThat(browser.post("/login", credentials(email, PASSWORD)).statusCode()).isEqualTo(401);

            String newAccess = accessToken(browser.post("/login", credentials(email, NEW_PASSWORD)));
            assertThat(browser.me(newAccess).statusCode()).isEqualTo(200);
            assertThat(browser.post("/logout", null).statusCode()).isEqualTo(204);
            assertThat(browser.me(newAccess).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void deletionRequiresRecentPasswordAuthentication() throws Exception {
        String email = newEmail();
        try (var browser = new Browser()) {
            registerAndVerify(browser, email);
            String oldAccess = accessToken(browser.post("/login", credentials(email, PASSWORD)));
            jdbc.update("""
                    UPDATE refresh_tokens SET password_authenticated_at = UTC_TIMESTAMP(6) - INTERVAL 6 MINUTE
                    WHERE user_id = (SELECT id FROM users WHERE canonical_email = ?)
                    """, email);
            assertThat(browser.send("DELETE", "/api/v1/me", null, oldAccess).statusCode()).isEqualTo(403);
            String freshAccess = accessToken(browser.post("/login", credentials(email, PASSWORD)));
            assertThat(browser.send("DELETE", "/api/v1/me", null, freshAccess).statusCode()).isEqualTo(204);
            assertThat(browser.me(freshAccess).statusCode()).isEqualTo(401);
            assertThat(browser.post("/login", credentials(email, PASSWORD)).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void studentWorkspaceKeepsProfilesPrivateAndRejectsOverlaps() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));

            var dashboard = owner.send("GET", "/api/v1/dashboard", null, token);
            assertThat(dashboard.statusCode()).isEqualTo(200);
            assertThat(json.readTree(dashboard.body()).get("todayTasks").size()).isZero();
            var profile = owner.send("PUT", "/api/v1/me/profile",
                    """
                    {"displayName":"Private learner","institution":"Example College","discoverable":false}
                    """, token);
            assertThat(profile.statusCode()).isEqualTo(200);
            assertThat(profile.body()).contains("Private learner");
            assertThat(json.readTree(profile.body()).get("onboardingComplete").booleanValue()).isFalse();
            assertThat(owner.send("PUT", "/api/v1/me/preferences",
                    """
                    {"language":"en","timeZone":"Asia/Kolkata","learningStyles":["practice"],"skills":["SQL"]}
                    """, token).statusCode()).isEqualTo(200);
            var catalog = owner.send("GET", "/api/v1/subjects", null, token);
            assertThat(catalog.statusCode()).isEqualTo(200);
            var subjects = json.readTree(catalog.body()).get("items");
            assertThat(subjects.size()).isPositive();
            String subjectId = subjects.get(0).get("id").asText();
            assertThat(owner.send("PUT", "/api/v1/me/subjects",
                    "{\"subjectIds\":[\"%s\"]}".formatted(subjectId), token).statusCode()).isEqualTo(200);
            var completed = owner.send("GET", "/api/v1/dashboard", null, token);
            assertThat(json.readTree(completed.body()).get("onboardingComplete").booleanValue()).isTrue();
            var goal = owner.send("POST", "/api/v1/me/goals",
                    "{\"subjectId\":\"%s\",\"description\":\"Practice key concepts\"}".formatted(subjectId), token);
            assertThat(goal.statusCode()).isEqualTo(201);
            String goalId = json.readTree(goal.body()).get("id").asText();
            assertThat(peer.send("DELETE", "/api/v1/me/goals/" + goalId, null, peerToken).statusCode())
                    .isEqualTo(404);
            assertThat(owner.send("DELETE", "/api/v1/me/goals/" + goalId, null, token).statusCode())
                    .isEqualTo(204);
            assertThat(peer.send("GET", "/api/v1/me/profile", null, peerToken).body())
                    .doesNotContain("Private learner", "Example College");
            assertThat(owner.send("PUT", "/api/v1/me/availability",
                    """
                    {"slots":[{"day":"MONDAY","start":"18:00","end":"19:00"},
                    {"day":"MONDAY","start":"18:30","end":"19:30"}]}
                    """, token).statusCode()).isEqualTo(400);
            assertThat(owner.send("GET", "/api/v1/dashboard", null, null).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void focusSessionSurvivesRetrievalAndCompletesOnlyOnce() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));

            String collection = "/api/v1/focus/sessions";
            String body = "{\"focusMinutes\":25,\"breakMinutes\":5}";
            var first = owner.send("POST", collection, body, token);
            assertThat(first.statusCode()).isEqualTo(201);
            String id = json.readTree(first.body()).get("id").asText();
            String resource = collection + "/" + id;
            assertThat(owner.send("GET", resource, null, token).body()).contains("ACTIVE");
            assertThat(owner.send("POST", collection, body, token).statusCode()).isEqualTo(409);
            assertThat(peer.send("GET", resource, null, peerToken).statusCode()).isEqualTo(404);
            var completed = owner.send("POST", resource + "/complete", null, token);
            assertThat(completed.statusCode()).isEqualTo(200);
            assertThat(completed.body()).contains("COMPLETED");
            assertThat(owner.send("POST", resource + "/complete", null, token).body())
                    .isEqualTo(completed.body());

            var exam = owner.send("POST", "/api/v1/exams", """
                    {"subjectId":"7","title":"Database final","examDate":"2030-12-12",
                     "timeZone":"Asia/Kolkata","topicIds":["13"]}
                    """, token);
            assertThat(exam.statusCode()).isEqualTo(201);
            String examPath = "/api/v1/exams/" + json.readTree(exam.body()).get("id").asText();
            assertThat(peer.send("GET", examPath, null, peerToken).statusCode()).isEqualTo(404);
            assertThat(owner.send("POST", "/api/v1/exams", """
                    {"subjectId":"7","title":"Invalid topic","examDate":"2030-12-12",
                     "timeZone":"Asia/Kolkata","topicIds":["1"]}
                    """, token).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void recurringTaskCompletionProducesExactlyOneNextOccurrence() throws Exception {
        try (var owner = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            var created = owner.send("POST", "/api/v1/tasks", """
                    {"title":"Review notes","dueAt":"2030-03-09T14:00:00Z",
                     "recurrenceRule":"DAILY","recurrenceZone":"America/New_York"}
                    """, token);
            assertThat(created.statusCode()).isEqualTo(201);
            var task = json.readTree(created.body());
            String path = "/api/v1/tasks/" + task.get("id").asText();
            String completion = "{\"state\":\"COMPLETED\",\"version\":%d}".formatted(task.get("version").intValue());
            assertThat(owner.send("PATCH", path, completion, token).statusCode()).isEqualTo(200);
            assertThat(owner.send("PATCH", path, completion, token).statusCode()).isEqualTo(200);
            var list = owner.send("GET", "/api/v1/tasks?state=OPEN", null, token);
            assertThat(list.statusCode()).isEqualTo(200);
            var items = json.readTree(list.body()).get("items");
            assertThat(items.size()).isOne();
            assertThat(items.get(0).get("dueAt").asText()).isEqualTo("2030-03-10T13:00:00Z");
        }
    }

    @Test
    void resourceDownloadsRequireCurrentPermission() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));
            String peerId = json.readTree(peer.me(peerToken).body()).get("id").asText();

            assertThat(owner.upload("notes.exe", "notes", token).statusCode()).isEqualTo(415);
            var upload = owner.upload("notes.txt", "Study notes", token);
            assertThat(upload.statusCode()).isEqualTo(201);
            String id = json.readTree(upload.body()).get("id").asText();
            String path = "/api/v1/resources/" + id;
            assertThat(peer.send("GET", path + "/download", null, peerToken).statusCode()).isEqualTo(404);
            assertThat(owner.send("POST", path + "/permissions",
                    "{\"email\":\"%s\"}".formatted(peerEmail), token).statusCode()).isEqualTo(201);
            var download = peer.send("GET", path + "/download", null, peerToken);
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo("Study notes");
            assertThat(owner.send("DELETE", path + "/permissions/" + peerId, null, token).statusCode()).isEqualTo(204);
            assertThat(peer.send("GET", path + "/download", null, peerToken).statusCode()).isEqualTo(404);
            assertThat(owner.send("DELETE", path, null, token).statusCode()).isEqualTo(204);
            assertThat(owner.send("GET", path + "/download", null, token).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void partnerDiscoveryProtectsPrivateFieldsAndBlocksDuplicateRequests() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));
            String peerId = json.readTree(peer.me(peerToken).body()).get("id").asText();

            assertThat(peer.send("PUT", "/api/v1/me/profile",
                    """
                    {"displayName":"Discoverable learner","bio":"Private matching fixture","discoverable":true}
                    """, peerToken).statusCode()).isEqualTo(200);
            String sharedSubjects = "{\"subjectIds\":[\"7\"]}";
            assertThat(owner.send("PUT", "/api/v1/me/subjects", sharedSubjects, token).statusCode()).isEqualTo(200);
            assertThat(peer.send("PUT", "/api/v1/me/subjects", sharedSubjects, peerToken).statusCode()).isEqualTo(200);
            var results = owner.send("GET", "/api/v1/partners", null, token);
            assertThat(results.statusCode()).isEqualTo(200);
            assertThat(results.body()).contains("Discoverable learner").doesNotContain(peerEmail, "Private matching fixture");
            assertThat(json.readTree(results.body()).get("items").get(0).get("score").doubleValue()).isPositive();
            String request = "{\"userId\":\"%s\"}".formatted(peerId);
            var connection = owner.send("POST", "/api/v1/connections/requests", request, token);
            assertThat(connection.statusCode()).isEqualTo(201);
            assertThat(owner.send("POST", "/api/v1/connections/requests", request, token).statusCode()).isEqualTo(409);
            String requestId = json.readTree(connection.body()).get("id").asText();
            assertThat(peer.send("PATCH", "/api/v1/connections/requests/" + requestId,
                    "{\"status\":\"ACCEPTED\"}", peerToken).statusCode()).isEqualTo(200);
            var conversation = owner.send("POST", "/api/v1/conversations", request, token);
            assertThat(conversation.statusCode()).isEqualTo(201);
            String conversationId = json.readTree(conversation.body()).get("id").asText();
            String messages = "/api/v1/conversations/" + conversationId + "/messages";
            var first = owner.send("POST", messages, "{\"body\":\"First study message\"}", token);
            assertThat(first.statusCode()).isEqualTo(201);
            String firstId = json.readTree(first.body()).get("id").asText();
            assertThat(owner.send("POST", messages, "{\"body\":\"Missed study message\"}", token).statusCode())
                    .isEqualTo(201);
            var recovered = peer.send("GET", messages + "?afterId=" + firstId, null, peerToken);
            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(recovered.body()).contains("Missed study message").doesNotContain("First study message");
            assertThat(owner.send("PUT", "/api/v1/blocks/" + peerId, null, token).statusCode()).isEqualTo(204);
            assertThat(peer.send("GET", messages, null, peerToken).statusCode()).isEqualTo(404);
            assertThat(owner.send("GET", "/api/v1/partners", null, token).body())
                    .doesNotContain("Discoverable learner");
        }
    }

    @Test
    void roomCapacityAndMembershipAreEnforcedAcrossConcurrentJoins() throws Exception {
        try (var owner = new Browser(); var peer = new Browser(); var other = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));
            String otherEmail = newEmail();
            registerAndVerify(other, otherEmail);
            String otherToken = accessToken(other.post("/login", credentials(otherEmail, PASSWORD)));

            var room = owner.send("POST", "/api/v1/rooms",
                    "{\"name\":\"Capacity test\",\"visibility\":\"PUBLIC\",\"capacity\":2}", token);
            assertThat(room.statusCode()).isEqualTo(201);
            String path = "/api/v1/rooms/" + json.readTree(room.body()).get("id").asText();
            boolean peerJoined;
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> peer.send("PUT", path + "/members/me", null, peerToken));
                var second = executor.submit(() -> other.send("PUT", path + "/members/me", null, otherToken));
                var firstResult = first.get();
                var secondResult = second.get();
                assertThat(java.util.List.of(firstResult.statusCode(), secondResult.statusCode()))
                        .containsExactlyInAnyOrder(200, 409);
                peerJoined = firstResult.statusCode() == 200;
            }
            var chat = owner.send("POST", path + "/messages", "{\"body\":\"Room study plan\"}", token);
            assertThat(chat.statusCode()).isEqualTo(201);
            String pin = path + "/messages/" + json.readTree(chat.body()).get("id").asText() + "/pin";
            assertThat((peerJoined ? peer : other).send("PUT", pin, null, peerJoined ? peerToken : otherToken)
                    .statusCode()).isEqualTo(403);
            assertThat(owner.send("PUT", pin, null, token).statusCode()).isEqualTo(204);
            var privateRoom = owner.send("POST", "/api/v1/rooms",
                    "{\"name\":\"Private group\",\"visibility\":\"PRIVATE\",\"capacity\":2}", token);
            assertThat(privateRoom.statusCode()).isEqualTo(201);
            String privatePath = "/api/v1/rooms/" + json.readTree(privateRoom.body()).get("id").asText();
            assertThat(peer.send("GET", privatePath + "/messages", null, peerToken).statusCode()).isEqualTo(404);
            var response = new java.util.concurrent.LinkedBlockingQueue<String>();
            var socket = peer.client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .header("Origin", "http://localhost:5173").subprotocols("v12.stomp")
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/messages"),
                            new java.net.http.WebSocket.Listener() {
                                private final StringBuilder text = new StringBuilder();

                                @Override
                                public java.util.concurrent.CompletionStage<?> onText(
                                        java.net.http.WebSocket connection, CharSequence part, boolean last) {
                                    text.append(part);
                                    if (last) {
                                        response.add(text.toString());
                                        text.setLength(0);
                                    }
                                    connection.request(1);
                                    return null;
                                }
                            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                socket.sendText("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                        + peerToken + "\n\n\0", true).get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.poll(10, java.util.concurrent.TimeUnit.SECONDS)).startsWith("CONNECTED");
                socket.sendText("SUBSCRIBE\nid:private-room\ndestination:/user/queue"
                        + privatePath.substring("/api/v1".length()) + "\n\n\0", true)
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.poll(10, java.util.concurrent.TimeUnit.SECONDS)).startsWith("ERROR");
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void mentorStreamsAndStoresTurnsOnlyForTheConversationOwner() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));

            String base = "/api/v1/ai/conversations";
            var created = owner.send("POST", base, "{\"title\":\"Database questions\"}", token);
            assertThat(created.statusCode()).isEqualTo(201);
            String path = base + "/" + json.readTree(created.body()).get("id").asText();
            assertThat(peer.send("GET", path + "/messages", null, peerToken).statusCode()).isEqualTo(404);
            String prompt = "{\"message\":\"Explain normalization\",\"mode\":\"EXPLAIN\"}";
            assertThat(peer.send("POST", path + "/stream", prompt, peerToken).statusCode()).isEqualTo(404);
            var stream = owner.send("POST", path + "/stream", prompt, token);
            assertThat(stream.statusCode()).isEqualTo(200);
            assertThat(stream.headers().firstValue("content-type")).hasValueSatisfying(type -> assertThat(type).contains("text/event-stream"));
            assertThat(stream.body()).contains("event:delta", "event:complete", "Normalize");
            var history = owner.send("GET", path + "/messages", null, token);
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(history.body()).contains("Explain normalization", "Normalize your schema.");
            var timeout = owner.send("POST", path + "/stream",
                    "{\"message\":\"Timeout test fixture\",\"mode\":\"EXPLAIN\"}", token);
            assertThat(timeout.statusCode()).isEqualTo(200);
            assertThat(timeout.body()).contains("event:error").doesNotContain("event:complete");
            assertThat(owner.send("POST", path + "/stream", prompt, token).statusCode()).isEqualTo(429);
        }
    }

    @Test
    void documentLifecycleEnforcesOwnershipAndReturnsCanonicalCitations() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));
            var upload = owner.upload("document.txt", "Source fixture", token);
            assertThat(upload.statusCode()).isEqualTo(201);
            String resource = json.readTree(upload.body()).get("id").asText();
            var created = owner.send("POST", "/api/v1/documents",
                    "{\"resourceId\":\"%s\"}".formatted(resource), token);
            assertThat(created.statusCode()).isEqualTo(201);
            String path = "/api/v1/documents/" + json.readTree(created.body()).get("id").asText();
            assertThat(peer.send("GET", path + "/status", null, peerToken).statusCode()).isEqualTo(404);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                    assertThat(owner.send("GET", path + "/status", null, token).body()).contains("FAILED"));
            assertThat(owner.send("POST", path + "/retry", null, token).statusCode()).isEqualTo(200);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                    assertThat(owner.send("GET", path + "/status", null, token).body()).contains("READY"));
            var answer = owner.send("POST", path + "/ask", "{\"question\":\"What is in the source?\"}", token);
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(json.readTree(answer.body()).get("citations").get(0).get("text").asText())
                    .isEqualTo("Source fixture");
            var empty = owner.send("POST", path + "/ask", "{\"question\":\"No matches fixture\"}", token);
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(json.readTree(empty.body()).get("citations").size()).isZero();
            assertThat(owner.send("DELETE", path, null, token).statusCode()).isEqualTo(204);
            assertThat(owner.send("GET", path + "/status", null, token).statusCode()).isEqualTo(404);
            long documentId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id=?", Integer.class, documentId)).isZero());
        }
    }

    @Test
    void quizzesHideAnswersAndGradeSubmissionOnlyOnce() throws Exception {
        try (var owner = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            var quiz = owner.send("POST", "/api/v1/quizzes", """
                    {"title":"Database practice","questions":[{"prompt":"What does SQL query?","type":"MCQ",
                    "options":["Tables","Images"],"correctIndex":0,"explanation":"SQL queries tables.","topicId":"13"}]}
                    """, token);
            assertThat(quiz.statusCode()).isEqualTo(201);
            String path = "/api/v1/quizzes/" + json.readTree(quiz.body()).get("id").asText();
            var details = owner.send("GET", path, null, token);
            assertThat(details.statusCode()).isEqualTo(200);
            assertThat(details.body()).doesNotContain("correctIndex", "SQL queries tables.");
            String question = json.readTree(details.body()).get("questions").get(0).get("id").asText();
            var attempt = owner.send("POST", path + "/attempts", null, token);
            assertThat(attempt.statusCode()).isEqualTo(201);
            String submit = "/api/v1/attempts/" + json.readTree(attempt.body()).get("id").asText() + "/submit";
            String answer = "{\"answers\":[{\"questionId\":\"%s\",\"selectedIndex\":0}]}".formatted(question);
            var graded = owner.send("POST", submit, answer, token);
            assertThat(graded.statusCode()).isEqualTo(200);
            assertThat(json.readTree(graded.body()).get("score").intValue()).isOne();
            assertThat(owner.send("POST", submit, answer, token).body()).isEqualTo(graded.body());
        }
    }

    @Test
    void flashcardReviewsAreIdempotentAndAdvanceDueDates() throws Exception {
        try (var owner = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            var deck = owner.send("POST", "/api/v1/flashcard-decks", """
                    {"title":"Database terms","cards":[{"front":"What is SQL?","back":"A query language."}]}
                    """, token);
            assertThat(deck.statusCode()).isEqualTo(201);
            String cardId = json.readTree(deck.body()).get("cards").get(0).get("id").asText();
            String review = "/api/v1/flashcards/" + cardId + "/reviews";
            String requestKey = UUID.randomUUID().toString();
            String recall = "{\"rating\":\"GOOD\",\"requestId\":\"%s\"}".formatted(requestKey);
            var scheduled = owner.send("POST", review, recall, token);
            assertThat(scheduled.statusCode()).isEqualTo(200);
            var result = json.readTree(scheduled.body());
            assertThat(java.time.Instant.parse(result.get("dueAt").asText()))
                    .isAfter(java.time.Instant.parse(result.get("reviewedAt").asText()));
            assertThat(owner.send("POST", review, recall, token).body()).isEqualTo(scheduled.body());
            assertThat(owner.send("POST", review, recall.replace("GOOD", "EASY"), token).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void proposalRequiresOwnerApprovalAndDoesNotDuplicateTasks() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));

            String date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
            String base = "/api/v1/plans/proposals";
            assertThat(owner.send("PUT", "/api/v1/me/subjects", "{\"subjectIds\":[\"7\"]}", token).statusCode()).isEqualTo(200);
            var mastery = owner.send("GET", "/api/v1/analytics/topics", null, token);
            assertThat(mastery.statusCode()).isEqualTo(200);
            assertThat(mastery.body()).contains("\"state\":\"INSUFFICIENT\"", "\"score\":null");
            String request = "{\"title\":\"Database review\",\"startDate\":\"%s\",\"endDate\":\"%s\",\"minutesPerDay\":%d}";
            assertThat(owner.send("POST", base, request.formatted(date, date, 15), token).statusCode()).isEqualTo(502);
            var preview = owner.send("POST", base, request.formatted(date, date, 60), token);
            assertThat(preview.statusCode()).isEqualTo(201);
            String location = base + "/" + json.readTree(preview.body()).get("id").asText();
            assertThat(peer.send("GET", location, null, peerToken).statusCode()).isEqualTo(404);
            assertThat(owner.send("GET", "/api/v1/tasks", null, token).body()).doesNotContain("Review databases fixture");
            var approval = owner.send("POST", location + "/approve", null, token);
            assertThat(approval.statusCode()).isEqualTo(200);
            assertThat(approval.body()).contains("APPROVED");
            assertThat(owner.send("POST", location + "/approve", null, token).body()).isEqualTo(approval.body());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_tasks WHERE title=?",
                    Integer.class, "Review databases fixture")).isOne();
        }
    }

    @Test
    void collaborationEnforcesConsentAndUniqueVotes() throws Exception {
        try (var owner = new Browser(); var peer = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String peerEmail = newEmail();
            registerAndVerify(peer, peerEmail);
            String peerToken = accessToken(peer.post("/login", credentials(peerEmail, PASSWORD)));
            var community = owner.send("POST", "/api/v1/communities",
                    "{\"subjectId\":\"7\",\"name\":\"Collaborative databases\"}", token);
            assertThat(community.statusCode()).isEqualTo(201);
            String communityId = json.readTree(community.body()).get("id").asText();
            String communityPath = "/api/v1/communities/" + communityId;
            assertThat(peer.send("PUT", communityPath + "/members/me", null, peerToken).statusCode()).isEqualTo(200);
            var post = owner.send("POST", "/api/v1/posts",
                    "{\"communityId\":\"%s\",\"title\":\"Normalization\",\"body\":\"Study together\"}".formatted(communityId), token);
            assertThat(post.statusCode()).isEqualTo(201);
            String postPath = "/api/v1/posts/" + json.readTree(post.body()).get("id").asText();
            String vote = "{\"value\":1}";
            for (int i = 0; i < 2; i++) {
                var voted = peer.send("PUT", postPath + "/vote", vote, peerToken);
                assertThat(voted.statusCode()).isEqualTo(200);
                assertThat(json.readTree(voted.body()).get("score").intValue()).isOne();
            }
            assertThat(peer.send("DELETE", postPath, null, peerToken).statusCode()).isEqualTo(403);
            var resource = owner.upload("community-notes.txt", "Shared study notes", token);
            assertThat(resource.statusCode()).isEqualTo(201);
            String shared = communityPath + "/resources/" + json.readTree(resource.body()).get("id").asText();
            assertThat(owner.send("PUT", shared, null, token).statusCode()).isEqualTo(204);
            var download = peer.send("GET", shared + "/download", null, peerToken);
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo("Shared study notes");

            var room = owner.send("POST", "/api/v1/rooms",
                    "{\"name\":\"Assistant test room\",\"visibility\":\"PRIVATE\",\"capacity\":2}", token);
            assertThat(room.statusCode()).isEqualTo(201);
            String path = "/api/v1/rooms/" + json.readTree(room.body()).get("id").asText();
            assertThat(peer.send("GET", path + "/assistant", null, peerToken).statusCode()).isEqualTo(404);
            String disclosure = "{\"disclosed\":true}";
            assertThat(owner.send("POST", path + "/summaries", disclosure, token).statusCode()).isEqualTo(409);
            assertThat(owner.send("PUT", path + "/assistant/consent", "{\"consented\":true}", token).statusCode())
                    .isEqualTo(204);
            assertThat(owner.send("POST", path + "/notes", "{\"text\":\"Review normal forms.\"}", token).statusCode())
                    .isEqualTo(201);
            var summary = owner.send("POST", path + "/summaries", disclosure, token);
            assertThat(summary.statusCode()).isEqualTo(201);
            String proposalId = json.readTree(summary.body()).get("proposals").get(0).get("id").asText();
            String approve = path + "/assistant/proposals/" + proposalId + "/approve";
            var saved = owner.send("POST", approve, null, token);
            assertThat(saved.statusCode()).isEqualTo(200);
            assertThat(json.readTree(saved.body()).get("approved").booleanValue()).isTrue();
            assertThat(owner.send("POST", approve, null, token).body()).isEqualTo(saved.body());
        }
    }

    @Test
    void taskNotificationsAreRetrySafeAndRespectPreferences() throws Exception {
        try (var owner = new Browser()) {
            String email = newEmail();
            registerAndVerify(owner, email);
            String token = accessToken(owner.post("/login", credentials(email, PASSWORD)));
            String notificationPath = "/api/v1/notifications";
            String lastTask = null;
            for (boolean enabled : new boolean[] {true, false}) {
                String preference = "{\"taskReminders\":%s,\"invitations\":false,\"messages\":false,\"exams\":false,\"revision\":false}"
                        .formatted(enabled);
                int saved = owner.send("PUT", "/api/v1/me/notification-preferences", preference, token).statusCode();
                assertThat(saved).isIn(200, 204);
                var task = owner.send("POST", "/api/v1/tasks", "{\"title\":\"Review notes\"}", token);
                assertThat(task.statusCode()).isEqualTo(201);
                String taskId = json.readTree(task.body()).get("id").asText();
                lastTask = taskId;
                String scheduled = java.time.Instant.now().plusSeconds(300).toString();
                var reminder = owner.send("POST", "/api/v1/tasks/" + taskId + "/reminders",
                        "{\"scheduledAt\":\"%s\"}".formatted(scheduled), token);
                assertThat(reminder.statusCode()).isEqualTo(201);
                jdbc.update("UPDATE task_reminders SET scheduled_at=UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE task_id=?", taskId);
                notifications.deliver();
                notifications.deliver();
                var list = owner.send("GET", notificationPath, null, token);
                assertThat(list.statusCode()).isEqualTo(200);
                assertThat(json.readTree(list.body()).get("items").size()).isOne();
                assertThat(list.body()).contains("TASK_REMINDER");
            }
            var complete = owner.send("PATCH", "/api/v1/tasks/" + lastTask,
                    "{\"state\":\"COMPLETED\",\"version\":0}", token);
            assertThat(complete.statusCode()).isEqualTo(200);
            for (int i = 0; i < 2; i++) {
                var awards = owner.send("GET", "/api/v1/achievements", null, token);
                assertThat(awards.statusCode()).isEqualTo(200);
                assertThat(json.readTree(awards.body()).get("xp").intValue()).isEqualTo(10);
            }
            var overview = owner.send("GET", "/api/v1/analytics/overview", null, token);
            assertThat(overview.statusCode()).isEqualTo(200);
            assertThat(json.readTree(overview.body()).get("completedTasks").intValue()).isOne();
        }
    }

    private void registerAndVerify(Browser browser, String email) throws Exception {
        browser.bootstrapCsrf();
        assertThat(browser.post("/register", credentials(email, PASSWORD)).statusCode()).isEqualTo(202);
        assertThat(browser.post("/verify", tokenBody(mailer.verificationToken(email))).statusCode()).isEqualTo(204);
    }

    private String accessToken(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("accessToken").asText();
    }

    private static String newEmail() {
        return "student-" + UUID.randomUUID() + "@example.test";
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"%s\"}".formatted(token);
    }

    private static String resetBody(String token) {
        return "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD);
    }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager();
        private final URI origin = URI.create("http://127.0.0.1:" + port);
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        void bootstrapCsrf() throws Exception {
            assertThat(send("GET", "/api/v1/auth/csrf", null, null).statusCode()).isEqualTo(204);
            assertThat(cookie("XSRF-TOKEN")).isNotBlank();
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return send("POST", "/api/v1/auth" + path, body, null);
        }

        HttpResponse<String> me(String token) throws Exception {
            return send("GET", "/api/v1/me", null, token);
        }

        HttpResponse<String> upload(String filename, String content, String token) throws Exception {
            String boundary = "studysphere-test-boundary";
            String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\nContent-Type: text/plain\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
            var request = HttpRequest.newBuilder(origin.resolve("/api/v1/resources"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, String body, String accessToken) throws Exception {
            String csrf = cookie("XSRF-TOKEN");
            if (!method.equals("GET") && csrf == null && !path.equals("/api/v1/auth/register")) {
                bootstrapCsrf();
                csrf = cookie("XSRF-TOKEN");
            }
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15));
            if (csrf != null && !method.equals("GET")) request.header("X-XSRF-TOKEN", csrf);
            if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .map(HttpCookie::getValue).findFirst().orElse(null);
        }

        void installRefreshCookie(String value) {
            var cookie = new HttpCookie("studysphere_refresh", value);
            cookie.setPath("/api/v1/auth");
            cookie.setVersion(0);
            cookies.getCookieStore().add(origin, cookie);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MailConfiguration {
        @Bean
        @Primary
        DocumentEngine testDocumentEngine() {
            return new DocumentEngine() {
                private final ConcurrentHashMap<Long, java.util.List<Long>> index = new ConcurrentHashMap<>();
                private final java.util.Set<Long> firstAttempts = ConcurrentHashMap.newKeySet();
                @Override public java.util.List<Chunk> extract(java.io.InputStream input, String mediaType)
                        throws java.io.IOException {
                    return java.util.List.of(new Chunk(null,
                            new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                }
                @Override public void index(long owner, long id, java.util.List<IndexedChunk> chunks) {
                    if (firstAttempts.add(id)) throw new IllegalStateException("Simulated index outage");
                    index.put(id, chunks.stream().map(IndexedChunk::id).toList());
                }
                @Override public java.util.List<Long> search(long owner, long id, String query, int limit) {
                    if (query.equals("No matches fixture")) return java.util.List.of();
                    return index.getOrDefault(id, java.util.List.of());
                }
                @Override public void delete(long owner, long id) { index.remove(id); }
                @Override public String answer(String question, java.util.List<Source> sources) { return "Grounded fixture"; }
            };
        }

        @Bean
        @Primary
        MentorProvider testMentorProvider() {
            return new MentorProvider() {
                @Override public Flux<String> stream(String mode, java.util.List<Turn> turns) {
                    if (mode.equals("ROOM_ASSISTANT")) {
                        return Flux.just("{\"text\":\"Review normal forms.\",\"proposals\":[{\"kind\":\"TASK\",\"title\":\"Review notes fixture\"}]}");
                    }
                    if (mode.equals("PLAN_PROPOSAL")) {
                        String date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString();
                        return Flux.just("""
                                ```json
                                {"tasks":[{"title":"Review databases fixture","date":"%s","minutes":30}]}
                                ```
                                """.formatted(date));
                    }
                    if (turns.getLast().text().equals("Timeout test fixture")) return Flux.never();
                    return Flux.just("Normalize", " your schema.");
                }
                @Override public boolean available() { return true; }
                @Override public String id() { return "test"; }
            };
        }

        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    static final class RecordingMailer implements AuthMailer {
        private final ConcurrentHashMap<String, String> verifications = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> resets = new ConcurrentHashMap<>();

        @Override
        public void sendVerification(String email, String link) {
            verifications.put(email, link);
        }

        @Override
        public void sendPasswordReset(String email, String link) {
            resets.put(email, link);
        }

        String verificationToken(String email) {
            return token(verifications.get(email));
        }

        String resetToken(String email) {
            return token(resets.get(email));
        }

        private static String token(String link) {
            assertThat(link != null && URI.create(link).getFragment().startsWith("token=")).isTrue();
            return URI.create(link).getFragment().substring("token=".length());
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " for an empty MySQL test schema.");
        }
        return value;
    }

    private static void requireFreshSchema(String url, String user, String password) {
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT DATABASE(), COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                     """)) {
            if (!result.next() || result.getString(1) == null || result.getLong(2) != 0) {
                throw new IllegalStateException("Use a dedicated, empty MySQL schema for integration tests.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not inspect the MySQL test schema.", e);
        }
    }
}
