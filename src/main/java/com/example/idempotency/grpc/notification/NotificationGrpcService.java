package com.example.idempotency.grpc.notification;

import com.example.idempotency.grpc.notification.proto.ListRequest;
import com.example.idempotency.grpc.notification.proto.ListResponse;
import com.example.idempotency.grpc.notification.proto.NotificationItem;
import com.example.idempotency.grpc.notification.proto.NotificationServiceGrpc;
import com.example.idempotency.grpc.notification.proto.SendRequest;
import com.example.idempotency.grpc.notification.proto.SendResponse;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {

    private final NotificationService service;
    private final NotificationRepository repository;

    public NotificationGrpcService(NotificationService service, NotificationRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public void send(SendRequest request, StreamObserver<SendResponse> observer) {
        Notification n = service.send(request.getRecipient(), request.getMessage());
        observer.onNext(SendResponse.newBuilder()
                .setId(n.getId().toString())
                .setStatus(n.getStatus())
                .build());
        observer.onCompleted();
    }

    @Override
    public void list(ListRequest request, StreamObserver<ListResponse> observer) {
        ListResponse.Builder builder = ListResponse.newBuilder();
        repository.findAll().forEach(n -> builder.addItems(NotificationItem.newBuilder()
                .setId(n.getId().toString())
                .setRecipient(n.getRecipient())
                .setMessage(n.getMessage())
                .setStatus(n.getStatus())
                .build()));
        observer.onNext(builder.build());
        observer.onCompleted();
    }
}
