# One image for every node role. Jepsen drives the node over SSH as root.
# ubuntu:24.04, pinned by digest (resolved 2026-11-13); postgresql-16 still
# floats with the apt archive, so the minor version is whatever it served.
ARG BASE=ubuntu:24.04@sha256:2198e4c65076e9cb0b3d695219df667c0e3b0072da64ca3c2648aca17dcecaf5
FROM ${BASE}
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      openssh-server sudo iptables iproute2 iputils-ping procps psmisc \
      faketime libfaketime postgresql-16 libpq5 libsqlite3-0 ca-certificates curl tcpdump \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd \
    && echo 'root:root' | chpasswd \
    && sed -ri 's/^#?PermitRootLogin.*/PermitRootLogin yes/; s/^#?PasswordAuthentication.*/PasswordAuthentication yes/; s/^#?MaxSessions.*/MaxSessions 100/; s/^#?MaxStartups.*/MaxStartups 100/' /etc/ssh/sshd_config \
    && echo 'UseDNS no' >> /etc/ssh/sshd_config
ENV PATH=/usr/lib/postgresql/16/bin:$PATH
RUN echo 'PATH=/usr/lib/postgresql/16/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' > /etc/environment
EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
