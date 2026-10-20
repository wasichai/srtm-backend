#!/bin/sh
# primer arranque: un rol y una base por app, con postgis. las claves llegan por pg.env (SRTM_DB_PASSWORD,
# CATASTRO_DB_PASSWORD), nunca escritas aquí
set -eu
psql -v ON_ERROR_STOP=1 -U postgres \
  -v srtm_pw="$SRTM_DB_PASSWORD" -v catastro_pw="$CATASTRO_DB_PASSWORD" <<'SQL'
CREATE ROLE srtm LOGIN PASSWORD :'srtm_pw';
CREATE ROLE catastro LOGIN PASSWORD :'catastro_pw';
CREATE DATABASE srtm OWNER srtm;
CREATE DATABASE catastro OWNER catastro;
\c srtm
CREATE EXTENSION IF NOT EXISTS postgis;
\c catastro
CREATE EXTENSION IF NOT EXISTS postgis;
SQL
