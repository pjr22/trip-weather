# HOWTO: Implement Self-Hosted Map Tiles for Trip Weather

This guide provides step-by-step instructions for implementing self-hosted map tiles using tileserver-gl-light and Docker, replacing the dependency on OpenStreetMap's public tile servers.

## Overview

This implementation will:
1. Set up a local tile server using tileserver-gl-light
2. Download or generate map tiles for your desired coverage area
3. Configure the Trip Weather application to use the local tile server
4. Make the tile server configurable for easy switching between local and public tiles

## Prerequisites

- Docker and Docker Compose installed
- Basic understanding of Spring Boot configuration
- Sufficient disk space (recommended: 50GB+ for regional coverage)
- Administrative access to modify application configuration

## Step 1: Set Up Tile Server Infrastructure

### 1.1 Create Docker Compose Configuration

Create `docker-compose.yml` in the project root:

```yaml
version: '3.8'

services:
  tileserver:
    image: maptiler/tileserver-gl-light
    container_name: trip-weather-tiles
    ports:
      - "8081:8080"
    volumes:
      - ./tiles:/data
      - ./config:/config
    environment:
      - TILESERVER_PORT=8080
      - TILESERVER_BIND=0.0.0.0
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  # Optional: Add nginx for caching and load balancing
  nginx:
    image: nginx:alpine
    container_name: trip-weather-proxy
    ports:
      - "8082:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/cache:/var/cache/nginx
    depends_on:
      - tileserver
    restart: unless-stopped
```

### 1.2 Create Necessary Directories

```bash
mkdir -p tiles config nginx/cache
```

### 1.3 Configure Tile Server

Create `config/config.json`:

```json
{
  "styles": {
    "osm-liberty": {
      "style": "https://raw.githubusercontent.com/maputnik/osm-liberty/gh-pages/style.json",
      "tilejson": "https://free.tilehosting.com/data/v3.json?key={key}",
      "raster": true,
      "name": "OSM Liberty"
    },
    "basic": {
      "style": "https://raw.githubusercontent.com/maputnik/osm-liberty/gh-pages/style.json",
      "tilejson": "http://tileserver/data/v3.json",
      "raster": true,
      "name": "Basic Style"
    }
  },
  "data": {
    "v3": {
      "mbtiles": "data.mbtiles"
    }
  }
}
```

## Step 2: Obtain Map Tiles

### Option A: Download Pre-built MBTiles (Recommended for Testing)

1. **Download regional MBTiles**:
   ```bash
   # Example: Download a small region for testing
   curl -L -o tiles/data.mbtiles "https://download.maptiler.com/data/countries/united-states-of-america.mbtiles"
   ```

2. **Alternative sources for MBTiles**:
   - MapTiler Cloud (free tiers available)
   - Geofabrik Download Server (for OSM data)
   - Various open data portals

### Option B: Generate Custom Tiles (Advanced)

1. **Download OSM data**:
   ```bash
   # Download data for a specific region
   curl -L -o osm-data.osm.pbf "https://download.geofabrik.de/north-america/us-latest.osm.pbf"
   ```

2. **Generate tiles using tilemaker** (additional Docker service):
   ```yaml
   # Add to docker-compose.yml
   tilemaker:
     image: ghcr.io/systemed/tilemaker:latest
     container_name: tilemaker
     volumes:
       - ./tiles:/output
       - ./osm-data:/data
     command: ["/data/us-latest.osm.pbf", "/output/data.mbtiles", "--config", "/config/config.json", "--process", "/config/process.lua"]
     depends_on:
       - tileserver
   ```

## Step 3: Update Trip Weather Application

### 3.1 Add Map Configuration Properties

Update `src/main/resources/application.properties`:

```properties
# Map tile configuration
map.tiles.enabled=true
map.tiles.local.url=http://localhost:8081/data/v3/{z}/{x}/{y}.png
map.tiles.local.attribution=&copy; Local Tile Server | &copy; OpenStreetMap contributors
map.tiles.local.max-zoom=18

# Fallback to public tiles
map.tiles.fallback.url=https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png
map.tiles.fallback.attribution=&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors
map.tiles.fallback.max-zoom=19

# Tile server health check
map.tiles.health.url=http://localhost:8081/health
map.tiles.health.timeout=5000

# Use local tiles by default (set to false to use public tiles)
map.tiles.use-local=true
```

### 3.2 Create Map Configuration Controller

Create `src/main/java/com/pjr22/tripweather/controller/MapConfigController.java`:

```java
package com.pjr22.tripweather.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/map")
public class MapConfigController {

    private final RestClient restClient;
    private final boolean useLocalTiles;
    private final String localTileUrl;
    private final String localAttribution;
    private final int localMaxZoom;
    private final String fallbackTileUrl;
    private final String fallbackAttribution;
    private final int fallbackMaxZoom;
    private final String healthCheckUrl;
    private final Duration healthTimeout;

    public MapConfigController(
            @Value("${map.tiles.use-local:true}") boolean useLocalTiles,
            @Value("${map.tiles.local.url:http://localhost:8081/data/v3/{z}/{x}/{y}.png}") String localTileUrl,
            @Value("${map.tiles.local.attribution:&copy; Local Tile Server | &copy; OpenStreetMap contributors}") String localAttribution,
            @Value("${map.tiles.local.max-zoom:18}") int localMaxZoom,
            @Value("${map.tiles.fallback.url:https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png}") String fallbackTileUrl,
            @Value("${map.tiles.fallback.attribution:&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors}") String fallbackAttribution,
            @Value("${map.tiles.fallback.max-zoom:19}") int fallbackMaxZoom,
            @Value("${map.tiles.health.url:http://localhost:8081/health}") String healthCheckUrl,
            @Value("${map.tiles.health.timeout:5000}") long healthTimeoutMs) {
        
        this.useLocalTiles = useLocalTiles;
        this.localTileUrl = localTileUrl;
        this.localAttribution = localAttribution;
        this.localMaxZoom = localMaxZoom;
        this.fallbackTileUrl = fallbackTileUrl;
        this.fallbackAttribution = fallbackAttribution;
        this.fallbackMaxZoom = fallbackMaxZoom;
        this.healthCheckUrl = healthCheckUrl;
        this.healthTimeout = Duration.ofMillis(healthTimeoutMs);
        
        this.restClient = RestClient.builder()
                .build();
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getMapConfig() {
        Map<String, Object> config = new HashMap<>();
        
        if (useLocalTiles && isLocalTileServerHealthy()) {
            config.put("tilesUrl", localTileUrl);
            config.put("attribution", localAttribution);
            config.put("maxZoom", localMaxZoom);
            config.put("source", "local");
        } else {
            config.put("tilesUrl", fallbackTileUrl);
            config.put("attribution", fallbackAttribution);
            config.put("maxZoom", fallbackMaxZoom);
            config.put("source", "fallback");
            
            if (useLocalTiles) {
                config.put("warning", "Local tile server unavailable, using fallback");
            }
        }
        
        return ResponseEntity.ok(config);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getTileServerHealth() {
        Map<String, Object> health = new HashMap<>();
        boolean healthy = isLocalTileServerHealthy();
        
        health.put("localServerHealthy", healthy);
        health.put("useLocalTiles", useLocalTiles);
        health.put("healthCheckUrl", healthCheckUrl);
        
        return ResponseEntity.ok(health);
    }

    private boolean isLocalTileServerHealthy() {
        if (!useLocalTiles) {
            return false;
        }
        
        try {
            String response = restClient.get()
                    .uri(healthCheckUrl)
                    .retrieve()
                    .body(String.class);
            
            return response != null && !response.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
```

### 3.3 Update Frontend JavaScript

Modify `src/main/resources/static/js/map.js` to use dynamic tile configuration:

```javascript
// Add this function at the top of the file
async function getMapConfig() {
    try {
        const response = await fetch('/api/map/config');
        return await response.json();
    } catch (error) {
        console.warn('Failed to get map config, using defaults:', error);
        // Fallback to public OSM tiles
        return {
            tilesUrl: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 19,
            source: 'fallback'
        };
    }
}

// Replace the initializeMap function's tile layer section:
function initializeMap(lat, lng, zoom) {
    map = L.map('map').setView([lat, lng], zoom);

    // Get map configuration dynamically
    getMapConfig().then(config => {
        L.tileLayer(config.tilesUrl, {
            attribution: config.attribution,
            maxZoom: config.maxZoom
        }).addTo(map);
        
        // Log which tile source is being used
        console.log('Using tile source:', config.source);
        if (config.warning) {
            console.warn(config.warning);
        }
    }).catch(error => {
        console.error('Error loading map config:', error);
        // Use hardcoded fallback
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 19
        }).addTo(map);
    });

    // ... rest of the function remains the same
```

## Step 4: Deployment and Testing

### 4.1 Start the Tile Server

```bash
# Start the tile server
docker-compose up -d tileserver

# Check logs
docker-compose logs -f tileserver

# Verify it's running
curl http://localhost:8081/health
```

### 4.2 Update Application Startup

Modify your startup script to ensure tile server starts before the application:

```bash
#!/bin/bash
# start-trip-weather.sh

echo "Starting tile server..."
docker-compose up -d tileserver

echo "Waiting for tile server to be healthy..."
for i in {1..30}; do
    if curl -f http://localhost:8081/health > /dev/null 2>&1; then
        echo "Tile server is healthy!"
        break
    fi
    echo "Waiting for tile server... ($i/30)"
    sleep 2
done

echo "Starting Trip Weather application..."
./gradlew bootRun
```

### 4.3 Test the Configuration

1. **Start the application** with the new configuration
2. **Open the browser** and check the developer console
3. **Verify tile source** in console logs
4. **Test fallback behavior** by stopping the tile server

## Step 5: Production Considerations

### 5.1 Security and Access Control

Add nginx configuration in `nginx/nginx.conf`:

```nginx
events {
    worker_connections 1024;
}

http {
    upstream tileserver {
        server tileserver:8080;
    }

    # Rate limiting to prevent abuse
    limit_req_zone $binary_remote_addr zone=tile_limit:10m rate=10r/s;

    server {
        listen 80;
        
        # Cache tiles for better performance
        proxy_cache_path /var/cache/nginx levels=1:2 keys_zone=tiles:10m max_size=1g inactive=60m;

        location /tiles/ {
            limit_req zone=tile_limit burst=20 nodelay;
            
            proxy_pass http://tileserver/data/;
            proxy_cache tiles;
            proxy_cache_valid 200 1h;
            proxy_cache_key "$request_uri";
            
            add_header X-Cache-Status $upstream_cache_status;
        }

        location /health {
            proxy_pass http://tileserver/health;
        }
    }
}
```

### 5.2 Monitoring and Maintenance

Add health checks to your application:

```java
// Add to TripweatherApplication.java or create a separate health component
@Scheduled(fixedRate = 300000) // Check every 5 minutes
public void monitorTileServerHealth() {
    // Log tile server health status
    // Send alerts if unhealthy for extended periods
}
```

### 5.3 Backup and Recovery

```bash
# Backup script for tiles
#!/bin/bash
# backup-tiles.sh

DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/backups/tiles"
TILES_DIR="./tiles"

mkdir -p $BACKUP_DIR

# Compress and backup tiles
tar -czf "$BACKUP_DIR/tiles_backup_$DATE.tar.gz" -C $TILES_DIR .

# Keep only last 7 backups
find $BACKUP_DIR -name "tiles_backup_*.tar.gz" -mtime +7 -delete
```

## Step 6: Advanced Configuration

### 6.1 Multiple Map Styles

Update the `config/config.json` to support multiple styles:

```json
{
  "styles": {
    "osm-liberty": {
      "style": "https://raw.githubusercontent.com/maputnik/osm-liberty/gh-pages/style.json",
      "tilejson": "http://tileserver/data/v3.json",
      "raster": true,
      "name": "OSM Liberty"
    },
    "dark-matter": {
      "style": "https://raw.githubusercontent.com/maputnik/dark-matter/gh-pages/style.json",
      "tilejson": "http://tileserver/data/v3.json",
      "raster": true,
      "name": "Dark Matter"
    },
    "positron": {
      "style": "https://raw.githubusercontent.com/maputnik/positron/gh-pages/style.json",
      "tilejson": "http://tileserver/data/v3.json",
      "raster": true,
      "name": "Positron"
    }
  },
  "data": {
    "v3": {
      "mbtiles": "data.mbtiles"
    }
  }
}
```

### 6.2 Dynamic Style Switching

Add style selection to the frontend:

```javascript
// Add to map.js
async function switchMapStyle(styleName) {
    const config = await getMapConfig();
    const styleUrl = `http://localhost:8081/styles/${styleName}.json`;
    
    // Remove current tile layer
    map.eachLayer(layer => {
        if (layer instanceof L.TileLayer) {
            map.removeLayer(layer);
        }
    });
    
    // Add new style
    L.tileLayer(styleUrl, {
        attribution: config.attribution,
        maxZoom: config.maxZoom
    }).addTo(map);
}
```

## Troubleshooting

### Common Issues and Solutions

1. **Tile server not responding**:
   - Check Docker logs: `docker-compose logs tileserver`
   - Verify port conflicts: `netstat -tulpn | grep 8081`
   - Check MBTiles file integrity

2. **Missing tiles at high zoom levels**:
   - Verify MBTiles contains required zoom levels
   - Check maxZoom configuration
   - Consider generating additional tiles

3. **Performance issues**:
   - Enable nginx caching
   - Consider using SSD storage for tiles
   - Monitor server resource usage

4. **CORS issues**:
   - Add CORS headers to nginx configuration
   - Ensure proper proxy settings

### Health Monitoring

Create monitoring endpoints and alerts for:
- Tile server availability
- Disk space usage
- Response times
- Error rates

## Conclusion

This implementation provides a robust, configurable solution for self-hosted map tiles that:
- Maintains backward compatibility with public tiles as fallback
- Supports easy switching between local and public tiles
- Includes health monitoring and caching
- Is scalable for production use

The configuration allows for gradual migration - you can start with public tiles and switch to local tiles as soon as your tile server is ready, with automatic fallback if issues arise.
