package co.finanzas.bootstrap;

import co.finanzas.adapters.in.worker.EmailPollingJob;
import co.finanzas.adapters.out.mail.*;
import co.finanzas.adapters.out.persistence.postgres.PostgresEmailInbox;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.application.email.port.in.PollMailboxUseCase;
import co.finanzas.application.email.port.out.*;
import co.finanzas.application.email.service.PollMailboxService;
import co.finanzas.application.ingestion.port.out.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Configuration(proxyBeanMethods=false)
@Profile("worker & !api")
@ConditionalOnProperty(name="finance.email.enabled",havingValue="true")
@EnableScheduling
public class EmailConfiguration {
    @Bean GmailAuthenticationPolicy gmailAuthenticationPolicy(@Value("${finance.email.sender}") String sender){return new GmailAuthenticationPolicy(sender);}
    @Bean MailReaderPort mailReader(@Value("${finance.email.username}") String username,
            @Value("${finance.email.app-password}") String password,@Value("${finance.email.access-token}") String token,
            GmailAuthenticationPolicy policy){
        return new GmailImapReader(username,token.isBlank()?password:token,!token.isBlank(),policy);
    }
    @Bean MailDecoderPort mailDecoder(GmailAuthenticationPolicy policy,ObjectMapper mapper,IngestionRequestDecoder decoder){return new NuMailDecoder(policy,mapper,decoder);}
    @Bean PostgresEmailInbox emailInbox(JdbcTemplate jdbc,PlatformTransactionManager manager,@Value("${finance.email.connection-id}") UUID connection){
        var tx=new TransactionTemplate(manager);tx.setTimeout(10);return new PostgresEmailInbox(jdbc,tx,connection);
    }
    @Bean ApplicationRunner mailboxBinding(PostgresEmailInbox inbox,DigestPort digests,
            @Value("${finance.email.account-id}") UUID account,@Value("${finance.email.destination-account-id}") UUID destination,
            @Value("${finance.email.folder}") String folder,@Value("${finance.email.username}") String username){
        if(folder.isBlank() || folder.length()>255 || folder.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid Gmail folder");
        return args->inbox.configure(account,destination,folder,digests.sha256(username.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
    }
    @Bean PollMailboxUseCase pollMailbox(EmailInboxRepository repository,MailReaderPort reader,MailDecoderPort decoder,
            PayloadCipherPort cipher,DigestPort digests,@Value("${finance.email.backfill:false}") boolean backfill){
        return new PollMailboxService(repository,reader,decoder,cipher,digests,backfill);
    }
    @Bean EmailPollingJob emailPollingJob(PollMailboxUseCase mailbox){return new EmailPollingJob(mailbox);}
}
