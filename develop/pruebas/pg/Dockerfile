# el mismo postgis de compose.yml de dev, con las dos bases. COPY sin --chmod: un daemon remoto puede no tener buildkit
FROM postgis/postgis:18-3.6
COPY 10-bases.sh /docker-entrypoint-initdb.d/10-bases.sh
RUN chmod 755 /docker-entrypoint-initdb.d/10-bases.sh
