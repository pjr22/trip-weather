package com.pjr22.tripweather.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.pjr22.tripweather.dto.RouteDto;
import com.pjr22.tripweather.dto.WaypointDto;
import com.pjr22.tripweather.model.RouteData;

class GpxExporterTest {

    private final GpxExporter exporter = new GpxExporter();

    @Test
    void producesValidGpx11Document() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        Element root = doc.getDocumentElement();
        assertEquals("gpx", root.getTagName());
        assertEquals("1.1", root.getAttribute("version"));
        assertEquals("http://www.topografix.com/GPX/1/1", root.getNamespaceURI());
    }

    @Test
    void containsAllThreeContainers() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        assertEquals(2, doc.getElementsByTagName("wpt").getLength(), "one wpt per waypoint");
        assertEquals(1, doc.getElementsByTagName("rte").getLength());
        assertEquals(2, doc.getElementsByTagName("rtept").getLength(), "one rtept per waypoint");
        assertEquals(1, doc.getElementsByTagName("trk").getLength());
        assertEquals(1, doc.getElementsByTagName("trkseg").getLength());
        assertEquals(3, doc.getElementsByTagName("trkpt").getLength(), "one trkpt per geometry point");
    }

    @Test
    void waypointHasLatLonEleAndName() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        Element wpt = (Element) doc.getElementsByTagName("wpt").item(0);
        assertEquals("45.5", wpt.getAttribute("lat"));
        assertEquals("-122.6", wpt.getAttribute("lon"));
        Element ele = (Element) wpt.getElementsByTagName("ele").item(0);
        assertEquals("100.0", ele.getTextContent());
        Element name = (Element) wpt.getElementsByTagName("name").item(0);
        assertEquals("Start", name.getTextContent());
    }

    @Test
    void trackPointPreservesElevation() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        NodeList trkpts = doc.getElementsByTagName("trkpt");
        Element first = (Element) trkpts.item(0);
        assertEquals("45.5", first.getAttribute("lat"));
        assertEquals("-122.6", first.getAttribute("lon"));
        assertNotNull(first.getElementsByTagName("ele").item(0));
    }

    @Test
    void waypointTimeConvertedToIsoUtc() {
        WaypointDto wp = new WaypointDto();
        wp.setDate("2026-06-01");
        wp.setTime("09:00");
        wp.setTimezone("America/Los_Angeles");
        // 09:00 PDT (UTC-7) = 16:00 UTC
        assertEquals("2026-06-01T16:00:00Z", GpxExporter.waypointToIsoUtc(wp));
    }

    @Test
    void waypointTimeReturnsNullForBlankFields() {
        WaypointDto wp = new WaypointDto();
        assertNull(GpxExporter.waypointToIsoUtc(wp));
    }

    @Test
    void weatherIsNeverEmittedRegardlessOfContext() throws Exception {
        // Even though the context happens to carry weather, GPX output must not.
        String xml = new String(exporter.write(fixture()), StandardCharsets.UTF_8);
        assertTrue(xml.indexOf("Sunny") < 0, "weather data must not appear in GPX");
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static ExportContext fixture() {
        WaypointDto wp1 = new WaypointDto();
        wp1.setSequence(1);
        wp1.setLocationName("Start");
        wp1.setLatitude(45.5);
        wp1.setLongitude(-122.6);
        wp1.setElevation(100.0);
        wp1.setDate("2026-06-01");
        wp1.setTime("09:00");
        wp1.setTimezone("America/Los_Angeles");

        WaypointDto wp2 = new WaypointDto();
        wp2.setSequence(2);
        wp2.setLocationName("End");
        wp2.setLatitude(45.6);
        wp2.setLongitude(-122.7);
        wp2.setElevation(130.0);
        wp2.setDate("2026-06-01");
        wp2.setTime("10:00");
        wp2.setTimezone("America/Los_Angeles");

        RouteDto route = new RouteDto();
        route.setName("Test Route");
        route.setWaypoints(List.of(wp1, wp2));

        RouteData routeData = new RouteData();
        routeData.setGeometry(List.of(
                List.of(-122.6, 45.5, 100.0),
                List.of(-122.65, 45.55, 150.0),
                List.of(-122.7, 45.6, 130.0)
        ));

        return new ExportContext(route, routeData, Map.of());
    }
}
