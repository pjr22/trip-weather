#!/bin/bash

# username is postgres
echo "starting postgis with name: tripdb, username: postgres, password: tripdb"

docker run -d \
	--name tripdb \
	-e POSTGRES_PASSWORD=tripdb \
        -p 5432:5432 \
        --net forgotten_net \
        postgis/postgis:18-3.6
