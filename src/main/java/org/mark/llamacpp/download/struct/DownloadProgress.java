package org.mark.llamacpp.download.struct;

import java.net.URI;
import java.nio.file.Path;

public final class DownloadProgress {
	private final DownloadState state;
	private final URI sourceUri;
	private final URI finalUri;
	private final Path targetFile;
	private final long totalBytes;
	private final long downloadedBytes;
	private final int partsTotal;
	private final int partsCompleted;
	private final long startedAtNanos;
	private final long finishedAtNanos;
	private final String errorMessage;

	public DownloadProgress(
			DownloadState state,
			URI sourceUri,
			URI finalUri,
			Path targetFile,
			long totalBytes,
			long downloadedBytes,
			int partsTotal,
			int partsCompleted,
			long startedAtNanos,
			long finishedAtNanos,
			String errorMessage) {
		this.state = state;
		this.sourceUri = sourceUri;
		this.finalUri = finalUri;
		this.targetFile = targetFile;
		this.totalBytes = totalBytes;
		this.downloadedBytes = downloadedBytes;
		this.partsTotal = partsTotal;
		this.partsCompleted = partsCompleted;
		this.startedAtNanos = startedAtNanos;
		this.finishedAtNanos = finishedAtNanos;
		this.errorMessage = errorMessage;
	}

	public DownloadState getState() {
		return state;
	}

	public URI getSourceUri() {
		return sourceUri;
	}

	public URI getFinalUri() {
		return finalUri;
	}

	public Path getTargetFile() {
		return targetFile;
	}

	public long getTotalBytes() {
		return totalBytes;
	}

	public long getDownloadedBytes() {
		return downloadedBytes;
	}

	public int getPartsTotal() {
		return partsTotal;
	}

	public int getPartsCompleted() {
		return partsCompleted;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public double getProgressRatio() {
		if (totalBytes <= 0) {
			return 0.0;
		}
		double r = (double) downloadedBytes / (double) totalBytes;
		if (r < 0.0) {
			return 0.0;
		}
		if (r > 1.0) {
			return 1.0;
		}
		return r;
	}

	public long getElapsedMillis() {
		long start = startedAtNanos;
		if (start <= 0) {
			return 0;
		}
		long end = finishedAtNanos > 0 ? finishedAtNanos : System.nanoTime();
		long diff = end - start;
		if (diff < 0) {
			return 0;
		}
		return diff / 1_000_000L;
	}

	public long getSpeedBytesPerSecond() {
		long elapsedMillis = getElapsedMillis();
		if (elapsedMillis <= 0) {
			return 0;
		}
		return (downloadedBytes * 1000L) / elapsedMillis;
	}
}

