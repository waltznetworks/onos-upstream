FROM debian:jessie
MAINTAINER Victor Silva <victor@waltznetworks.com>

# Add Java 8 repository
ENV DEBIAN_FRONTEND noninteractive
RUN echo debconf shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
    echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main" | tee /etc/apt/sources.list.d/webupd8team-java.list && \
    echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu trusty main" | tee -a /etc/apt/sources.list.d/webupd8team-java.list && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys EEA14886

# Set the environment variables
ENV HOME /root
ENV JAVA_HOME /usr/lib/jvm/java-8-oracle
ENV ONOS_ROOT /src/onos
ENV KARAF_VERSION 3.0.5
ENV KARAF_ROOT /root/onos/apache-karaf-$KARAF_VERSION
ENV KARAF_LOG /root/onos/apache-karaf-$KARAF_VERSION/data/log/karaf.log
ENV PATH $PATH:$KARAF_ROOT/bin

# Download packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    oracle-java8-installer \
    oracle-java8-set-default && \
    rm -rf /var/lib/apt/lists/*

# Copy and unpackage ONOS
WORKDIR /root
COPY    buck-out/gen/tools/package/onos-package/onos.tar.gz onos.tar.gz
RUN     mkdir onos && \
        tar -xf onos.tar.gz -C onos --strip-components=1 && \
        rm -rf onos.tar.gz
