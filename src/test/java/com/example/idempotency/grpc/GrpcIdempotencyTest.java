package com.example.idempotency.grpc;

import com.example.idempotency.BaseIntegrationTest;
import com.example.idempotency.grpc.notification.NotificationRepository;
import com.example.idempotency.grpc.notification.proto.NotificationServiceGrpc;
import com.example.idempotency.grpc.notification.proto.SendRequest;
import com.example.idempotency.grpc.notification.proto.SendResponse;
import com.example.idempotency.idempotency.IdempotencyKeyRepository;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@DisplayName("Идемпотентность gRPC (ServerInterceptor + общий IdempotencyService)")
class GrpcIdempotencyTest extends BaseIntegrationTest {

    private static final String IN_PROCESS_NAME = "idempotency-test";

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    IdempotencyKeyRepository idempotencyKeyRepository;

    private ManagedChannel channel;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        notificationRepository.deleteAll();
        channel = InProcessChannelBuilder.forName(IN_PROCESS_NAME).usePlaintext().build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Send с одним idempotency-key дважды - одна запись и тот же id ответа")
    void sameKey_returnsCachedResponse() {
        String key = UUID.randomUUID().toString();
        SendRequest request = SendRequest.newBuilder()
                .setRecipient("user-1")
                .setMessage("Hello, world!")
                .build();

        SendResponse first = stubWithKey(key).send(request);
        SendResponse second = stubWithKey(key).send(request);

        Assertions.assertEquals(first.getId(), second.getId(),
                "Реплей gRPC-ответа должен вернуть тот же id");
        Assertions.assertEquals(first.getStatus(), second.getStatus());
        Assertions.assertEquals(1, notificationRepository.count(),
                "Повторный gRPC-вызов с тем же ключом не должен создавать дубликат");
    }

    @Test
    @DisplayName("Send с разными idempotency-key - две записи")
    void differentKeys_createTwoNotifications() {
        stubWithKey(UUID.randomUUID().toString()).send(SendRequest.newBuilder()
                .setRecipient("a").setMessage("m1").build());
        stubWithKey(UUID.randomUUID().toString()).send(SendRequest.newBuilder()
                .setRecipient("b").setMessage("m2").build());

        Assertions.assertEquals(2, notificationRepository.count());
    }

    @Test
    @DisplayName("Send без idempotency-key - Status.INVALID_ARGUMENT")
    void missingKey_returnsInvalidArgument() {
        NotificationServiceGrpc.NotificationServiceBlockingStub bare =
                NotificationServiceGrpc.newBlockingStub(channel);

        StatusRuntimeException ex = Assertions.assertThrows(StatusRuntimeException.class,
                () -> bare.send(SendRequest.newBuilder()
                        .setRecipient("x").setMessage("y").build()));
        Assertions.assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
        Assertions.assertEquals(0, notificationRepository.count());
    }

    @Test
    @DisplayName("Реплей восстанавливает байт-в-байт сериализованный protobuf")
    void replayedResponseBytesAreIdentical() {
        String key = UUID.randomUUID().toString();
        SendRequest request = SendRequest.newBuilder()
                .setRecipient("user-2").setMessage("Stable").build();

        byte[] firstBytes = stubWithKey(key).send(request).toByteArray();
        byte[] secondBytes = stubWithKey(key).send(request).toByteArray();

        Assertions.assertArrayEquals(firstBytes, secondBytes);
    }

    private NotificationServiceGrpc.NotificationServiceBlockingStub stubWithKey(String key) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER), key);
        return NotificationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
