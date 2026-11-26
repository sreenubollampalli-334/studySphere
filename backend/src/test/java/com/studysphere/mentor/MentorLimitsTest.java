package com.studysphere.mentor;

import com.studysphere.mentor.provider.MentorProvider;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MentorLimitsTest {
    private static final MentorService.Run RUN = new MentorService.Run(1, 2, 3, "EXPLAIN", List.of());

    @Test
    void timeoutCancelsUpstreamAndRecordsUsage() throws InterruptedException {
        var provider = mock(MentorProvider.class);
        var service = mock(MentorService.class);
        var cancelled = new CountDownLatch(1);
        when(provider.stream("EXPLAIN", List.of())).thenReturn(Flux.<String>never().doOnCancel(cancelled::countDown));

        new MentorStream(service, provider, 1).start(mock(SseEmitter.class), RUN);

        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        verify(service, timeout(2_000)).close(RUN, "TIMEOUT", 0);
    }

    @Test
    void clientCompletionCancelsUpstream() throws InterruptedException {
        var provider = mock(MentorProvider.class);
        var service = mock(MentorService.class);
        var emitter = mock(SseEmitter.class);
        var completion = new AtomicReference<Runnable>();
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any());
        when(provider.stream("EXPLAIN", List.of())).thenReturn(Flux.<String>never()
                .doOnSubscribe(subscription -> subscribed.countDown()).doOnCancel(cancelled::countDown));

        new MentorStream(service, provider, 15).start(emitter, RUN);
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        completion.get().run();

        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        verify(service).close(RUN, "CANCELLED", 0);
    }

    @Test
    void existingUsageTriggersRateLimitBeforeMessagesOrProviderCalls() {
        var provider = mock(MentorProvider.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = spy(new MentorService(jdbc, provider, 1, 5));
        when(provider.available()).thenReturn(true);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(Long.class), eq(1L))).thenReturn(List.of(1L));
        doReturn(new MentorService.Conversation("2", "Study", null)).when(service).conversation(1, 2);
        when(jdbc.queryForObject(contains("INTERVAL 1 MINUTE"), eq(Integer.class), eq(1L))).thenReturn(1);
        when(jdbc.queryForObject(contains("UTC_DATE()"), eq(Integer.class), eq(1L))).thenReturn(1);

        assertThatThrownBy(() -> service.begin(1, 2, "Question", "EXPLAIN", 15))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(429));
        verify(provider, never()).stream(any(), anyList());
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(org.springframework.jdbc.support.KeyHolder.class));
    }
}
