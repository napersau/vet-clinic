package com.vetclinic.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodesTest {

    @Test
    void randomSuffix_hasTheRequestedLengthAndOnlyUnambiguousUppercaseCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(Codes.randomSuffix(6)).hasSize(6).matches("[A-HJ-NP-Z2-9]{6}");
        }
    }

    @Test
    void randomSuffix_isRandomEnoughNotToCollideInASmallSample() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            seen.add(Codes.randomSuffix(6));
        }
        assertThat(seen).hasSizeGreaterThan(1990);
    }
}
