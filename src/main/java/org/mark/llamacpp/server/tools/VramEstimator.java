package org.mark.llamacpp.server.tools;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.gguf.GGUFMetaData;

/**
 * 粗略预估模型所需的显存。
 */
public final class VramEstimator {

	private VramEstimator() {

	}

	public static final class Result {
		private final long weightsBytes;
		private final long kvBytes;
		private final long computeBytes;
		private final long totalBytes;

		public Result(long weightsBytes, long kvBytes, long computeBytes) {
			this.weightsBytes = weightsBytes;
			this.kvBytes = kvBytes;
			this.computeBytes = computeBytes;
			this.totalBytes = weightsBytes + kvBytes + computeBytes;
		}

		public long getWeightsBytes() {
			return weightsBytes;
		}

		public long getKvBytes() {
			return kvBytes;
		}

		public long getComputeBytes() {
			return computeBytes;
		}

		public long getTotalBytes() {
			return totalBytes;
		}

		public double getWeightsMiB() {
			return bytesToMiB(weightsBytes);
		}

		public double getKvMiB() {
			return bytesToMiB(kvBytes);
		}

		public double getComputeMiB() {
			return bytesToMiB(computeBytes);
		}

		public double getTotalMiB() {
			return bytesToMiB(totalBytes);
		}
	}

	@Deprecated
	public static Result estimate(GGUFModel model, int ctxSize) {
		Integer defaultUb = Math.min(1024, Math.max(1, ctxSize));
		return estimate(model, ctxSize, defaultUb, false);
	}
	
	/**
	 * 	计算需要的显存。
	 * @param model
	 * @param ctxSize
	 * @param ubatchSize
	 * @param flashAttention
	 * @return
	 */
	public static Result estimate(GGUFModel model, int ctxSize, Integer ubatchSize, boolean flashAttention) {
		long weights = model != null ? model.getSize() : 0L;
		GGUFMetaData md = model != null ? model.getPrimaryModel() : null;
		if (md == null) {
			return new Result(weights, 0L, 0L);
		}
		Integer emb = md.getEmbeddingLength();
		Integer nLayer = md.getNLayer();
		Integer nHead = md.getNHead();
		Integer nKvHead = md.getNKvHead();
		if (emb == null) {
			String arch = md.getStringValue("general.architecture");
			emb = arch != null ? md.getIntValue(arch + ".embedding_length") : null;
		}
		if (nLayer == null) {
			String arch = md.getStringValue("general.architecture");
			if (arch != null) {
				Integer v1 = md.getIntValue(arch + ".n_layer");
				Integer v2 = md.getIntValue(arch + ".block_count");
				nLayer = v1 != null ? v1 : v2;
			}
		}
		if (nHead == null) {
			String arch = md.getStringValue("general.architecture");
			nHead = arch != null ? md.getIntValue(arch + ".n_head") : null;
		}
		if (nKvHead == null) {
			String arch = md.getStringValue("general.architecture");
			Integer v1 = arch != null ? md.getIntValue(arch + ".n_kv_head") : null;
			Integer v2 = arch != null ? md.getIntValue(arch + ".n_head_kv") : null;
			nKvHead = v1 != null ? v1 : v2;
		}
		if (emb == null || nLayer == null) {
			return new Result(weights, 0L, 0L);
		}
		if (nKvHead == null && nHead != null) {
			nKvHead = nHead;
		}
		int dkv = emb;
		if (nHead != null && nKvHead != null && nHead > 0) {
			dkv = emb * nKvHead / nHead;
		}
		long bytesPerTokenPerLayer = (long) dkv * 4L;
		long kv = (long) nLayer * (long) ctxSize * bytesPerTokenPerLayer;
		int ub = ubatchSize != null ? Math.max(1, ubatchSize) : Math.min(1024, Math.max(1, ctxSize));
		double bytesPerEl = 2.0;
		// double factor = flashAttention ? 3.0 : 5.34;
		double factor = 5.34;
		long compute = (long) Math.round(nLayer * (long) ub * emb * bytesPerEl * factor);
		return new Result(weights, kv, compute);
	}
	
	/**
	 * 	显示为MiB
	 * @param bytes
	 * @return
	 */
	private static double bytesToMiB(long bytes) {
		return bytes / 1024.0 / 1024.0;
	}
}
