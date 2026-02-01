package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;



/**
 * 	llamacpp进程
 */
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
	 * 	程序启动后的输入流
	 */
	private BufferedWriter stdwriter;
	
	/**
	 * 	运行时设置的上下文。
	 */
	private int ctxSize;
	
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
	 * 	在模型加载成功后调用。
	 * @param ctxSize
	 */
	public void setCtxSize(int ctxSize) {
		this.ctxSize = ctxSize;
	}
	
	/**
	 * 	获取模型加载后实际的上下文长度。
	 * @return
	 */
	public int getCtxSize() {
		return this.ctxSize;
	}
	
	/**
	 * 	写入输入内容
	 * @param cmd
	 */
	public synchronized void send(String cmd) {
		try {
			if(this.stdwriter != null) {
				this.stdwriter.write(cmd);
			}
		}catch (Exception e) {
			e.printStackTrace();
		}
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
			List<String> args = splitCommandLineArgs(cmd);
			ProcessBuilder pb = new ProcessBuilder(args);
			pb.redirectErrorStream(true); // 不合并错误流和标准输出流
			
			// 设置LD_LIBRARY_PATH环境变量，解决共享库加载问题
			// 从命令中提取llama-server的路径，并设置其所在目录为库搜索路径
			if (!args.isEmpty()) {
				String serverPath = args.get(0);
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
			// 正经启动
			this.process = pb.start();
			
			// 获取PID (Java 9+ 提供了getPid方法)
			// 获取输入流
			try {
				this.pid = this.process.pid();
				this.stdwriter = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
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

	private static List<String> splitCommandLineArgs(String commandLine) {
		List<String> out = new ArrayList<>();
		if (commandLine == null) {
			return out;
		}
		String s = commandLine.trim();
		if (s.isEmpty()) {
			return out;
		}

		StringBuilder cur = new StringBuilder();
		boolean inSingle = false;
		boolean inDouble = false;

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (inDouble && c == '\\') {
				if (i + 1 < s.length()) {
					char n = s.charAt(i + 1);
					if (n == '"') {
						cur.append(n);
						i++;
						continue;
					}
				}
				cur.append(c);
				continue;
			}
			if (inSingle && c == '\\') {
				if (i + 1 < s.length()) {
					char n = s.charAt(i + 1);
					if (n == '\'') {
						cur.append(n);
						i++;
						continue;
					}
				}
				cur.append(c);
				continue;
			}

			if (c == '"' && !inSingle) {
				inDouble = !inDouble;
				continue;
			}
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
				continue;
			}

			if (!inSingle && !inDouble && Character.isWhitespace(c)) {
				if (cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
				continue;
			}

			cur.append(c);
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		return out;
	}
	
	/**
	 * 启动输出读取线程
	 */
	private void startOutputReaders() {
		// 标准输出读取线程
		this.outputThread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					// 将输出的内容转给处理器
					if (this.outputHandler != null) {
						this.outputHandler.accept(line);
					}
					System.out.println(line);
				}
			} catch (IOException e) {
				if (this.isRunning.get() && this.outputThread != null) {
					this.outputHandler.accept("读取输出时发生错误: " + e.getMessage());
				}
			}
		});
		this.outputThread.setDaemon(true);
		this.outputThread.start();
	}
	
	
	
	/**
	 * 停止进程
	 * @return 是否停止成功
	 */
	public synchronized boolean stop() {
		if (!this.isRunning.get()) {
			return false;
		}
		
		this.isRunning.set(false);
		
		if (this.process != null) {
			this.process.destroy();
			
			try {
				// 等待进程结束
				if (!this.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				this.process.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}
		
		// 等待输出线程结束
		if (this.outputThread != null) {
			try {
				this.outputThread.interrupt();
				this.outputThread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		if (this.errorThread != null) {
			try {
				this.errorThread.interrupt();
				this.errorThread.join(1000);
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
		return this.name;
	}
	
	/**
	 * 获取启动命令
	 * @return 启动命令
	 */
	public String getCmd() {
		return this.cmd;
	}
	
	/**
	 * 获取进程PID
	 * @return 进程PID，如果获取失败返回-1
	 */
	public long getPid() {
		return this.pid;
	}
	
	/**
	 * 检查进程是否正在运行
	 * @return 是否正在运行
	 */
	public boolean isRunning() {
		return this.isRunning.get() && this.process != null && this.process.isAlive();
	}
	
	/**
	 * 获取进程退出码
	 * @return 进程退出码，如果进程仍在运行返回null
	 */
	public Integer getExitCode() {
		if (this.process != null && !this.process.isAlive()) {
			return this.process.exitValue();
		}
		return null;
	}
}
