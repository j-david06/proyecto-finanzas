package co.finanzas.application.email.model;

import java.time.Instant;
public record FetchedMail(long uid,Instant receivedAt,byte[] bytes,String rejectionCode) {
    public FetchedMail { bytes=bytes==null?null:bytes.clone(); }
    @Override public byte[] bytes() { return bytes==null?null:bytes.clone(); }
}
