/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package github.luckygc.cap;

import org.apache.commons.lang3.StringUtils;

/**
 * <a href="https://capjs.js.org">capjs</a> 服务端算法实现 与JavaScript版本完全一致的PRNG实现
 */
public final class RandomUtil {

    private RandomUtil() {
    }

    /**
     * 从字符串种子生成指定长度的确定性十六进制字符串
     *
     * @param seed   初始种子值
     * @param length 输出十六进制字符串长度
     * @return 从种子生成的确定性十六进制字符串
     */
    public static String prng(String seed, int length) {
        if (StringUtils.isBlank(seed) || length <= 0) {
            throw new IllegalArgumentException("种子不能为空且长度必须大于0");
        }

        int state = fnv1a(seed);
        StringBuilder result = new StringBuilder(length);

        while (result.length() < length) {
            int rnd = next(state);
            state = rnd;
            // 使用与JavaScript完全一致的十六进制转换
            result.append(toHexString(rnd));
        }

        return result.substring(0, length);
    }

    /**
     * FNV-1a哈希算法 - 与JavaScript版本完全一致 利用Java int的32位环绕特性，等价于JavaScript的ToInt32
     *
     * @param str 输入字符串
     * @return 32位哈希值
     */
    private static int fnv1a(String str) {
        int hash = 0x811c9dc5; // 2166136261
        for (int i = 0; i < str.length(); i++) {
            hash ^= str.charAt(i);
            // 乘以FNV倍数16777619，等价于移位相加
            // Java int的32位环绕特性确保与JavaScript完全一致
            hash += (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24);
        }
        return hash; // 32位环绕自动处理，无需额外操作
    }

    /**
     * Xorshift32算法 - 与JavaScript版本完全一致
     *
     * @param state 当前状态
     * @return 下一个32位随机数
     */
    private static int next(int state) {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state;
    }

    /**
     * 与JavaScript完全一致的十六进制转换 模拟JavaScript的 toString(16).padStart(8, "0")
     *
     * @param value 32位整数
     * @return 8位十六进制字符串
     */
    private static String toHexString(int value) {
        // 将int转换为无符号32位值，然后转十六进制
        String hex = Integer.toHexString(value);
        // 补齐8位，与JavaScript的padStart(8, "0")一致
        if (hex.length() < 8) {
            return StringUtils.leftPad(hex, 8, '0');
        }
        return hex;
    }
}
