package co.finanzas.infrastructure;

import co.finanzas.bootstrap.FinanceApplication;
import co.finanzas.application.ingestion.port.in.*;
import co.finanzas.application.processing.model.*;
import co.finanzas.application.processing.port.in.ProcessIngestionUseCase;
import co.finanzas.application.processing.port.out.ProcessingRepository;
import co.finanzas.domain.transaction.*;
import co.finanzas.domain.shared.Money;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_TEST_DB_URL", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionProcessingTest {
    private final String schema="finanzas_processing_"+UUID.randomUUID().toString().replace("-","");
    private final UUID owner=UUID.randomUUID(), account=UUID.randomUUID(), connection=UUID.randomUUID();
    private final String key="pf_ing_"+connection+"."+"s".repeat(43);
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private ProcessingRepository repository;
    private ProcessIngestionUseCase processor;

    @BeforeAll void start() {
        context=new SpringApplicationBuilder(FinanceApplication.class).profiles("api","demo").run(
                "--spring.datasource.url="+System.getenv("FINANCE_TEST_DB_URL"),
                "--spring.datasource.username="+System.getenv().getOrDefault("FINANCE_TEST_DB_USER","finance_test"),
                "--spring.datasource.password="+System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD",""),
                "--spring.datasource.hikari.connection-init-sql=SET search_path TO "+schema,
                "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,
                "--spring.jpa.properties.hibernate.default_schema="+schema,"--server.port=0",
                "--finance.ingestion.require-https=false","--finance.processing.scheduling-enabled=false",
                "--finance.processing.max-attempts=3","--finance.processing.retry-seconds=1",
                "--finance.ingestion.payload-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        jdbc=context.getBean(JdbcTemplate.class); repository=context.getBean(ProcessingRepository.class);
        processor=context.getBean(ProcessIngestionUseCase.class);
    }
    @BeforeEach void fixtures() throws Exception {
        assertEquals(schema,jdbc.queryForObject("SELECT current_schema()",String.class));
        jdbc.update("DELETE FROM ingestion_logs"); jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM source_connections"); jdbc.update("DELETE FROM accounts");
        jdbc.update("INSERT INTO accounts(id,owner_id,name,kind) VALUES (?,?,'Test','DEPOSIT')",account,owner);
        jdbc.update("INSERT INTO source_connections(id,owner_id,source,name,api_key_sha256,allowed_account_ids) VALUES (?,?,'IOS_SHORTCUTS','Test',?,ARRAY[?]::uuid[])",
                connection,owner,java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)),account);
    }
    @AfterAll void close() throws Exception {
        if(context!=null) context.close();
        if(!schema.matches("finanzas_processing_[a-f0-9]{32}")) throw new IllegalStateException();
        try(var c=SchemaMigrationTest.open();var s=c.createStatement()){s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
    private UUID accept(String body) {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        var decoded=context.getBean(IngestionRequestDecoder.class).decode(bytes);
        return context.getBean(ReceiveIngestionUseCase.class).receive(
                context.getBean(AuthenticateApiKeyUseCase.class).authenticate(key),UUID.randomUUID(),account,decoded.amount(),bytes).ingestionId();
    }
    private String body(String kind,String bankId,String amount) {
        String bank=bankId==null ? "" : ",\"bank_provider\":\"NU\",\"bank_event_id\":\""+bankId+"\"";
        return """
                {"schema_version":1,"trigger":"APPLE_PAY","format":"NORMALIZED","account_id":"%s",
                "movement":{"kind":"%s","amount":"%s","occurred_at":"2026-11-13T12:00:00-05:00","merchant":"Prueba"%s}}
                """.formatted(account,kind,amount,bank);
    }
    private String state(UUID id) { return jdbc.queryForObject("SELECT state FROM ingestion_logs WHERE id=?",String.class,id); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM transactions",Integer.class); }
    private Transaction posting(IngestionWork work) {
        return new Transaction(work.id(),work.owner(),repository.account(work,account),null,null,null,
                TransactionKind.EXPENSE,TransactionStatus.POSTED,new Money(new java.math.BigDecimal("45.90")),Instant.parse("2026-11-13T17:00:00Z"),"Prueba",null,null,null,null,null);
    }

    @Test void postsIncomeAndExpenseAndExposesScopedResult() {
        UUID expense=accept(body("EXPENSE",null,"45.90")),income=accept(body("INCOME",null,"100.01"));
        assertTrue(processor.processNext()); assertTrue(processor.processNext()); assertFalse(processor.processNext());
        assertEquals(2,count()); assertEquals("PROCESSED",state(expense)); assertEquals("PROCESSED",state(income));
        var receipt=context.getBean(ReceiveIngestionUseCase.class).status(context.getBean(AuthenticateApiKeyUseCase.class).authenticate(key),expense);
        assertEquals(expense,receipt.transactionId()); assertNull(receipt.errorCode()); assertEquals(1,receipt.attempts());
        assertEquals("POSTED",jdbc.queryForObject("SELECT status FROM transactions WHERE id=?",String.class,expense));
        assertEquals(new java.math.BigDecimal("45.90"),jdbc.queryForObject("SELECT amount FROM transactions WHERE id=?",java.math.BigDecimal.class,expense));
    }
    @Test void concurrentWorkersCreateOneTransactionPerIngestion() throws Exception {
        for(int i=0;i<12;i++) accept(body("EXPENSE",null,"45.90"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(()->{start.await(); while(processor.processNext()) {} return null;}));
            start.countDown(); for(var future:futures) future.get(30,TimeUnit.SECONDS);
        }
        assertEquals(12,count());
        assertEquals(12,jdbc.queryForObject("SELECT count(*) FROM ingestion_logs WHERE state='PROCESSED' AND attempts=1",Integer.class));
    }
    @Test void expiredLeaseIsReclaimedAndOldTokenCannotWriteOrFail() {
        UUID id=accept(body("EXPENSE",null,"45.90")); var old=repository.claim().orElseThrow();
        jdbc.update("UPDATE ingestion_logs SET lease_until=now()-interval '1 second' WHERE id=?",id);
        var current=repository.claim().orElseThrow(); assertNotEquals(old.leaseToken(),current.leaseToken());
        assertEquals(2,current.attempts()); assertFalse(repository.complete(old,posting(old),"normalized-v1"));
        assertFalse(repository.fail(old,new ProcessingFailure(ProcessingFailure.Code.TEMPORARY_FAILURE,true)));
        assertTrue(repository.complete(current,posting(current),"normalized-v1")); assertEquals(1,count());
    }
    @Test void sameBankEventLinksExistingTransactionAndConflictingContentRequiresReview() {
        UUID first=accept(body("EXPENSE","bank-1","45.90")); processor.processNext();
        UUID duplicate=accept(body("EXPENSE","bank-1","45.90")); processor.processNext();
        UUID conflict=accept(body("EXPENSE","bank-1","45.91")); processor.processNext();
        assertEquals(1,count()); assertEquals("PROCESSED",state(duplicate)); assertEquals("REVIEW",state(conflict));
        assertEquals(first,jdbc.queryForObject("SELECT transaction_id FROM ingestion_logs WHERE id=?",UUID.class,duplicate));
        assertEquals("BANK_EVENT_CONFLICT",jdbc.queryForObject("SELECT error_code FROM ingestion_logs WHERE id=?",String.class,conflict));
    }
    @Test void concurrentBankDuplicatesConvergeOnOneFinancialRow() throws Exception {
        for(int i=0;i<6;i++) accept(body("EXPENSE","same-bank-event","45.90"));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<6;i++) futures.add(executor.submit(()->{start.await(); return processor.processNext();}));
            start.countDown(); for(var future:futures) future.get(30,TimeUnit.SECONDS);
        }
        assertEquals(1,count());
        assertEquals(6,jdbc.queryForObject("SELECT count(*) FROM ingestion_logs WHERE state='PROCESSED'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(DISTINCT transaction_id) FROM ingestion_logs",Integer.class));
    }
    @Test void rawMessageNeedsParserAndCreatesNoTransaction() {
        UUID id=accept("""
                {"schema_version":1,"trigger":"SMS","format":"RAW_MESSAGE","account_id":"%s",
                "message":{"text":"Mensaje Nu","received_at":"2026-11-13T12:00:00-05:00"}}
                """.formatted(account));
        processor.processNext(); assertEquals("REVIEW",state(id)); assertEquals(0,count());
        assertEquals("PARSER_UNAVAILABLE",jdbc.queryForObject("SELECT error_code FROM ingestion_logs WHERE id=?",String.class,id));
    }
    @Test void tamperingWithEncryptedPayloadOrHashNeverPosts() {
        UUID id=accept(body("EXPENSE",null,"45.90"));
        jdbc.update("UPDATE ingestion_logs SET payload_encrypted=set_byte(payload_encrypted,20,get_byte(payload_encrypted,20) # 1) WHERE id=?",id);
        processor.processNext(); assertEquals("REVIEW",state(id)); assertEquals(0,count());
        UUID hashId=accept(body("EXPENSE",null,"45.90"));
        jdbc.update("UPDATE ingestion_logs SET payload_sha256=decode(repeat('00',32),'hex') WHERE id=?",hashId);
        processor.processNext(); assertEquals("REVIEW",state(hashId)); assertEquals(0,count());
    }
    @Test void missingKeyRetriesWithBackoffThenBecomesDead() {
        UUID id=accept(body("EXPENSE",null,"45.90")); jdbc.update("UPDATE ingestion_logs SET payload_key_id='old' WHERE id=?",id);
        processor.processNext(); assertEquals("RETRY",state(id)); assertFalse(processor.processNext());
        assertTrue(jdbc.queryForObject("SELECT available_at>updated_at FROM ingestion_logs WHERE id=?",Boolean.class,id));
        for(int i=0;i<2;i++) { jdbc.update("UPDATE ingestion_logs SET available_at=now()-interval '1 second' WHERE id=?",id); processor.processNext(); }
        assertEquals("DEAD",state(id)); assertEquals(0,count()); assertFalse(processor.processNext());
    }
    @Test void crashesOnLastLeaseEventuallyBecomeDead() {
        UUID id=accept(body("EXPENSE",null,"45.90"));
        for(int i=0;i<3;i++) { assertTrue(repository.claim().isPresent()); jdbc.update("UPDATE ingestion_logs SET lease_until=now()-interval '1 second' WHERE id=?",id); }
        assertTrue(repository.claim().isEmpty()); assertEquals("DEAD",state(id)); assertEquals(0,count());
    }
    @Test void revokedConnectionOrInactiveAccountCannotPostAcceptedWork() {
        UUID id=accept(body("EXPENSE",null,"45.90")); jdbc.update("UPDATE source_connections SET active=false WHERE id=?",connection);
        processor.processNext(); assertEquals("REVIEW",state(id)); assertEquals(0,count());
        jdbc.update("UPDATE source_connections SET active=true WHERE id=?",connection);
        UUID inactive=accept(body("EXPENSE",null,"45.90")); jdbc.update("UPDATE accounts SET active=false WHERE id=?",account);
        processor.processNext(); assertEquals("REVIEW",state(inactive)); assertEquals(0,count());
    }
    @Test void financialInsertRollsBackWhenAcknowledgementFails() {
        UUID id=accept(body("EXPENSE",null,"45.90"));
        jdbc.execute("CREATE FUNCTION fail_processing_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.state='PROCESSED' THEN RAISE EXCEPTION 'test rollback' USING ERRCODE='40001'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_ack BEFORE UPDATE ON ingestion_logs FOR EACH ROW EXECUTE FUNCTION fail_processing_ack()");
        try {
            processor.processNext(); assertEquals(0,count()); assertEquals("RETRY",state(id));
        } finally { jdbc.execute("DROP TRIGGER test_ack ON ingestion_logs"); jdbc.execute("DROP FUNCTION fail_processing_ack()"); }
        jdbc.update("UPDATE ingestion_logs SET available_at=now()-interval '1 second' WHERE id=?",id);
        processor.processNext(); assertEquals(1,count()); assertEquals("PROCESSED",state(id));
    }
}
