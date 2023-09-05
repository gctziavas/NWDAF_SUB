CREATE DATABASE metrics;

\connect metrics;

DROP TABLE IF EXISTS metrics.public.nf_load_metrics;
DROP TABLE IF EXISTS ue_mobility_metrics;

CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE TABLE IF NOT EXISTS metrics.public.nf_load_metrics (
  time TIMESTAMPTZ,
  data JSONB,
  nfInstanceId UUID,
  nfSetId varchar(100),
  nfCpuUsage int,
  nfMemoryUsage int,
  nfStorageUsage int,
  nfLoadLevelAverage int,
  nfLoadLevelpeak int,
  nfLoadAvgInAoi int,
  areaOfInterestId UUID
);
SELECT create_hypertable('nf_load_metrics','time');

CREATE INDEX IF NOT EXISTS ix_data_time ON metrics.public.nf_load_metrics (data, time DESC, nfInstanceId);


CREATE TABLE IF NOT EXISTS metrics.public.ue_mobility_metrics (
  time TIMESTAMPTZ,
  data JSONB
);
SELECT create_hypertable('ue_mobility_metrics','time');

CREATE INDEX IF NOT EXISTS ix_data_time_ue_mobility_metrics ON metrics.public.ue_mobility_metrics (data, time DESC);