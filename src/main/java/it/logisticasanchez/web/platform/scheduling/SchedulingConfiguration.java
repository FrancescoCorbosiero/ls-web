package it.logisticasanchez.web.platform.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduler for the background work of the app: the retention runner
 * ({@code platform}) and the outbox worker ({@code notifications}). Each registers its own
 * task through a {@link org.springframework.scheduling.annotation.SchedulingConfigurer} bean
 * fed by its typed settings, and only when its {@code enabled} setting is true, so the test
 * profile runs nothing in the background and calls the services directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {}
