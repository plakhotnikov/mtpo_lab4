package com.example.idempotency.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки механизма идемпотентности.
 * Управляются через application.yml / профили Spring.
 */
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties {

    /** Включён ли фильтр идемпотентности */
    private boolean enabled = true;

    /** Время жизни ключа идемпотентности в часах */
    private int keyTtlHours = 24;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getKeyTtlHours() { return keyTtlHours; }
    public void setKeyTtlHours(int keyTtlHours) { this.keyTtlHours = keyTtlHours; }
}
