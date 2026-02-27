package cc.katr;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 等同于无状态的纯函数工具
 * 基于纯随机分配的 Base62 短链接生成器
 */
public class ShortLinkGenerator {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = BASE62.length();
    private static final int DEFAULT_LENGTH = 8;

    /**
     * 生成基于 Base62 的随机短链接（默认8位长度，提供了 62^8 ≈ 218万亿 种组合）
     */
    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /**
     * 自定义长度的生成
     */
    public static String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE)));
        }
        return sb.toString();
    }
}
