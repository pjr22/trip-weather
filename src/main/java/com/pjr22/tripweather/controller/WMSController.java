package com.pjr22.tripweather.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.model.BoundingBox;
import com.pjr22.tripweather.service.WMSCapabilitiesService;

@RestController
@RequestMapping("/api/wms")
public class WMSController {

    private final WMSCapabilitiesService wmsCapabilitiesService;

    public WMSController(WMSCapabilitiesService wmsCapabilitiesService) {
        this.wmsCapabilitiesService = wmsCapabilitiesService;
    }

    @GetMapping(path = "/layers", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> getLayerNames() {
        return wmsCapabilitiesService.getLayerNames();
    }

    @GetMapping(path = "/layer/boundingBox", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BoundingBox> getLayerBoundingBox(@RequestParam String layerName) {
        BoundingBox boundingBox = wmsCapabilitiesService.getLayerBoundingBox(layerName);
        if (boundingBox == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(boundingBox);
    }

    @GetMapping(path = "/layer/resolutions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Double>> getLayerResolutions(@RequestParam String layerName) {
        List<Double> resolutions = wmsCapabilitiesService.getLayerResolutions(layerName);
        if (resolutions.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(resolutions);
    }

    @GetMapping(path = "/layer/validTimes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> getLayerValidTimes(@RequestParam String layerName) {
        List<String> validTimes = wmsCapabilitiesService.getLayerValidTimes(layerName);
        if (validTimes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(validTimes);
    }
}