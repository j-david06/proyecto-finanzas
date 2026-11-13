package co.finanzas.infrastructure;

import co.finanzas.bootstrap.FinanceApplication;
import co.finanzas.adapters.out.persistence.postgres.PostgresEmailInbox;
import co.finanzas.adapters.out.crypto.*;
import co.finanzas.adapters.out.mail.*;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.application.email.model.*;
import co.finanzas.application.email.port.out.MailReaderPort;
import co.finanzas.application.email.service.PollMailboxService;
import co.finanzas.application.processing.port.in.ProcessIngestionUseCase;
import co.finanzas.ingestion.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailInboxTest {
    private final String schema="finanzas_email_"+UUID.randomUUID().toString().replace("-","");
    private final UUID owner=UUID.randomUUID(),account=UUID.randomUUID(),card=UUID.randomUUID(),connection=UUID.randomUUID();
    private ConfigurableApplicationContext context;private JdbcTemplate jdbc;private PostgresEmailInbox repository;
    private final Sha256Digests digests=new Sha256Digests();
    private final AesGcmPayloadCipher cipher=new AesGcmPayloadCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","v1");
    @BeforeAll void start(){
        context=new SpringApplicationBuilder(FinanceApplication.class).profiles("worker").run(
                "--spring.datasource.url="+System.getenv("FINANCE_TEST_DB_URL"),
                "--spring.datasource.username="+System.getenv().getOrDefault("FINANCE_TEST_DB_USER","finance_test"),
                "--spring.datasource.password="+System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD",""),
                "--spring.datasource.hikari.connection-init-sql=SET search_path TO "+schema,
                "--spring.flyway.schemas="+schema,"--spring.flyway.default-schema="+schema,"--spring.jpa.properties.hibernate.default_schema="+schema,
                "--finance.processing.scheduling-enabled=false","--spring.main.keep-alive=false",
                "--finance.ingestion.payload-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        jdbc=context.getBean(JdbcTemplate.class);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));tx.setTimeout(10);
        repository=new PostgresEmailInbox(jdbc,tx,connection);
    }
    @BeforeEach void fixtures(){
        assertEquals(schema,jdbc.queryForObject("SELECT current_schema()",String.class));
        jdbc.update("DELETE FROM billing_statements");jdbc.update("DELETE FROM email_receipts");jdbc.update("DELETE FROM ingestion_logs");
        jdbc.update("DELETE FROM transactions");jdbc.update("DELETE FROM email_mailboxes");jdbc.update("DELETE FROM source_connections");jdbc.update("DELETE FROM accounts");
        jdbc.update("INSERT INTO accounts(id,owner_id,name,kind,bank_reference) VALUES (?,?,'Deposit','DEPOSIT','ABC123')",account,owner);
        jdbc.update("INSERT INTO accounts(id,owner_id,name,kind,card_last4) VALUES (?,?,'Card','CREDIT_CARD','9876')",card,owner);
        jdbc.update("INSERT INTO source_connections(id,owner_id,source,name,allowed_account_ids) VALUES (?,?,'IMAP','Gmail test',ARRAY[?,?]::uuid[])",connection,owner,account,card);
        repository.configure(account,card,"Nu",new byte[32]);
    }
    @AfterAll void close()throws Exception{
        if(context!=null)context.close();if(!schema.matches("finanzas_email_[a-f0-9]{32}"))throw new IllegalStateException();
        try(var c=SchemaMigrationTest.open();var s=c.createStatement()){s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    }
    private PollMailboxService service(List<FetchedMail> messages,boolean backfill){
        MailReaderPort reader=lease->new MailBatch(777,10,9,messages.stream().filter(m->m.uid()>lease.lastUid()).toList());
        return withReader(reader,backfill);
    }
    private PollMailboxService withReader(MailReaderPort reader,boolean backfill){
        var decoder=new NuMailDecoder(new GmailAuthenticationPolicy(MailDecoderTest.SENDER),new ObjectMapper(),context.getBean(IngestionRequestDecoder.class));
        return new PollMailboxService(repository,reader,decoder,cipher,digests,backfill);
    }
    private FetchedMail payment(long uid)throws Exception{return new FetchedMail(uid,Instant.parse("2026-11-13T18:00:00Z"),
            MailDecoderTest.bytes(MailDecoderTest.message(NuMailDecoder.PAYMENT,MailDecoderTest.payment(),MailDecoderTest.AUTH)),null);}
    private FetchedMail statement(long uid,String amount,Instant at)throws Exception{return new FetchedMail(uid,at,
            MailDecoderTest.bytes(MailDecoderTest.message(NuMailDecoder.STATEMENT,NuMessageParserTest.sample("statement").replace("190.000,00",amount),MailDecoderTest.AUTH)),null);}
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    @Test void atomicallyQueuesPaymentAndPersistsStatementWithoutCreatingAnExpense()throws Exception{
        var poll=service(List.of(payment(1),statement(2,"190.000,00",Instant.parse("2026-10-15T18:00:00Z"))),true);
        assertEquals(0,poll.poll());assertEquals(2,poll.poll());assertEquals(0,poll.poll());
        assertEquals(1,count("ingestion_logs"));assertEquals(1,count("billing_statements"));assertEquals(0,count("transactions"));
        assertTrue(context.getBean(ProcessIngestionUseCase.class).processNext());assertEquals(1,count("transactions"));
        assertEquals("TRANSFER",jdbc.queryForObject("SELECT kind FROM transactions",String.class));
        assertEquals("2026-11-04",jdbc.queryForObject("SELECT payment_due_date::text FROM billing_statements",String.class));
        jdbc.update("UPDATE email_mailboxes SET last_uid=0");poll.poll();
        assertEquals(2,count("email_receipts"));assertEquals(1,count("ingestion_logs"));assertFalse(context.getBean(ProcessIngestionUseCase.class).processNext());
        assertFalse(new String(jdbc.queryForObject("SELECT payload_encrypted FROM email_receipts WHERE kind='STATEMENT'",byte[].class),java.nio.charset.StandardCharsets.UTF_8).contains("190.000"));
    }
    @Test void defaultFromNowDoesNotImportHistoricalMessages()throws Exception{
        var poll=service(List.of(payment(1)),false);assertEquals(0,poll.poll());assertEquals(0,poll.poll());
        assertEquals(0,count("email_receipts"));assertEquals(9L,jdbc.queryForObject("SELECT last_uid FROM email_mailboxes",Long.class));
    }
    @Test void uidValidityChangeStopsInsteadOfSilentlyResettingCursor()throws Exception{
        service(List.of(payment(1)),true).poll();
        var changed=withReader(lease->new MailBatch(778,10,9,List.of()),true);
        assertThrows(IllegalStateException.class,changed::poll);
        assertEquals("UIDVALIDITY_CHANGED",jdbc.queryForObject("SELECT error_code FROM email_mailboxes",String.class));
        assertEquals(777L,jdbc.queryForObject("SELECT uid_validity FROM email_mailboxes",Long.class));assertEquals(0,count("ingestion_logs"));
    }
    @Test void expiredLeaseCannotSaveOrAdvanceAfterAnotherWorkerReclaims()throws Exception{
        service(List.of(),true).poll();var old=repository.claim().orElseThrow();
        jdbc.update("UPDATE email_mailboxes SET lease_until=now()-interval '1 second'");var current=repository.claim().orElseThrow();
        assertFalse(repository.save(old,777,payment(1),DecodedMail.rejected("REVIEW","TEST"),null,null));
        assertFalse(repository.advance(old,9));repository.release(old);
        assertEquals(current.token(),jdbc.queryForObject("SELECT lease_token FROM email_mailboxes",UUID.class));repository.release(current);
    }
    @Test void poisonMessageIsReviewedAndDoesNotPreventTheNextPayment()throws Exception{
        var bad=new FetchedMail(1,Instant.now(),new byte[]{1,2,3},null);var poll=service(List.of(bad,payment(2)),true);
        poll.poll();assertEquals(2,poll.poll());assertEquals(1,count("ingestion_logs"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM email_receipts WHERE state='REVIEW' OR state='IGNORED'",Integer.class));
    }
    @Test void cursorAcknowledgementFailureRollsBackFinancialInboxAndAudit()throws Exception{
        var poll=service(List.of(payment(1)),true);poll.poll();
        jdbc.execute("CREATE FUNCTION fail_email_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.last_uid>0 THEN RAISE EXCEPTION 'test rollback' USING ERRCODE='40001'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_email_ack BEFORE UPDATE ON email_mailboxes FOR EACH ROW EXECUTE FUNCTION fail_email_ack()");
        try{assertThrows(RuntimeException.class,poll::poll);assertEquals(0,count("email_receipts"));assertEquals(0,count("ingestion_logs"));}
        finally{jdbc.execute("DROP TRIGGER test_email_ack ON email_mailboxes");jdbc.execute("DROP FUNCTION fail_email_ack()");}
        assertEquals(1,poll.poll());assertEquals(1,count("email_receipts"));
    }
    @Test void laterReceiptUpdatesBillingButOlderReceiptCannotOverwriteIt()throws Exception{
        var poll=service(List.of(statement(1,"200.000,00",Instant.parse("2026-10-16T18:00:00Z")),
                statement(2,"190.000,00",Instant.parse("2026-10-15T18:00:00Z"))),true);
        poll.poll();poll.poll();assertEquals(2,count("email_receipts"));assertEquals(1,count("billing_statements"));
        assertEquals(new java.math.BigDecimal("200000.00"),jdbc.queryForObject("SELECT minimum_payment FROM billing_statements",java.math.BigDecimal.class));
        assertEquals(0,count("transactions"));assertEquals(0,count("ingestion_logs"));
    }
    @Test void mailboxCannotBeReboundToADifferentGmailLogin(){
        byte[] other=new byte[32];java.util.Arrays.fill(other,(byte)1);
        assertThrows(IllegalStateException.class,()->repository.configure(account,card,"Nu",other));
        assertEquals(1,count("email_mailboxes"));
    }
    @Test void revocationPreventsCursorInitializationAndFurtherClaims(){
        var lease=repository.claim().orElseThrow();jdbc.update("UPDATE source_connections SET active=false WHERE id=?",connection);
        assertFalse(repository.initialize(lease,777,9));repository.release(lease);assertTrue(repository.claim().isEmpty());
        assertNull(jdbc.queryForObject("SELECT uid_validity FROM email_mailboxes",Long.class));
    }
    @Test void emailBackupAndShortcutsReceiptConvergeOnTheSamePayment()throws Exception{
        UUID ios=UUID.randomUUID(),event=UUID.randomUUID(),ingestion=UUID.randomUUID();
        jdbc.update("INSERT INTO source_connections(id,owner_id,source,name,api_key_sha256,allowed_account_ids) VALUES (?,?,'IOS_SHORTCUTS','Test iOS',?,ARRAY[?,?]::uuid[])",ios,owner,new byte[32],account,card);
        byte[] payload=NuMessageParserTest.body(account,card,"EMAIL",NuMessageParserTest.sample("card-payment")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var encrypted=cipher.encrypt(payload,(owner+":"+ios+":"+event).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO ingestion_logs(id,owner_id,source,source_connection_id,idempotency_key,payload_sha256,payload_encrypted,payload_key_id) VALUES (?,?,'IOS_SHORTCUTS',?,?,?,?,?)",
                ingestion,owner,ios,event.toString(),digests.sha256(payload),encrypted.bytes(),encrypted.keyId());
        var processor=context.getBean(ProcessIngestionUseCase.class);assertTrue(processor.processNext());
        var poll=service(List.of(payment(1)),true);poll.poll();poll.poll();assertTrue(processor.processNext());
        assertEquals(2,count("ingestion_logs"));assertEquals(1,count("transactions"));
        assertEquals(1,jdbc.queryForObject("SELECT count(DISTINCT transaction_id) FROM ingestion_logs WHERE state='PROCESSED'",Integer.class));
    }
}
