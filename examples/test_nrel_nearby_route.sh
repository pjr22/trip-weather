#!/bin/bash

# Test script for NREL Nearby Route API
# This script sends a POST request to the NREL API with the contents of nrel_nearby_route_request.json

# Run from this script's directory so sibling files and ../ references resolve consistently.
cd "$(dirname "$0")" || exit 1

# API endpoint
API_URL="https://developer.nrel.gov/api/alt-fuel-stations/v1/nearby-route"

# API KEY (kept in project root, ignored by .gitignore)
API_KEY=`cat ../developer.nrel.gov_api_key.txt`

# Request file (sibling)
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
