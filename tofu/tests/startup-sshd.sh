#!/usr/bin/env bash
# Runs tofu/scripts/startup.sh as a node, for real, on Ubuntu 24.04 with
# OpenSSH from its archive, and checks the sshd it leaves behind with
# `sshd -T -C`. The metadata server, apt-get and systemctl are stubs (there
# is no GCE and no systemd here), so this checks the script's own logic and
# sshd's reading of the drop-in, not systemd's socket activation.
#
# It changes /etc and /root, so it runs in a throwaway Ubuntu 24.04
# container (the offline job does
#   docker run --rm -v "$PWD:/w:ro" ubuntu:24.04 bash /w/tofu/tests/startup-sshd.sh)
# or on a throwaway VM. Needs root.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
script=$here/../scripts/startup.sh
subnet=10.78.0.0/24

if ! command -v sshd >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq >/dev/null
  apt-get install -y -qq --no-install-recommends openssh-server >/dev/null
fi

# What a fresh 24.04 node looks like before ssh.service ever ran: no
# /run/sshd, no host keys yet. And the GCE image's own drop-in, after ours.
rm -rf /run/sshd /var/lib/zj-ready /etc/ssh/sshd_config.d/*zcube-jepsen.conf
mkdir -p /root/zj-test-hostkeys
mv /etc/ssh/ssh_host_* /root/zj-test-hostkeys/ 2>/dev/null || true
printf 'PasswordAuthentication no\n' > /etc/ssh/sshd_config.d/50-cloudimg-settings.conf

stubs=$(mktemp -d)
cat > "$stubs/curl" <<'STUB'
#!/bin/sh
for a; do url=$a; done
case "${url##*/}" in
  zj-role)     echo node ;;
  zj-subnet)   echo 10.78.0.0/24 ;;
  zj-hosts)    printf '10.78.0.10 control\n10.78.0.11 n1' ;;
  zj-root-key) echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample zj-test' ;;
  *) exit 22 ;;
esac
STUB
printf '#!/bin/sh\nexit 0\n' > "$stubs/apt-get"
printf '#!/bin/sh\necho "$*" >> %s/systemctl.log\n' "$stubs" > "$stubs/systemctl"
chmod +x "$stubs"/*

PATH="$stubs:$PATH" bash "$script"

fail=0
expect() {
  local what=$1 got=$2 want=$3
  if [ "$got" = "$want" ]; then echo "ok   $what: $got"
  else echo "FAIL $what: got '$got', want '$want'"; fail=1; fi
}

hk=$(mktemp -d)
ssh-keygen -q -t ed25519 -N '' -f "$hk/k"
T() { sshd -T -h "$hk/k" -C "user=root,host=x,laddr=10.78.0.11,lport=22,addr=$1" | awk -v k="$2" '$1 == k { print $2 }'; }

expect "root from IAP (35.235.240.7)"       "$(T 35.235.240.7 permitrootlogin)" no
expect "root from elsewhere (203.0.113.9)"  "$(T 203.0.113.9 permitrootlogin)" no
expect "root from control (10.78.0.10)"     "$(T 10.78.0.10 permitrootlogin)" without-password
expect "root from n2 (10.78.0.12)"          "$(T 10.78.0.12 permitrootlogin)" without-password
expect "image drop-in still global (IAP)"   "$(T 35.235.240.7 passwordauthentication)" no
expect "image drop-in still global (subnet)" "$(T 10.78.0.10 passwordauthentication)" no
expect "maxsessions"                        "$(T 10.78.0.10 maxsessions)" 100
expect "authorized_keys from="              "$(cut -d' ' -f1 /root/.ssh/authorized_keys)" "from=\"$subnet\""
expect "systemctl calls"                    "$(cat "$stubs/systemctl.log")" "try-reload-or-restart ssh.service
disable --now postgresql"
expect "/run/sshd"                          "$(stat -c %a /run/sshd)" 755
expect "ready marker"                       "$(test -f /var/lib/zj-ready && echo yes)" yes

# A broken drop-in must stop the script before anything else happens.
rm -f /var/lib/zj-ready
bad=$(mktemp)
sed 's/^UseDNS no$/UseDNS maybe-not/' "$script" > "$bad"
if PATH="$stubs:$PATH" bash "$bad" >/dev/null 2>&1; then
  echo "FAIL: a drop-in sshd rejects did not stop the script"; fail=1
else
  expect "bad drop-in stops the script" "$(test -f /var/lib/zj-ready && echo yes || echo no)" no
fi

rm -rf "$stubs" "$hk" "$bad"
[ "$fail" = 0 ] && echo "startup.sh sshd checks: all passed"
exit "$fail"
