package com.nilebank.finance;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "nilebank.finance",
    name = "scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FinanceScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(FinanceScheduler.class);
  private final FinanceRecurrenceService service;

  public FinanceScheduler(FinanceRecurrenceService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${nilebank.finance.scheduler-delay-ms:60000}")
  public void generateDue() {
    try {
      service.materializeAll();
    } catch (RuntimeException failure) {
      LOG.warn("recurrence_generation_failed type={}", failure.getClass().getSimpleName());
    }
  }
}
