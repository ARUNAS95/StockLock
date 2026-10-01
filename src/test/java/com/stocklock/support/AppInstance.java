package com.stocklock.support;

import com.stocklock.StockLockApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A complete, independent StockLock application (own web server, own connection pool, own beans,
 * own scheduler) sharing only the PostgreSQL/Redis/Kafka containers with its siblings, which is
 * exactly what separate processes share. No JVM-level object is shared through application code.
 */
public final class AppInstance implements AutoCloseable {

    private final String name;
    private final ConfigurableApplicationContext context;
    private final StockLockClient api;

    private AppInstance(String name, ConfigurableApplicationContext context) {
        this.name = name;
        this.context = context;
        this.api = new StockLockClient(((ServletWebServerApplicationContext) context).getWebServer().getPort());
    }

    public static AppInstance start(String name, Map<String, Object> overrides) {
        Map<String, Object> properties = TestContainers.connectionProperties();
        properties.put("server.port", 0);
        properties.put("management.metrics.tags.instance", name);
        properties.put("spring.kafka.listener.auto-startup", false);
        properties.putAll(overrides);
        List<String> args = new ArrayList<>();
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(StockLockApplication.class)
                .profiles("it")
                .run(args.toArray(String[]::new));
        return new AppInstance(name, context);
    }

    public String name() {
        return name;
    }

    public StockLockClient api() {
        return api;
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public int port() {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @Override
    public void close() {
        context.close();
    }
}
