package com.pjr22.tripweather.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class KmlExporterTest {

    private final KmlExporter exporter = new KmlExporter();

    @Test
    void rootIsKml22Namespace() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        Element root = doc.getDocumentElement();
        assertEquals("kml", root.getTagName());
        assertEquals("http://www.opengis.net/kml/2.2", root.getNamespaceURI());
    }

    @Test
    void includesLineStringWithSpaceSeparatedCoordinates() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        NodeList lineStrings = doc.getElementsByTagNameNS("*", "LineString");
        assertEquals(1, lineStrings.getLength());
        Element coords = (Element) ((Element) lineStrings.item(0))
                .getElementsByTagNameNS("*", "coordinates").item(0);
        String text = coords.getTextContent().trim();
        // Three points → two spaces.
        assertEquals(2, text.split(" ").length - 1);
        assertTrue(text.startsWith("-122.6,45.5,100"), "first coord lon,lat,ele");
    }

    @Test
    void hasOnePlacemarkForRoutePlusOnePerWaypoint() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        NodeList placemarks = doc.getElementsByTagNameNS("*", "Placemark");
        assertEquals(3, placemarks.getLength(), "1 route + 2 waypoints");
    }

    @Test
    void waypointPlacemarkHasPointWithCoordinates() throws Exception {
        Document doc = parse(exporter.write(fixture()));
        NodeList placemarks = doc.getElementsByTagNameNS("*", "Placemark");
        // Index 0 is the LineString; indices 1+ are waypoints.
        Element wpPlacemark = (Element) placemarks.item(1);
        Element point = (Element) wpPlacemark.getElementsByTagNameNS("*", "Point").item(0);
        Element coords = (Element) point.getElementsByTagNameNS("*", "coordinates").item(0);
        assertEquals("-122.6,45.5,100.0", coords.getTextContent());
    }

    @Test
    void definesRouteLineStyleWithAppColor() throws Exception {
        String xml = new String(exporter.write(fixture()), StandardCharsets.UTF_8);
        // KML aabbggrr for #0066cc with full alpha is ffcc6600.
        assertTrue(xml.contains("<color>ffcc6600</color>"));
    }

    @Test
    void weatherIsNeverEmittedRegardlessOfContext() {
        String xml = new String(exporter.write(fixture()), StandardCharsets.UTF_8);
        assertTrue(xml.indexOf("Sunny") < 0, "weather must not appear in KML");
    }

    @Test
    void buildDescriptionIncludesArrivalAndDuration() {
        WaypointDto wp = new WaypointDto();
        wp.setDate("2026-06-01");
        wp.setTime("09:00");
        wp.setTimezone("America/Los_Angeles");
        wp.setDurationMin(30);
        String desc = KmlExporter.buildDescription(wp);
        assertTrue(desc.contains("Arrival: 2026-06-01 09:00"));
        assertTrue(desc.contains("America/Los_Angeles"));
        assertTrue(desc.contains("Stop duration: 30 min"));
    }

    @Test
    void buildDescriptionEmptyForBlankWaypoint() {
        assertEquals("", KmlExporter.buildDescription(new WaypointDto()));
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
