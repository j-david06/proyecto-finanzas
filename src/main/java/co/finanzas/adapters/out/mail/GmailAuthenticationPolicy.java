package co.finanzas.adapters.out.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import java.util.Locale;

/** Trust only Gmail's own, single authentication result, over authenticated Gmail IMAPS delivery. */
public final class GmailAuthenticationPolicy {
    private final String sender;
    public GmailAuthenticationPolicy(String sender) {
        if(sender==null || !sender.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+"))throw new IllegalArgumentException("Configure the exact Nu sender");
        this.sender=sender.toLowerCase(Locale.ROOT);
    }
    public String rejection(Message message) throws MessagingException {
        try { return checkedRejection(message); }
        catch(IllegalArgumentException exception){return "INVALID_AUTHENTICATION_HEADER";}
    }
    private String checkedRejection(Message message) throws MessagingException {
        String[] from=message.getHeader("From");
        if(from==null || from.length!=1 || from[0].length()>512)return "SENDER_NOT_ALLOWED";
        InternetAddress[] addresses;
        try { addresses=InternetAddress.parse(from[0],true); }
        catch(jakarta.mail.internet.AddressException exception){return "SENDER_NOT_ALLOWED";}
        if(addresses.length!=1 || !sender.equalsIgnoreCase(addresses[0].getAddress()))return "SENDER_NOT_ALLOWED";
        String[] headers=message.getHeader("Authentication-Results");
        if(headers==null || headers.length>20)return "AUTHENTICATION_REQUIRED";
        String trusted=null;
        for(String header:headers) {
            String cleaned=clean(header);
            String authserv=cleaned.split(";",2)[0].strip();
            if(!authserv.equals("mx.google.com"))continue;
            if(trusted!=null)return "AMBIGUOUS_AUTHENTICATION";
            trusted=cleaned;
        }
        if(trusted==null)return "AUTHENTICATION_REQUIRED";
        String domain=sender.substring(sender.indexOf('@')+1);
        int passes=0;
        for(String clause:trusted.split(";")) {
            String trimmed=clause.strip();
            if(!trimmed.startsWith("dmarc="))continue;
            if(!trimmed.matches("dmarc=pass(?:[ \\t]+[a-z0-9_.-]+=[a-z0-9@._/-]+)*"))return "DMARC_NOT_ALIGNED";
            var claims=java.util.Arrays.stream(trimmed.split("[ \\t]+")).filter(token->token.startsWith("header.from=")).toList();
            if(claims.size()!=1 || !claims.getFirst().equals("header.from="+domain))return "DMARC_NOT_ALIGNED";
            passes++;
        }
        return passes==1?null:"DMARC_NOT_ALIGNED";
    }
    private static String clean(String input) {
        if(input.length()>8192)throw new IllegalArgumentException("Invalid authentication header");
        String unfolded=MimeUtility.unfold(input).toLowerCase(Locale.ROOT);
        StringBuilder result=new StringBuilder();int depth=0;boolean escape=false;
        for(char c:unfolded.toCharArray()) {
            if(escape){escape=false;continue;}
            if(depth>0 && c=='\\'){escape=true;continue;}
            if(c=='('){if(++depth>8)throw new IllegalArgumentException("Invalid authentication comment");continue;}
            if(c==')'){if(--depth<0)throw new IllegalArgumentException("Invalid authentication comment");result.append(' ');continue;}
            if(depth==0){if(c=='"' || (Character.isISOControl(c) && c!='\t'))throw new IllegalArgumentException("Invalid authentication header");result.append(c);}
        }
        if(depth!=0 || escape)throw new IllegalArgumentException("Invalid authentication comment");
        return result.toString();
    }
}
