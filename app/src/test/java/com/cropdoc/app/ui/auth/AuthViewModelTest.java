package com.cropdoc.app.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ui.common.Result;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Unit tests for AuthViewModel input validation, registration, password reset, and DPDP deletion.
 */
public final class AuthViewModelTest {

    @Rule
    public final InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();

    private AuthRepository mockRepo;
    private ScanRepository mockScanRepo;
    private AuthViewModel viewModel;

    @Before
    public void setUp() {
        mockRepo = mock(AuthRepository.class);
        mockScanRepo = mock(ScanRepository.class);
        Executor direct = Runnable::run;
        AppExecutors executors = new AppExecutors(direct, direct, direct, direct);
        viewModel = new AuthViewModel(mockRepo, mockScanRepo, executors);
    }

    @Test
    public void signInWithEmail_rejectsInvalidEmailWithoutNetworkCall() {
        viewModel.signInWithEmail("invalid-email", "secret123");
        Result<String> state = viewModel.getAuthState().getValue();
        assertNotNull(state);
        assertEquals(Result.Status.ERROR, state.getStatus());
    }

    @Test
    public void continueAsGuest_emitsSuccessState() {
        doAnswer(invocation -> {
            AuthRepository.AuthCallback cb = invocation.getArgument(0);
            cb.onSuccess("guest_offline_farmer", true);
            return null;
        }).when(mockRepo).continueAsGuest(any());
        viewModel.continueAsGuest();
        Result<String> state = viewModel.getAuthState().getValue();
        assertNotNull(state);
        assertEquals(Result.Status.SUCCESS, state.getStatus());
        assertEquals("guest_offline_farmer", state.getData());
    }

    @Test
    public void signInWithEmail_validCredentials_emitsSuccess() {
        doAnswer(invocation -> {
            AuthRepository.AuthCallback cb = invocation.getArgument(2);
            cb.onSuccess("uid_farmer_42", false);
            return null;
        }).when(mockRepo).signInWithEmail(eq("ravi@kisan.in"), eq("pass1234"), any());
        viewModel.signInWithEmail("ravi@kisan.in", "pass1234");
        Result<String> state = viewModel.getAuthState().getValue();
        assertNotNull(state);
        assertEquals("uid_farmer_42", state.getData());
    }

    @Test
    public void registerWithEmail_validCredentials_emitsSuccess() {
        doAnswer(invocation -> {
            AuthRepository.AuthCallback cb = invocation.getArgument(2);
            cb.onSuccess("uid_new_farmer", false);
            return null;
        }).when(mockRepo).registerWithEmail(eq("new@kisan.in"), eq("pass1234"), any());
        viewModel.registerWithEmail("new@kisan.in", "pass1234");
        Result<String> state = viewModel.getAuthState().getValue();
        assertNotNull(state);
        assertEquals("uid_new_farmer", state.getData());
    }

    @Test
    public void sendPasswordReset_validEmail_emitsResetSentStatus() {
        doAnswer(invocation -> {
            AuthRepository.AuthCallback cb = invocation.getArgument(1);
            cb.onSuccess("ravi@kisan.in", false);
            return null;
        }).when(mockRepo).sendPasswordReset(eq("ravi@kisan.in"), any());
        viewModel.sendPasswordReset("ravi@kisan.in");
        assertEquals("RESET_SENT", viewModel.getStatusMessage().getValue());
    }

    @Test
    public void deleteAccountAndData_wipesScansAndDeletesAccount() {
        org.mockito.Mockito.when(mockRepo.getCurrentUserId()).thenReturn("uid_farmer_42");
        doAnswer(invocation -> {
            AuthRepository.AuthCallback cb = invocation.getArgument(0);
            cb.onSuccess("guest_offline_farmer", true);
            return null;
        }).when(mockRepo).deleteCurrentAccount(any());
        viewModel.deleteAccountAndData();
        verify(mockScanRepo).deleteAllUserScansSync("uid_farmer_42");
        assertEquals("ACCOUNT_DELETED", viewModel.getStatusMessage().getValue());
    }
}
