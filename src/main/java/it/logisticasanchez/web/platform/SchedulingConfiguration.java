package it.logisticasanchez.web.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling for the retention runner and the outbox worker. Each scheduled bean is created
 * only when its own {@code ls.*.enabled} property is true, so the test profile runs nothing in
 * the background and calls the services directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ls.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {}
