package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ApplicationDTO;
import com.keny.jobassistant.model.dto.ApplicationEventPageDTO;
import com.keny.jobassistant.model.entity.Application;
import com.keny.jobassistant.model.entity.ApplicationEvent;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.request.ApplicationStatusUpdateRequest;
import com.keny.jobassistant.model.enums.ApplicationStatus;
import com.keny.jobassistant.repository.ApplicationEventRepository;
import com.keny.jobassistant.repository.ApplicationRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.impl.ApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 状态修改与事件历史的业务逻辑，依赖全部 mock，不需要数据库。 */
@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long APPLICATION_ID = 42L;

    @Mock
    private ApplicationRepository applicationRepository;
    @Mock
    private ApplicationEventRepository applicationEventRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private ApplicationServiceImpl applicationService;

    @BeforeEach
    void setUp() {
        lenient().when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    @Test
    void illegalTransitionShouldBeRejectedBeforeTouchingDatabase() {
        assertThatThrownBy(() -> applicationService.updateStatus(APPLICATION_ID,
                request(ApplicationStatus.OFFER, ApplicationStatus.APPLIED)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION.getCode()));

        verifyNoInteractions(applicationRepository, applicationEventRepository);
    }

    @Test
    void missingStatusShouldBeRejected() {
        assertThatThrownBy(() -> applicationService.updateStatus(APPLICATION_ID,
                request(null, ApplicationStatus.SCREENING)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode()));
    }

    @Test
    void staleFromStatusShouldReturnConflict() {
        when(applicationRepository.transitionStatus(eq(APPLICATION_ID), eq(USER_ID),
                eq(ApplicationStatus.SCREENING), eq(ApplicationStatus.INTERVIEW), any())).thenReturn(0);
        when(applicationRepository.existsByIdAndUser_Id(APPLICATION_ID, USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> applicationService.updateStatus(APPLICATION_ID,
                request(ApplicationStatus.SCREENING, ApplicationStatus.INTERVIEW)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.APPLICATION_STATUS_CONFLICT.getCode()));

        verify(applicationEventRepository, never()).save(any());
    }

    @Test
    void otherUsersApplicationShouldReturnNotFound() {
        when(applicationRepository.transitionStatus(any(), any(), any(), any(), any())).thenReturn(0);
        when(applicationRepository.existsByIdAndUser_Id(APPLICATION_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> applicationService.updateStatus(APPLICATION_ID,
                request(ApplicationStatus.APPLIED, ApplicationStatus.SCREENING)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void successfulTransitionShouldRecordEvent() {
        Application reference = new Application();
        reference.setId(APPLICATION_ID);
        when(applicationRepository.transitionStatus(eq(APPLICATION_ID), eq(USER_ID),
                eq(ApplicationStatus.INTERVIEW), eq(ApplicationStatus.REJECTED), any())).thenReturn(1);
        when(applicationRepository.getReferenceById(APPLICATION_ID)).thenReturn(reference);
        when(applicationRepository.findByIdAndUser_Id(APPLICATION_ID, USER_ID))
                .thenReturn(Optional.of(application(ApplicationStatus.REJECTED)));

        ApplicationStatusUpdateRequest request = request(ApplicationStatus.INTERVIEW, ApplicationStatus.REJECTED);
        request.setNote("No visa sponsorship");
        ApplicationDTO result = applicationService.updateStatus(APPLICATION_ID, request);

        ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(applicationEventRepository).save(eventCaptor.capture());
        ApplicationEvent event = eventCaptor.getValue();
        assertThat(event.getApplication()).isSameAs(reference);
        assertThat(event.getFromStatus()).isEqualTo(ApplicationStatus.INTERVIEW);
        assertThat(event.getToStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(event.getOperatorUserId()).isEqualTo(USER_ID);
        assertThat(event.getNote()).isEqualTo("No visa sponsorship");
        assertThat(event.getCreateTime()).isNotNull();

        assertThat(result.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(result.getAllowedNextStatuses()).isEmpty();
    }

    @Test
    void eventPageShouldFetchOneExtraRowToDetectNextPage() {
        when(applicationRepository.existsByIdAndUser_Id(APPLICATION_ID, USER_ID)).thenReturn(true);
        // size = 2，仓库返回 3 条（id 9、8、7），说明还有下一页。
        when(applicationEventRepository.findByApplication_IdAndIdLessThanOrderByIdDesc(
                eq(APPLICATION_ID), eq(10L), any(Pageable.class)))
                .thenReturn(events(9, 8, 7));

        ApplicationEventPageDTO page = applicationService.listEvents(APPLICATION_ID, 10L, 2);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(applicationEventRepository).findByApplication_IdAndIdLessThanOrderByIdDesc(
                eq(APPLICATION_ID), eq(10L), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(3);
        assertThat(pageableCaptor.getValue().getOffset()).isZero();

        assertThat(page.getItems()).extracting("id").containsExactly(9L, 8L);
        assertThat(page.isHasNext()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(8L);
    }

    @Test
    void lastEventPageShouldHaveNoCursor() {
        when(applicationRepository.existsByIdAndUser_Id(APPLICATION_ID, USER_ID)).thenReturn(true);
        when(applicationEventRepository.findByApplication_IdOrderByIdDesc(eq(APPLICATION_ID), any(Pageable.class)))
                .thenReturn(events(2, 1));

        ApplicationEventPageDTO page = applicationService.listEvents(APPLICATION_ID, null, 20);

        assertThat(page.getItems()).extracting("id").containsExactly(2L, 1L);
        assertThat(page.isHasNext()).isFalse();
        assertThat(page.getNextCursor()).isNull();
    }

    @Test
    void eventsOfOtherUsersApplicationShouldReturnNotFound() {
        when(applicationRepository.existsByIdAndUser_Id(APPLICATION_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> applicationService.listEvents(APPLICATION_ID, null, 20))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
        verifyNoInteractions(applicationEventRepository);
    }

    @Test
    void invalidEventPageSizeShouldBeRejected() {
        assertThatThrownBy(() -> applicationService.listEvents(APPLICATION_ID, null, 101))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> applicationService.listEvents(APPLICATION_ID, 0L, 20))
                .isInstanceOf(BusinessException.class);
    }

    private static ApplicationStatusUpdateRequest request(ApplicationStatus from, ApplicationStatus to) {
        ApplicationStatusUpdateRequest request = new ApplicationStatusUpdateRequest();
        request.setFromStatus(from);
        request.setToStatus(to);
        return request;
    }

    private static Application application(ApplicationStatus status) {
        Job job = new Job();
        job.setId(3L);
        Application application = new Application();
        application.setId(APPLICATION_ID);
        application.setJob(job);
        application.setStatus(status);
        return application;
    }

    private static List<ApplicationEvent> events(long... ids) {
        return LongStream.of(ids).mapToObj(id -> {
            ApplicationEvent event = new ApplicationEvent();
            event.setId(id);
            event.setToStatus(ApplicationStatus.SCREENING);
            return event;
        }).toList();
    }
}
