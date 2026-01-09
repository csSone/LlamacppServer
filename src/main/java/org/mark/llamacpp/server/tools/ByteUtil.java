package org.mark.llamacpp.server.tools;

/**
 * 操作byte的辅助工具类。
 */
public class ByteUtil {

	/**
	 * 将byte数字转换为二进制（以char形式呈现）
	 * 
	 * @param b
	 * @return
	 */
	public static char[] byteToBinaryArray(byte b) {
		char[] binary = new char[8];
		for (int i = 0; i < 8; i++) {
			// 从最高位开始，逐位检查
			binary[i] = ((b & (1 << (7 - i))) != 0) ? '1' : '0';
		}
		return binary;
	}
	
	/**
	 * 	将二进制数组（char表示）转换为byte数字
	 * @param binary
	 * @return
	 */
	public static byte binaryArrayToByte(char[] binary) {
		if (binary == null || binary.length != 8) {
			throw new IllegalArgumentException("Binary array must be exactly 8 elements long");
		}

		byte result = 0;
		for (int i = 0; i < 8; i++) {
			if (binary[i] == '1') {
				result |= (1 << (7 - i)); // 设置第 (7-i) 位
			} else if (binary[i] != '0') {
				throw new IllegalArgumentException("Binary array must contain only '0' or '1', found: " + binary[i]);
			}
			// 如果是 '0'，则无需操作（默认为0）
		}
		return result;
	}
}
