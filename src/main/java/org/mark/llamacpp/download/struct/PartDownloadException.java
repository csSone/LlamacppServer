package org.mark.llamacpp.download.struct;

import java.io.IOException;

public final class PartDownloadException extends IOException {
	private static final long serialVersionUID = 1L;
	private final long bytesWritten;

	public PartDownloadException(IOException cause, long bytesWritten) {
		super(cause.getMessage(), cause);
		this.bytesWritten = bytesWritten;
	}

	public long getBytesWritten() {
		return bytesWritten;
	}
}

