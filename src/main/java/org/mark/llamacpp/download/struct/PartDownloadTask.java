package org.mark.llamacpp.download.struct;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PartDownloadTask implements Callable<Void> {
	private final HttpClient httpClient;
	private final URI uri;
	private final String userAgent;
	private final Duration timeout;
	private final Part part;
	private final Path partFile;
	private final int maxRetries;
	private final AtomicLong downloadedBytes;
	private final AtomicInteger partsCompleted;
	private final AtomicBoolean stopRequested;
	private final Set<AutoCloseable> activeResources;
	private final long expectedBytes;
	private long existingBytesAtAttemptStart;

	public PartDownloadTask(
			HttpClient httpClient,
			URI uri,
			String userAgent,
			Duration timeout,
			Part part,
			Path partFile,
			int maxRetries,
			AtomicLong downloadedBytes,
			AtomicInteger partsCompleted,
			AtomicBoolean stopRequested,
			Set<AutoCloseable> activeResources) {
		this.httpClient = httpClient;
		this.uri = uri;
		this.userAgent = userAgent;
		this.timeout = timeout;
		this.part = part;
		this.partFile = partFile;
		this.maxRetries = maxRetries;
		this.downloadedBytes = downloadedBytes;
		this.partsCompleted = partsCompleted;
		this.stopRequested = stopRequested;
		this.activeResources = activeResources;
		this.expectedBytes = part.length();
	}

	@Override
	public Void call() throws Exception {
		ensureParentDirectory(this.partFile);

		long backoffMillis = 200;
		int attempt = 0;
		while (true) {
			this.checkStop();
			attempt++;
			long bytesThisAttempt = 0;
			try {
				bytesThisAttempt = this.downloadOnce();
				this.partsCompleted.incrementAndGet();
				return null;
			} catch (InterruptedException e) {
				throw e;
			} catch (IOException e) {
				if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
					throw new InterruptedException("下载已暂停");
				}
				long rollbackBytes = bytesThisAttempt;
				if (e instanceof PartDownloadException pde) {
					rollbackBytes = pde.getBytesWritten();
				}
				if (rollbackBytes > 0) {
					this.downloadedBytes.addAndGet(-rollbackBytes);
				}
				if (this.existingBytesAtAttemptStart > 0) {
					this.downloadedBytes.addAndGet(-this.existingBytesAtAttemptStart);
				}
				Files.deleteIfExists(this.partFile);
				this.existingBytesAtAttemptStart = 0;
				if (attempt > this.maxRetries) {
					throw e;
				}
				Thread.sleep(backoffMillis);
				backoffMillis = Math.min(backoffMillis * 2, 5_000);
			}
		}
	}

	private long downloadOnce() throws IOException, InterruptedException {
		this.checkStop();

		long existing = 0;
		if (Files.exists(this.partFile)) {
			existing = Files.size(this.partFile);
			if (existing >= this.expectedBytes) {
				return 0;
			}
		}
		this.existingBytesAtAttemptStart = existing;

		long startInclusive = this.part.getStartInclusive() + existing;
		if (startInclusive > this.part.getEndInclusive()) {
			return 0;
		}

		HttpRequest request = HttpRequest.newBuilder()
				.uri(this.uri)
				.timeout(this.timeout)
				.header("User-Agent", this.userAgent)
				.header("Range", "bytes=" + startInclusive + "-" + this.part.getEndInclusive())
				.GET()
				.build();

		HttpResponse<InputStream> response = this.httpClient.send(request, BodyHandlers.ofInputStream());
		if (response.statusCode() != 206) {
			throw new IOException("分片下载失败，HTTP状态码: " + response.statusCode());
		}

		long bytesWritten = 0;
		try (InputStream in = new BufferedInputStream(response.body());
				OutputStream out = new BufferedOutputStream(new FileOutputStream(this.partFile.toString(), existing > 0))) {
			this.activeResources.add(in);
			this.activeResources.add(out);
			byte[] buffer = new byte[1024 * 256];
			int read;
			try {
				while ((read = in.read(buffer)) != -1) {
					this.checkStop();
					out.write(buffer, 0, read);
					bytesWritten += read;
					this.downloadedBytes.addAndGet(read);
				}
			} catch (IOException e) {
				if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
					throw new InterruptedException("下载已暂停");
				}
				throw e;
			} finally {
				this.activeResources.remove(in);
				this.activeResources.remove(out);
			}
		} catch (IOException e) {
			throw new PartDownloadException(e, bytesWritten);
		}

		long actual = Files.size(this.partFile);
		if (actual != this.expectedBytes) {
			throw new IOException("分片大小不匹配，期望: " + this.expectedBytes + " 实际: " + actual);
		}

		long expectedWritten = this.expectedBytes - existing;
		if (bytesWritten != expectedWritten) {
			throw new IOException("分片下载字节数不匹配，期望: " + expectedWritten + " 实际: " + bytesWritten);
		}

		return bytesWritten;
	}

	private void checkStop() throws InterruptedException {
		if (this.stopRequested.get() || Thread.currentThread().isInterrupted()) {
			throw new InterruptedException("下载已暂停");
		}
	}

	private static void ensureParentDirectory(Path targetFile) throws IOException {
		Path parent = targetFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}
}

