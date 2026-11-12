# One image for every node role. Jepsen drives the node over SSH as root.
ARG BASE=ubuntu:24.04
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
