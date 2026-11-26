package com.studysphere.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.studysphere.community.CommunityService;
import com.studysphere.messaging.MessageService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdministrationTest {
    @Test
    void privateReportIntakeStoresOnlyMetadataInTheAdminQueue() {
        var jdbc = mock(JdbcTemplate.class);
        new ReportIntake(jdbc).record(ReportIntake.Kind.PRIVATE_MESSAGE, 4L, 2L, 3L, "Private detail");

        verify(jdbc).update(anyString(),eq("PRIVATE_MESSAGE"),eq(4L),eq(2L),eq(3L),isNull());
    }

    @Test
    void dismissingAReportCannotRemoveContent() {
        var jdbc = mock(JdbcTemplate.class);
        var messages = mock(MessageService.class);
        var communities = mock(CommunityService.class);
        var admin = new AdminService(jdbc,messages,communities,BigDecimal.ZERO,BigDecimal.ZERO);

        assertThatThrownBy(() -> admin.review(1L, ReportIntake.Kind.PRIVATE_MESSAGE, 4L, 2L,
                AdminService.ReportStatus.DISMISSED, "Reviewed", true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(jdbc,messages,communities);
    }
}
