#!/usr/bin/env bash
# GCE startup script of every VM of the run (tofu/main.tf). Runs as root on
# each boot; idempotent. What it does depends on the instance metadata:
#
#   zj-role     node | control
#   zj-hosts    "<ip> <name>" lines for /etc/hosts (control, n1..n5)
#   zj-subnet   the run's subnet; root may SSH in from there only
#   zj-root-key (nodes) the public key for root's authorized_keys
#
# Nodes get what docker/Dockerfile puts in the Docker image; the control VM
# gets a JDK, Leiningen and graphviz. Both write /var/lib/zj-ready last,
# which the workflow waits for.
set -euo pipefail

md() {
  curl -fsS --retry 5 -H 'Metadata-Flavor: Google' \
    "http://metadata.google.internal/computeMetadata/v1/instance/attributes/$1"
}

retry() {
  local i
  for i in 1 2 3 4 5 6; do
    if "$@"; then return 0; fi
    echo "retry $i: $*" >&2
    sleep $((i * 5))
  done
  return 1
}

ready=/var/lib/zj-ready
role=$(md zj-role)
subnet=$(md zj-subnet)

# Names first, on every boot.
sed -i '/ # zcube-jepsen$/d' /etc/hosts
md zj-hosts | sed 's/$/ # zcube-jepsen/' >> /etc/hosts

if [ -f "$ready" ]; then
  echo "zcube-jepsen: $role already set up"
  exit 0
fi

export DEBIAN_FRONTEND=noninteractive

case "$role" in
  node)
    install -d -m 700 /root/.ssh
    md zj-root-key > /root/.ssh/authorized_keys
    chmod 600 /root/.ssh/authorized_keys
    # Root by key, from the subnet only (the control VM). Jepsen opens many
    # sessions at once. Everything else stays as the image has it (OS Login).
    cat > /etc/ssh/sshd_config.d/60-zcube-jepsen.conf <<CONF
MaxSessions 100
MaxStartups 100
UseDNS no
Match Address $subnet
    PermitRootLogin prohibit-password
CONF
    sshd -t
    systemctl reload ssh

    # The harness creates its own clusters; no default one on 5432.
    install -d /etc/postgresql-common/createcluster.d
    echo 'create_main_cluster = false' > /etc/postgresql-common/createcluster.d/60-zcube-jepsen.conf

    retry apt-get update -qq
    retry apt-get install -y -qq --no-install-recommends \
      openssh-server sudo iptables iproute2 iputils-ping procps psmisc \
      faketime libfaketime postgresql-16 libpq5 libsqlite3-0 ca-certificates \
      curl tcpdump
    systemctl disable --now postgresql >/dev/null 2>&1 || true
    echo 'PATH=/usr/lib/postgresql/16/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' > /etc/environment
    ;;

  control)
    lein_version=2.11.2
    # sha256 of bin/lein at that tag, as in .github/workflows/jepsen.yml.
    lein_sha256=f83e3de252cb09d301f18f1912acffa807b54c1c9a96772ae63847a946032bd4
    retry apt-get update -qq
    retry apt-get install -y -qq --no-install-recommends \
      openjdk-21-jdk-headless graphviz gnuplot-nox curl ca-certificates rsync
    retry curl -fsSL -o /usr/local/bin/lein.new \
      "https://raw.githubusercontent.com/technomancy/leiningen/${lein_version}/bin/lein"
    echo "${lein_sha256}  /usr/local/bin/lein.new" | sha256sum -c -
    chmod 755 /usr/local/bin/lein.new
    mv /usr/local/bin/lein.new /usr/local/bin/lein
    ;;

  *)
    echo "zcube-jepsen: unknown role '$role'" >&2
    exit 1
    ;;
esac

touch "$ready"
echo "zcube-jepsen: $role ready"
