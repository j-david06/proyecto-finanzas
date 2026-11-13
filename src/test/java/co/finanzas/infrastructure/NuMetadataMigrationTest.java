package co.finanzas.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_TEST_DB_URL",matches=".+")
class NuMetadataMigrationTest {
    @Test void upgradesExistingV1WithoutLosingData() throws Exception {
        String schema="finanzas_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var config=Flyway.configure().dataSource(System.getenv("FINANCE_TEST_DB_URL"),
                System.getenv().getOrDefault("FINANCE_TEST_DB_USER","finance_test"),System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD",""))
                .schemas(schema).defaultSchema(schema);
        try(var c=SchemaMigrationTest.open();var s=c.createStatement()) {
            assertEquals(1,config.target("1").load().migrate().migrationsExecuted);
            s.execute("SET search_path TO "+schema);
            s.execute("INSERT INTO accounts(id,owner_id,name,kind) VALUES ('"+UUID.randomUUID()+"','"+UUID.randomUUID()+"','Existing','DEPOSIT')");
            assertEquals(2,config.target("latest").load().migrate().migrationsExecuted);
            try(var rs=s.executeQuery("SELECT name,bank_reference,card_last4 FROM accounts")) {
                assertTrue(rs.next());assertEquals("Existing",rs.getString(1));assertNull(rs.getString(2));assertNull(rs.getString(3));
            }
        } finally {
            if(!schema.matches("finanzas_upgrade_[a-f0-9]{32}"))throw new IllegalStateException();
            try(var c=SchemaMigrationTest.open();var s=c.createStatement()){s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
        }
    }
}
