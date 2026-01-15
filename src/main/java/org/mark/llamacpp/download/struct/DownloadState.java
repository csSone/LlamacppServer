package org.mark.llamacpp.download.struct;

public enum DownloadState {
	IDLE,
	PREPARING,
	DOWNLOADING,
	MERGING,
	VERIFYING,
	COMPLETED,
	FAILED
}

