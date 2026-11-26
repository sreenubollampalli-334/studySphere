package com.studysphere.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class NotificationServiceTest {
    @Test
    void incompletePreferencesCannotOverwriteSavedSettings() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new NotificationService(jdbc);

        assertThatThrownBy(() -> service.save(1L,
                new NotificationService.Preferences(true, true, null, false, true)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(jdbc);
    }

    @Test
    void markReadDoesNotExposeAnotherAccountsNotification() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new NotificationService(jdbc);
        when(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notifications WHERE id=? AND user_id=?)",
                Boolean.class, 4L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.read(2L, 4L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
