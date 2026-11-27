package com.nilebank.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;

/** Only for Render's edge-terminated HTTPS service, not direct HTTP hosting. */
@Configuration(proxyBeanMethods = false)
@Profile("render")
public class RenderConfig {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> renderHttpsConnector() {
    return factory ->
        factory.addConnectorCustomizers(
            connector -> {
              // Avoid accepting caller-supplied proxy headers as proof of HTTPS.
              connector.setScheme("https");
              connector.setSecure(true);
              connector.setProxyPort(443);
            });
  }
}
