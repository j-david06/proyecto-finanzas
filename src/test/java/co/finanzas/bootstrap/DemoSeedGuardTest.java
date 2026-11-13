package co.finanzas.bootstrap;

import co.finanzas.adapters.out.crypto.Sha256Digests;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DemoSeedGuardTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    @Test void neverProvisionsThePersonalFinanceDatabase() {
        var runner = new DemoSeedConfiguration().demoSeed(jdbc, manager, new Sha256Digests(),
                "jdbc:postgresql://127.0.0.1:55432/finanzas", "127.0.0.1");
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verifyNoInteractions(jdbc, manager);
    }

    @Test void refusesProvisioningWhenServerIsExposedToTheNetwork() {
        var runner = new DemoSeedConfiguration().demoSeed(jdbc, manager, new Sha256Digests(),
                "jdbc:postgresql://127.0.0.1:55432/finanzas_demo", "0.0.0.0");
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verifyNoInteractions(jdbc, manager);
    }

    @Test void checksTheActualDatabaseBeforeReadingCredentialsOrWritingRecords() {
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("finanzas");
        var runner = new DemoSeedConfiguration().demoSeed(jdbc, manager, new Sha256Digests(),
                "jdbc:postgresql://127.0.0.1:55432/finanzas_demo", "127.0.0.1");
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verify(jdbc).queryForObject("SELECT current_database()", String.class);
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(manager);
    }
}
