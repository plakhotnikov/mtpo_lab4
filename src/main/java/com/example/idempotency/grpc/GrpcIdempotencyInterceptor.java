package com.example.idempotency.grpc;

import com.example.idempotency.config.IdempotencyProperties;
import com.example.idempotency.idempotency.IdempotencyKeyEntity;
import com.example.idempotency.idempotency.IdempotencyService;
import com.example.idempotency.idempotency.ResponseKind;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * gRPC-перехватчик идемпотентности - переиспользует тот же IdempotencyService,
 * что и HTTP-фильтр (REST/SOAP/GraphQL). Транспорт у gRPC принципиально иной
 * (HTTP/2 + бинарный protobuf), поэтому servlet-фильтр здесь неприменим, но
 * семантика «зарегистрируй ключ -> выполни -> закешируй ответ -> реплей» та же.
 *
 * Алгоритм:
 *   1. Если идемпотентность выключена (профиль non-idempotent) - bypass.
 *   2. Если в Metadata нет idempotency-key - INVALID_ARGUMENT.
 *   3. Если ключ уже в БД и есть закешированный байтовый ответ -
 *      десериализовать через MethodDescriptor.responseMarshaller, отправить
 *      клиенту через sendHeaders+sendMessage+close(OK), вернуть no-op
 *      Listener (handler не вызывается).
 *   4. Иначе зарегистрировать ключ (DB UNIQUE -> ALREADY_EXISTS на гонке),
 *      обернуть call в ForwardingServerCall: перехватить sendMessage(RespT),
 *      сериализовать через тот же marshaller, сохранить байты в idempotency_keys.
 *
 * Перехватчик полностью generic - он не знает proto-типы и работает с любым
 * unary-методом любого gRPC-сервиса в этом приложении.
 */
@GrpcGlobalServerInterceptor
public class GrpcIdempotencyInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcIdempotencyInterceptor.class);
    private static final Metadata.Key<String> IDEMPOTENCY_KEY =
            Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final String CONTENT_TYPE = "application/grpc+proto";

    private final IdempotencyService idempotencyService;
    private final IdempotencyProperties properties;

    public GrpcIdempotencyInterceptor(IdempotencyService idempotencyService, IdempotencyProperties properties) {
        this.idempotencyService = idempotencyService;
        this.properties = properties;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        if (!properties.isEnabled()) {
            return next.startCall(call, headers);
        }

        String key = headers.get(IDEMPOTENCY_KEY);
        if (key == null || key.isBlank()) {
            call.close(Status.INVALID_ARGUMENT.withDescription(
                    "idempotency-key metadata is required"), new Metadata());
            return noopListener();
        }

        var existing = idempotencyService.findByKey(key);
        if (existing.isPresent()) {
            IdempotencyKeyEntity cached = existing.get();
            if (cached.getResponseStatus() != null && cached.getResponseBodyBytes() != null) {
                log.info("gRPC: повторный вызов с ключом {} - реплей кешированного ответа", key);
                try {
                    RespT message = call.getMethodDescriptor()
                            .getResponseMarshaller()
                            .parse(new ByteArrayInputStream(cached.getResponseBodyBytes()));
                    call.sendHeaders(new Metadata());
                    call.sendMessage(message);
                    call.close(Status.OK, new Metadata());
                } catch (Exception e) {
                    call.close(Status.INTERNAL.withDescription("replay failed: " + e.getMessage()),
                            new Metadata());
                }
                return noopListener();
            }
            call.close(Status.ABORTED.withDescription(
                    "Request with this key is already being processed"), new Metadata());
            return noopListener();
        }

        try {
            idempotencyService.registerKey(key, "GRPC", call.getMethodDescriptor().getFullMethodName());
        } catch (DataIntegrityViolationException e) {
            call.close(Status.ALREADY_EXISTS.withDescription(
                    "Concurrent request registered the same key"), new Metadata());
            return noopListener();
        }

        var wrapped = new CachingServerCall<>(call, key);
        return next.startCall(wrapped, headers);
    }

    private static <ReqT> ServerCall.Listener<ReqT> noopListener() {
        return new ServerCall.Listener<>() {};
    }

    private final class CachingServerCall<ReqT, RespT>
            extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

        private final String key;

        CachingServerCall(ServerCall<ReqT, RespT> delegate, String key) {
            super(delegate);
            this.key = key;
        }

        @Override
        public void sendMessage(RespT message) {
            try {
                InputStream stream = getMethodDescriptor().getResponseMarshaller().stream(message);
                byte[] bytes = stream.readAllBytes();
                idempotencyService.saveBinaryResponse(key, Status.OK.getCode().value(), CONTENT_TYPE, bytes);
            } catch (IOException e) {
                log.warn("gRPC: не удалось сохранить ответ для ключа {}: {}", key, e.toString());
            }
            super.sendMessage(message);
        }
    }
}
