package co.finanzas.ingestion;

import co.finanzas.adapters.in.rest.security.ApiKeyAuthenticationFilter;
import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.domain.shared.OwnerId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {
    private final AuthenticatedSource source = new AuthenticatedSource(UUID.randomUUID(),
            new OwnerId(UUID.randomUUID()), new byte[32], Set.of(UUID.randomUUID()));

    @Test void limitsChunkedBodiesWithoutTrustingContentLength() throws Exception {
        var filter = new ApiKeyAuthenticationFilter(key -> source, new ObjectMapper(), 32, false);
        var request = request();
        request.setContent(new byte[33]);
        var wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        var response = new MockHttpServletResponse();
        filter.doFilter(wrapped, response, (req, res) -> fail("Oversized body reached MVC"));
        assertEquals(413, response.getStatus());
    }

    @Test void rejectsPlaintextEvenWhenClientForgesProxyHeaders() throws Exception {
        var filter = new ApiKeyAuthenticationFilter(key -> source, new ObjectMapper(), 32, true);
        var request = request();
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Untrusted proxy header accepted"));
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("HTTPS_REQUIRED"));
    }

    @Test void rejectsAmbiguousAuthenticationAndIdempotencyHeaders() throws Exception {
        for (String header : new String[]{"X-API-Key", "Idempotency-Key"}) {
            var request = request();
            request.addHeader(header, "another-value");
            var response = new MockHttpServletResponse();
            new ApiKeyAuthenticationFilter(key -> source, new ObjectMapper(), 32, false)
                    .doFilter(request, response, (req, res) -> fail("Duplicate header accepted"));
            assertEquals(header.equals("X-API-Key") ? 401 : 400, response.getStatus());
        }
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/transactions/ingest");
        request.setServletPath("/api/v1/transactions/ingest");
        request.addHeader("X-API-Key", "test-key");
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
        return request;
    }
}
