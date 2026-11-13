#!/usr/bin/env bash
set -uo pipefail
SUDO=""; [ "$(id -u)" = 0 ] || SUDO=sudo
for i in 1 2 3 4 5; do docker rm -f "n$i" >/dev/null 2>&1; done
docker network rm zj >/dev/null 2>&1
$SUDO sed -i '/ # zcube-jepsen$/d' /etc/hosts
echo "nodes down"
