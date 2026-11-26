package com.studysphere.mentor;

import com.studysphere.mentor.provider.MentorProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Component
class MentorStream {
    private final MentorService service;
    private final MentorProvider provider;
    private final int timeoutSeconds;

    MentorStream(MentorService service, MentorProvider provider,
            @Value("${studysphere.mentor.timeout-seconds:45}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Mentor timeout must be 1–120 seconds.");
        }
        this.service = service;
        this.provider = provider;
        this.timeoutSeconds = timeoutSeconds;
    }

    SseEmitter open(long owner, long conversation, String prompt, String mode) {
        var run = service.begin(owner, conversation, prompt, mode, timeoutSeconds);
        // The worker enforces the model deadline. The servlet deadline leaves time to send its error event.
        var emitter = new SseEmitter((timeoutSeconds + 5L) * 1_000);
        start(emitter, run);
        return emitter;
    }

    void start(SseEmitter emitter, MentorService.Run run) {
        new Session(emitter, run).start();
    }

    private final class Session {
        private final SseEmitter emitter;
        private final MentorService.Run run;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicInteger outputSize = new AtomicInteger();
        private final StringBuilder output = new StringBuilder();
        private final reactor.core.Disposable.Swap subscription = Disposables.swap();
        private final reactor.core.Disposable.Swap keepAlive = Disposables.swap();
        private final reactor.core.Disposable.Swap deadline = Disposables.swap();

        Session(SseEmitter emitter, MentorService.Run run) {
            this.emitter = emitter;
            this.run = run;
        }

        void start() {
            emitter.onTimeout(() -> fail("TIMEOUT", "MENTOR_TIMEOUT", "The response timed out."));
            emitter.onError(error -> cancel());
            emitter.onCompletion(this::cancel);
            deadline.update(Schedulers.boundedElastic().schedule(
                    () -> fail("TIMEOUT", "MENTOR_TIMEOUT", "The response timed out."),
                    timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS));
            keepAlive.update(Flux.interval(Duration.ofSeconds(3)).publishOn(Schedulers.boundedElastic())
                    .subscribe(tick -> heartbeat(), error -> cancel()));
            subscription.update(Flux.defer(() -> provider.stream(run.mode(), run.turns()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .publishOn(Schedulers.boundedElastic(), 8)
                    .subscribe(this::delta, this::providerFailed, this::complete));
        }

        void delta(String text) {
            if (terminal.get() || text.isEmpty()) return;
            if (text.length() > MentorService.MAX_OUTPUT - outputSize.get()) {
                fail("LIMIT", "MENTOR_OUTPUT_LIMIT", "The response reached the output limit.");
                return;
            }
            output.append(text);
            outputSize.addAndGet(text.length());
            event("delta", Map.of("text", text));
        }

        void heartbeat() {
            if (terminal.get()) return;
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException disconnected) {
                cancel();
            }
        }

        void complete() {
            if (output.toString().isBlank()) {
                fail("FAILED", "MENTOR_EMPTY_RESPONSE", "The provider returned no response.");
                return;
            }
            if (!terminal.compareAndSet(false, true)) return;
            stop();
            try {
                var message = service.finish(run, output.toString());
                event("complete", Map.of("message", message));
            } catch (RuntimeException failure) {
                record("FAILED");
                event("error", Map.of("code", "MENTOR_UNAVAILABLE", "message", "The response could not be saved."));
            } finally {
                emitter.complete();
            }
        }

        void providerFailed(Throwable error) {
            if (error instanceof TimeoutException) {
                fail("TIMEOUT", "MENTOR_TIMEOUT", "The response timed out.");
            } else {
                fail("FAILED", "MENTOR_UNAVAILABLE", "The provider could not complete the response.");
            }
        }

        void fail(String status, String code, String message) {
            if (!terminal.compareAndSet(false, true)) return;
            stop();
            record(status);
            try {
                event("error", Map.of("code", code, "message", message));
            } finally {
                emitter.complete();
            }
        }

        void cancel() {
            if (!terminal.compareAndSet(false, true)) return;
            stop();
            record("CANCELLED");
            emitter.complete();
        }

        void record(String status) {
            try {
                service.close(run, status, outputSize.get());
            } catch (RuntimeException failure) {
                // Retain the started usage entry so provider requests still count toward the quota.
                org.slf4j.LoggerFactory.getLogger(MentorStream.class).warn("Could not finalize mentor usage.");
            }
        }

        void stop() {
            subscription.dispose();
            keepAlive.dispose();
            deadline.dispose();
        }

        synchronized void event(String name, Object data) {
            if (name.equals("delta") && terminal.get()) return;
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException disconnected) {
                cancel();
            }
        }
    }
}
