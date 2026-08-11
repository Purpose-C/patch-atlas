package io.github.patchatlas.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 用 ProcessBuilder 执行参数列表；不经过 shell。 */
final class ProcessBuilderCommandExecutor implements CommandExecutor {

    private static final Duration PROCESS_STOP_GRACE = Duration.ofSeconds(1);

    @Override
    public CommandExecution execute(List<String> command, Duration timeout, int maxOutputBytes) {
        Instant started = Instant.now();
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException ex) {
            return new CommandExecution(
                    null,
                    Duration.between(started, Instant.now()),
                    false,
                    "",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        TailBuffer tail = new TailBuffer(maxOutputBytes);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> outputReader = executor.submit(() -> drain(process.getInputStream(), tail));
            boolean finished = waitFor(process, timeout);
            if (!finished) {
                stop(process);
            }
            String readFailure = awaitOutput(outputReader);
            Duration elapsed = Duration.between(started, Instant.now());
            if (!finished) {
                return new CommandExecution(null, elapsed, true, tail.text(), readFailure);
            }
            return new CommandExecution(process.exitValue(), elapsed, false, tail.text(), readFailure);
        }
    }

    private boolean waitFor(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void stop(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(PROCESS_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(PROCESS_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void drain(InputStream input, TailBuffer tail) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                tail.append(buffer, read);
            }
        } catch (IOException ex) {
            throw new OutputReadException(ex);
        }
    }

    private String awaitOutput(Future<?> outputReader) {
        try {
            outputReader.get(PROCESS_STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "output capture interrupted";
        } catch (ExecutionException ex) {
            return "output capture failed: " + ex.getCause().getMessage();
        } catch (TimeoutException ex) {
            outputReader.cancel(true);
            return "output capture timed out";
        }
    }

    private static final class OutputReadException extends RuntimeException {

        private OutputReadException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static final class TailBuffer {

        private static final byte[] TRUNCATED = "[truncated]\n".getBytes(StandardCharsets.UTF_8);

        private final byte[] bytes;
        private int size;
        private boolean truncated;

        private TailBuffer(int capacity) {
            this.bytes = new byte[capacity];
        }

        private void append(byte[] source, int length) {
            if (length >= bytes.length) {
                System.arraycopy(source, length - bytes.length, bytes, 0, bytes.length);
                size = bytes.length;
                truncated = true;
                return;
            }
            int overflow = Math.max(0, size + length - bytes.length);
            if (overflow > 0) {
                System.arraycopy(bytes, overflow, bytes, 0, size - overflow);
                size -= overflow;
                truncated = true;
            }
            System.arraycopy(source, 0, bytes, size, length);
            size += length;
        }

        private String text() {
            if (!truncated) {
                return new String(bytes, 0, size, StandardCharsets.UTF_8);
            }
            int markerLength = Math.min(TRUNCATED.length, bytes.length);
            int tailLength = bytes.length - markerLength;
            byte[] summary = new byte[bytes.length];
            System.arraycopy(TRUNCATED, 0, summary, 0, markerLength);
            System.arraycopy(bytes, size - tailLength, summary, markerLength, tailLength);
            return new String(summary, StandardCharsets.UTF_8);
        }
    }
}
