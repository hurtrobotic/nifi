FROM ubuntu:14.04

# Install Tesseract dependencies and the language support
RUN apt-get update && apt-get install -y tesseract-ocr 
RUN apt-get install -y tesseract-ocr-eng 
RUN apt-get install -y tesseract-ocr-afr
RUN apt-get install -y tesseract-ocr-ara
RUN apt-get install -y tesseract-ocr-aze
RUN apt-get install -y tesseract-ocr-bel
RUN apt-get install -y tesseract-ocr-ben
RUN apt-get install -y tesseract-ocr-bul
RUN apt-get install -y tesseract-ocr-cat
RUN apt-get install -y tesseract-ocr-ces
RUN apt-get install -y tesseract-ocr-chi-sim
RUN apt-get install -y tesseract-ocr-chi-tra
RUN apt-get install -y tesseract-ocr-chr
RUN apt-get install -y tesseract-ocr-dan
RUN apt-get install -y tesseract-ocr-deu
RUN apt-get install -y tesseract-ocr-deu-frak
RUN apt-get install -y tesseract-ocr-dev
RUN apt-get install -y tesseract-ocr-ell
RUN apt-get install -y tesseract-ocr-enm
RUN apt-get install -y tesseract-ocr-epo
RUN apt-get install -y tesseract-ocr-equ
RUN apt-get install -y tesseract-ocr-est
RUN apt-get install -y tesseract-ocr-eus
RUN apt-get install -y tesseract-ocr-fin
RUN apt-get install -y tesseract-ocr-fra
RUN apt-get install -y tesseract-ocr-frk
RUN apt-get install -y tesseract-ocr-frm
RUN apt-get install -y tesseract-ocr-glg
RUN apt-get install -y tesseract-ocr-grc
RUN apt-get install -y tesseract-ocr-heb
RUN apt-get install -y tesseract-ocr-hin
RUN apt-get install -y tesseract-ocr-hrv
RUN apt-get install -y tesseract-ocr-hun
RUN apt-get install -y tesseract-ocr-ind
RUN apt-get install -y tesseract-ocr-isl
RUN apt-get install -y tesseract-ocr-ita
RUN apt-get install -y tesseract-ocr-ita-old
RUN apt-get install -y tesseract-ocr-jpn
RUN apt-get install -y tesseract-ocr-kan
RUN apt-get install -y tesseract-ocr-kor
RUN apt-get install -y tesseract-ocr-lav
RUN apt-get install -y tesseract-ocr-lit
RUN apt-get install -y tesseract-ocr-mal
RUN apt-get install -y tesseract-ocr-mkd
RUN apt-get install -y tesseract-ocr-mlt
RUN apt-get install -y tesseract-ocr-msa
RUN apt-get install -y tesseract-ocr-nld
RUN apt-get install -y tesseract-ocr-nor
RUN apt-get install -y tesseract-ocr-osd
RUN apt-get install -y tesseract-ocr-pol
RUN apt-get install -y tesseract-ocr-por
RUN apt-get install -y tesseract-ocr-ron
RUN apt-get install -y tesseract-ocr-rus
RUN apt-get install -y tesseract-ocr-slk
RUN apt-get install -y tesseract-ocr-slk-frak
RUN apt-get install -y tesseract-ocr-slv
RUN apt-get install -y tesseract-ocr-spa
RUN apt-get install -y tesseract-ocr-spa-old
RUN apt-get install -y tesseract-ocr-sqi
RUN apt-get install -y tesseract-ocr-srp
RUN apt-get install -y tesseract-ocr-swa
RUN apt-get install -y tesseract-ocr-swe
RUN apt-get install -y tesseract-ocr-tam
RUN apt-get install -y tesseract-ocr-tel
RUN apt-get install -y tesseract-ocr-tgl
RUN apt-get install -y tesseract-ocr-tha
RUN apt-get install -y tesseract-ocr-tur
RUN apt-get install -y tesseract-ocr-ukr
RUN apt-get install -y tesseract-ocr-vie

# Install System components needed
RUN apt-get install -y curl && apt-get install -y wget && apt-get install -y ghostscript
# Install Java
RUN echo debconf shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && echo debconf shared/accepted-oracle-license-v1-1 seen true | debconf-set-selections && apt-get install -y software-properties-common && add-apt-repository ppa:webupd8team/java -y && apt-get update && apt-get install -y oracle-java8-installer && apt-get install -y oracle-java8-set-default
RUN echo zaerzaze

ARG UID=1000
ARG GID=1000
ARG NIFI_VERSION=1.6.0
ARG MIRROR=https://archive.apache.org/dist

ENV NIFI_BASE_DIR /opt/nifi 
ENV NIFI_HOME=${NIFI_BASE_DIR}/nifi-${NIFI_VERSION} \
    NIFI_BINARY_URL=/nifi/${NIFI_VERSION}/nifi-${NIFI_VERSION}-bin.tar.gz

RUN echo bravo

# Setup NiFi user
RUN groupadd -g ${GID} nifi || groupmod -n nifi `getent group ${GID} | cut -d: -f1` \
    && useradd --shell /bin/bash -u ${UID} -g ${GID} -m nifi \
    && mkdir -p ${NIFI_HOME}/conf/templates \
    && mkdir -p ${NIFI_HOME}/conf/profiles \
    && mkdir -p ${NIFI_HOME}/conf/profiles/com.cybozu.labs \
    && chown -R nifi:nifi ${NIFI_BASE_DIR} \
    && apt-get update \
    && apt-get install -y jq xmlstarlet locales 

RUN locale-gen fr_FR.UTF-8 && \
	update-locale LANG=fr_FR.UTF-8 && \
    echo "LANGUAGE=fr_FR.UTF-8" >> /etc/default/locale && \
    echo "LC_ALL=fr_FR.UTF-8" >> /etc/default/locale && \
    echo "LC_CTYPE=UTF-8" >> /etc/default/locale
    
USER nifi

# Download, validate, and expand Apache NiFi binary.
RUN curl -fSL ${MIRROR}/${NIFI_BINARY_URL} -o ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.tar.gz \
    && echo "$(curl https://archive.apache.org/dist/${NIFI_BINARY_URL}.sha256) *${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.tar.gz" | sha256sum -c - \
    && tar -xvzf ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.tar.gz -C ${NIFI_BASE_DIR} \
    && rm ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.tar.gz \
    && chown -R nifi:nifi ${NIFI_HOME}

USER root
RUN  echo "java.arg.8=-Dfile.encoding=UTF-8" >> ${NIFI_HOME}/conf/bootstrap.conf
RUN  echo "java.arg.9=-Dsun.jnu.encoding=UTF-8" >> ${NIFI_HOME}/conf/bootstrap.conf

RUN mkdir /nifi-added-nar-bundles 

# copy devs
RUN echo SCHENGEN GERMANIA
ADD ./nifi-nar-bundles/nifi-tess4J-bundle/nifi-tess4J-nar/target/*.nar ${NIFI_HOME}/lib/
ADD ./sh/*.* ${NIFI_BASE_DIR}/scripts/
ADD ./nifi-resources/*.properties ${NIFI_HOME}/conf/
ADD ./nifi-resources/profiles/com.cybozu.labs ${NIFI_HOME}/conf/profiles/com.cybozu.labs/


# copy devs
VOLUME     ${NIFI_BASE_DIR}/modules \
           ${NIFI_HOME}/importscripts \
		   ${NIFI_BASE_DIR}/flux \           
           ${NIFI_HOME}/logs \           
		   ${NIFI_HOME}/flowfile_repository \
		   ${NIFI_HOME}/database_repository \
		   ${NIFI_HOME}/content_repository \
		   ${NIFI_HOME}/provenance_repository 
           
RUN chown -R nifi:nifi ${NIFI_BASE_DIR}/scripts && chmod -R 755 ${NIFI_BASE_DIR}/scripts/*.sh

USER nifi
# Web HTTP(s) & Socket Site-to-Site Ports
EXPOSE 8080 8443 10000

WORKDIR ${NIFI_HOME}

# Apply configuration and start NiFi
CMD ${NIFI_BASE_DIR}/scripts/start.sh