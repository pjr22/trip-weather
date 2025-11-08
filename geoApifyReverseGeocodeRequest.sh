#!/bin/bash

LAT=${1:-39.735457}
LON=${2:--104.988349}
KEY=`cat geoApify_api_key.txt`
URL="https://api.geoapify.com/v1/geocode/reverse?lat=${LAT}&lon=${LON}&apiKey=${KEY}"
curl -s --location --request GET "https://api.geoapify.com/v1/geocode/reverse?lat=${LAT}&lon=${LON}&apiKey=${KEY}" | jq
