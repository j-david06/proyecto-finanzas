package co.finanzas.ingestion;
import co.finanzas.adapters.in.cli.EmailPreview;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class EmailPreviewTest {
    @Test void previewsFixtureWithoutDatabaseOrGmailCredentials()throws Exception{
        String output=new EmailPreview().inspect(Path.of("src/test/resources/nu/statement-preview.eml"),"nu@example.test",Instant.parse("2026-10-15T18:00:00Z"));
        assertTrue(output.contains("OFFLINE_PREVIEW_NO_WRITES"));assertTrue(output.contains("190000.00"));assertTrue(output.contains("2026-11-04"));
    }
}
