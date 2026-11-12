package com.workblog.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HourlyThrottleTest {
    @Test
    void allows_up_to_max_per_key_and_undo_gives_one_back() {
        HourlyThrottle t = new HourlyThrottle();
        assertThat(t.allow("a", 2)).isTrue();
        assertThat(t.allow("a", 2)).isTrue();
        assertThat(t.allow("a", 2)).isFalse();
        assertThat(t.allow("b", 2)).isTrue();
        t.undo("a");
        assertThat(t.allow("a", 2)).isTrue();
    }

    @Test
    void a_full_table_drops_the_oldest_key_instead_of_refusing_new_ones() {
        HourlyThrottle t = new HourlyThrottle();
        for (int i = 0; i < 10_050; i++) assertThat(t.allow("k" + i, 1)).isTrue();
        assertThat(t.allow("k10049", 1)).isFalse(); // 최근 키는 그대로 센다
    }

    @Test
    void ipv6_addresses_in_one_64_share_a_key_and_ipv4_is_kept() {
        assertThat(HourlyThrottle.clientKey("2001:db8:1:2::1")).isEqualTo(HourlyThrottle.clientKey("2001:db8:1:2:ffff::9"));
        assertThat(HourlyThrottle.clientKey("2001:db8:1:2::1")).isNotEqualTo(HourlyThrottle.clientKey("2001:db8:1:3::1"));
        assertThat(HourlyThrottle.clientKey("10.1.2.3")).isEqualTo("10.1.2.3");
    }
}
