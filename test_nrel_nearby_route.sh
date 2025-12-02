#!/bin/bash

# Test script for NREL Nearby Route API
# This script sends a POST request to the NREL API with the contents of nrel_nearby_route_request.json

# API endpoint
API_URL="https://developer.nrel.gov/api/alt-fuel-stations/v1/nearby-route"

# API KEY
API_KEY=`cat developer.nrel.gov_api_key.txt`

# Request file
REQUEST_FILE="nrel_nearby_route_request.json"

# Check if the request file exists
if [ ! -f "$REQUEST_FILE" ]; then
    echo "Error: Request file $REQUEST_FILE not found!"
    exit 1
fi

# Send POST request with curl
curl -s -X POST \
     -H "Content-Type: application/json" \
     -H "Accept: application/json" \
     -d @"$REQUEST_FILE" \
     "${API_URL}?api_key=${API_KEY}"
