package com.novabank.transaction.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novabank.transaction.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Talks to account-service's internal API (never exposed through the gateway). */
@Component
public class AccountClient {

    private final RestClient client;
    private final ObjectMapper mapper;

    public AccountClient(RestClient.Builder builder, ObjectMapper mapper,
                         @Value("${novabank.services.account-url}") String accountUrl,
                         @Value("${novabank.security.internal-api-key}") String internalKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.mapper = mapper;
        this.client = builder
                .baseUrl(accountUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Api-Key", internalKey)
                .build();
    }

    public record AccountRef(String accountNumber, Long customerId, String status) {
    }

    /** Thrown when account-service rejected the request on business grounds (4xx). */
    public static class AccountRejectedException extends RuntimeException {
        private final int status;

        public AccountRejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    public Set<String> accountNumbersOf(Long customerId) {
        List<AccountRef> accounts = call(() -> client.get()
                .uri(uri -> uri.path("/internal/accounts").queryParam("customerId", customerId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<AccountRef>>() {
                }));
        return accounts == null ? Set.of() : accounts.stream().map(AccountRef::accountNumber).collect(Collectors.toSet());
    }

    public void transfer(String from, String to, BigDecimal amount, Long requestedBy, String reference) {
        call(() -> client.post().uri("/internal/accounts/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fromAccount", from, "toAccount", to, "amount", amount,
                        "requestedBy", requestedBy, "reference", reference))
                .retrieve()
                .toBodilessEntity());
    }

    public void deposit(String to, BigDecimal amount, String reference) {
        call(() -> client.post().uri("/internal/accounts/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("toAccount", to, "amount", amount, "reference", reference))
                .retrieve()
                .toBodilessEntity());
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                throw new AccountRejectedException(ex.getStatusCode().value(), detailOf(ex));
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Account service error. Please try again shortly.");
        } catch (ResourceAccessException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Account service is unavailable. Please try again shortly.");
        }
    }

    private String detailOf(RestClientResponseException ex) {
        try {
            JsonNode node = mapper.readTree(ex.getResponseBodyAsString());
            if (node.hasNonNull("detail")) {
                return node.get("detail").asText();
            }
        } catch (Exception ignored) {
            // fall through to the generic message
        }
        return "Request rejected by account service";
    }
}
