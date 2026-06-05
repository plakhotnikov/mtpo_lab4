package com.example.idempotency.grpc.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Идемпотентность гарантируется ВНЕШНЕ: GrpcIdempotencyInterceptor
     * (транспорт) + UNIQUE(recipient, message_hash) (домен). Сервис только
     * вставляет; в профиле non-idempotent оба слоя отключены - это
     * демонстрируется тестом grpcDuplicate_whenInterceptorDisabled.
     */
    @Transactional
    public Notification send(String recipient, String message) {
        String hash = sha256(message);
        return repository.save(new Notification(recipient, message, hash));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
