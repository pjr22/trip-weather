package com.pjr22.tripweather.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolyParserTest {

    /** Trivial single-ring "Colorado-ish" rectangle. */
    private static final String SINGLE_POLY = """
            colorado
            1
                -109.05  41.00
                -102.04  41.00
                -102.04  37.00
                -109.05  37.00
                -109.05  41.00
            END
            END
            """;

    /** One outer ring, one inner ring (hole) — the format Geofabrik uses for
     *  countries like Italy with the Vatican carved out. */
    private static final String POLY_WITH_HOLE = """
            shape
            outer
                0.0  0.0
                10.0  0.0
                10.0 10.0
                0.0 10.0
                0.0  0.0
            END
            !inner
                3.0 3.0
                7.0 3.0
                7.0 7.0
                3.0 7.0
                3.0 3.0
            END
            END
            """;

    /** Two disjoint outer rings — i.e. an actual MultiPolygon, like Hawaii. */
    private static final String MULTI_POLY = """
            islands
            big
                0.0  0.0
                2.0  0.0
                2.0  2.0
                0.0  2.0
                0.0  0.0
            END
            small
                10.0 10.0
                11.0 10.0
                11.0 11.0
                10.0 11.0
                10.0 10.0
            END
            END
            """;

    /** A ring whose first vertex doesn't equal the last — parser must close it. */
    private static final String UNCLOSED_RING_POLY = """
            unclosed
            1
                0.0 0.0
                1.0 0.0
                1.0 1.0
                0.0 1.0
            END
            END
            """;

    @Test
    void parses_single_polygon() throws IOException {
        MultiPolygon mp = PolyParser.parse(new StringReader(SINGLE_POLY));

        assertThat(mp.getNumGeometries()).isEqualTo(1);
        assertThat(mp.getSRID()).isEqualTo(4326);

        Polygon p = (Polygon) mp.getGeometryN(0);
        assertThat(p.getExteriorRing().getNumPoints()).isEqualTo(5);
        assertThat(p.getNumInteriorRing()).isZero();
    }

    @Test
    void parses_polygon_with_hole() throws IOException {
        MultiPolygon mp = PolyParser.parse(new StringReader(POLY_WITH_HOLE));

        assertThat(mp.getNumGeometries()).isEqualTo(1);
        Polygon p = (Polygon) mp.getGeometryN(0);
        assertThat(p.getNumInteriorRing()).isEqualTo(1);
        assertThat(p.getInteriorRingN(0).getNumPoints()).isEqualTo(5);
    }

    @Test
    void parses_multi_polygon() throws IOException {
        MultiPolygon mp = PolyParser.parse(new StringReader(MULTI_POLY));

        assertThat(mp.getNumGeometries()).isEqualTo(2);
    }

    @Test
    void closes_unclosed_ring() throws IOException {
        MultiPolygon mp = PolyParser.parse(new StringReader(UNCLOSED_RING_POLY));

        Polygon p = (Polygon) mp.getGeometryN(0);
        // Original 4 vertices + auto-closure = 5
        assertThat(p.getExteriorRing().getNumPoints()).isEqualTo(5);
        assertThat(p.getExteriorRing().getCoordinateN(0))
                .isEqualTo(p.getExteriorRing().getCoordinateN(4));
    }

    @Test
    void rejects_empty_input() {
        assertThatThrownBy(() -> PolyParser.parse(new StringReader("")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Empty");
    }

    @Test
    void rejects_inner_ring_with_no_outer() {
        String bad = """
                broken
                !inner
                    0.0 0.0
                    1.0 0.0
                    1.0 1.0
                    0.0 0.0
                END
                END
                """;
        assertThatThrownBy(() -> PolyParser.parse(new StringReader(bad)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no preceding outer ring");
    }

    @Test
    void rejects_ring_with_too_few_vertices() {
        String bad = """
                broken
                1
                    0.0 0.0
                    1.0 0.0
                END
                END
                """;
        assertThatThrownBy(() -> PolyParser.parse(new StringReader(bad)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("at least 3");
    }

    @Test
    void rejects_malformed_coordinate() {
        String bad = """
                broken
                1
                    0.0 0.0
                    not-a-number 0.0
                    1.0 1.0
                    0.0 0.0
                END
                END
                """;
        assertThatThrownBy(() -> PolyParser.parse(new StringReader(bad)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed");
    }
}
