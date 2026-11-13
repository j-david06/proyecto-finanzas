package co.finanzas.bootstrap;

import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.adapters.out.crypto.*;
import co.finanzas.application.ingestion.port.out.DigestPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("api | worker")
public class IngestionCodecConfiguration {
    @Bean Clock ingestionClock() { return Clock.systemUTC(); }
    @Bean DigestPort digests() { return new Sha256Digests(); }
    @Bean AesGcmPayloadCipher payloadCipher(@Value("${finance.ingestion.payload-key-base64}") String key,
                                           @Value("${finance.ingestion.payload-key-id}") String keyId) {
        return new AesGcmPayloadCipher(key, keyId);
    }
    @Bean IngestionRequestDecoder ingestionRequestDecoder(ObjectMapper mapper, Validator validator) {
        return new IngestionRequestDecoder(mapper, validator);
    }
}
