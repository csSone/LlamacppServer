package org.mark.llamacpp.server.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 	执行命令返回结果
 */
public class CommandLineRunner {

	/**
	 * 命令执行结果
	 */
	public static class CommandResult {
		/** 标准输出内容 */
		private final String output;
		/** 错误信息（失败时） */
		private final String error;
		/** 退出码 */
		private final Integer exitCode;

		public CommandResult(String output, String error, Integer exitCode) {
			this.output = output;
			this.error = error;
			this.exitCode = exitCode;
		}

		public String getOutput() {
			return output;
		}

		public String getError() {
			return error;
		}

		public Integer getExitCode() {
			return exitCode;
		}

		@Override
		public String toString() {
			return "CommandResult{" +
					"output='" + output + '\'' +
					", error='" + error + '\'' +
					", exitCode=" + exitCode +
					'}';
		}
	}

	/**
	 * 默认超时时间（秒）
	 */
	private static final int DEFAULT_TIMEOUT_SECONDS = 5;

	/**
	 * 执行命令行命令并获取返回结果（使用默认5秒超时）
	 *
	 * @param command 要执行的命令
	 * @return 执行结果
	 */
	public static CommandResult execute(String command) {
		return execute(command, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * 执行命令行命令并获取返回结果
	 *
	 * @param command 要执行的命令
	 * @param timeoutSeconds 超时时间（秒）
	 * @return 执行结果
	 */
	public static CommandResult execute(String command, int timeoutSeconds) {
		return execute(command.split("\\s+"), timeoutSeconds);
	}

	/**
	 * 执行命令行命令并获取返回结果（使用默认5秒超时）
	 *
	 * @param commandArray 命令数组
	 * @return 执行结果
	 */
	public static CommandResult execute(String[] commandArray) {
		return execute(commandArray, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * 执行命令行命令并获取返回结果（使用虚拟线程协程）
	 *
	 * @param commandArray 命令数组
	 * @param timeoutSeconds 超时时间（秒）
	 * @return 执行结果
	 */
	public static CommandResult execute(String[] commandArray, int timeoutSeconds) {
		// 使用虚拟线程（协程）异步执行命令
		CompletableFuture<CommandResult> future = CompletableFuture.supplyAsync(
				() -> executeInternal(commandArray),
				Executors.newVirtualThreadPerTaskExecutor()
		);

		try {
			// 等待执行完成或超时
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			// 超时取消任务
			future.cancel(true);
			return new CommandResult("", "命令执行超时（" + timeoutSeconds + "秒）", null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new CommandResult("", "命令执行被中断: " + e.getMessage(), null);
		} catch (ExecutionException e) {
			return new CommandResult("", "命令执行异常: " + e.getCause().getMessage(), null);
		}
	}

	/**
	 * 内部执行命令的方法
	 *
	 * @param commandArray 命令数组
	 * @return 执行结果
	 */
	private static CommandResult executeInternal(String[] commandArray) {
		if (commandArray == null || commandArray.length == 0) {
			return new CommandResult("", "命令不能为空", null);
		}

		ProcessBuilder pb = new ProcessBuilder(commandArray);
		pb.redirectErrorStream(false); // 不合并错误流和标准输出流
		applyExecutableDirEnv(pb, commandArray);

		Process process;
		try {
			process = pb.start();
		} catch (IOException e) {
			return new CommandResult("", "启动进程失败: " + e.getMessage(), null);
		}

		// 读取标准输出
		StringBuilder outputBuilder = new StringBuilder();
		Thread outputThread = Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					outputBuilder.append(line).append(System.lineSeparator());
				}
			} catch (IOException e) {
				// 读取输出时发生错误
			}
		});

		// 读取错误输出
		StringBuilder errorBuilder = new StringBuilder();
		Thread errorThread = Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					errorBuilder.append(line).append(System.lineSeparator());
				}
			} catch (IOException e) {
				// 读取错误输出时发生错误
			}
		});

		try {
			// 等待进程结束
			int exitCode = process.waitFor();

			// 等待输出线程完成
			outputThread.join();
			errorThread.join();

			String output = outputBuilder.toString().trim();
			String error = errorBuilder.toString().trim();

			return new CommandResult(output, error, exitCode);

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return new CommandResult("", "命令执行被中断: " + e.getMessage(), null);
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	private static void applyExecutableDirEnv(ProcessBuilder pb, String[] commandArray) {
		if (pb == null || commandArray == null || commandArray.length == 0) {
			return;
		}
		String exe = commandArray[0];
		if (exe == null || exe.isBlank()) {
			return;
		}
		File exeFile = new File(exe);
		File exeDir = exeFile.getParentFile();
		try {
			Path real = exeFile.toPath().toRealPath();
			if (real.getParent() != null) {
				exeDir = real.getParent().toFile();
			}
		} catch (Exception ignored) {
		}
		if (exeDir == null) {
			return;
		}

		pb.directory(exeDir);

		String osName = System.getProperty("os.name");
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		Map<String, String> env = pb.environment();

		if (os.contains("win")) {
			String currentPath = env.get("PATH");
			String dir = exeDir.getAbsolutePath();
			if (currentPath == null || currentPath.isBlank()) {
				env.put("PATH", dir);
			} else if (!currentPath.contains(dir)) {
				env.put("PATH", dir + ";" + currentPath);
			}
			return;
		}

		String currentLdPath = env.get("LD_LIBRARY_PATH");
		String dir = exeDir.getAbsolutePath();
		if (currentLdPath == null || currentLdPath.isBlank()) {
			env.put("LD_LIBRARY_PATH", dir);
		} else if (!currentLdPath.contains(dir)) {
			env.put("LD_LIBRARY_PATH", dir + ":" + currentLdPath);
		}
	}

}
