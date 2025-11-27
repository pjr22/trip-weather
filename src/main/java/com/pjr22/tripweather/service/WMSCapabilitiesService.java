package com.pjr22.tripweather.service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.pjr22.tripweather.model.BoundingBox;
import com.pjr22.tripweather.model.CapabilitiesData;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
/**
 * See: https://digital.weather.gov/staticpages/mapservices.php
 * and: https://digital.weather.gov/ndfd.conus/wms?REQUEST=GetCapabilities
 */
public class WMSCapabilitiesService {

    private Map<String, CapabilitiesData> layerCapabilities = new HashMap<>();
    private Map<String, List<Double>> layerResolutions = new HashMap<>();
    private Map<String, String> layerDescriptions = new HashMap<>();
    private static final String CAPABILITIES_FILE = "conus_capabilities.xml";
    private static final String LAYER_DESCRIPTIONS_FILE = "ndfd.conus_layer_descriptions.json";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @PostConstruct
    public void initialize() {
        try {
            // Load layer descriptions first
            loadLayerDescriptions();
            
            log.info("Loading WMS capabilities from {}", CAPABILITIES_FILE);
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(CAPABILITIES_FILE);
            
            if (inputStream == null) {
                log.error("Could not find {}", CAPABILITIES_FILE);
                return;
            }

            XmlMapper xmlMapper = new XmlMapper();
            JsonNode rootNode = xmlMapper.readTree(inputStream);
            
            // First, parse all TileSets to build a map of layer names to resolutions
            parseTileSets(rootNode);
            
            // Then parse the layers
            parseLayers(rootNode);
            
            log.info("Successfully loaded {} WMS layers", layerCapabilities.size());
            
        } catch (Exception e) {
            log.error("Error loading WMS capabilities", e);
        }
    }

    private void parseTileSets(JsonNode rootNode) {
        JsonNode capabilityNode = rootNode.path("Capability");
        JsonNode vendorSpecificNode = capabilityNode.path("VendorSpecificCapabilities");
        JsonNode tileSets = vendorSpecificNode.path("TileSet");
        
        if (tileSets.isArray()) {
            for (JsonNode tileSet : tileSets) {
                String layersName = tileSet.path("Layers").asText();
                String resolutionsStr = tileSet.path("Resolutions").asText();
                
                if (!layersName.isEmpty() && !resolutionsStr.isEmpty()) {
                    List<Double> resolutions = parseResolutions(resolutionsStr);
                    layerResolutions.put(layersName, resolutions);
                }
            }
        }
    }

    private List<Double> parseResolutions(String resolutionsStr) {
        List<Double> resolutions = new ArrayList<>();
        String[] resolutionArray = resolutionsStr.split(" ");
        
        for (String resStr : resolutionArray) {
            try {
                if (!resStr.isEmpty()) {
                    resolutions.add(Double.parseDouble(resStr));
                }
            } catch (NumberFormatException e) {
                log.warn("Could not parse resolution: {}", resStr);
            }
        }
        
        return resolutions;
    }

    private void parseLayers(JsonNode rootNode) {
        JsonNode capabilityNode = rootNode.path("Capability");
        JsonNode layerNode = capabilityNode.path("Layer");
        
        // Find the nested Layer elements (skip the root Layer)
        JsonNode nestedLayers = layerNode.path("Layer");
        
        if (nestedLayers.isArray()) {
            for (JsonNode layer : nestedLayers) {
                CapabilitiesData capabilities = parseLayer(layer);
                if (capabilities != null) {
                    layerCapabilities.put(capabilities.getLayerName(), capabilities);
                }
            }
        }
    }

    private CapabilitiesData parseLayer(JsonNode layerNode) {
        try {
            String layerName = layerNode.path("Name").asText();
            String layerTitle = layerNode.path("Title").asText();
            
            // Use description from JSON file if available, otherwise fall back to title
            String layerDescription = layerDescriptions.getOrDefault(layerName, layerTitle);
            
            // Parse SRS values
            List<String> srsValues = new ArrayList<>();
            JsonNode srsNodes = layerNode.path("SRS");
            if (srsNodes.isArray()) {
                for (JsonNode srsNode : srsNodes) {
                    String srs = srsNode.asText().trim();
                    if (!srs.isEmpty()) {
                        srsValues.add(srs);
                    }
                }
            } else if (!srsNodes.asText().isEmpty()) {
                srsValues.add(srsNodes.asText().trim());
            }
            
            // Parse BoundingBox
            BoundingBox boundingBox = null;
            JsonNode bboxNode = layerNode.path("BoundingBox");
            if (!bboxNode.isMissingNode() && !bboxNode.isNull()) {
                String srs = bboxNode.path("SRS").asText();
                double minx = bboxNode.path("minx").asDouble();
                double miny = bboxNode.path("miny").asDouble();
                double maxx = bboxNode.path("maxx").asDouble();
                double maxy = bboxNode.path("maxy").asDouble();
                boundingBox = new BoundingBox(minx, miny, maxx, maxy, srs);
            }
            
            // Get resolutions from our pre-built map
            List<Double> resolutions = layerResolutions.getOrDefault(layerName, new ArrayList<>());
            
            // Parse valid times from Dimension element
            List<String> validTimes = new ArrayList<>();
            int validTimesCount = 0;
            long validTimesInterval = 60; // Default to 60 minutes
            
            JsonNode dimensionNodes = layerNode.path("Dimension");
            if (dimensionNodes.isArray()) {
                for (JsonNode dimensionNode : dimensionNodes) {
                    String name = dimensionNode.path("name").asText();
                    if ("vtit".equals(name)) {
                        String timesStr = extractDimensionValues(dimensionNode);
                        validTimes = parseValidTimes(timesStr);
                        validTimesCount = validTimes.size();
                        validTimesInterval = calculateInterval(validTimes, layerName);
                        break;
                    }
                }
            } else if (!dimensionNodes.isMissingNode() && !dimensionNodes.isNull()) {
                String name = dimensionNodes.path("name").asText();
                if ("vtit".equals(name)) {
                    String timesStr = extractDimensionValues(dimensionNodes);
                    validTimes = parseValidTimes(timesStr);
                    validTimesCount = validTimes.size();
                    validTimesInterval = calculateInterval(validTimes, layerName);
                }
            }
            
            return new CapabilitiesData(
                layerName, 
                layerTitle, 
                layerDescription, 
                srsValues, 
                boundingBox, 
                resolutions, 
                validTimesCount, 
                validTimesInterval,
                validTimes
            );
            
        } catch (Exception e) {
            log.error("Error parsing layer", e);
            return null;
        }
    }

    public Map<String, String> getLayerNames() {
        Map<String, String> layers = new TreeMap<>();        
        layerCapabilities.entrySet().stream().forEach(entry ->
              layers.put(entry.getKey(), entry.getValue().getLayerDescription()));

        return layers;
    }

    public BoundingBox getLayerBoundingBox(String layerName) {
        CapabilitiesData capabilities = layerCapabilities.get(layerName);
        return capabilities != null ? capabilities.getBoundingBox() : null;
    }

    public List<Double> getLayerResolutions(String layerName) {
        CapabilitiesData capabilities = layerCapabilities.get(layerName);
        return capabilities != null ? capabilities.getResolutions() : new ArrayList<>();
    }

    public List<String> getLayerValidTimes(String layerName) {
        CapabilitiesData capabilities = layerCapabilities.get(layerName);
        if (capabilities == null) {
            log.warn("Layer {} not found", layerName);
            return new ArrayList<>();
        } else {
           // TODO: remove
           // log.info("Found CapabilitiesData for layer {}:\n{}", layerName, capabilities);
        }

        if (capabilities.getValidTimesCount() < 1 || capabilities.getValidTimesInterval() < 1) {
            log.warn("No valid times defined for layer {}", layerName);
            return new ArrayList<>();
        }
        
        List<String> validTimes = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime midnight = now.withHour(0);
        int intervalHrs = (int)capabilities.getValidTimesInterval() / 60;
        int intervals = now.getHour() / intervalHrs;
        ZonedDateTime firstValidTime = midnight.withHour(intervals * intervalHrs);
        
        // TODO: remove
        // log.info("now: {}, midnight: {}, intervalHrs: {}, intervals: {}, firstValidTime: {}", now, midnight, intervalHrs, intervals, firstValidTime);
        
        for (int i = 0; i < capabilities.getValidTimesCount(); ++i) {
           ZonedDateTime validTime = firstValidTime.plusHours(i * intervalHrs);
           validTimes.add(validTime.format(TIME_FORMATTER));
        }

        return new ArrayList<>(validTimes);
    }

    private String extractDimensionValues(JsonNode dimensionNode) {
        if (dimensionNode == null || dimensionNode.isMissingNode() || dimensionNode.isNull()) {
            return "";
        }

        String textContent = dimensionNode.path("").asText();
        if (textContent == null || textContent.isBlank()) {
            textContent = dimensionNode.asText();
        }
        return textContent != null ? textContent.trim() : "";
    }

    private List<String> parseValidTimes(String timesStr) {
        if (timesStr == null || timesStr.isBlank()) {
            return new ArrayList<>();
        }

        return Arrays.stream(timesStr.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toList());
    }

    private long calculateInterval(List<String> validTimes, String layerName) {
        if (validTimes.size() < 2) {
            return 60;
        }

        try {
            LocalDateTime firstTime = LocalDateTime.parse(validTimes.get(0), TIME_FORMATTER);
            LocalDateTime secondTime = LocalDateTime.parse(validTimes.get(1), TIME_FORMATTER);
            long interval = ChronoUnit.MINUTES.between(firstTime, secondTime);
            return interval > 0 ? interval : 60;
        } catch (Exception e) {
            log.warn("Could not calculate time interval for layer {}", layerName, e);
            return 60;
        }
    }
    
    private void loadLayerDescriptions() {
        try {
            log.info("Loading layer descriptions from {}", LAYER_DESCRIPTIONS_FILE);
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(LAYER_DESCRIPTIONS_FILE);
            
            if (inputStream == null) {
                log.error("Could not find {}", LAYER_DESCRIPTIONS_FILE);
                return;
            }

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(inputStream);
            
            // Parse the JSON object and populate the layerDescriptions map
            rootNode.fields().forEachRemaining(entry -> {
                String layerName = entry.getKey();
                String description = entry.getValue().asText();
                layerDescriptions.put(layerName, description);
            });
            
            log.info("Successfully loaded {} layer descriptions", layerDescriptions.size());
            
        } catch (Exception e) {
            log.error("Error loading layer descriptions", e);
        }
    }
}
