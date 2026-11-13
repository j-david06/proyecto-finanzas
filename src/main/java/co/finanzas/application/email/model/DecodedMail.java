package co.finanzas.application.email.model;
import java.time.LocalDate;
public record DecodedMail(String kind,String state,String errorCode,byte[] payload,LocalDate cutOn,
                           co.finanzas.domain.billing.BillingSnapshot billing) {
    public DecodedMail(String kind,String state,String errorCode,byte[] payload,LocalDate cutOn) {
        this(kind,state,errorCode,payload,cutOn,null);
    }
    public DecodedMail { payload=payload==null?null:payload.clone(); }
    @Override public byte[] payload() { return payload==null?null:payload.clone(); }
    public static DecodedMail rejected(String state,String code) { return new DecodedMail("UNKNOWN",state,code,null,null); }
}
