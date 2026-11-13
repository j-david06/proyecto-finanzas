package co.finanzas.adapters.in.rest.security;

import co.finanzas.adapters.in.rest.ApiErrors;
import co.finanzas.adapters.in.rest.ApiProblem;
import co.finanzas.application.ingestion.model.IngestionProblem;
import co.finanzas.application.ingestion.port.in.AuthenticateApiKeyUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final AuthenticateApiKeyUseCase authentication;
    private final ObjectMapper mapper;
    private final int maxPayloadBytes;
    private final boolean requireHttps;

    public ApiKeyAuthenticationFilter(AuthenticateApiKeyUseCase authentication, ObjectMapper mapper,
                                      int maxPayloadBytes, boolean requireHttps) {
        if (maxPayloadBytes < 1 || maxPayloadBytes > 1_048_576) throw new IllegalArgumentException("Invalid payload limit");
        this.authentication = authentication;
        this.mapper = mapper;
        this.maxPayloadBytes = maxPayloadBytes;
        this.requireHttps = requireHttps;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isIngest(request) && !(request.getMethod().equals("GET")
                && request.getServletPath().matches("/api/v1/ingestions/[0-9a-f-]{36}"));
    }

    private boolean isIngest(HttpServletRequest request) {
        return request.getMethod().equals("POST") && request.getServletPath().equals("/api/v1/transactions/ingest");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // Never trust X-Forwarded-Proto from the client. Direct TLS or a trusted container/proxy setup is required.
            if (requireHttps && !request.isSecure()) throw new ApiProblem(400, "HTTPS_REQUIRED");
            var source = authentication.authenticate(singleHeader(request, "X-API-Key", 401));
            var securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(source, null,
                    List.of(new SimpleGrantedAuthority("INGESTION"))));
            SecurityContextHolder.setContext(securityContext);
            HttpServletRequest admitted = request;
            if (isIngest(request)) {
                singleHeader(request, "Idempotency-Key", 400);
                if (request.getHeader("Content-Encoding") != null
                        && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity")) {
                    throw new ApiProblem(415, "UNSUPPORTED_CONTENT_ENCODING");
                }
                if (request.getContentLengthLong() > maxPayloadBytes) throw new ApiProblem(413, "PAYLOAD_TOO_LARGE");
                byte[] raw = request.getInputStream().readNBytes(maxPayloadBytes + 1);
                if (raw.length > maxPayloadBytes) throw new ApiProblem(413, "PAYLOAD_TOO_LARGE");
                admitted = new BufferedRequest(request, raw);
            }
            chain.doFilter(admitted, response);
        } catch (IngestionProblem problem) {
            ApiErrors.write(mapper, response, ApiErrors.status(problem), problem.code().name());
        } catch (ApiProblem problem) {
            ApiErrors.write(mapper, response, problem.status(), problem.code());
        } catch (DataAccessException exception) {
            ApiErrors.write(mapper, response, 503, "STORAGE_UNAVAILABLE");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String singleHeader(HttpServletRequest request, String name, int status) {
        var headers = Collections.list(request.getHeaders(name));
        if (headers.size() != 1 || headers.getFirst().isBlank()) throw new ApiProblem(status, "INVALID_CREDENTIAL_OR_HEADER");
        return headers.getFirst();
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BufferedRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous endpoint"); }
            };
        }
    }
}
