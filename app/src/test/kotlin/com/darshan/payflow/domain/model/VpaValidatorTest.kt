package com.darshan.payflow.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VpaValidatorTest {
    @Test
    fun `accepts common vpa shapes`() {
        for (vpa in listOf("alice@okbank", "9876543210@upi", "a.b-c_d@ybl", "ALICE@OKICICI", "shop.123@paytm", "ab@ab")) {
            assertThat(VpaValidator.validate(vpa)).isEqualTo(VpaValidation.Valid(vpa.lowercase()))
        }
    }

    @Test
    fun `rejects malformed vpas with a reason`() {
        assertThat(VpaValidator.validate("")).isEqualTo(VpaValidation.Invalid(VpaError.EMPTY))
        assertThat(VpaValidator.validate("alice")).isEqualTo(VpaValidation.Invalid(VpaError.MISSING_HANDLE))
        assertThat(VpaValidator.validate("@okbank")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("alice@")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("alice@ok bank")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("a@b@c")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("alice@ok.bank")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("ali ce@okbank")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("а@okbank")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT)) // Cyrillic а
        assertThat(VpaValidator.validate("‮alice@okbank")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("javascript:alert(1)@x")).isEqualTo(VpaValidation.Invalid(VpaError.BAD_FORMAT))
        assertThat(VpaValidator.validate("a".repeat(300) + "@okbank")).isEqualTo(VpaValidation.Invalid(VpaError.TOO_LONG))
    }

    @Test
    fun `normalises surrounding whitespace and case`() {
        assertThat(VpaValidator.validate("  Alice@OkBank  ")).isEqualTo(VpaValidation.Valid("alice@okbank"))
        assertThat(VpaValidator.isValid("alice@okbank")).isTrue()
        assertThat(VpaValidator.isValid("alice")).isFalse()
    }
}
