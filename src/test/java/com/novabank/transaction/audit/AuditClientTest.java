package com.novabank.transaction.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.novabank.transaction.admin.AdminTransactionController;
import com.novabank.transaction.txn.TransactionDtos.DepositRequest;
import com.novabank.transaction.txn.TransactionDtos.TransactionDto;
import com.novabank.transaction.txn.TransactionRepository;
import com.novabank.transaction.txn.TransactionService;
import com.novabank.transaction.txn.TxnStatus;
import com.novabank.transaction.txn.TxnType;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import com.novabank.transaction.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auditing must never fail, block or roll back the business action. */
class AuditClientTest {

    /** Runs the delivery on the calling thread, so the test sees its outcome. */
    private static final Executor SAME_THREAD = Runnable::run;

    private static AuditEvent event() {
        return new AuditEvent(AuditEvent.SERVICE, Instant.now(), 1L, "ADMIN", "admin@novabank.dev",
                "DEPOSIT", "TRANSACTION", "NB26110-ABC123", AuditEvent.Outcome.SUCCESS, 201, null,
                Map.of("toAccount", "100245180031", "amount", "250.00"), "c-1", "203.0.113.7");
    }

    private static RestClient.Builder jsonBuilder() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        return RestClient.builder().messageConverters(c -> {
            c.clear();
            c.add(new MappingJackson2HttpMessageConverter(json));
        });
    }

    @Test
    void springCanCreateTheClient() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "novabank.services.auth-url", "http://auth", "novabank.security.internal-api-key", "k")));
            ctx.registerBean(RestClient.Builder.class, (java.util.function.Supplier<RestClient.Builder>) RestClient::builder);
            ctx.register(AuditClient.class);
            ctx.refresh();
            assertThat(ctx.getBean(AuditClient.class)).isNotNull();
        }
    }

    @Test
    void sendsTheEventWithTheInternalKey() {
        RestClient.Builder builder = jsonBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://auth/internal/audit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "k"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.service").value("transactions"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.action").value("DEPOSIT"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.details.amount").value("250.00"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        new AuditClient(builder, "http://auth", "k", SAME_THREAD).send(event());

        server.verify();
    }

    @Test
    void anErrorFromAuthServiceIsSwallowed() {
        RestClient.Builder builder = jsonBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://auth/internal/audit")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatCode(() -> new AuditClient(builder, "http://auth", "k", SAME_THREAD).send(event()))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnreachableAuthServiceIsSwallowed() {
        AuditClient client = new AuditClient(RestClient.builder(), "http://localhost:1", "k", SAME_THREAD);

        assertThatCode(() -> client.send(event())).doesNotThrowAnyException();
    }

    @Test
    void aRejectedBackgroundTaskIsSwallowed() {
        Executor full = task -> {
            throw new java.util.concurrent.RejectedExecutionException("shutting down");
        };
        AuditClient client = new AuditClient(RestClient.builder(), "http://auth", "k", full);

        assertThatCode(() -> client.send(event())).doesNotThrowAnyException();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tellerDepositSucceedsWhileAuthServiceIsDown() throws Exception {
        TransactionService service = mock(TransactionService.class);
        when(service.deposit(any(DepositRequest.class), eq(1L), eq("key-1"))).thenReturn(new TransactionDto(
                9L, "NB26110-ABC123", TxnType.DEPOSIT, null, "100245180031", new BigDecimal("250.00"), "USD",
                TxnStatus.COMPLETED, "Branch deposit", null, 1L, Instant.now(), Instant.now(), null));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("1").claim("role", "ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        AuditClient down = new AuditClient(RestClient.builder(), "http://localhost:1", "k", SAME_THREAD);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AdminTransactionController(service, mock(TransactionRepository.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler(), new ProblemDetailAuditAdvice())
                .addMappedInterceptors(new String[]{"/api/**"}, new AuditInterceptor(down))
                .build();

        mvc.perform(post("/api/admin/transactions/deposit").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toAccount\":\"100245180031\",\"amount\":250.00}"))
                .andExpect(status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.reference").value("NB26110-ABC123"));

        verify(service).deposit(any(DepositRequest.class), eq(1L), eq("key-1"));
    }
}
