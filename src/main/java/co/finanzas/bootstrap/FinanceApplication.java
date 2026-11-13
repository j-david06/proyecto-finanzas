package co.finanzas.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(scanBasePackages = "co.finanzas",
        exclude = UserDetailsServiceAutoConfiguration.class)
public class FinanceApplication {
    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("email-preview")) {
            try {
                if(args.length!=4)throw new IllegalArgumentException("Usage: email-preview FILE SENDER RECEIVED_AT_ISO");
                System.out.println(new co.finanzas.adapters.in.cli.EmailPreview().inspect(java.nio.file.Path.of(args[1]),args[2],java.time.Instant.parse(args[3])));
            } catch(Exception exception) {
                System.err.println("EMAIL_PREVIEW_FAILED: use email-preview FILE SENDER RECEIVED_AT_ISO; no database writes performed");
                System.exit(1);
            }
            return;
        }
        SpringApplication.run(FinanceApplication.class, args);
    }
}
