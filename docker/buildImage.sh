#!/bin/bash

cp ../build/libs/tripweather-0.0.1-SNAPSHOT.jar .
docker build -t org.pjr22/trip-weather:latest .

