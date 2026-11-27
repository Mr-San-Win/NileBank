package com.nilebank.auth.recovery;

import java.util.Properties;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
public class RecoveryMailDelivery {
  private final RecoverySettings settings;
  private final JavaMailSenderImpl sender;

  public RecoveryMailDelivery(RecoverySettings settings) {
    this.settings = settings;
    sender = new JavaMailSenderImpl();
    if (!settings.enabled && !settings.verificationEnabled) return;
    sender.setHost(settings.host);
    sender.setPort(settings.port);
    if (!settings.username.isBlank()) {
      sender.setUsername(settings.username);
      sender.setPassword(settings.password);
    }
    Properties props = sender.getJavaMailProperties();
    props.setProperty("mail.smtp.auth", String.valueOf(!settings.username.isBlank()));
    props.setProperty("mail.smtp.starttls.enable", String.valueOf(settings.tls));
    props.setProperty("mail.smtp.starttls.required", String.valueOf(settings.tls));
    props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    props.setProperty("mail.smtp.connectiontimeout", "5000");
    props.setProperty("mail.smtp.timeout", "5000");
    props.setProperty("mail.smtp.writetimeout", "5000");
    props.setProperty("mail.debug", "false");
  }

  public void send(String address, String token) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(settings.from);
    message.setTo(address);
    message.setSubject("NileBank demo password reset");
    message.setText(
        "A password reset was requested for your NileBank simulator profile. This link expires in"
            + " 15 minutes and can be used once.\n\n"
            + settings.baseUrl
            + "/#reset="
            + token
            + "\n\n"
            + "If you did not request this, ignore this email. No bank funds or account details are"
            + " included.");
    sender.send(message);
  }

  public void verifyEmail(String address, String token) {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(settings.from);
    message.setTo(address);
    message.setSubject("Confirm your NileBank profile email");
    message.setText(
        "Confirm this email for your NileBank simulator profile. The link expires in 24 hours and"
            + " works once.\n\n"
            + settings.baseUrl
            + "/#verify="
            + token
            + "\n\n"
            + "This does not sign you in, link a Google identity, reset a password or transfer"
            + " money. Ignore this if you did not request it.");
    sender.send(message);
  }
}
