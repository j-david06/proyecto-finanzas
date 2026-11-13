package co.finanzas.adapters.out.mail;

import jakarta.mail.*;
import jakarta.mail.internet.ContentType;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Bounded text extraction. Attachments, embedded messages and remote resources are never opened. */
public final class MimeTextExtractor {
    public String extract(Part part) throws MessagingException,IOException {
        String text=extract(part,0,new int[]{0});
        if(text==null || text.length()>8192)throw new IllegalArgumentException("Unsupported email body");
        return text.replace('\u00a0',' ').replace('\u202f',' ').lines().map(String::strip).map(s->s.replaceAll("[ \\t]+"," "))
                .filter(s->!s.isEmpty()).collect(Collectors.joining("\n"));
    }
    private String extract(Part part,int depth,int[] parts) throws MessagingException,IOException {
        if(depth>8 || ++parts[0]>40)throw new IllegalArgumentException("MIME limits exceeded");
        if(Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName()!=null || part.isMimeType("message/*"))return null;
        if(part.isMimeType("multipart/*")) {
            Multipart content=(Multipart)part.getContent();
            if(content.getCount()>40)throw new IllegalArgumentException("MIME limits exceeded");
            if(part.isMimeType("multipart/alternative")) {
                for(int i=0;i<content.getCount();i++)if(content.getBodyPart(i).isMimeType("text/plain")) {
                    String plain=extract(content.getBodyPart(i),depth+1,parts);if(plain!=null)return plain;
                }
            }
            for(int i=0;i<content.getCount();i++){String found=extract(content.getBodyPart(i),depth+1,parts);if(found!=null)return found;}
            return null;
        }
        if(!part.isMimeType("text/plain") && !part.isMimeType("text/html"))return null;
        if(part.getSize()>262144)throw new IllegalArgumentException("Text part too large");
        byte[] data;
        try(var stream=part.getInputStream()){data=stream.readNBytes(262145);}
        if(data.length>262144)throw new IllegalArgumentException("Text part too large");
        String charset=new ContentType(part.getContentType()).getParameter("charset");
        String text=new String(data,charset==null?StandardCharsets.US_ASCII:Charset.forName(charset));
        if(part.isMimeType("text/plain"))return text;
        Document document=Jsoup.parse(text);document.select("head,script,style,iframe,object,svg").remove();
        StringBuilder result=new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor(){
            @Override public void head(Node node,int ignored){
                if(node instanceof TextNode value)result.append(value.text());
                if(node instanceof Element element && element.normalName().equals("br"))result.append('\n');
            }
            @Override public void tail(Node node,int ignored){
                if(node instanceof Element element) {
                    if(element.normalName().equals("td") || element.normalName().equals("th"))result.append(' ');
                    else if(element.tag().isBlock())result.append('\n');
                }
            }
        },document.body());
        return result.toString();
    }
}
