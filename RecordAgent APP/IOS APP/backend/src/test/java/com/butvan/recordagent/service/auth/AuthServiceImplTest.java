package com.butvan.recordagent.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.butvan.recordagent.dto.auth.AuthDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.auth.AuthRepository;
import com.butvan.recordagent.service.auth.impl.AuthServiceImpl;
import com.butvan.recordagent.service.mail.MailService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证验证码发送的频率限制和邮件失败处理，不记录或比较验证码明文。 */
class AuthServiceImplTest {
    private final AuthRepository store = mock(AuthRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final MailService mail = mock(MailService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        service = new AuthServiceImpl(new com.butvan.recordagent.config.BusinessProperties(), mock(com.butvan.recordagent.service.auth.AuthenticationRateLimiter.class), store, encoder, mail, transactions);
    }

    @Test
    void secondRequestWithinCooldownDoesNotSendAnotherEmail() {
        String email = "person@example.test";
        when(store.lastChallengeAt(email, "login"))
                .thenReturn(Optional.empty(), Optional.of(OffsetDateTime.now()));
        when(encoder.encode(anyString())).thenReturn("stored-hash");

        AuthDtos.CodeRequest request = new AuthDtos.CodeRequest(" Person@Example.Test ", "login");
        assertThat(service.sendCode(request).retryAfterSeconds()).isEqualTo(60);
        assertThatThrownBy(() -> service.sendCode(request))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ApiErrorCode.RATE_LIMITED));

        verify(store, org.mockito.Mockito.times(2)).lockChallengeRequest(email, "login");
        verify(mail).send(eq(email), anyString());
        verify(store).createChallenge(any(), eq(email), eq("login"), eq("stored-hash"), any());
    }

    @Test
    void mailFailureDoesNotPersistAnUnsentCode() {
        String email = "person@example.test";
        when(store.lastChallengeAt(email, "login")).thenReturn(Optional.empty());
        doThrow(new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "mail unavailable"))
                .when(mail).send(eq(email), anyString());

        assertThatThrownBy(() -> service.sendCode(new AuthDtos.CodeRequest(email, "login")))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(ApiErrorCode.SERVICE_UNAVAILABLE));
        verify(store, org.mockito.Mockito.never()).createChallenge(any(), anyString(), anyString(), anyString(), any());
    }
}
