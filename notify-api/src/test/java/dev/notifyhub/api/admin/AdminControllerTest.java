package dev.notifyhub.api.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.notifyhub.api.admin.DlqReprocessService.FailureReason;
import dev.notifyhub.api.admin.DlqReprocessService.Report;
import dev.notifyhub.common.domain.Channel;
import java.util.Arrays;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.ErrorResponseException;

/**
 * Fatia web dos endpoints admin (ADM-04, D-04): interceptor {@code X-API-Key} em tempo constante e
 * fail closed, validação de canal e {@code max}, e Problem Details do 401, do 400 e do 503. Sem Docker.
 */
@WebMvcTest(AdminController.class)
@TestPropertySource(properties = "notify.admin.api-key=test-key")
class AdminControllerTest {

    private static final String PATH = "/admin/dlq/email/reprocess";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DlqReprocessService service;

    private static MockHttpServletRequestBuilder withKey(String path) {
        return post(path).header("X-API-Key", "test-key");
    }

    // ---------------------------------------------------------------- 401 (T-03-04, T-03-05)

    @Test
    void missingKeyIsUnauthorizedBeforeTouchingTheDlq() throws Exception {
        mvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:unauthorized"))
                .andExpect(jsonPath("$.instance").value(PATH));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test-keyx", "test-ke", "TEST-KEY", "test-key "})
    void wrongKeyIsUnauthorized(String key) throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", key))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:unauthorized"))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(containsString("test-key"))));
        verifyNoInteractions(service);
    }

    @Test
    void missingKeyWithAnInvalidChannelIsStillUnauthorized() throws Exception {
        mvc.perform(post("/admin/dlq/push/reprocess"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:unauthorized"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankConfiguredKeyRefusesEveryCall(String configured) {
        AdminWebConfiguration.ApiKeyInterceptor interceptor = new AdminWebConfiguration.ApiKeyInterceptor(configured);
        assertFailsClosed(interceptor);
    }

    @Test
    void nullConfiguredKeyRefusesEveryCall() {
        assertFailsClosed(new AdminWebConfiguration.ApiKeyInterceptor(null));
    }

    private static void assertFailsClosed(AdminWebConfiguration.ApiKeyInterceptor interceptor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("X-API-Key", "qualquer");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        e -> Assertions.assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    // ---------------------------------------------------------------- 200

    @Test
    void rightKeyWithoutMaxUsesTheDefaultOfOneHundred() throws Exception {
        when(service.reprocess(Channel.EMAIL, 100)).thenReturn(new Report(100, 3, 1, 2));

        mvc.perform(withKey(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(100))
                .andExpect(jsonPath("$.reprocessed").value(3))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.discarded").value(2));
        verify(service).reprocess(Channel.EMAIL, 100);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 1000})
    void maxAtTheBoundariesIsAccepted(int max) throws Exception {
        when(service.reprocess(Channel.EMAIL, max)).thenReturn(new Report(max, 0, 0, 0));

        mvc.perform(withKey(PATH).param("max", Integer.toString(max)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(max));
        verify(service).reprocess(Channel.EMAIL, max);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sms", "webhook"})
    void everyChannelIdIsAccepted(String id) throws Exception {
        Channel channel = Arrays.stream(Channel.values()).filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        when(service.reprocess(channel, 100)).thenReturn(new Report(100, 0, 0, 0));

        mvc.perform(withKey("/admin/dlq/" + id + "/reprocess")).andExpect(status().isOk());
        verify(service).reprocess(channel, 100);
    }

    // ---------------------------------------------------------------- 400 (D-04)

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "abc", "1.5", "99999999999"})
    void maxOutsideOneToTheLimitOrNotAnIntIsAValidationError(String max) throws Exception {
        mvc.perform(withKey(PATH).param("max", max))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("max"))
                .andExpect(jsonPath("$.instance").value(PATH));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"push", "EMAIL", "Email"})
    void unknownChannelIdIsAValidationErrorListingTheAcceptedIds(String canal) throws Exception {
        mvc.perform(withKey("/admin/dlq/" + canal + "/reprocess"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("canal"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("email, sms, webhook")));
        verifyNoInteractions(service);
    }

    @Test
    void invalidChannelAndMaxComeBackInASingleValidationError() throws Exception {
        mvc.perform(withKey("/admin/dlq/push/reprocess").param("max", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("canal"))
                .andExpect(jsonPath("$.errors[1].field").value("max"));
        verifyNoInteractions(service);
    }

    // ---------------------------------------------------------------- 503 (D-03)

    @Test
    void interruptedReprocessIsAServiceUnavailableProblemWithPartialCounts() throws Exception {
        when(service.reprocess(any(), anyInt())).thenThrow(new ReprocessInterruptedException(Channel.EMAIL,
                new Report(10, 1, 0, 0), FailureReason.PUBLISH_TIMEOUT));

        mvc.perform(withKey(PATH).param("max", "10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:notify-hub:problem:reprocess-interrupted"))
                .andExpect(jsonPath("$.requested").value(10))
                .andExpect(jsonPath("$.reprocessed").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.discarded").value(0))
                .andExpect(jsonPath("$.reason").value("PUBLISH_TIMEOUT"));
    }
}
