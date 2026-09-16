#!/bin/sh
# One database per service, created the first time the postgres volume is initialised.
#
# THIS SCRIPT IS THE DATABASE-PER-SERVICE RULE, made real.
#
# The five services share one PostgreSQL server and have five separate databases on it. That is not a
# shortcut around "one database per service" - it is the enforcement mechanism. PostgreSQL has no
# cross-database joins and no cross-database foreign keys, so catalog-service physically cannot read
# order-service's tables, cannot write a constraint that spans them, and cannot open a transaction
# across both. Five containers would enforce exactly the same boundary at five times the memory, on a
# Docker VM that Phase 17 already found under pressure.
#
# What five containers WOULD buy is independent failure: one database going down here takes all five
# services with it. That is a real difference and worth naming, and it is the right trade for a
# learning stack whose point is the code boundary rather than the operational one.
#
# Scripts in /docker-entrypoint-initdb.d run only when the data directory is empty - so this happens
# once, on a fresh volume, and never again. Changing it later means `docker compose down -v`.

set -e

for service in customer catalog inventory order notification; do
    # Each service gets its own database AND its own role, so a leaked connection string is limited
    # to one service's data. The password is the same one compose already holds; separate passwords
    # per service would be better and would need five more variables in .env to demonstrate the same
    # idea.
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
        CREATE DATABASE ecomdemo_${service};
        GRANT ALL PRIVILEGES ON DATABASE ecomdemo_${service} TO ${POSTGRES_USER};
SQL
    echo "created database ecomdemo_${service}"
done
