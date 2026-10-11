package io.github.mocchikon.hentie.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCheckServiceTest
{
    @Test
    void aHigherNumberIsNewer()
    {
        assertThat(UpdateCheckService.isNewer("v0.0.2", "0.0.1-SNAPSHOT")).isTrue();
        assertThat(UpdateCheckService.isNewer("1.10.0", "1.9.3")).isTrue();
        assertThat(UpdateCheckService.isNewer("2", "1.99.99")).isTrue();
        assertThat(UpdateCheckService.isNewer("V1.0.1", "1.0")).isTrue();
    }

    @Test
    void aLowerOrEqualNumberIsNot()
    {
        assertThat(UpdateCheckService.isNewer("v1.0.0", "1.0.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("1.0", "1.0.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("1.9.3", "1.10.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("0.0.1", "0.0.2-SNAPSHOT")).isFalse();
    }

    @Test
    void theReleaseOfASnapshotIsNewerButQualifiersAreNotOrdered()
    {
        assertThat(UpdateCheckService.isNewer("1.0.0", "1.0.0-SNAPSHOT")).isTrue();
        assertThat(UpdateCheckService.isNewer("1.0.0-beta", "1.0.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("1.0.0-rc2", "1.0.0-rc1")).isFalse();
    }

    @Test
    void anUnreadableVersionIsNeverNewer()
    {
        assertThat(UpdateCheckService.isNewer("latest", "1.0.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("1.0.0", null)).isFalse();
        assertThat(UpdateCheckService.isNewer("", "1.0.0")).isFalse();
        assertThat(UpdateCheckService.isNewer("99999999999999999999", "1.0.0")).isFalse();
    }
}
