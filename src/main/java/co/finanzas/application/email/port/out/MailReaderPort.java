package co.finanzas.application.email.port.out;
import co.finanzas.application.email.model.*;
public interface MailReaderPort { MailBatch read(MailboxLease lease); }
