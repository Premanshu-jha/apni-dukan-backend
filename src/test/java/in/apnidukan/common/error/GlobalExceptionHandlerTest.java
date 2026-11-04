package in.apnidukan.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void aDataStoreThatDoesntAnswerIs503WithRetryAfter() {
        for (Exception e : new Exception[] {new RedisConnectionFailureException("down"), new QueryTimeoutException("slow"),
                new org.springframework.transaction.CannotCreateTransactionException("no connection")}) {
            ResponseEntity<ErrorResponse> r = handler.handleUnavailable(e);
            assertThat(r.getStatusCode().value()).isEqualTo(503);
            assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(r.getBody().code()).isEqualTo("SERVICE_BUSY");
        }
    }
}
