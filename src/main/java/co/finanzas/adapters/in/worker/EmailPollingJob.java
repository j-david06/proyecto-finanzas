package co.finanzas.adapters.in.worker;

import co.finanzas.application.email.port.in.PollMailboxUseCase;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class EmailPollingJob {
    private final PollMailboxUseCase mailbox;
    public EmailPollingJob(PollMailboxUseCase mailbox){this.mailbox=mailbox;}
    @Scheduled(fixedDelayString="${finance.email.poll-delay-ms:60000}",initialDelayString="${finance.email.poll-delay-ms:60000}")
    public void poll(){
        try { mailbox.poll(); }
        catch(RuntimeException exception){LoggerFactory.getLogger(EmailPollingJob.class).warn("Email polling interrupted: MAILBOX_POLL_FAILED");}
    }
}
