package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class LlamaCppProcess {
	
	/**
	 * 	这个进程的名字。是唯一的。
	 */
	private final String name;
	
	/**
	 * 	这个进程的启动命令。参考：/home/mark/llama.cpp-master/llama-server -m path -fa 1 -c 65536
	 */
	private final String cmd;

	/**
	 * 	启动该进程后获得的pid号。
	 */
	private long pid;
	
	/**
	 * 	进程对象
	 */
	private Process process;
	
	/**
	 * 	异步执行线程对象
	 */
	private Thread outputThread;
	
	/**
	 * 	错误输出线程对象
	 */
	private Thread errorThread;
	
	/**
	 * 	进程是否正在运行
	 */
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	
	/**
	 * 	输出处理器
	 */
	private Consumer<String> outputHandler;
	
	/**
	 * 	构造器。
	 * @param name 进程名称
	 * @param cmd 启动命令
	 */
	public LlamaCppProcess(String name, String cmd) {
		this.name = name;
		this.cmd = cmd;
	}
	
	/**
	 * 设置输出处理器
	 * @param outputHandler 输出处理器
	 */
	public void setOutputHandler(Consumer<String> outputHandler) {
		this.outputHandler = outputHandler;
	}
	
	/**
	 * 异步启动进程
	 * @return 是否启动成功
	 */
	public synchronized boolean start() {
		if (isRunning.get()) {
			return false;
		}
		
		try {
			// 使用ProcessBuilder启动进程，可以更好地控制进程
			ProcessBuilder pb = new ProcessBuilder(cmd.split("\\s+"));
			pb.redirectErrorStream(true); // 不合并错误流和标准输出流
			
			// 设置LD_LIBRARY_PATH环境变量，解决共享库加载问题
			// 从命令中提取llama-server的路径，并设置其所在目录为库搜索路径
			String[] cmdParts = cmd.split("\\s+");
			if (cmdParts.length > 0) {
				String serverPath = cmdParts[0];
				// 如果是绝对路径
				if (serverPath.startsWith("/")) {
					int lastSlash = serverPath.lastIndexOf('/');
					if (lastSlash > 0) {
						String libPath = serverPath.substring(0, lastSlash);
						// 获取当前环境变量
						Map<String, String> env = pb.environment();
						String currentLdPath = env.get("LD_LIBRARY_PATH");
						if (currentLdPath != null && !currentLdPath.isEmpty()) {
							env.put("LD_LIBRARY_PATH", libPath + ":" + currentLdPath);
						} else {
							env.put("LD_LIBRARY_PATH", libPath);
						}
					}
				}
			}
			
			this.process = pb.start();
			
			// 获取PID (Java 9+ 提供了getPid方法)
			try {
				this.pid = this.process.pid();
			} catch (Exception e) {
				e.printStackTrace();
				// 如果获取不到PID，使用一个默认值
				this.pid = -1;
			}
			
			this.isRunning.set(true);
			
			// 启动输出读取线程
			this.startOutputReaders();
			
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * 启动输出读取线程
	 */
	private void startOutputReaders() {
		// 标准输出读取线程
		outputThread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					if (outputHandler != null) {
						outputHandler.accept(line);
					}
					System.out.println(line);
				}
			} catch (IOException e) {
				if (isRunning.get() && outputThread != null) {
					outputHandler.accept("读取输出时发生错误: " + e.getMessage());
				}
			}
		});
		outputThread.setDaemon(true);
		outputThread.start();
	}
	
	/**
	 * 停止进程
	 * @return 是否停止成功
	 */
	public synchronized boolean stop() {
		if (!isRunning.get()) {
			return false;
		}
		
		isRunning.set(false);
		
		if (process != null) {
			process.destroy();
			
			try {
				// 等待进程结束
				if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				process.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}
		
		// 等待输出线程结束
		if (outputThread != null) {
			try {
				outputThread.interrupt();
				outputThread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		if (errorThread != null) {
			try {
				errorThread.interrupt();
				errorThread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		return true;
	}
	
	/**
	 * 获取进程名称
	 * @return 进程名称
	 */
	public String getName() {
		return name;
	}
	
	/**
	 * 获取启动命令
	 * @return 启动命令
	 */
	public String getCmd() {
		return cmd;
	}
	
	/**
	 * 获取进程PID
	 * @return 进程PID，如果获取失败返回-1
	 */
	public long getPid() {
		return pid;
	}
	
	/**
	 * 检查进程是否正在运行
	 * @return 是否正在运行
	 */
	public boolean isRunning() {
		return isRunning.get() && process != null && process.isAlive();
	}
	
	/**
	 * 获取进程退出码
	 * @return 进程退出码，如果进程仍在运行返回null
	 */
	public Integer getExitCode() {
		if (process != null && !process.isAlive()) {
			return process.exitValue();
		}
		return null;
	}
}
