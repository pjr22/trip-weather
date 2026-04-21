#!/bin/bash
set -eu

# Requires TRIP_DB_PASSWORD to be set in the environment.
# username is postgres
echo "starting postgis container 'tripdb' (username: postgres, password: from \$TRIP_DB_PASSWORD)"

docker run -d \
	--name tripdb \
	-e POSTGRES_PASSWORD="$TRIP_DB_PASSWORD" \
        -p 5432:5432 \
        --net forgotten_net \
        postgis/postgis:18-3.6
