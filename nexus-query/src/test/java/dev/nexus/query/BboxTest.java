package dev.nexus.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BboxTest {

    @Test
    void parsesLonLatOrder() {
        Bbox b = Bbox.parse("77.4,12.8,77.8,13.1");
        assertThat(b.contains(77.59, 12.97)).isTrue();
        assertThat(b.contains(12.97, 77.59)).as("swapped lat/lon falls outside").isFalse();
    }

    @Test
    void searchBoxCoversTheViewport() {
        Bbox b = Bbox.parse("77.4,12.8,77.8,13.1");
        // 0.4 degrees of longitude at 12.8N is about 43 km
        assertThat(b.widthKm()).isGreaterThan(43);
        assertThat(b.heightKm()).isGreaterThan(33);
    }

    @Test
    void rejectsInvalid() {
        assertThatThrownBy(() -> Bbox.parse("1,2,3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bbox.parse("10,10,5,20")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bbox.parse("0,0,200,10")).isInstanceOf(IllegalArgumentException.class);
    }
}
