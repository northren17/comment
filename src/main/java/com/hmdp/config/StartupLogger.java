package com.hmdp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger {
    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Backend started successfully.");
    }
}
