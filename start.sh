#!/bin/bash
cd "$(dirname "$0")"
exec java -Xms2G -Xmx4G -jar paper-26.2-103.jar --nogui
