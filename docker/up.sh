#!/usr/bin/env bash
# Bring up 5 nodes (n1..n5), each a container with its own network namespace,
# on a dedicated bridge. The Jepsen control process runs on the host and
# reaches the nodes by name through /etc/hosts.
set -euo pipefail
cd "$(dirname "$0")"
NET=zj
SUBNET=10.77.0.0/24
IMAGE=zcube-jepsen-node
SUDO=""; [ "$(id -u)" = 0 ] || SUDO=sudo

docker build ${DOCKER_BUILD_ARGS:-} -t "$IMAGE" .
docker network inspect "$NET" >/dev/null 2>&1 || docker network create --subnet "$SUBNET" "$NET" >/dev/null

for i in 1 2 3 4 5; do
  docker rm -f "n$i" >/dev/null 2>&1 || true
  # NET_ADMIN: iptables partitions. NET_RAW: ping/tcpdump.
  # Deliberately no SYS_TIME: the wall clock is not namespaced, so a node
  # stepping it would step the host and every other node. See README.
  docker run -d --name "n$i" --hostname "n$i" --network "$NET" --ip "10.77.0.1$i" \
    --cap-add NET_ADMIN --cap-add NET_RAW --shm-size 256m "$IMAGE" >/dev/null
done

# A throwaway key pair for the control process (Jepsen falls over on
# password auth when no SSH agent is running).
mkdir -p .ssh
if [ ! -f .ssh/id_ed25519 ]; then
  # Generated on a node so the host needs no ssh tooling.
  docker exec n1 sh -c "rm -f /tmp/k /tmp/k.pub; ssh-keygen -q -t ed25519 -N '' -f /tmp/k"
  docker cp -q n1:/tmp/k .ssh/id_ed25519
  docker cp -q n1:/tmp/k.pub .ssh/id_ed25519.pub
  docker exec n1 rm -f /tmp/k /tmp/k.pub
  chmod 600 .ssh/id_ed25519
fi
for i in 1 2 3 4 5; do
  docker exec "n$i" install -d -m 700 /root/.ssh
  docker cp -q .ssh/id_ed25519.pub "n$i:/root/.ssh/authorized_keys"
  docker exec "n$i" sh -c 'chown root:root /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys'
done

$SUDO sed -i '/ # zcube-jepsen$/d' /etc/hosts
for i in 1 2 3 4 5; do
  echo "10.77.0.1$i n$i # zcube-jepsen" | $SUDO tee -a /etc/hosts >/dev/null
done

for i in 1 2 3 4 5; do
  for _ in $(seq 1 50); do
    if (exec 3<>"/dev/tcp/n$i/22") 2>/dev/null; then break; fi; sleep 0.2
  done
done
echo "nodes up: n1..n5 on $SUBNET"
