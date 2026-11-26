#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
    echo 'Usage: data-snapshot.sh backup SOURCE_SCHEMA NEW_DIRECTORY [RESOURCE_DIRECTORY]' >&2
    echo '       data-snapshot.sh restore studysphere_restore_* SNAPSHOT_DIRECTORY [NEW_RESOURCE_DIRECTORY]' >&2
    exit 2
}

(( $# == 3 || $# == 4 )) || usage
mode=$1
database=$2
snapshot=$3
resources=${4:-}
[[ $database =~ ^[a-zA-Z][a-zA-Z0-9_]{0,63}$ ]] || usage
mysql_args=(--protocol=TCP "--host=${MYSQL_HOST:-127.0.0.1}" "--port=${MYSQL_TCP_PORT:-3306}")
if [[ -n ${MYSQL_LOGIN_PATH:-} ]]; then
    mysql_args=("--login-path=$MYSQL_LOGIN_PATH" "${mysql_args[@]}")
fi
if [[ -n ${MYSQL_USER:-} ]]; then
    mysql_args+=("--user=$MYSQL_USER")
fi

case $mode in
    backup)
        [[ -z $resources || -d $resources ]] || usage
        if [[ -n $resources ]]; then
            resources=$(cd -- "$resources" && pwd -P)
            parent=$(cd -- "$(dirname -- "$snapshot")" && pwd -P)
            if [[ $parent == "$resources" || $parent == "$resources/"* ]]; then
                echo 'Snapshot must be outside the resources directory.' >&2
                exit 2
            fi
        fi
        mkdir -- "$snapshot"
        mysqldump "${mysql_args[@]}" --single-transaction --quick --no-tablespaces \
            --set-gtid-purged=OFF --column-statistics=0 "$database" > "$snapshot/mysql.sql"
        printf '%s\n' "$database" > "$snapshot/source-schema"
        files=(mysql.sql source-schema)
        if [[ -n $resources ]]; then
            tar -cf "$snapshot/resources.tar" -C "$resources" .
            files+=(resources.tar)
        fi
        (cd -- "$snapshot" && sha256sum "${files[@]}" > SHA256SUMS)
        ;;
    restore)
        [[ $database =~ ^studysphere_restore_[a-zA-Z0-9_]+$ ]] || usage
        snapshot=$(cd -- "$snapshot" && pwd -P)
        files=(mysql.sql source-schema)
        if [[ -f $snapshot/resources.tar ]]; then
            [[ -n $resources && ! -e $resources && ! -L $resources ]] || usage
            files+=(resources.tar)
        else
            [[ -z $resources ]] || usage
        fi
        (cd -- "$snapshot" && sha256sum "${files[@]}" | cmp - SHA256SUMS)
        [[ $(< "$snapshot/source-schema") != "$database" ]] || usage
        mysql "${mysql_args[@]}" --execute="CREATE DATABASE \`$database\` CHARACTER SET utf8mb4"
        mysql "${mysql_args[@]}" --binary-mode --database="$database" < "$snapshot/mysql.sql"
        if [[ -n $resources ]]; then
            mkdir -- "$resources"
            tar -xf "$snapshot/resources.tar" --no-same-owner --no-same-permissions -C "$resources"
        fi
        ;;
    *) usage ;;
esac
