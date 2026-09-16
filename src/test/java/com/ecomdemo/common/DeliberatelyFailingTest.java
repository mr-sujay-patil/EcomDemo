package com.ecomdemo.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporary. Exists only to prove that a red build blocks a merge, and is deleted immediately after.
 */
class DeliberatelyFailingTest {

    @Test
    void thisTestFailsOnPurpose_toProveCiBlocksTheMerge() {
        assertThat(2 + 2).as("a deliberate failure, so CI goes red").isEqualTo(5);
    }
}
