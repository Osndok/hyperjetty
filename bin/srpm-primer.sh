#!/bin/bash
#
# Builds an SRPM for this project, and stashes it on the specified devtools repo.
#
# usage: bin/srpm-primer.sh [reponame]
#
# Last touch: 2014-07-01 / REH
#
# (1) build slaves must be able to log into devtools & use 'mock'
# (2) fixing up srpm/rpm build chain initiation
#

set -eu

PROJECT=hyperjetty

# ----------------------------------------------

REPO_NAME=${1:-snapshot}

# ----------------------------------------------

SPEC_IN=etc/$PROJECT.spec.in
SPEC_OUT=target/$PROJECT.spec

BUILD_MACHINE=builder@devtools.allogy.com

KNOWN_HOSTS=~/.ssh/known_hosts

SSH_CONFIG=~/.ssh/config

# ----------------------------------------------

VERSION=$(cat .version)
echo "VERSION=$VERSION (.version)"

# http://semver.org/
MAJOR=$(echo $VERSION | cut -f1 -d.)
MINOR=$(echo $VERSION | cut -f2 -d.)
PATCH=$(echo $VERSION | cut -f3 -d.)
BUILD=${BUILD_NUMBER:-0}

echo "MAJOR=$MAJOR"
echo "MINOR=$MINOR"
echo "PATCH=$PATCH"
echo "BUILD=$BUILD"

REPLACEMENTS="-e s/@MAJOR@/$MAJOR/g -e s/@MINOR@/$MINOR/g -e s/@PATCH@/$PATCH/g -e s/@BUILD@/$BUILD/g"

if [ "$REPO_NAME" == "snapshot" ]; then
	REPLACEMENTS="$REPLACEMENTS -e s/@MUTEX@/snapshot/g -e s/@-SNAPSHOT@/-snapshot/g -e s/@_SNAPSHOT@/_snapshot/g -e s/@.SNAPSHOT@/.snapshot/g"

	SELF_SOURCE_DIR="$PROJECT-$VERSION.snapshot/"
	SELF_SOURCE_REFERENCE="$PROJECT-$VERSION.snapshot.tar.gz"
else
	REPLACEMENTS="$REPLACEMENTS -e s/@MUTEX@/v${MAJOR}/g -e s/@-SNAPSHOT@//g -e s/@_SNAPSHOT@//g -e s/@.SNAPSHOT@//g"

	SELF_SOURCE_DIR="$PROJECT-$VERSION/"
	SELF_SOURCE_REFERENCE="$PROJECT-$VERSION.tar.gz"
fi

sed $REPLACEMENTS "$SPEC_IN" > "$SPEC_OUT"

echo "SSD=$SELF_SOURCE_DIR (root of git-archive)"
echo "SSR=$SELF_SOURCE_REFERENCE (git-archive filename)"

SPEC_BASE=$(basename $SPEC_OUT)

LOCAL_DIR=$(mktemp -d /tmp/build-srpm.XXXXXXXXX)

SOURCES_LIST=$LOCAL_DIR/sources.txt

if ! grep -q devtools $KNOWN_HOSTS ; then
	ssh-keyscan devtools.allogy.com >> $KNOWN_HOSTS
fi

if ! grep -q devtools $SSH_CONFIG ; then
	# This is intended to effect the Jenkins Java-7 slave, until such a time that it is parameterized or repaired
	if grep jenkins_ssh_id_rsa $SSH_CONFIG ; then

cat - >> $SSH_CONFIG <<EOF

# Following entry was added by hyperjetty::etc/srpm-primer.sh script
# ?: maybe user should be 'jenkins'? (but currently has noshell/nologin)
Host devtools.allogy.com
User builder
IdentityFile ~/.ssh/jenkins_ssh_id_rsa

EOF

	else
		uname -a
		echo 2>&1 $(hostname)": don't know how to repair access/permissions to devtools"
	fi
fi


if which spectool ; then
	cat $SPEC_OUT | spectool - | tr -s ' :' ':' | cut -f2- -d: > $SOURCES_LIST
else
	cat $SPEC_OUT | ssh "$BUILD_MACHINE" spectool - | tr -s ' :' ':' | cut -f2- -d: > $SOURCES_LIST
fi

#
# SOURCES_LIST is a file that contains a list of all macro-expanded "Source" references
# from the rpm spec file.
#
# Examples:
#
#  "target/some.jar" 
#  "module/target/some.jar" - something that this project builds (i.e. maven output)
#
#  "machine:/usr/src/archive.tgz" - a file, not generally accessible, that we are keeping.
#
#  "http://some.host/project/archive.tgz" - a publicly reachable file
#
#  "https://jenkins.allogy.com/job/Project/artifact/blah.jar"
#            - does not work ATM due to permission issues
#
#
# NB: reachability is not super-imperative, because the sources are bundled into the final
#     "source rpm" (SRPM) which is, effectively, the unit of build information. Therefore,
#     if a publicly-reachable file drops offline, we might not notice at first (b/c the
#     acquire step will cache it); but when we do (e.g. due to a broken build), we can
#     re-acquire the file by extracting it from the last successful SRPM build *even* if
#     it has dropped from the acquire cache.
#

REMOTE_DIR=$(ssh "$BUILD_MACHINE" mktemp -d /tmp/build-srpm.XXXXXXXXX)
REMOTE="$BUILD_MACHINE:$REMOTE_DIR"

function xfer_self_source()
{
	pwd
	git rev-parse HEAD
	git archive --format=tar --prefix="$SELF_SOURCE_DIR" HEAD | gzip > $LOCAL_DIR/$SELF_SOURCE_REFERENCE
	scp "$LOCAL_DIR/$SELF_SOURCE_REFERENCE" "$REMOTE"
}

function if_source_is_safe_path()
{
	if [[ "$SOURCE" == *../* ]]; then
		echo 1>&2 "UNSAFE PATH 1: $SOURCE"
		exit 2
	elif [[ "$SOURCE" == /* ]]; then
		echo 1>&2 "UNSAFE PATH 2: $SOURCE"
		exit 3
	fi
}

#--------------
# TEST VECTORS
#--------------
#
# SOURCE=target/hyperjetty.spec
# SOURCE=hyperjetty-webapp/target/hyperjetty-webapp.jar
# SOURCE=http://redmine.allogy.com/attachments/download/1053/onboarding-webflow.pdf
# SOURCE=https://redmine.allogy.com/attachments/download/1053/onboarding-webflow.pdf
# SOURCE=devtools:/usr/src/epel-release-6-5.noarch.rpm
#

while read SOURCE
do

	if [ "$SOURCE" == "$SELF_SOURCE_REFERENCE" ] || [[ "$SOURCE" == */$SELF_SOURCE_REFERENCE ]]; then
		echo "SELF:  $SOURCE"
		xfer_self_source
	elif [[ $SOURCE == target/* ]] || [[ $SOURCE == */target/* ]] || [ -f "$SOURCE" ]; then
		echo "XFER:  $SOURCE"
		if_source_is_safe_path
		scp "$SOURCE" "$REMOTE"
	else
		echo 2>&1 "DEFER: $SOURCE"
	fi

done < $SOURCES_LIST

scp "$SPEC_OUT" "$REMOTE"

if ssh "$BUILD_MACHINE" /builder/srpm-chain.sh "$REMOTE_DIR" "$REPO_NAME" ; then
	rm -rf "$LOCAL_DIR"
	echo 1>&2 "$0: SUCCESS"
	exit 0
else
	rm -rf "$LOCAL_DIR"
	echo 1>&2 "$0: FAILURE"
	exit 1
fi
