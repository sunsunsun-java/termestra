package dev.termestra.platform.cli.team;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects an HTTP response body without ever retaining more than the configured byte limit. */
final class BoundedUtf8BodyHandler implements HttpResponse.BodyHandler<String> {
    private final int maxBytes;

    BoundedUtf8BodyHandler(int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo responseInfo) {
        boolean announcedTooLarge = responseInfo.headers().firstValueAsLong("content-length")
                .stream().anyMatch(length -> length > maxBytes);
        return new Subscriber(maxBytes, announcedTooLarge);
    }

    static boolean limitExceeded(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ResponseTooLarge) return true;
            current = current.getCause();
        }
        return false;
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final int maxBytes;
        private final ByteArrayOutputStream body;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final boolean announcedTooLarge;
        private Flow.Subscription subscription;
        private int received;
        private boolean complete;

        private Subscriber(int maxBytes, boolean announcedTooLarge) {
            this.maxBytes = maxBytes;
            this.announcedTooLarge = announcedTooLarge;
            this.body = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            if (announcedTooLarge) {
                fail(new ResponseTooLarge(maxBytes));
                return;
            }
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (complete) return;
            for (ByteBuffer buffer : buffers) {
                int count = buffer.remaining();
                if (count > maxBytes - received) {
                    fail(new ResponseTooLarge(maxBytes));
                    return;
                }
                byte[] bytes = new byte[count];
                buffer.get(bytes);
                body.writeBytes(bytes);
                received += count;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            if (complete) return;
            complete = true;
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            if (complete) return;
            complete = true;
            result.complete(body.toString(StandardCharsets.UTF_8));
        }

        private void fail(IOException failure) {
            complete = true;
            subscription.cancel();
            result.completeExceptionally(failure);
        }
    }

    private static final class ResponseTooLarge extends IOException {
        private ResponseTooLarge(int maxBytes) {
            super("Termestra runtime response exceeds " + maxBytes + " bytes");
        }
    }
}
