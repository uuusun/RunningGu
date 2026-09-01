package com.runninggu.server.member.application;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.auth.infrastructure.EmailVerificationRepository;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MemberDeletionServiceTest {

    @Test
    void 인증행과_세션을_계약_잠금순서대로_명시적으로_삭제한다() {
        long userId = 17L;
        String email = "runner@example.com";
        String reauthToken = "reauth-token";
        ReauthTokenManager reauthTokenManager = mock(ReauthTokenManager.class);
        LoginIdentityRepository loginIdentityRepository = mock(LoginIdentityRepository.class);
        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        EmailVerificationRepository emailVerificationRepository =
                mock(EmailVerificationRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        LoginIdentity identity = mock(LoginIdentity.class);
        when(reauthTokenManager.decodeUserId(reauthToken)).thenReturn(userId);
        when(loginIdentityRepository.findByUserIdForUpdate(userId))
                .thenReturn(Optional.of(identity));
        when(identity.getProvider()).thenReturn(LoginProvider.EMAIL);
        when(identity.getProviderSubject()).thenReturn(email);
        MemberDeletionService service = new MemberDeletionService(
                reauthTokenManager,
                loginIdentityRepository,
                refreshTokenRepository,
                emailVerificationRepository,
                appUserRepository);

        service.delete(userId, reauthToken);

        InOrder order = inOrder(
                loginIdentityRepository,
                emailVerificationRepository,
                refreshTokenRepository,
                appUserRepository);
        order.verify(loginIdentityRepository).findByUserIdForUpdate(userId);
        order.verify(emailVerificationRepository).deleteAllByEmailInIdOrder(email);
        order.verify(refreshTokenRepository).deleteAllByUserIdInIdOrder(userId);
        order.verify(appUserRepository).deleteAllByIdInBatch(List.of(userId));
    }
}
