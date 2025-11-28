#!/bin/bash

# Test script for EV Charging Station API endpoint

# Source environment variables
source setEnvVariables.source

# Define the base URL
BASE_URL="http://localhost:8090"

# Test data - route from Denver to Salt Lake City
ROUTE_DATA='{
  "route": [
    [-104.9903, 39.7392],
    [-105.2211, 39.7555],
    [-106.8269, 39.5321],
    [-107.8804, 39.0639],
    [-108.5519, 38.8395],
    [-109.5435, 38.8463],
    [-110.7963, 39.1603],
    [-111.8537, 38.9314]
  ],
  "parameters": {
    "fuel_type": "ELEC",
    "status": "E",
    "access": "public",
    "distance": "5",
    "limit": "20"
  }
}'

echo "Testing EV Charging Station API endpoint..."
echo "Request data:"
echo "$ROUTE_DATA" | jq .
echo ""

# Make the API call
echo "Making API call to $BASE_URL/api/ev-charging/stations..."
RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "$ROUTE_DATA" \
  "$BASE_URL/api/ev-charging/stations")

echo "Response:"
echo "$RESPONSE" | jq .

# Check if we got stations
STATION_COUNT=$(echo "$RESPONSE" | jq '.features | length // 0')
echo ""
echo "Found $STATION_COUNT EV charging stations"

# Test health endpoint
echo ""
echo "Testing health endpoint..."
HEALTH_RESPONSE=$(curl -s "$BASE_URL/api/ev-charging/health")
echo "Health check: $HEALTH_RESPONSE"