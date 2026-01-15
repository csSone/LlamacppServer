package org.mark.llamacpp.download.struct;

import java.nio.file.Path;
import java.util.Objects;

public final class PartWithFile {
	private final Part part;
	private final Path file;

	public PartWithFile(Part part, Path file) {
		this.part = Objects.requireNonNull(part, "part");
		this.file = Objects.requireNonNull(file, "file");
	}

	public Part getPart() {
		return part;
	}

	public Path getFile() {
		return file;
	}
}

