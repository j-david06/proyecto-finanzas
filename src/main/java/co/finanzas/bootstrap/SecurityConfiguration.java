package co.finanzas.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import co.finanzas.adapters.in.rest.ApiErrors;
import co.finanzas.adapters.in.rest.security.ApiKeyAuthenticationFilter;
import co.finanzas.application.ingestion.port.in.AuthenticateApiKeyUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;

/** API Keys are scoped to ingestion routes; other routes remain closed. */
@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticateApiKeyUseCase authentication,
                                            ObjectMapper mapper,
                                            @Value("${finance.ingestion.max-payload-bytes}") int maxPayload,
                                            @Value("${finance.ingestion.require-https}") boolean requireHttps,
                                            Environment environment) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> ApiErrors.write(mapper, response, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((request, response, exception) -> ApiErrors.write(mapper, response, 403, "FORBIDDEN")))
                .addFilterBefore(new ApiKeyAuthenticationFilter(authentication, mapper, maxPayload, requireHttps),
                        UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(policy -> policy.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(requests -> {
                    if (environment.matchesProfiles("demo")) {
                        requests.requestMatchers(HttpMethod.GET, "/demo", "/demo/**").permitAll();
                    }
                    requests.requestMatchers(HttpMethod.POST, "/api/v1/transactions/ingest").hasAuthority("INGESTION")
                            .requestMatchers(HttpMethod.GET, "/api/v1/ingestions/*").hasAuthority("INGESTION")
                            .anyRequest().denyAll();
                })
                .build();
    }
}
