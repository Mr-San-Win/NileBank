package com.nilebank.finance;

import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class FinanceSchedulingConfig {
  @Bean
  public Clock financeClock() {
    return Clock.system(ZoneId.of("Asia/Bangkok"));
  }
}
