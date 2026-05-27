package com.example.idempotency.soap.shipment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShipmentService {

    private final ShipmentRepository repository;

    public ShipmentService(ShipmentRepository repository) {
        this.repository = repository;
    }

    /**
     * Создание отправления. Идемпотентность гарантируется ДВУМЯ внешними
     * механизмами: на транспортном уровне - IdempotencyFilter (HTTP), на
     * domain-уровне - UNIQUE(tracking_number). Сервис намеренно не делает
     * application-level дедупликацию, чтобы профиль non-idempotent (фильтр
     * выключен + constraint снят) демонстрировал реальный баг с дубликатами.
     */
    @Transactional
    public Shipment create(String trackingNumber, String recipient) {
        return repository.save(new Shipment(trackingNumber, recipient));
    }

    public Shipment getByTrackingNumber(String trackingNumber) {
        return repository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new IllegalArgumentException("Shipment not found: " + trackingNumber));
    }
}
