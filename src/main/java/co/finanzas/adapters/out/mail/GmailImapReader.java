package co.finanzas.adapters.out.mail;

import co.finanzas.application.email.model.*;
import co.finanzas.application.email.port.out.MailReaderPort;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;

public final class GmailImapReader implements MailReaderPort {
    private final String username,secret;
    private final boolean oauth;
    private final GmailAuthenticationPolicy policy;
    public GmailImapReader(String username,String secret,boolean oauth,GmailAuthenticationPolicy policy) {
        if(username==null || username.isBlank() || secret==null || secret.isBlank())throw new IllegalArgumentException("Configure Gmail credentials locally");
        this.username=username;this.secret=secret;this.oauth=oauth;this.policy=policy;
    }
    public static Properties secureProperties(boolean oauth) {
        var p=new Properties();p.setProperty("mail.imaps.ssl.enable","true");p.setProperty("mail.imaps.ssl.checkserveridentity","true");
        p.setProperty("mail.imaps.ssl.protocols","TLSv1.2 TLSv1.3");p.setProperty("mail.imaps.peek","true");
        p.setProperty("mail.imaps.connectiontimeout","10000");p.setProperty("mail.imaps.timeout","5000");p.setProperty("mail.imaps.writetimeout","5000");
        p.setProperty("mail.imaps.partialfetch","true");p.setProperty("mail.imaps.fetchsize","16384");
        p.setProperty("mail.debug","false");if(oauth)p.setProperty("mail.imaps.auth.mechanisms","XOAUTH2");
        return p;
    }
    @Override public MailBatch read(MailboxLease lease) {
        try(Store store=Session.getInstance(secureProperties(oauth)).getStore("imaps")) {
            store.connect("imap.gmail.com",993,username,secret);
            try(Folder folder=store.getFolder(lease.folder())) {
                folder.open(Folder.READ_ONLY);
                if(!(folder instanceof UIDFolder uids))throw new IllegalStateException("UID support required");
                long validity=uids.getUIDValidity(),next=uids.getUIDNext();
                if(validity<1 || next<1)throw new IllegalStateException("Invalid UID namespace");
                if(lease.uidValidity()==null || validity!=lease.uidValidity())return new MailBatch(validity,next,Math.min(lease.lastUid(),next-1),List.of());
                long end=Math.min(next-1,lease.lastUid()+500);
                if(end<=lease.lastUid())return new MailBatch(validity,next,lease.lastUid(),List.of());
                Message[] candidates=uids.getMessagesByUID(lease.lastUid()+1,end);
                var profile=new FetchProfile();profile.add(UIDFolder.FetchProfileItem.UID);folder.fetch(candidates,profile);
                var messages=new ArrayList<FetchedMail>();
                var ordered=new TreeMap<Long,Message>();
                for(Message candidate:candidates){long uid=uids.getUID(candidate);if(uid>lease.lastUid() && uid<=end)ordered.put(uid,candidate);}
                for(var entry:ordered.entrySet()) {
                    long uid=entry.getKey();Message candidate=entry.getValue();
                    Date received=candidate.getReceivedDate();String rejection=policy.rejection(candidate);
                    if(received==null)rejection="MISSING_INTERNAL_DATE";
                    byte[] evidence=null;
                    if(rejection==null) {
                        try {
                            String subject=candidate.getSubject();
                            if(!NuMailDecoder.PAYMENT.equals(subject) && !NuMailDecoder.STATEMENT.equals(subject))rejection="UNSUPPORTED_NU_SUBJECT";
                            else {
                                // Rebuild a text-only message. PDF/other attachments are never read or copied.
                                var safe=new MimeMessage(Session.getInstance(new Properties()));
                                safe.setHeader("From",candidate.getHeader("From")[0]);safe.setSubject(subject,"UTF-8");
                                for(String header:candidate.getHeader("Authentication-Results"))safe.addHeader("Authentication-Results",header);
                                safe.setText(new MimeTextExtractor().extract(candidate),"UTF-8");safe.saveChanges();
                                var output=new ByteArrayOutputStream();safe.writeTo(output);evidence=output.toByteArray();
                            }
                        } catch(IllegalArgumentException exception){rejection="INVALID_EMAIL_CONTENT";}
                    }
                    messages.add(new FetchedMail(uid,received==null?Instant.EPOCH:received.toInstant(),evidence,rejection));
                    if(messages.size()==10){end=uid;break;}
                }
                messages.sort(Comparator.comparingLong(FetchedMail::uid));
                return new MailBatch(validity,next,end,messages);
            }
        } catch(Exception exception){throw new IllegalStateException("GMAIL_READ_FAILED");}
    }
}
