package com.studysphere.document.engine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.studysphere.document.engine.DocumentEngine.IndexUnavailable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class QdrantDocuments {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String path;
    private final String key;
    private final double minimumScore;
    private Integer dimension;

    QdrantDocuments(ObjectMapper json, URI endpoint, String collection, String key, double minimumScore) {
        this.json = json;
        this.path = endpoint.toString().replaceFirst("/$", "") + "/collections/" + collection;
        this.key = key;
        this.minimumScore = minimumScore;
    }

    void index(long owner, long document, List<Point> points) {
        if (points.isEmpty()) return;
        ensureCollection(points.getFirst().vector().length);
        var payload = Map.of("owner_id", owner, "document_id", document);
        var batch = points.stream().map(point -> Map.of(
                "id", point.id(), "vector", point.vector(), "payload", payload)).toList();
        requireSuccess(request("PUT", "/points?wait=true", Map.of("points", batch)));
    }

    List<Long> search(long owner, long document, float[] vector, int limit) {
        var response = request("POST", "/points/query", Map.of(
                "query", vector,
                "filter", filter(owner, document),
                "limit", limit,
                "score_threshold", minimumScore,
                "with_payload", List.of("owner_id", "document_id")));
        requireSuccess(response);
        JsonNode points = json.readTree(response.body()).path("result").path("points");
        if (!points.isArray()) throw new IllegalStateException("Unexpected vector index response.");
        var ids = new ArrayList<Long>();
        for (JsonNode point : points) {
            JsonNode id = point.path("id");
            JsonNode payload = point.path("payload");
            if (id.isIntegralNumber() && id.asLong() > 0
                    && payload.path("owner_id").asLong() == owner
                    && payload.path("document_id").asLong() == document
                    && point.path("score").asDouble(-1) >= minimumScore) {
                ids.add(id.asLong());
            }
        }
        return ids.stream().distinct().limit(limit).toList();
    }

    void delete(long owner, long document) {
        var response = request("POST", "/points/delete?wait=true", Map.of("filter", filter(owner, document)));
        if (response.statusCode() != 404) requireSuccess(response);
    }

    private synchronized void ensureCollection(int expectedDimension) {
        if (dimension != null && dimension == expectedDimension) return;
        var current = request("GET", "", null);
        if (current.statusCode() == 404) {
            var created = request("PUT", "", Map.of(
                    "vectors", Map.of("size", expectedDimension, "distance", "Cosine")));
            if (created.statusCode() >= 400 && created.statusCode() != 409) requireSuccess(created);
            current = request("GET", "", null);
        }
        requireSuccess(current);
        JsonNode vectors = json.readTree(current.body()).path("result").path("config").path("params").path("vectors");
        if (vectors.path("size").asInt() != expectedDimension || !vectors.path("distance").asText().equalsIgnoreCase("Cosine")) {
            throw new IndexUnavailable();
        }
        dimension = expectedDimension;
    }

    private static Map<String, Object> filter(long owner, long document) {
        return Map.of("must", List.of(
                Map.of("key", "owner_id", "match", Map.of("value", owner)),
                Map.of("key", "document_id", "match", Map.of("value", document))));
    }

    private HttpResponse<String> request(String method, String suffix, Object body) {
        var builder = HttpRequest.newBuilder(URI.create(path + suffix)).timeout(Duration.ofSeconds(15));
        if (!key.isBlank()) builder.header("api-key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IndexUnavailable();
        } catch (IOException failure) {
            throw new IndexUnavailable();
        }
    }

    private static void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IndexUnavailable();
        }
    }

    record Point(long id, float[] vector) {}
}
