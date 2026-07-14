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

package github.luckygc.cap.utils;

import java.util.Objects;

/** 与 capjs-core 线格式一致的 FNV-1a 与 xorshift32 工具。 */
public final class RandomUtil {

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;

    private RandomUtil() {}

    /** 从字符串种子生成指定长度的确定性十六进制字符串。 */
    public static String prng(String seed, int length) {
        if (seed == null || seed.isBlank() || length <= 0) {
            throw new IllegalArgumentException("种子不能为空且长度必须大于0");
        }
        return prngFromHash(fnv1a(seed), length);
    }

    /** 计算字符串的 32 位 FNV-1a 状态。 */
    public static int fnv1a(String value) {
        return fnv1aResume(FNV_OFFSET_BASIS, value);
    }

    /** 从已有 FNV-1a 状态继续处理后缀，避免重新扫描公共前缀。 */
    public static int fnv1aResume(int state, String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        int hash = state;
        for (int index = 0; index < suffix.length(); index++) {
            hash ^= suffix.charAt(index);
            hash += (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24);
        }
        return hash;
    }

    /** 从已有 32 位状态生成指定长度的十六进制字符串。 */
    public static String prngFromHash(int initialHash, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("长度必须大于0");
        }
        int state = initialHash;
        StringBuilder result = new StringBuilder(length + 7);
        while (result.length() < length) {
            state = next(state);
            result.append("%08x".formatted(state));
        }
        return result.substring(0, length);
    }

    private static int next(int state) {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state;
    }
}
