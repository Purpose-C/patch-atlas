package fixtures;

/** 沙箱复现校准使用的最小 fixture,内含一个经典的 off-by-one 缺陷。 */
public final class StringUtils {

    private StringUtils() {
    }

    /** 返回非空字符串的最后一个字符。 */
    public static char lastChar(String s) {
        return s.charAt(s.length() - 1);
    }
}
