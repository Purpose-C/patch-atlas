package fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StringUtilsTest {

    /** 触发测试:在 fixed 提交上通过,在 buggy 提交上失败。 */
    @Test
    void lastCharReturnsFinalCharacter() {
        assertEquals('c', StringUtils.lastChar("abc"));
    }
}
