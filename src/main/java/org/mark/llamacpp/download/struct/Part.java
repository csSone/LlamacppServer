package org.mark.llamacpp.download.struct;

public final class Part {
	private final long startInclusive;
	private final long endInclusive;

	public Part(long startInclusive, long endInclusive) {
		if (startInclusive < 0 || endInclusive < startInclusive) {
			throw new IllegalArgumentException("invalid range: " + startInclusive + "-" + endInclusive);
		}
		this.startInclusive = startInclusive;
		this.endInclusive = endInclusive;
	}

	public long getStartInclusive() {
		return this.startInclusive;
	}

	public long getEndInclusive() {
		return this.endInclusive;
	}

	public long length() {
		return this.endInclusive - this.startInclusive + 1;
	}
}

