package co.finanzas.application.email.port.out;
import co.finanzas.application.email.model.*;
public interface MailDecoderPort { DecodedMail decode(MailboxLease lease,FetchedMail mail); }
